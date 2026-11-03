package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelListTest {

    @Test
    fun theModelsAddressIsBuiltFromTheServerAddress() {
        assertEquals("http://192.168.1.5:8080/v1/models", ModelList.url("http://192.168.1.5:8080/v1"))
        assertEquals("http://192.168.1.5:8080/v1/models", ModelList.url("http://192.168.1.5:8080/v1/ "))
        assertEquals("https://api.openai.com/v1/models", ModelList.url("https://api.openai.com/v1"))
    }

    @Test
    fun anAddressThatEndsInTheChatPathStillFindsTheModels() {
        assertEquals("https://x.example/v1/models", ModelList.url("https://x.example/v1/chat/completions"))
    }

    @Test
    fun namesAreReadAndSorted() {
        val json = """{"object":"list","data":[{"id":"qwen2"},{"id":"Llama-3.1"},{"id":"gemma"}]}"""
        assertEquals(listOf("gemma", "Llama-3.1", "qwen2"), ModelList.parse(json))
    }

    @Test
    fun duplicatesAndBlankEntriesAreLeftOut() {
        val json = """{"data":[{"id":"a"},{"id":"a"},{"id":""},{"name":"no id"},5]}"""
        assertEquals(listOf("a"), ModelList.parse(json))
    }

    @Test
    fun unreadableResponsesGiveAnEmptyList() {
        assertEquals(emptyList<String>(), ModelList.parse(""))
        assertEquals(emptyList<String>(), ModelList.parse("not json"))
        assertEquals(emptyList<String>(), ModelList.parse("""{"data":"nope"}"""))
        assertEquals(emptyList<String>(), ModelList.parse("""{"models":[{"id":"x"}]}"""))
    }
}
