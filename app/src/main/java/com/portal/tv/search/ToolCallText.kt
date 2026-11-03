package com.portal.tv.search

import org.json.JSONObject

/**
 * Some models (Qwen, for one) write a tool call as text, <tool_call>{...}</tool_call>, instead of the
 * structured call the server is meant to turn it into, especially when the server's chat template
 * doesn't match. This finds those calls so Portal can run them, and keeps the tags out of the chat.
 * Free of Android types so it can be unit tested.
 */
object ToolCallText {

    private const val OPEN = "<tool_call>"
    private val BLOCK = Regex("<tool_call>(.*?)(?:</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)
    private val FUNCTION = Regex("<function=([^>\\s]+)>(.*?)(?:</function>|$)", RegexOption.DOT_MATCHES_ALL)
    private val PARAMETER = Regex("<parameter=([^>\\s]+)>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)

    class Extracted(val text: String, val calls: List<ToolCall>)

    /**
     * The part of a streaming reply that is safe to show: everything before a tool call starts, and
     * nothing of a tag that is only partly written yet (such as a trailing "<tool_c").
     */
    fun visiblePrefix(text: String): String {
        val at = text.indexOf("<tool_call")
        if (at >= 0) return text.substring(0, at)
        for (k in minOf(OPEN.length - 1, text.length) downTo 1) {
            if (text.endsWith(OPEN.substring(0, k))) return text.substring(0, text.length - k)
        }
        return text
    }

    /** The reply without its tool-call blocks, and the calls those blocks asked for. */
    fun extract(text: String): Extracted {
        if (!text.contains("<tool_call")) return Extracted(text, emptyList())
        val calls = BLOCK.findAll(text).mapNotNull { parse(it.groupValues[1].trim()) }
            .mapIndexed { i, c -> c.copy(id = "call_text_$i") }.toList()
        return Extracted(BLOCK.replace(text, "").trim(), calls)
    }

    private fun parse(body: String): ToolCall? {
        if (body.startsWith("{")) {
            return try {
                val o = JSONObject(body)
                val fn = o.optJSONObject("function") ?: o
                val name = fn.optString("name").takeIf { it.isNotEmpty() } ?: return null
                val args = fn.opt("arguments") ?: fn.opt("parameters")
                ToolCall("", name, when (args) {
                    is JSONObject -> args.toString()
                    is String -> args.ifBlank { "{}" }
                    else -> "{}"
                })
            } catch (e: Exception) {
                null
            }
        }
        // The XML style: <function=web_search><parameter=query>text</parameter></function>
        val f = FUNCTION.find(body) ?: return null
        val args = JSONObject()
        PARAMETER.findAll(f.groupValues[2]).forEach { args.put(it.groupValues[1], it.groupValues[2].trim()) }
        return ToolCall("", f.groupValues[1], args.toString())
    }
}
