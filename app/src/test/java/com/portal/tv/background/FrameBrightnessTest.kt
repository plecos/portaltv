package com.portal.tv.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBrightnessTest {

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun blackIsZeroAndWhiteIs255() {
        assertEquals(0f, FrameBrightness.mean(IntArray(10) { grey(0) }), 0f)
        assertEquals(255f, FrameBrightness.mean(IntArray(10) { grey(255) }), 1f)
    }

    @Test
    fun anEmptyPictureCountsAsBlack() {
        assertEquals(0f, FrameBrightness.mean(IntArray(0)), 0f)
        assertTrue(FrameBrightness.isBlank(FrameBrightness.mean(IntArray(0))))
    }

    @Test
    fun anAlmostBlackFrameIsBlankButADarkSceneIsNot() {
        assertTrue(FrameBrightness.isBlank(FrameBrightness.mean(IntArray(10) { grey(6) })))
        assertFalse(FrameBrightness.isBlank(FrameBrightness.mean(IntArray(10) { grey(40) })))
    }

    @Test
    fun aNightSceneThatShowsAsAnEmptyTileIsTooDark() {
        // A campfire at night averaged 16 on the TV and looked like a blank tile; the dimmest good ones average about 30.
        assertTrue(FrameBrightness.isBlank(16f))
        assertFalse(FrameBrightness.isBlank(30f))
    }

    @Test
    fun theMeanWeighsGreenMostLikeTheEyeDoes() {
        val green = (0xFF shl 24) or (0xFF shl 8)
        val blue = (0xFF shl 24) or 0xFF
        assertTrue(FrameBrightness.mean(intArrayOf(green)) > FrameBrightness.mean(intArrayOf(blue)))
    }

    @Test
    fun aMostlyBlackFrameWithABrightPatchAveragesBetween() {
        val pixels = IntArray(100) { if (it < 10) grey(255) else grey(0) }
        assertEquals(25.5f, FrameBrightness.mean(pixels), 1f)
    }
}
