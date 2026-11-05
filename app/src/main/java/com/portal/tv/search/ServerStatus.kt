package com.portal.tv.search

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks an AI server whether it is working on anything, for the times its answer stream goes quiet. A server that is
 * still generating says so (and a finished or dead one says it isn't), which is better than guessing from how long the
 * silence has lasted. It understands two common shapes, found next to the chat address rather than under it:
 * `/status`, an object with `busy` (and `queued`), and llama.cpp's `/slots`, a list of slots with `is_processing`.
 */
object ServerStatus {

    private val paths = listOf("/status", "/slots")

    /** For each server, the path that answered in a way we understand, or "" for a server that has none. */
    private val known = ConcurrentHashMap<String, String>()

    /** The server's own address: scheme, host and port, without `/v1` or any other path. */
    internal fun root(base: String): String? = try {
        val uri = URI(base.trim())
        if (uri.scheme == null || uri.host == null) null
        else uri.scheme + "://" + uri.host + (if (uri.port != -1) ":${uri.port}" else "")
    } catch (e: Exception) {
        null
    }

    /**
     * True if the server says it isn't working on anything, false if it says it is, null if it can't be asked or the
     * answer isn't one we understand. Blocks briefly, so call it off the main thread.
     */
    fun idle(base: String, apiKey: String?): Boolean? {
        val root = root(base) ?: return null
        val remembered = known[root]
        if (remembered == "") return null
        for (path in if (remembered != null) listOf(remembered) else paths) {
            val body = fetch(root + path, apiKey)
            if (body == null) {
                // Couldn't reach it right now: say nothing, and ask again next time.
                if (remembered != null) return null
                continue
            }
            val idle = parseIdle(body)
            if (idle != null) {
                known[root] = path
                return idle
            }
        }
        if (remembered == null) known[root] = ""
        return null
    }

    /** What a status reply says: true for idle, false for busy, null if it isn't a shape we know. */
    internal fun parseIdle(body: String): Boolean? {
        val text = body.trim()
        try {
            if (text.startsWith("[")) {
                val slots = JSONArray(text)
                if (slots.length() == 0) return null
                var seen = false
                for (i in 0 until slots.length()) {
                    val slot = slots.optJSONObject(i) ?: continue
                    if (!slot.has("is_processing")) continue
                    seen = true
                    if (slot.optBoolean("is_processing")) return false
                }
                return if (seen) true else null
            }
            if (text.startsWith("{")) {
                val status = JSONObject(text)
                if (!status.has("busy")) return null
                return !status.optBoolean("busy") && status.optInt("queued", 0) == 0
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun fetch(url: String, apiKey: String?): String? {
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return null }
        return try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            if (!apiKey.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (connection.responseCode !in 200..299) "" else connection.inputStream.bufferedReader().readText().take(200_000)
        } catch (e: java.io.IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
