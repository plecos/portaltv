package com.portal.tv.screensaver

import org.junit.Assert.assertFalse
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
}
