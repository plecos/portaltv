package com.portal.tv.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfSetupTest {

    @Test
    fun portalIsTheScreenSaverWhenNamedAndOn() {
        assertTrue(SelfSetup.isPortalSaver(SelfSetup.SAVER, enabled = true))
    }

    @Test
    fun theShortSpellingOfTheComponentCounts() {
        assertTrue(SelfSetup.isPortalSaver("com.portal.tv/.screensaver.ScreensaverService", enabled = true))
    }

    @Test
    fun portalCanBeOneOfSeveralComponents() {
        val list = "com.google.android.apps.tv.dreamx/.service.Backdrop, ${SelfSetup.SAVER}"
        assertTrue(SelfSetup.isPortalSaver(list, enabled = true))
    }

    @Test
    fun aScreenSaverThatIsTurnedOffDoesNotCount() {
        assertFalse(SelfSetup.isPortalSaver(SelfSetup.SAVER, enabled = false))
    }

    @Test
    fun anotherScreenSaverOrNoneDoesNotCount() {
        assertFalse(SelfSetup.isPortalSaver("com.google.android.apps.tv.dreamx/.service.Backdrop", enabled = true))
        assertFalse(SelfSetup.isPortalSaver(null, enabled = true))
        assertFalse(SelfSetup.isPortalSaver("", enabled = true))
    }

    @Test
    fun restoreBringsBackWhatTheTvHad() {
        val commands = SelfSetup.restoreScreensaverCommands(
            saved = true,
            components = "com.google.android.apps.tv.dreamx/.service.Backdrop",
            onSleep = "0"
        )
        assertEquals(
            listOf(
                "settings put secure screensaver_components 'com.google.android.apps.tv.dreamx/.service.Backdrop'",
                "settings put secure screensaver_activate_on_sleep 0",
                "settings put secure screensaver_enabled 1"
            ),
            commands
        )
    }

    @Test
    fun restoreClearsSettingsThatWereNotSetBefore() {
        val commands = SelfSetup.restoreScreensaverCommands(saved = true, components = null, onSleep = null)
        assertEquals(
            listOf(
                "settings delete secure screensaver_components",
                "settings delete secure screensaver_activate_on_sleep",
                "settings put secure screensaver_enabled 1"
            ),
            commands
        )
    }

    @Test
    fun restoreAfterAnOlderSetupJustTurnsTheTvsSaverBackOn() {
        // Older setups switched the system screen saver off and saved nothing.
        val commands = SelfSetup.restoreScreensaverCommands(saved = false, components = null, onSleep = null)
        assertEquals(
            listOf(
                "settings delete secure screensaver_components",
                "settings put secure screensaver_enabled 1"
            ),
            commands
        )
    }

    @Test
    fun aStartAfterTimeStartsTheSaverAfterThatLong() {
        assertEquals(
            listOf(
                "settings put secure screensaver_enabled 1",
                "settings put system screen_off_timeout 300000"
            ),
            SelfSetup.startAfterCommands(5)
        )
        assertEquals(1_800_000L, SelfSetup.startAfter(30).timeoutMs)
    }

    @Test
    fun startAfterOffStopsTheSaverAndLeavesTheTimeAlone() {
        assertEquals(listOf("settings put secure screensaver_enabled 0"), SelfSetup.startAfterCommands(0))
        assertFalse(SelfSetup.startAfter(0).enabled)
        assertEquals(null, SelfSetup.startAfter(0).timeoutMs)
    }

    @Test
    fun restoreAlsoPutsBackTheTvsStartTime() {
        val commands = SelfSetup.restoreScreensaverCommands(
            saved = true,
            components = null,
            onSleep = "0",
            timeoutMs = 600_000L
        )
        assertEquals(
            listOf(
                "settings delete secure screensaver_components",
                "settings put secure screensaver_activate_on_sleep 0",
                "settings put system screen_off_timeout 600000",
                "settings put secure screensaver_enabled 1"
            ),
            commands
        )
    }

    @Test
    fun restoreLeavesTheStartTimeAloneWhenNoneWasSaved() {
        val commands = SelfSetup.restoreScreensaverCommands(saved = true, components = null, onSleep = null, timeoutMs = null)
        assertFalse(commands.any { "screen_off_timeout" in it })
    }
}
