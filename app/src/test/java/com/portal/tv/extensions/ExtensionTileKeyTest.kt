package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionTileKeyTest {

    @Test
    fun aTileKeyRoundTrips() {
        val key = ExtensionTileKey.of("net.example.panel", "lights")
        assertEquals("portal.ext:net.example.panel/lights", key)
        assertEquals("net.example.panel" to "lights", ExtensionTileKey.parse(key))
        assertTrue(ExtensionTileKey.isTile(key))
    }

    @Test
    fun anOrdinaryPackageNameIsNotATile() {
        assertFalse(ExtensionTileKey.isTile("com.netflix.ninja"))
        assertNull(ExtensionTileKey.parse("com.netflix.ninja"))
    }

    @Test
    fun brokenKeysAreRefused() {
        assertNull(ExtensionTileKey.parse("portal.ext:"))
        assertNull(ExtensionTileKey.parse("portal.ext:net.example.panel"))
        assertNull(ExtensionTileKey.parse("portal.ext:net.example.panel/"))
        assertNull(ExtensionTileKey.parse("portal.ext:/lights"))
    }

    @Test
    fun theTileIdIsWhatFollowsTheLastSlash() {
        assertEquals("a/b" to "c", ExtensionTileKey.parse("portal.ext:a/b/c"))
    }
}

class ExtensionLabelsTest {

    @Test
    fun statusSaysWhatIsAllowed() {
        assertEquals("Needs your permission", ExtensionLabels.status(ExtensionState.NOT_ALLOWED, emptySet()))
        assertEquals("Allowed: tiles, cards", ExtensionLabels.status(ExtensionState.ALLOWED, linkedSetOf("tiles", "cards")))
        assertEquals("Allowed: settings page", ExtensionLabels.status(ExtensionState.ALLOWED, setOf("settings")))
        assertTrue(ExtensionLabels.status(ExtensionState.SIGNER_CHANGED, emptySet()).contains("someone else"))
        assertEquals("Needs a newer Portal", ExtensionLabels.status(ExtensionState.UNSUPPORTED, emptySet()))
        assertEquals("Can't be used", ExtensionLabels.status(ExtensionState.INVALID, emptySet()))
    }

    @Test
    fun theSummaryCountsFoundAndAllowed() {
        assertEquals("None installed", ExtensionLabels.summary(0, 0))
        assertEquals("1 found, needs your permission", ExtensionLabels.summary(1, 0))
        assertEquals("3 found, need your permission", ExtensionLabels.summary(3, 0))
        assertEquals("1 found, needs your permission", ExtensionLabels.summary(1, 0))
        assertEquals("2 found, 1 allowed", ExtensionLabels.summary(2, 1))
        assertEquals("2 found, 1 allowed · 1 update", ExtensionLabels.summary(2, 1, 1))
        assertEquals("2 found, need your permission · 2 updates", ExtensionLabels.summary(2, 0, 2))
    }

    @Test
    fun everyKnownCapabilityHasAShortName() {
        Capability.known.forEach { assertTrue(it, ExtensionLabels.shortName(it).isNotBlank()) }
        assertEquals("settings page", ExtensionLabels.shortName(Capability.SETTINGS))
        // One it doesn't know is shown as written.
        assertEquals("teleport", ExtensionLabels.shortName("teleport"))
    }
}
