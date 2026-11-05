package com.portal.tv.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * How the end of an answer is found, against a small server that ends its stream in different ways (or doesn't), and
 * that can be asked whether it is busy.
 */
class LlmClientStreamEndTest {

    private lateinit var server: ServerSocket
    private val release = CountDownLatch(1)

    /** What /status says: "idle", "busy", or null for a server with no such page. */
    @Volatile private var status: String? = null

    private var oldAfter = 0L
    private var oldPoll = 0L
    private var oldSilence = 0L

    @Before
    fun setUp() {
        oldAfter = LlmClient.statusAfterMs; oldPoll = LlmClient.statusPollMs; oldSilence = LlmClient.silenceEndsAnswerMs
        LlmClient.statusAfterMs = 150
        LlmClient.statusPollMs = 100
        LlmClient.silenceEndsAnswerMs = 900
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        server.close()
        LlmClient.statusAfterMs = oldAfter; LlmClient.statusPollMs = oldPoll; LlmClient.silenceEndsAnswerMs = oldSilence
    }

    private fun chunk(text: String) = """data: {"choices":[{"delta":{"content":"$text"},"finish_reason":null}]}""" + "\n\n"
    private fun finish(reason: String) = """data: {"choices":[{"delta":{},"finish_reason":"$reason"}]}""" + "\n\n"

    /** The stream the chat request gets back. */
    @Volatile private var body = ""
    @Volatile private var hold = false

    /**
     * The whole request: its headers and the body they announce. A single read can return only part of it, and closing
     * a connection with part of the request unread makes the client see a reset instead of the answer.
     */
    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var needed = -1
        try {
            while (true) {
                val text = bytes.toString(Charsets.ISO_8859_1)
                val headerEnd = text.indexOf("\r\n\r\n")
                if (headerEnd >= 0) {
                    if (needed < 0) {
                        val length = Regex("(?i)content-length:\\s*(\\d+)").find(text.substring(0, headerEnd))?.groupValues?.get(1)?.toInt() ?: 0
                        needed = headerEnd + 4 + length
                    }
                    if (bytes.size() >= needed) break
                }
                val n = input.read(buffer)
                if (n < 0) break
                bytes.write(buffer, 0, n)
            }
        } catch (e: Exception) {
            // Timed out or reset: use what arrived.
        }
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 2000
            val request = readRequest(socket)
            val out = socket.getOutputStream()
            if (request.startsWith("GET /status")) {
                val reply = when (status) {
                    "idle" -> """{"busy": false, "queued": 0}"""
                    "busy" -> """{"busy": true, "queued": 0}"""
                    else -> null
                }
                if (reply == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                else out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply").toByteArray())
                out.flush()
            } else if (request.startsWith("GET /slots")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body.toByteArray())
                out.flush()
                if (hold) release.await(30, TimeUnit.SECONDS)
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
        body = chunk("Hello") + chunk(" there") + finish("stop") + "data: [DONE]\n\n"; hold = true
        val (text, ms) = ask()
        assertEquals("Hello there", text)
        assertTrue("took $ms ms", ms < 400)
    }

    @Test
    fun aFinishReasonEndsTheAnswerEvenIfTheServerKeepsTheConnectionOpen() {
        body = chunk("Hello") + chunk(" there") + finish("stop"); hold = true
        val (text, ms) = ask()
        assertEquals("Hello there", text)
        assertTrue("took $ms ms", ms < 400)
    }

    @Test
    fun aClosedConnectionStillEndsTheAnswer() {
        body = chunk("Done") + chunk("."); hold = false
        assertEquals("Done.", ask().first)
    }

    @Test
    fun aQuietServerThatSaysItIsIdleHasFinishedTheAnswer() {
        body = chunk("The answer") + chunk(" is 42."); hold = true; status = "idle"
        val (text, ms) = ask()
        assertEquals("The answer is 42.", text)
        // The status check ended it: well before the silence limit that a server without one would need.
        assertTrue("took $ms ms", ms < 800)
    }

    @Test
    fun aQuietServerThatSaysItIsBusyIsGivenMoreTime() {
        body = chunk("Still") + chunk(" thinking"); hold = true; status = "busy"
        var result: Pair<String, Long>? = null
        val worker = thread { result = ask() }
        // Well past both the status check and the silence limit, and still waiting.
        Thread.sleep(2_000)
        assertFalse("ended while the server was busy", result != null)
        status = "idle"
        worker.join(5_000)
        assertEquals("Still thinking", result?.first)
    }

    @Test
    fun aQuietServerThatCantBeAskedEndsAfterTheSilenceLimit() {
        body = chunk("No") + chunk(" status page"); hold = true; status = null
        val (text, ms) = ask()
        assertEquals("No status page", text)
        assertTrue("took $ms ms", ms in 800..5_000)
    }
}
