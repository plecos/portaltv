package com.portal.tv.search

/**
 * One message in a chat; [role] is "user" or "assistant" when saved. While the model is using tools a
 * request also carries "assistant" messages with [toolCalls] and "tool" messages answering them by
 * [toolCallId]; those only live for one question and are never saved.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

/** The instructions and message list sent to the AI. Free of Android types so it can be unit tested. */
object ChatPrompt {

    private const val TV_CONTEXT =
        "You are an assistant inside Portal, a home screen app on a television. The user is a person " +
            "using a TV remote and reading from across the room, so keep answers short, clear and " +
            "easy to read. Simple markdown such as bold text and short lists is fine; avoid tables."

    private const val KID_CONTEXT =
        " The user is a child. Always keep your answers age appropriate: simple, kind and safe. " +
            "Do not discuss violence, sexual content, drugs, or other mature topics. If asked about " +
            "something like that, gently say you can't help with it and suggest asking a parent."

    fun systemPrompt(isKid: Boolean): String = if (isKid) TV_CONTEXT + KID_CONTEXT else TV_CONTEXT

    private val NOW_FORMAT = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z", java.util.Locale.US)

    /** The line that tells the model what day and time it is, since it can't know that itself. */
    fun nowLine(now: java.time.ZonedDateTime): String =
        " The current date and time is ${now.format(NOW_FORMAT)}."

    /**
     * The system prompt (with [summary] of any earlier, compacted conversation) followed by the
     * messages of [history], oldest first. Keeping the request small is [Compaction]'s job.
     */
    fun build(
        history: List<ChatMessage>, isKid: Boolean, summary: String? = null, toolNote: String = "",
        now: java.time.ZonedDateTime? = java.time.ZonedDateTime.now()
    ): List<ChatMessage> {
        val system = systemPrompt(isKid) + (now?.let { nowLine(it) } ?: "") + toolNote +
            if (summary.isNullOrBlank()) "" else "\n\nSummary of the earlier conversation:\n$summary"
        return listOf(ChatMessage("system", system)) + history
    }
}
