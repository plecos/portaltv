package com.portal.tv.search

import org.json.JSONObject

/** Reads the server-sent events an OpenAI-compatible server streams back. */
object Sse {

    sealed interface Event {
        /** More of the answer. */
        data class Text(val text: String) : Event

        /** Pieces of the tool calls the model is making; a call arrives split across many events. */
        data class ToolDeltas(val deltas: List<ToolDelta>) : Event

        /** The stream is finished. */
        data object Done : Event

        /** The server reported an error inside the stream. */
        data class Error(val message: String) : Event
    }

    /** One piece of a tool call: its position, and any of its id, function name and argument text so far. */
    data class ToolDelta(val index: Int, val id: String?, val name: String?, val arguments: String?)

    /** Turns one line of the stream into an event, or null for lines that carry nothing (blank, comments, role-only chunks). */
    fun parse(line: String): Event? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") return Event.Done
        return try {
            val json = JSONObject(data)
            json.optJSONObject("error")?.let { return Event.Error(it.text("message").ifEmpty { "Error" }) }
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            val calls = delta.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                return Event.ToolDeltas((0 until calls.length()).mapNotNull { i ->
                    val c = calls.optJSONObject(i) ?: return@mapNotNull null
                    val fn = c.optJSONObject("function")
                    ToolDelta(
                        index = c.optInt("index", i),
                        id = c.text("id").takeIf { it.isNotEmpty() },
                        name = fn?.text("name")?.takeIf { it.isNotEmpty() },
                        arguments = fn?.text("arguments")?.takeIf { it.isNotEmpty() }
                    )
                })
            }
            val text = delta.text("content")
            if (text.isEmpty()) null else Event.Text(text)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether this line says the answer is complete: its choice carries a finish reason ("stop", "tool_calls", "length"
     * and so on). Servers should follow it with [DONE] and close the connection, but some do neither, and waiting for them
     * leaves the answer looking unfinished until the read times out.
     */
    fun isFinal(line: String): Boolean {
        if (!line.startsWith("data:")) return false
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return false
        return try {
            val reason = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.takeUnless { it.isNull("finish_reason") }
                ?.optString("finish_reason").orEmpty()
            reason.isNotEmpty() && reason != "null"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The text under [key], or empty if it is missing or a JSON null. (org.json's optString turns a
     * null into the word "null", which is how a server's empty first chunk came to show as a reply.)
     */
    internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
