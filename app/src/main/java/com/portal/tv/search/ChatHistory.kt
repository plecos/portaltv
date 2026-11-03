package com.portal.tv.search

import android.content.Context
import org.json.JSONArray
import com.portal.tv.search.Sse.text
import org.json.JSONObject
import java.io.File

/** A profile's saved conversation with the AI, kept on the TV until it is cleared. */
object ChatHistory {

    /** The most messages kept per profile; older ones drop off. */
    const val MAX_MESSAGES = 200

    private fun file(context: Context, profileId: Int) = File(context.filesDir, "chat_$profileId.json")

    /** A saved conversation: messages kept in full, plus a summary of anything compacted away. */
    data class Chat(val summary: String, val messages: List<ChatMessage>) {
        companion object { val EMPTY = Chat("", emptyList()) }
    }

    fun load(context: Context, profileId: Int): Chat =
        file(context, profileId).takeIf { it.exists() }?.let { decode(it.readText()) } ?: Chat.EMPTY

    fun save(context: Context, profileId: Int, chat: Chat) {
        file(context, profileId).writeText(encode(chat))
    }

    fun clear(context: Context, profileId: Int) {
        file(context, profileId).delete()
    }

    internal fun encode(chat: Chat): String {
        val array = JSONArray()
        chat.messages.takeLast(MAX_MESSAGES).forEach {
            array.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        return JSONObject().put("summary", chat.summary).put("messages", array).toString()
    }

    /** Reads a saved chat (also the older format, a bare list of messages), ignoring anything malformed. */
    internal fun decode(json: String): Chat = try {
        val text = json.trim()
        if (text.startsWith("[")) Chat("", messages(JSONArray(text)))
        else JSONObject(text).let { Chat(it.text("summary"), messages(it.optJSONArray("messages"))) }
    } catch (e: Exception) {
        Chat.EMPTY
    }

    private fun messages(array: JSONArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val role = o.text("role")
            val content = o.text("content")
            if ((role == "user" || role == "assistant") && content.isNotEmpty()) ChatMessage(role, content) else null
        }
    }
}
