package com.portal.tv.search

import android.content.Context
import com.portal.tv.data.Profile

/** How much of search a profile may use. */
enum class SearchLevel(val label: String) {
    OFF("Off"),
    APPS("Apps only"),
    APPS_AI("Apps + AI");

    val findsApps get() = this != OFF
    val usesAi get() = this == APPS_AI
}

/**
 * Search choices. The level is per profile, and kid profiles start Off until a parent turns it on.
 * The AI server (address, model, key) is one setup for the whole TV.
 */
object SearchSettings {

    private const val PREFS = "search_settings"
    private const val KEY_API = "api_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Level ----------

    /** The default for a profile that has never been set: kids Off, everyone else apps only. */
    fun defaultLevel(isKids: Boolean): SearchLevel = if (isKids) SearchLevel.OFF else SearchLevel.APPS

    fun level(context: Context, profile: Profile): SearchLevel {
        val saved = prefs(context).getString("level_${profile.id}", null)
        return SearchLevel.entries.firstOrNull { it.name == saved } ?: defaultLevel(profile.isKids)
    }

    fun setLevel(context: Context, profileId: Int, level: SearchLevel) {
        prefs(context).edit().putString("level_$profileId", level.name).apply()
    }

    // ---------- Spoken replies ----------

    /** When the AI's replies are read aloud. Each profile has its own choice. */
    enum class SpokenReplies(val label: String) {
        OFF("Off"), AFTER_VOICE("After voice"), ALWAYS("Always")
    }

    fun spoken(context: Context, profileId: Int): SpokenReplies {
        val saved = prefs(context).getString("spoken_$profileId", null)
        return SpokenReplies.entries.firstOrNull { it.name == saved } ?: SpokenReplies.AFTER_VOICE
    }

    fun setSpoken(context: Context, profileId: Int, mode: SpokenReplies) {
        prefs(context).edit().putString("spoken_$profileId", mode.name).apply()
    }

    /** The voice (an engine voice name) a profile picked for spoken replies; null means the default. */
    fun voice(context: Context, profileId: Int): String? =
        prefs(context).getString("voice_$profileId", null)?.takeIf { it.isNotEmpty() }

    fun setVoice(context: Context, profileId: Int, name: String?) {
        prefs(context).edit().putString("voice_$profileId", name ?: "").apply()
    }

    fun clearForProfile(context: Context, profileId: Int) {
        prefs(context).edit().remove("level_$profileId").remove("spoken_$profileId").remove("voice_$profileId").apply()
        ChatHistory.clear(context, profileId)
    }

    // ---------- AI server (TV-wide) ----------

    fun baseUrl(context: Context): String = prefs(context).getString("base_url", "") ?: ""
    fun model(context: Context): String = prefs(context).getString("model", "") ?: ""
    /** The model's context size in tokens, as set by the user. Portal compacts the chat before it fills. */
    fun contextTokens(context: Context): Int = prefs(context).getInt("context_tokens", DEFAULT_CONTEXT_TOKENS)

    fun setContextTokens(context: Context, tokens: Int) {
        prefs(context).edit().putInt("context_tokens", tokens).apply()
    }

    const val DEFAULT_CONTEXT_TOKENS = 8_000
    val CONTEXT_CHOICES = listOf(4_000, 8_000, 16_000, 32_000, 128_000)

    // ---------- Web search (TV-wide) ----------

    fun webProvider(context: Context): WebProvider =
        WebProvider.entries.firstOrNull { it.name == prefs(context).getString("web_provider", null) }
            // DuckDuckGo was an option once; anyone who had it falls back to their own server if
            // there is one, and otherwise to Off.
            ?: if (Endpoint.problem(searxngUrl(context)) == null) WebProvider.SEARXNG else WebProvider.OFF

    fun setWebProvider(context: Context, provider: WebProvider) {
        prefs(context).edit().putString("web_provider", provider.name).apply()
    }

    fun searxngUrl(context: Context): String = prefs(context).getString("searxng_url", "") ?: ""

    fun setSearxngUrl(context: Context, value: String) {
        prefs(context).edit().putString("searxng_url", value.trim()).apply()
    }

    fun braveKey(context: Context): String? = SecretStore.get(context, "brave_key")
    fun hasBraveKey(context: Context): Boolean = SecretStore.has(context, "brave_key")
    fun setBraveKey(context: Context, value: String?) = SecretStore.put(context, "brave_key", value?.trim())

    /** True when the chosen web search service has what it needs to run. */
    fun isWebSearchReady(context: Context): Boolean =
        WebSearch.isReady(webProvider(context), searxngUrl(context), braveKey(context))

    // ---------- Tools support ----------

    private fun serverKey(context: Context) = "${baseUrl(context)}|${model(context)}"

    /** Remembers that this server and model refused tools, so Portal stops offering them. Changing either clears it. */
    fun markToolsUnsupported(context: Context) {
        prefs(context).edit().putString("tools_off_for", serverKey(context)).apply()
    }

    /** Forget a refusal, for example after the user restarts their server with tools turned on. */
    fun resetToolsSupport(context: Context) {
        prefs(context).edit().remove("tools_off_for").apply()
    }

    fun toolsSupported(context: Context): Boolean =
        prefs(context).getString("tools_off_for", null) != serverKey(context)

    fun apiKey(context: Context): String? = SecretStore.get(context, KEY_API)
    fun hasApiKey(context: Context): Boolean = SecretStore.has(context, KEY_API)

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString("base_url", value.trim()).apply()
    }

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString("model", value.trim()).apply()
    }

    /** An empty [value] removes the key; many local servers need none. */
    fun setApiKey(context: Context, value: String?) = SecretStore.put(context, KEY_API, value?.trim())

    /** True when a usable server address is saved. The model is optional. */
    fun isAiConfigured(context: Context): Boolean =
        Endpoint.problem(baseUrl(context)) == null
}
