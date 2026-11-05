package com.portal.tv.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** How the end of an answer is found, against a small server that ends its stream in different ways (or doesn't). */
class LlmClientStreamEndTest {

    private lateinit var server: ServerSocket
    private val keepOpen = java.util.concurrent.CountDownLatch(1)
    private var oldSilence = 0L

    @Before
    fun setUp() {
        oldSilence = LlmClient.silenceEndsAnswerMs
        LlmClient.silenceEndsAnswerMs = 600
        server = ServerSocket(0)
    }

    @After
    fun tearDown() {
        keepOpen.countDown()
        server.close()
        LlmClient.silenceEndsAnswerMs = oldSilence
    }

    private fun chunk(text: String) = """data: {"choices":[{"delta":{"content":"$text"},"finish_reason":null}]}""" + "\n\n"
    private fun finish(reason: String) = """data: {"choices":[{"delta":{},"finish_reason":"$reason"}]}""" + "\n\n"

    /** Answers one request with [body], then closes, or keeps the connection open and silent if [hold]. */
    private fun serve(body: String, hold: Boolean) {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                // Read the request headers and body loosely: until the request's JSON has arrived.
                val buffer = ByteArray(8192)
                socket.soTimeout = 2000
                runCatching { input.read(buffer) }
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body.toByteArray())
                out.flush()
                if (hold) keepOpen.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun ask(): Pair<String, Long> {
        val base = "http://127.0.0.1:${server.localPort}/v1"
        val start = System.nanoTime()
        val text = LlmClient.stream(base, "m", null, listOf(ChatMessage("user", "hi"))) {}
        return text to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun doneEndsTheAnswerAtOnce() {
        serve(chunk("Hello") + chunk(" there") + finish("stop") + "data: [DONE]\n\n", hold = true)
        val (text, ms) = ask()
        assertEquals("Hello there", text)
        assertTrue("took $ms ms", ms < 500)
    }

    @Test
    fun aFinishReasonEndsTheAnswerEvenIfTheServerKeepsTheConnectionOpen() {
        serve(chunk("Hello") + chunk(" there") + finish("stop"), hold = true)
        val (text, ms) = ask()
        assertEquals("Hello there", text)
        assertTrue("took $ms ms", ms < 500)
    }

    @Test
    fun aServerThatJustGoesQuietAfterTheAnswerIsTakenToHaveFinished() {
        serve(chunk("The answer") + chunk(" is 42."), hold = true)
        val (text, ms) = ask()
        assertEquals("The answer is 42.", text)
        // About the silence limit (600 ms), nowhere near the long read timeout.
        assertTrue("took $ms ms", ms in 500..5000)
    }

    @Test
    fun aClosedConnectionStillEndsTheAnswer() {
        serve(chunk("Done") + chunk("."), hold = false)
        val (text, _) = ask()
        assertEquals("Done.", text)
    }
}
