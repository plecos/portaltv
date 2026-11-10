package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundChoiceTest {

    private val allowed = setOf(Capability.BACKGROUND)

    @Test
    fun anAllowedChosenExtensionIsUsed() {
        assertEquals(BackgroundChoice.Use.Extension("a.b"), BackgroundChoice.resolve("a.b", allowed, hasService = true, kid = false))
    }

    @Test
    fun everyOtherCaseUsesPortalsOwn() {
        fun own(ext: String?, granted: Set<String>, service: Boolean, kid: Boolean) =
            BackgroundChoice.resolve(ext, granted, service, kid) is BackgroundChoice.Use.Own
        assertTrue(own("a.b", allowed, true, kid = true))
        assertTrue(BackgroundChoice.resolve("a.b", allowed, true, kid = true, kidAllowed = false) is BackgroundChoice.Use.Own)
        assertTrue(own(null, allowed, true, false))
        assertTrue(own("a.b", emptySet(), true, false))
        assertTrue(own("a.b", setOf(Capability.TILES, Capability.CARDS), true, false))
        assertTrue(own("a.b", allowed, false, false))
    }

    @Test
    fun aKidUsesItOnlyWhenAParentAllowedItForThem() {
        assertEquals(BackgroundChoice.Use.Extension("a.b"), BackgroundChoice.resolve("a.b", allowed, hasService = true, kid = true, kidAllowed = true))
        // A parent's yes doesn't override the rest: it must still be allowed to draw, and have something to open.
        assertTrue(BackgroundChoice.resolve("a.b", emptySet(), true, kid = true, kidAllowed = true) is BackgroundChoice.Use.Own)
        assertTrue(BackgroundChoice.resolve("a.b", allowed, false, kid = true, kidAllowed = true) is BackgroundChoice.Use.Own)
        // An adult profile is not affected by it either way.
        assertEquals(BackgroundChoice.Use.Extension("a.b"), BackgroundChoice.resolve("a.b", allowed, true, kid = false, kidAllowed = false))
    }

    @Test
    fun theSizeAskedForIsCapped() {
        assertEquals(1920 to 1080, BackgroundChoice.size(1920, 1080))
        assertEquals(1280 to 720, BackgroundChoice.size(1280, 720))
        assertEquals(1920 to 1080, BackgroundChoice.size(3840, 2160))
        assertEquals(1920 to 1080, BackgroundChoice.size(0, 0))
        val (w, h) = BackgroundChoice.size(4096, 1000)
        assertTrue(w <= 1920 && h <= 1080)
    }
}

class BackgroundManifestTest {

    private fun parse(body: String) = ManifestParser.parse(xml("""<portal-extension contract="1" id="p" label="P">$body</portal-extension>"""), "net.example.panel")

    @Test
    fun aBackgroundServiceIsReadAndImpliesItsCapability() {
        val m = (parse("""<background service=".Draw"/>""") as ManifestParser.Result.Ok).manifest
        assertEquals("net.example.panel.Draw", m.backgroundService)
        assertEquals(setOf(Capability.BACKGROUND), m.capabilities)
    }

    @Test
    fun screenSaverKeysAreOffUnlessAsked() {
        assertEquals(false, (parse("""<background service=".Draw"/>""") as ManifestParser.Result.Ok).manifest.backgroundKeys)
        assertEquals(false, (parse("""<background service=".Draw" screenSaverKeys="yes"/>""") as ManifestParser.Result.Ok).manifest.backgroundKeys)
        assertEquals(true, (parse("""<background service=".Draw" screenSaverKeys="true"/>""") as ManifestParser.Result.Ok).manifest.backgroundKeys)
    }

    @Test
    fun onlyLeftAndRightAreEverForwarded() {
        assertTrue(BackgroundProtocol.forwards(21) && BackgroundProtocol.forwards(22))
        // up, down, select, back, home, power, menu, play/pause, a letter: all of them still wake the TV.
        for (k in listOf(19, 20, 23, 4, 3, 26, 82, 85, 29)) assertTrue("key $k", !BackgroundProtocol.forwards(k))
    }

    @Test
    fun noBackgroundMeansNoCapability() {
        val m = (parse("") as ManifestParser.Result.Ok).manifest
        assertEquals(null, m.backgroundService)
        assertTrue(Capability.BACKGROUND !in m.capabilities)
    }

    @Test
    fun badBackgroundsAreReported() {
        assertTrue((parse("""<background/>""") as ManifestParser.Result.Invalid).reason.contains("no service"))
        assertTrue((parse("""<background service=".A"/><background service=".B"/>""") as ManifestParser.Result.Invalid).reason.contains("more than one"))
    }
}
