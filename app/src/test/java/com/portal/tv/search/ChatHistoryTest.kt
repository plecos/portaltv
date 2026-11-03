package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryTest {

    @Test
    fun messagesSurviveSavingAndLoading() {
        val messages = listOf(
            ChatMessage("user", "What's the weather like on Mars?"),
            ChatMessage("assistant", "Cold and dusty.\nAbout -60°C on average.")
        )
        assertEquals(ChatHistory.Chat("", messages), ChatHistory.decode(ChatHistory.encode(ChatHistory.Chat("", messages))))
    }

    @Test
    fun anEmptyChatRoundTrips() {
        assertEquals(ChatHistory.Chat.EMPTY, ChatHistory.decode(ChatHistory.encode(ChatHistory.Chat.EMPTY)))
    }

    @Test
    fun damagedFilesGiveAnEmptyChatRatherThanACrash() {
        assertEquals(ChatHistory.Chat.EMPTY, ChatHistory.decode("not json at all"))
        assertEquals(ChatHistory.Chat.EMPTY, ChatHistory.decode(""))
        assertEquals(ChatHistory.Chat.EMPTY, ChatHistory.decode("{\"a\":1}"))
    }

    @Test
    fun badEntriesAreSkippedButGoodOnesKept() {
        val json = """[{"role":"user","content":"hi"},{"role":"system","content":"x"},{"role":"assistant"},5,{"role":"assistant","content":"hello"}]"""
        assertEquals(listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "hello")), ChatHistory.decode(json).messages)
    }

    @Test
    fun onlyTheNewestMessagesAreKept() {
        val many = (1..300).map { ChatMessage(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val back = ChatHistory.decode(ChatHistory.encode(ChatHistory.Chat("", many))).messages
        assertEquals(ChatHistory.MAX_MESSAGES, back.size)
        assertEquals("m300", back.last().content)
        assertEquals("m101", back.first().content)
    }

    @Test
    fun theSummaryIsSavedWithTheMessages() {
        val chat = ChatHistory.Chat("They are planning a trip to Japan.", listOf(ChatMessage("user", "what about food?")))
        assertEquals(chat, ChatHistory.decode(ChatHistory.encode(chat)))
    }

    @Test
    fun chatsSavedBeforeSummariesExistStillLoad() {
        val old = """[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"}]"""
        val chat = ChatHistory.decode(old)
        assertEquals("", chat.summary)
        assertEquals(2, chat.messages.size)
    }
}
