package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {

    @Test
    fun chatUrlIsBuiltFromTheServerAddress() {
        assertEquals("https://api.openai.com/v1/chat/completions", Endpoint.chatUrl("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/chat/completions", Endpoint.chatUrl("https://api.openai.com/v1/"))
        assertEquals("http://192.168.1.5:11434/v1/chat/completions", Endpoint.chatUrl(" http://192.168.1.5:11434/v1 "))
    }

    @Test
    fun anAddressThatAlreadyEndsInTheChatPathIsKept() {
        assertEquals("https://x.example/v1/chat/completions", Endpoint.chatUrl("https://x.example/v1/chat/completions"))
    }

    @Test
    fun httpsAddressesAreAccepted() {
        assertNull(Endpoint.problem("https://api.openai.com/v1"))
        assertNull(Endpoint.problem("https://my-server.example.com:8443/v1"))
    }

    @Test
    fun httpIsAcceptedOnlyOnTheLocalNetwork() {
        assertNull(Endpoint.problem("http://192.168.1.50:11434/v1"))
        assertNull(Endpoint.problem("http://10.0.0.7:8080/v1"))
        assertNull(Endpoint.problem("http://172.20.1.1/v1"))
        assertNull(Endpoint.problem("http://localhost:1234/v1"))
        assertNull(Endpoint.problem("http://ollama.local:11434/v1"))
        assertNotNull(Endpoint.problem("http://api.openai.com/v1"))
        assertNotNull(Endpoint.problem("http://8.8.8.8/v1"))
        assertNotNull(Endpoint.problem("http://172.32.0.1/v1")) // just outside 172.16 to 172.31
    }

    @Test
    fun badAddressesGiveAReason() {
        assertNotNull(Endpoint.problem(""))
        assertNotNull(Endpoint.problem("   "))
        assertNotNull(Endpoint.problem("api.openai.com/v1"))
        assertNotNull(Endpoint.problem("ftp://example.com"))
        assertNotNull(Endpoint.problem("https://"))
    }

    @Test
    fun privateHostRanges() {
        assertTrue(Endpoint.isPrivateHost("192.168.0.1"))
        assertTrue(Endpoint.isPrivateHost("172.16.0.1"))
        assertTrue(Endpoint.isPrivateHost("172.31.255.255"))
        assertTrue(Endpoint.isPrivateHost("127.0.0.1"))
        assertTrue(Endpoint.isPrivateHost("169.254.1.1"))
        assertFalse(Endpoint.isPrivateHost("172.15.0.1"))
        assertFalse(Endpoint.isPrivateHost("192.169.0.1"))
        assertFalse(Endpoint.isPrivateHost("1.2.3.4"))
        assertFalse(Endpoint.isPrivateHost("example.com"))
        assertFalse(Endpoint.isPrivateHost("999.1.1.1"))
    }

    @Test
    fun aBlankModelIsReplacedByAPlaceholder() {
        assertEquals(Endpoint.DEFAULT_MODEL, Endpoint.modelFor(""))
        assertEquals(Endpoint.DEFAULT_MODEL, Endpoint.modelFor("   "))
    }

    @Test
    fun aSetModelIsSentAsTyped() {
        assertEquals("llama3.1", Endpoint.modelFor("llama3.1"))
        assertEquals("llama3.1", Endpoint.modelFor("  llama3.1 "))
    }
}
