package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {

    private fun look(alt: Float, c: Conditions = Conditions(cloud = 0f), snow: Float = 0f) = Look().apply { update(alt, c, snow, 0f) }
    private fun brightness(c: Int) = Colors.r(c) + Colors.g(c) + Colors.b(c)

    @Test
    fun daylightGrowsWithTheSun() {
        var last = -1f
        for (alt in -20..40 step 4) {
            val d = look(alt.toFloat()).day
            assertTrue("$alt: $d", d >= last)
            last = d
        }
        assertEquals(0f, look(-20f).day, 0f)
        assertEquals(1f, look(40f).day, 0f)
    }

    @Test
    fun theSkyIsDarkAtNightBrightByDayAndWarmNearTheHorizonAtSunset() {
        assertTrue(brightness(look(-20f).skyBottom) < 90)
        assertTrue(brightness(look(40f).skyTop) > 300)
        val sunset = look(-1f)
        assertTrue("orange near the horizon", Colors.r(sunset.skyBottom) > Colors.b(sunset.skyBottom) + 60)
        assertTrue(sunset.glowStrength > 0.5f)
    }

    @Test
    fun overcastAndStormsDarkenTheDay() {
        val clear = look(40f)
        val overcast = look(40f, Conditions(cloud = 1f))
        val storm = look(40f, Conditions(cloud = 1f, rain = 0.9f, thunder = 1f))
        assertTrue(brightness(overcast.skyTop) != brightness(clear.skyTop))
        assertTrue(brightness(storm.skyTop) < brightness(overcast.skyTop))
        assertTrue(storm.tintR < clear.tintR)
        assertTrue(storm.cloudLit != clear.cloudLit)
    }

    @Test
    fun starsShowOnlyOnClearNights() {
        assertTrue(look(-25f).starVisibility > 0.9f)
        assertEquals(0f, look(30f).starVisibility, 0.001f)
        assertTrue(look(-25f, Conditions(cloud = 1f)).starVisibility < 0.15f)
        assertTrue(look(-25f, Conditions(cloud = 0f, fog = 1f)).starVisibility < 0.01f)
    }

    @Test
    fun fogPullsTheSkyTowardOneColour() {
        val fog = look(40f, Conditions(cloud = 0f, fog = 1f))
        val gap = kotlin.math.abs(brightness(fog.skyTop) - brightness(fog.skyBottom))
        val clearGap = kotlin.math.abs(brightness(look(40f).skyTop) - brightness(look(40f).skyBottom))
        assertTrue("fog $gap vs clear $clearGap", gap < clearGap)
    }

    @Test
    fun everyColourIsAValidOpaqueRgb() {
        for (alt in -30..80 step 5) for (c in Conditions.PRESETS) {
            val l = look(alt.toFloat(), c.second)
            for (v in listOf(l.skyTop, l.skyBottom, l.fogColor, l.cloudLit, l.cloudShade, l.glow)) assertEquals(0xFF, v ushr 24)
            assertTrue(l.tintR in 0f..1.2f && l.tintG in 0f..1.2f && l.tintB in 0f..1.2f)
        }
    }
}
