package com.portal.tv.search

import org.json.JSONArray
import com.portal.tv.search.Sse.text
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Talks to an OpenAI-compatible chat server and streams the answer as it is written. */
object LlmClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    /**
     * How long to wait in silence for the server. A local model can take a while to start answering:
     * it may be loading, working through a long chat, or thinking before its first word.
     */
    private const val READ_TIMEOUT_MS = 180_000

    /**
     * A healthy server ends an answer with a finish reason, [DONE] and a closed connection, but some stop after the last
     * word and send none of them, which left the answer looking unfinished until the long wait above ran out. So once an
     * answer has begun and the server goes quiet for [statusAfterMs], Portal asks the server (see [ServerStatus]) whether
     * it is still working. Idle twice running means the answer is over; busy means keep waiting. A server that can't be
     * asked is taken to be finished after [silenceEndsAnswerMs] of silence. Reasoning and keep-alive lines count as the
     * server still being there. Tests shorten these.
     */
    @Volatile internal var statusAfterMs = 4_000L
    @Volatile internal var statusPollMs = 2_000L
    @Volatile internal var silenceEndsAnswerMs = 15_000L

    /** Thrown with a short, user-readable reason. */
    open class LlmException(message: String) : Exception(message)

    /** The request was bigger than the model's context. */
    class ContextTooLongException(message: String) : LlmException(message)

    /** What the model said, and any tools it asked to run instead of (or after) saying it. */
    data class Reply(val text: String, val toolCalls: List<ToolCall>)

    /** The server doesn't support tools (llama.cpp, for one, needs to be started with --jinja). */
    class ToolsNotSupportedException(message: String) : LlmException(message)

    /**
     * Sends [messages] and calls [onText] with each piece of the answer. Blocks, so call it off the
     * main thread. Returns the whole answer. Throws [LlmException] with a short reason on failure.
     */
    fun stream(
        base: String,
        model: String,
        apiKey: String?,
        messages: List<ChatMessage>,
        onText: (String) -> Unit
    ): String = streamWithTools(base, model, apiKey, messages, null, onText).text

    /**
     * Like [stream], but offers the model [tools]. If it decides to use one, the reply carries
     * [Reply.toolCalls] and usually no text. Throws [ToolsNotSupportedException] when the server
     * refuses a request that includes tools.
     */
    fun streamWithTools(
        base: String,
        model: String,
        apiKey: String?,
        messages: List<ChatMessage>,
        tools: org.json.JSONArray?,
        onText: (String) -> Unit
    ): Reply {
        Endpoint.problem(base)?.let { throw LlmException(it) }
        val body = requestBody(model, messages, tools)
        // A failure before anything came back (a stale connection, a server that was busy or still
        // waking up) is tried once more on a fresh connection; asking again usually just works.
        var lastFailure: LlmException? = null
        repeat(2) { attempt ->
            val sentAnything = booleanArrayOf(false)
            try {
                return exchange(base, apiKey, body, tools != null, sentAnything) { piece ->
                    sentAnything[0] = true
                    onText(piece)
                }
            } catch (e: RetryableFailure) {
                lastFailure = e.failure
                if (sentAnything[0]) throw e.failure
            }
        }
        throw lastFailure ?: LlmException("Couldn't reach the server")
    }

    /**
     * The finished reply. A model that wrote its tool call as text, <tool_call>{...}</tool_call>,
     * instead of a structured call still gets its tool run, and the tags stay out of the answer.
     */
    private fun finish(text: String, structured: List<ToolCall>): Reply {
        val found = ToolCallText.extract(text)
        return Reply(found.text, structured.ifEmpty { found.calls })
    }

    /** A failure that happened before the model had answered, so asking again is safe. */
    private class RetryableFailure(val failure: LlmException) : Exception()

    private fun exchange(
        base: String, apiKey: String?, body: String, toolsSent: Boolean,
        sentAnything: BooleanArray, onText: (String) -> Unit
    ): Reply {
        val connection = URL(Endpoint.chatUrl(base)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            // A new connection every time: a kept-alive one the server has already closed fails on its first use.
            connection.setRequestProperty("Connection", "close")
            if (!apiKey.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) throw describe(code, connection, toolsSent = toolsSent)

            val answer = StringBuilder()
            val calls = ToolCallAccumulator()
            // A watchdog: if the server goes quiet after the answer has begun, find out whether it is still working, and
            // if it isn't close the connection, which ends the read.
            val lastData = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
            val begun = java.util.concurrent.atomic.AtomicBoolean(false)
            val silent = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    var idleSeen = 0
                    var seenAt = 0L
                    while (true) {
                        Thread.sleep(minOf(250L, statusPollMs))
                        val lastNanos = lastData.get()
                        val quietMs = (System.nanoTime() - lastNanos) / 1_000_000
                        if (!begun.get() || quietMs < statusAfterMs) { idleSeen = 0; continue }
                        // Ask now and then, not on every tick.
                        val sinceAsk = (System.nanoTime() - seenAt) / 1_000_000
                        val askNow = seenAt == 0L || sinceAsk >= statusPollMs
                        var ended = false
                        if (askNow) {
                            seenAt = System.nanoTime()
                            when (ServerStatus.idle(base, apiKey)) {
                                // Idle on two asks in a row, with nothing arriving between: it is over, not between words.
                                true -> { idleSeen++; if (idleSeen >= 2 && lastData.get() == lastNanos) ended = true }
                                false -> idleSeen = 0
                                null -> if (quietMs > silenceEndsAnswerMs) ended = true
                            }
                        }
                        if (ended) {
                            silent.set(true)
                            connection.disconnect()
                            return@Thread
                        }
                    }
                } catch (e: InterruptedException) {
                    // the answer finished by itself
                }
            }.apply { isDaemon = true; start() }
            try {
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        lastData.set(System.nanoTime())
                        when (val event = Sse.parse(line)) {
                            is Sse.Event.Text -> { begun.set(true); answer.append(event.text); onText(event.text) }
                            is Sse.Event.ToolDeltas -> { begun.set(true); sentAnything[0] = true; calls.add(event.deltas) }
                            is Sse.Event.Error -> throw LlmException(event.message.take(120))
                            Sse.Event.Done -> return finish(answer.toString(), calls.calls())
                            null -> {}
                        }
                        // The finish reason is the end of the answer, whether or not [DONE] and a closed connection follow.
                        if (Sse.isFinal(line)) return finish(answer.toString(), calls.calls())
                    }
                }
            } catch (e: java.io.IOException) {
                // The watchdog closed the connection on a server that had finished without saying so.
                if (silent.get()) return finish(answer.toString(), calls.calls())
                throw e
            } finally {
                watchdog.interrupt()
            }
            val reply = finish(answer.toString(), calls.calls())
            if (reply.text.isEmpty() && reply.toolCalls.isEmpty()) throw RetryableFailure(LlmException("The server sent no answer"))
            return reply
        } catch (e: LlmException) {
            throw e
        } catch (e: RetryableFailure) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RetryableFailure(LlmException("The server took too long to answer"))
        } catch (e: java.io.IOException) {
            throw RetryableFailure(LlmException("Couldn't reach the server"))
        } finally {
            connection.disconnect()
        }
    }

    /** The JSON for a chat request, including the tool calls and tool answers of an in-progress question. */
    internal fun requestBody(model: String, messages: List<ChatMessage>, tools: org.json.JSONArray?): String {
        val json = JSONObject()
            .put("model", Endpoint.modelFor(model))
            .put("stream", true)
            .put("messages", JSONArray().also { array ->
                messages.forEach { m ->
                    val o = JSONObject().put("role", m.role).put("content", m.content)
                    if (m.toolCalls.isNotEmpty()) {
                        o.put("tool_calls", JSONArray().also { calls ->
                            m.toolCalls.forEach { c ->
                                calls.put(JSONObject()
                                    .put("id", c.id)
                                    .put("type", "function")
                                    .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments)))
                            }
                        })
                    }
                    m.toolCallId?.let { o.put("tool_call_id", it) }
                    array.put(o)
                }
            })
        if (tools != null) json.put("tools", tools)
        return json.toString()
    }

    /** The models the server offers, or null if it can't be asked. Blocks, so call it off the main thread. */
    fun listModels(base: String, apiKey: String?): List<String>? {
        if (Endpoint.problem(base) != null) return null
        val connection = URL(ModelList.url(base)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            if (!apiKey.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (connection.responseCode !in 200..299) null
            else ModelList.parse(connection.inputStream.bufferedReader().readText())
        } catch (e: java.io.IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** A one-line check that the address, model and key work: asks for a single word. */
    fun test(base: String, model: String, apiKey: String?): String? = try {
        stream(base, model, apiKey, listOf(ChatMessage("user", "Reply with the word ok.")), {})
        null
    } catch (e: LlmException) {
        e.message ?: "Couldn't connect"
    }

    private fun describe(code: Int, connection: HttpURLConnection, toolsSent: Boolean): LlmException {
        val detail = try {
            JSONObject(connection.errorStream?.bufferedReader()?.readText() ?: "")
                .optJSONObject("error")?.text("message").orEmpty()
        } catch (e: Exception) { "" }
        if (Compaction.isContextError(code, detail)) return ContextTooLongException("The chat is too long for the model")
        if (toolsSent && isToolsError(code, detail)) return ToolsNotSupportedException("The server doesn't support tools")
        return LlmException(when (code) {
            401, 403 -> "The server rejected the API key"
            404 -> "The server doesn't know that address or model"
            429 -> "The server says to slow down. Try again soon"
            in 500..599 -> "The server had a problem ($code)"
            else -> detail.take(100).ifEmpty { "The server answered with error $code" }
        })
    }

    /** True when an error from a request that included tools says the server can't handle them. */
    internal fun isToolsError(status: Int, message: String): Boolean {
        if (status < 400) return false
        val text = message.lowercase()
        return listOf("tool", "function call", "function_call", "jinja").any { text.contains(it) }
    }
}
