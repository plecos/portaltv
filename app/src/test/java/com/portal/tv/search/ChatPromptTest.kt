package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun adultPromptSaysItIsATvApp() {
        val p = ChatPrompt.systemPrompt(isKid = false)
        assertTrue(p.contains("television", ignoreCase = true))
        assertTrue(p.contains("remote", ignoreCase = true))
        assertFalse(p.contains("child", ignoreCase = true))
    }

    @Test
    fun kidPromptSaysTheUserIsAChildAndToBeAgeAppropriate() {
        val p = ChatPrompt.systemPrompt(isKid = true)
        assertTrue(p.contains("The user is a child"))
        assertTrue(p.contains("age appropriate"))
        assertTrue(p.contains("ask", ignoreCase = true) && p.contains("parent"))
    }

    @Test
    fun kidPromptKeepsTheTvContextToo() {
        assertTrue(ChatPrompt.systemPrompt(true).startsWith(ChatPrompt.systemPrompt(false)))
    }

    @Test
    fun theSystemPromptComesFirstThenHistory() {
        val history = listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "hello"), ChatMessage("user", "why?"))
        val messages = ChatPrompt.build(history, isKid = false)
        assertEquals("system", messages.first().role)
        assertEquals(history, messages.drop(1))
    }

    @Test
    fun everyMessageInTheHistoryIsSent_compactionKeepsItSmall() {
        val history = (1..50).map { ChatMessage(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val messages = ChatPrompt.build(history, isKid = true)
        assertEquals(51, messages.size)
        assertEquals("m1", messages[1].content)
        assertEquals("m50", messages.last().content)
    }

    @Test
    fun aSummaryOfEarlierMessagesGoesIntoTheSystemPrompt() {
        val messages = ChatPrompt.build(listOf(ChatMessage("user", "hi")), isKid = false, summary = "They like jazz.")
        assertEquals(2, messages.size)
        assertTrue(messages.first().content.startsWith(ChatPrompt.systemPrompt(false)))
        assertTrue(messages.first().content.contains("Summary of the earlier conversation"))
        assertTrue(messages.first().content.contains("They like jazz."))
    }

    @Test
    fun noSummaryMeansTheSystemPromptIsUnchanged() {
        assertEquals(ChatPrompt.systemPrompt(false), ChatPrompt.build(emptyList(), false, null, now = null).first().content)
        assertEquals(ChatPrompt.systemPrompt(false), ChatPrompt.build(emptyList(), false, "  ", now = null).first().content)
    }

    @Test
    fun kidSystemPromptIsInEveryRequest() {
        val messages = ChatPrompt.build(listOf(ChatMessage("user", "hi")), isKid = true)
        assertTrue(messages.first().content.contains("child"))
    }
}

class ChatPromptToolNoteTest {

    @Test
    fun theToolNoteIsAddedToTheSystemPrompt() {
        val note = Tools.note(openApp = true, webSearch = true)
        val system = ChatPrompt.build(emptyList(), isKid = false, toolNote = note).first().content
        assertTrue(system.startsWith(ChatPrompt.systemPrompt(false)))
        assertTrue(system.contains("web_search"))
    }

    @Test
    fun noNoteLeavesThePromptAlone() {
        assertEquals(ChatPrompt.systemPrompt(true), ChatPrompt.build(emptyList(), isKid = true, now = null).first().content)
    }
}

class ChatPromptDateTest {

    private val saturday = java.time.ZonedDateTime.of(2026, 10, 31, 11, 24, 0, 0, java.time.ZoneId.of("America/Los_Angeles"))

    @Test
    fun theSystemPromptStatesTheCurrentDateAndTime() {
        val system = ChatPrompt.build(emptyList(), isKid = false, now = saturday).first().content
        assertTrue(system.contains("The current date and time is Saturday, October 31, 2026 at 11:24 AM PDT."))
    }

    @Test
    fun theDateComesAfterTheInstructionsAndBeforeTheToolNote() {
        val system = ChatPrompt.build(emptyList(), isKid = true, toolNote = " TOOLS.", now = saturday).first().content
        assertTrue(system.startsWith(ChatPrompt.systemPrompt(true)))
        assertTrue(system.indexOf("The current date") < system.indexOf("TOOLS."))
    }

    @Test
    fun withNoTimeGivenThePromptIsJustTheInstructions() {
        assertEquals(ChatPrompt.systemPrompt(false), ChatPrompt.build(emptyList(), false, now = null).first().content)
    }

    @Test
    fun aKidSeesTheDateToo() {
        val system = ChatPrompt.build(emptyList(), isKid = true, now = saturday).first().content
        assertTrue(system.contains("October 31, 2026"))
        assertTrue(system.contains("The user is a child"))
    }
}
