package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {

    private fun chat(pairs: Int, size: Int = 40): List<ChatMessage> =
        (1..pairs).flatMap {
            listOf(ChatMessage("user", "q$it " + "x".repeat(size)), ChatMessage("assistant", "a$it " + "y".repeat(size)))
        }

    // ---------- estimating ----------

    @Test
    fun anEstimateGrowsWithLength() {
        assertTrue(Compaction.estimateTokens("hello") < Compaction.estimateTokens("hello ".repeat(100)))
        assertEquals(Compaction.estimateTokens("a".repeat(400)), (400 + 3) / 4 + 4)
    }

    @Test
    fun emptyTextStillCostsAFewTokens() {
        assertTrue(Compaction.estimateTokens("") > 0)
    }

    @Test
    fun requestSizeIncludesTheSystemPromptSummaryAndMessages() {
        val history = chat(2)
        val plain = Compaction.requestTokens(history, null, isKid = false)
        assertTrue(plain > Compaction.estimate(history))
        assertTrue(Compaction.requestTokens(history, "a summary ".repeat(30), false) > plain)
        assertTrue(Compaction.requestTokens(history, null, isKid = true) > plain) // the kid prompt is longer
    }

    // ---------- when to compact ----------

    @Test
    fun compactionStartsPastThreeQuartersOfTheContext() {
        assertFalse(Compaction.needsCompaction(5_999, 8_000))
        assertFalse(Compaction.needsCompaction(6_000, 8_000))
        assertTrue(Compaction.needsCompaction(6_001, 8_000))
        assertTrue(Compaction.needsCompaction(9_000, 8_000))
    }

    // ---------- splitting ----------

    @Test
    fun aShortChatHasNothingToSummarize() {
        assertNull(Compaction.split(chat(2), keepRecent = 6))
        assertNull(Compaction.split(emptyList(), keepRecent = 6))
    }

    @Test
    fun splitKeepsTheRecentMessagesAndSummarizesTheRest() {
        val history = chat(6) // 12 messages
        val (older, recent) = Compaction.split(history, keepRecent = 6)!!
        assertEquals(6, older.size)
        assertEquals(6, recent.size)
        assertEquals(history, older + recent)
    }

    @Test
    fun theKeptPartAlwaysStartsWithAQuestion() {
        val history = chat(5) + ChatMessage("user", "latest")
        for (keep in 1..8) {
            val parts = Compaction.split(history, keep) ?: continue
            assertEquals("user", parts.second.first().role)
            assertEquals("latest", parts.second.last().content)
            assertEquals(history, parts.first + parts.second)
        }
    }

    @Test
    fun theNewestMessageIsNeverSummarizedAway() {
        val history = chat(4) + ChatMessage("user", "newest question")
        val (_, recent) = Compaction.split(history, Compaction.KEEP_RECENT_TIGHT)!!
        assertEquals("newest question", recent.last().content)
    }

    // ---------- the last resort ----------

    @Test
    fun trimmingDropsTheOldestUntilItFits() {
        val history = chat(20, size = 400)
        val trimmed = Compaction.trimToFit(history, null, false, contextTokens = 1_000)
        assertTrue(trimmed.size < history.size)
        assertEquals(history.last(), trimmed.last())
        assertEquals("user", trimmed.first().role)
        assertTrue(Compaction.requestTokens(trimmed, null, false) <= 1_000 * Compaction.TRIGGER || trimmed.size == 1)
    }

    @Test
    fun trimmingLeavesAChatThatAlreadyFits() {
        val history = chat(2)
        assertEquals(history, Compaction.trimToFit(history, null, false, contextTokens = 100_000))
    }

    @Test
    fun trimmingNeverEmptiesTheChat() {
        val history = listOf(ChatMessage("user", "x".repeat(50_000)))
        assertEquals(history, Compaction.trimToFit(history, null, false, contextTokens = 100))
    }

    // ---------- asking for a summary ----------

    @Test
    fun theSummaryRequestHoldsTheOlderMessages() {
        val request = Compaction.summaryRequest(null, listOf(ChatMessage("user", "I like jazz"), ChatMessage("assistant", "Great taste")))
        assertEquals("system", request.first().role)
        val body = request.last().content
        assertTrue(body.contains("User: I like jazz"))
        assertTrue(body.contains("Assistant: Great taste"))
        assertFalse(body.contains("Summary so far"))
    }

    @Test
    fun anExistingSummaryIsCarriedIntoTheNextOne() {
        val request = Compaction.summaryRequest("They like jazz.", listOf(ChatMessage("user", "and blues")))
        assertTrue(request.last().content.contains("Summary so far:\nThey like jazz."))
    }

    // ---------- reading a "too long" error ----------

    @Test
    fun contextErrorsAreRecognizedFromTheServersMessage() {
        assertTrue(Compaction.isContextError(400, "This model's maximum context length is 8192 tokens"))
        assertTrue(Compaction.isContextError(400, "context_length_exceeded"))
        assertTrue(Compaction.isContextError(413, "Request too long"))
        assertTrue(Compaction.isContextError(400, "prompt is too long: 9000 tokens"))
    }

    @Test
    fun otherErrorsAreNotTakenForContextProblems() {
        assertFalse(Compaction.isContextError(400, "Invalid model"))
        assertFalse(Compaction.isContextError(401, "maximum context length"))
        assertFalse(Compaction.isContextError(500, "too long"))
        assertFalse(Compaction.isContextError(400, ""))
    }

    // ---------- the whole idea ----------

    @Test
    fun compactingBringsALongChatUnderTheTrigger() {
        val history = chat(40, size = 200)
        val budget = 4_000
        assertTrue(Compaction.needsCompaction(Compaction.requestTokens(history, null, false), budget))
        val (older, recent) = Compaction.split(history, Compaction.KEEP_RECENT)!!
        assertNotNull(older)
        val afterSummary = Compaction.requestTokens(recent, "a short summary of the older messages", false)
        assertFalse(Compaction.needsCompaction(afterSummary, budget))
    }
}
