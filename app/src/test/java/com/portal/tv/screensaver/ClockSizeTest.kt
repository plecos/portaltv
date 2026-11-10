package com.portal.tv.screensaver

import com.portal.tv.screensaver.ScreensaverSettings.ClockSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSizeTest {

    @Test
    fun largeIsTheOriginalSizeAndTheDefault() {
        assertEquals(1f, ClockSize.LARGE.scale, 0f)
        assertEquals(ClockSize.LARGE, ClockSize.fromKey(null))
        assertEquals(ClockSize.LARGE, ClockSize.fromKey("huge"))
    }

    @Test
    fun keysRoundTrip() {
        ClockSize.values().forEach { assertEquals(it, ClockSize.fromKey(it.key)) }
    }

    @Test
    fun sizesGetSmallerInOrder() {
        assertTrue(ClockSize.SMALL.scale < ClockSize.MEDIUM.scale)
        assertTrue(ClockSize.MEDIUM.scale < ClockSize.LARGE.scale)
    }
}
