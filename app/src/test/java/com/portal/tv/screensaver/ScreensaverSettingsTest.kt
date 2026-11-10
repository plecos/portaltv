package com.portal.tv.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverSettingsTest {

    @Test
    fun night_runsFrom10pmTo6am() {
        assertFalse(ScreensaverSettings.isNight(12))
        assertFalse(ScreensaverSettings.isNight(21))
        assertTrue(ScreensaverSettings.isNight(22))
        assertTrue(ScreensaverSettings.isNight(23))
        assertTrue(ScreensaverSettings.isNight(0))
        assertTrue(ScreensaverSettings.isNight(5))
        assertFalse(ScreensaverSettings.isNight(6))
    }

    @Test
    fun theTvTakesTheFirstAdultProfilesChoices() {
        // A kid is listed first; the first adult after them is the owner.
        assertEquals(2, ScreensaverSettings.ownerOf(listOf(1 to true, 2 to false, 3 to false)))
        assertEquals(1, ScreensaverSettings.ownerOf(listOf(1 to false, 2 to false)))
    }

    @Test
    fun withOnlyKidsTheFirstProfileIsTheOwner_andWithNoneThereIsNoOwner() {
        assertEquals(4, ScreensaverSettings.ownerOf(listOf(4 to true, 5 to true)))
        assertNull(ScreensaverSettings.ownerOf(emptyList()))
    }

    @Test
    fun aShaderBackgroundGaveAShaderScreenSaver_anythingElseGivesVideos() {
        assertEquals(ScreensaverSettings.LOOK_SHADER, ScreensaverSettings.lookFor("shader"))
        assertEquals(ScreensaverSettings.LOOK_VIDEO, ScreensaverSettings.lookFor("video"))
        assertEquals(ScreensaverSettings.LOOK_VIDEO, ScreensaverSettings.lookFor("off"))
        assertEquals(ScreensaverSettings.LOOK_VIDEO, ScreensaverSettings.lookFor(null))
    }

    @Test
    fun anExtensionsScreenSaverIsPickedByItsPackage() {
        val ext = ScreensaverSettings.LOOK_EXTENSION
        assertEquals("$ext:net.getportaltv.scenic", ScreensaverSettings.choiceOf(ext, "net.getportaltv.scenic"))
        assertEquals(ScreensaverSettings.LOOK_SHADER, ScreensaverSettings.choiceOf(ScreensaverSettings.LOOK_SHADER, "net.getportaltv.scenic"))
        // An extension look with no extension chosen is nothing a tile can show as chosen.
        assertEquals(ext, ScreensaverSettings.choiceOf(ext, null))
        assertEquals(ext to "net.getportaltv.scenic", ScreensaverSettings.parseChoice("$ext:net.getportaltv.scenic"))
        assertEquals(ScreensaverSettings.LOOK_VIDEO to null, ScreensaverSettings.parseChoice(ScreensaverSettings.LOOK_VIDEO))
    }
}
