package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerStatusTest {

    @Test
    fun theServersOwnAddressIsWhereTheChatAddressStarts() {
        assertEquals("http://192.168.10.20:8080", ServerStatus.root("http://192.168.10.20:8080/v1"))
        assertEquals("https://example.com", ServerStatus.root(" https://example.com/v1/ "))
        assertEquals("http://localhost:11434", ServerStatus.root("http://localhost:11434/v1/chat/completions"))
        assertNull(ServerStatus.root("not an address"))
    }

    @Test
    fun aBusyFlagSaysWhetherTheServerIsWorking() {
        assertEquals(false, ServerStatus.parseIdle("""{"busy": true, "queued": 0, "phase": "answering", "generated": 12}"""))
        assertEquals(true, ServerStatus.parseIdle("""{"busy": false, "queued": 0, "phase": "answering", "generated": 459}"""))
        // Work waiting in the queue is not idle.
        assertEquals(false, ServerStatus.parseIdle("""{"busy": false, "queued": 2}"""))
    }

    @Test
    fun llamaCppSlotsSayWhetherAnySlotIsProcessing() {
        assertEquals(true, ServerStatus.parseIdle("""[{"id": 0, "n_ctx": 4096, "is_processing": false}]"""))
        assertEquals(false, ServerStatus.parseIdle("""[{"id": 0, "is_processing": false}, {"id": 1, "is_processing": true}]"""))
    }

    @Test
    fun anythingElseCantBeTold() {
        assertNull(ServerStatus.parseIdle(""))
        assertNull(ServerStatus.parseIdle("<html>not found</html>"))
        assertNull(ServerStatus.parseIdle("""{"status": "ok"}"""))
        assertNull(ServerStatus.parseIdle("[]"))
        assertNull(ServerStatus.parseIdle("""[{"id": 0}]"""))
        assertNull(ServerStatus.parseIdle("{ broken"))
    }
}
