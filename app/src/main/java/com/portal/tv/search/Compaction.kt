package com.portal.tv.search

/**
 * Keeps a long chat inside the model's context. Portal can't know a server's real limit, so it
 * works from a context size the user sets, estimates how big the next request will be, and when
 * that gets close to the limit, summarizes the older messages and keeps only the recent ones.
 * Free of Android types so it can be unit tested.
 */
object Compaction {

    /** Compact once the request would use more than this share of the context size. */
    const val TRIGGER = 0.75

    /** Messages kept word for word when compacting. */
    const val KEEP_RECENT = 6

    /** A smaller tail for the retry after a server says the context was exceeded. */
    const val KEEP_RECENT_TIGHT = 2

    /** A rough size: about four characters per token, plus a few tokens of framing per message. */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4 + 4

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimateTokens(it.content) }

    /** The estimated size of the request that would be sent for [history] with [summary]. */
    fun requestTokens(history: List<ChatMessage>, summary: String?, isKid: Boolean): Int =
        estimate(ChatPrompt.build(history, isKid, summary))

    fun needsCompaction(requestTokens: Int, contextTokens: Int): Boolean =
        requestTokens > contextTokens * TRIGGER

    /**
     * Splits [history] into the older messages to summarize and the recent ones to keep, or null
     * when there is nothing older to summarize. The kept part always starts with a user message.
     */
    fun split(history: List<ChatMessage>, keepRecent: Int): Pair<List<ChatMessage>, List<ChatMessage>>? {
        if (history.size <= keepRecent) return null
        var cut = history.size - keepRecent
        // Don't start the kept part with an answer whose question is being summarized away.
        while (cut > 0 && history[cut].role != "user") cut--
        if (cut <= 0) return null
        return history.take(cut) to history.drop(cut)
    }

    /**
     * Last resort when summarizing isn't possible or isn't enough: drop the oldest messages until
     * the request fits, always keeping the newest one.
     */
    fun trimToFit(history: List<ChatMessage>, summary: String?, isKid: Boolean, contextTokens: Int): List<ChatMessage> {
        var kept = history
        while (kept.size > 1 && requestTokens(kept, summary, isKid) > contextTokens * TRIGGER) {
            kept = kept.drop(1)
            while (kept.size > 1 && kept.first().role != "user") kept = kept.drop(1)
        }
        return kept
    }

    private const val SUMMARY_SYSTEM =
        "You write short, faithful summaries of conversations so they can be continued later. " +
            "Keep names, facts, preferences and any question still open. Write plain text, " +
            "under 200 words, with no preamble."

    /** The messages that ask the model to fold [older] into [existingSummary]. */
    fun summaryRequest(existingSummary: String?, older: List<ChatMessage>): List<ChatMessage> {
        val transcript = older.joinToString("\n") {
            (if (it.role == "user") "User: " else "Assistant: ") + it.content
        }
        val prior = if (existingSummary.isNullOrBlank()) "" else "Summary so far:\n$existingSummary\n\n"
        return listOf(
            ChatMessage("system", SUMMARY_SYSTEM),
            ChatMessage("user", "${prior}New part of the conversation:\n$transcript\n\nWrite the updated summary.")
        )
    }

    private val CONTEXT_HINTS = listOf(
        "context length", "context_length", "maximum context", "context window", "too many tokens",
        "token limit", "too long", "reduce the length", "exceeds the context", "max_tokens"
    )

    /** True when an error response means the request was too big for the model's context. */
    fun isContextError(status: Int, message: String): Boolean {
        if (status != 400 && status != 413 && status != 422) return false
        val text = message.lowercase()
        return CONTEXT_HINTS.any { text.contains(it) }
    }
}
