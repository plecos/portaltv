package com.portal.tv.search

import com.portal.tv.search.Sse.text
import org.json.JSONObject

/** Reads the list of models an OpenAI-compatible server offers. Free of Android types so it can be unit tested. */
object ModelList {

    /** The address that lists models, for a server address such as "http://192.168.1.5:11434/v1". */
    fun url(base: String): String {
        val trimmed = base.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        return "$trimmed/models"
    }

    /** The model names in a `/models` response, sorted, or an empty list if it can't be read. */
    fun parse(json: String): List<String> = try {
        val data = JSONObject(json).optJSONArray("data")
        (0 until (data?.length() ?: 0))
            .mapNotNull { data!!.optJSONObject(it)?.text("id")?.takeIf { id -> id.isNotEmpty() } }
            .distinct()
            .sortedBy { it.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }
}
