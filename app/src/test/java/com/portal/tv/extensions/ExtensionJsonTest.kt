package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionJsonTest {

    @Test
    fun whatWasAllowedSurvivesStorage() {
        val map = mapOf(
            "net.example.panel" to Allowance("aa".repeat(32), linkedSetOf("tiles", "cards")),
            "net.example.photos" to Allowance("bb".repeat(32), linkedSetOf("settings"))
        )
        assertEquals(map, ExtensionJson.decode(ExtensionJson.encode(map)))
    }

    @Test
    fun nothingOrDamagedStorageMeansNothingAllowed() {
        assertTrue(ExtensionJson.decode(null).isEmpty())
        assertTrue(ExtensionJson.decode("not json").isEmpty())
        assertTrue(ExtensionJson.decode("""[{"package":"x"}]""").isEmpty())
    }

    @Test
    fun anEntryWithNoCapabilitiesReadsAsNone() {
        val map = ExtensionJson.decode("""[{"package":"x","signer":"aa"}]""")
        assertEquals(emptySet<String>(), map.getValue("x").capabilities)
    }
}
