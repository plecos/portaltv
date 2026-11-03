package com.portal.tv.search

import java.net.URI

/** Rules for the AI server address. Free of Android types so it can be unit tested. */
object Endpoint {

    /** Sent when no model is set: single-model servers such as llama.cpp and LM Studio ignore the name. */
    const val DEFAULT_MODEL = "default"

    /** The model name to put in a request: what the user set, or [DEFAULT_MODEL] when it's blank. */
    fun modelFor(model: String): String = model.trim().ifEmpty { DEFAULT_MODEL }


    /** The chat URL for a server address such as "https://api.openai.com/v1" or "http://192.168.1.5:11434/v1". */
    fun chatUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    /** Null when [base] is usable, otherwise a short reason to show the user. */
    fun problem(base: String): String? {
        val text = base.trim()
        if (text.isEmpty()) return "Enter the server address"
        val uri = try { URI(text) } catch (e: Exception) { return "That address isn't valid" }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (host.isNullOrEmpty() || (scheme != "http" && scheme != "https")) {
            return "Start with https:// (or http:// for a server on your network)"
        }
        if (scheme == "http" && !isPrivateHost(host)) {
            return "Use https:// unless the server is on your own network"
        }
        return null
    }

    /** True for addresses that never leave your network: localhost, .local names and private IP ranges. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h == "::1") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
