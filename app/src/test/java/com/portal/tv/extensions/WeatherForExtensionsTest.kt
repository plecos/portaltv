package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherForExtensionsTest {

    @Test
    fun everyWmoCodeGetsOneConditionAndValuesFromZeroToOne() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99, 123, -1)
        for (code in codes) {
            val (rain, snow, thunder) = WeatherForExtensions.precipitation(code)
            for (v in listOf(rain, snow, thunder, WeatherForExtensions.fog(code, null), WeatherForExtensions.clouds(code, null, maxOf(rain, snow)))) assertTrue("$code: $v", v in 0.0..1.0)
            assertTrue(WeatherForExtensions.condition(code, 0.5) in setOf("clear", "partly_cloudy", "cloudy", "fog", "drizzle", "rain", "snow", "thunderstorm"))
        }
    }

    @Test
    fun rainAndSnowAreNeverBoth() {
        for (code in 0..99) {
            val (rain, snow, _) = WeatherForExtensions.precipitation(code)
            assertTrue("$code", rain == 0.0 || snow == 0.0)
        }
    }

    @Test
    fun heavierWeatherCodesGiveHeavierValues() {
        assertTrue(WeatherForExtensions.precipitation(65).first > WeatherForExtensions.precipitation(61).first)
        assertTrue(WeatherForExtensions.precipitation(75).second > WeatherForExtensions.precipitation(71).second)
        assertEquals(1.0, WeatherForExtensions.precipitation(95).third, 0.0)
    }

    @Test
    fun cloudsFollowTheMeasuredCoverButRainMeansCloud() {
        assertEquals(0.3, WeatherForExtensions.clouds(1, 30, 0.0), 0.0)
        assertEquals(0.7, WeatherForExtensions.clouds(61, 10, 0.35), 0.0)
        assertEquals(1.0, WeatherForExtensions.clouds(3, 250, 0.0), 0.0)
    }

    @Test
    fun fogComesFromTheCodeOrFromHowFarOneCanSee() {
        assertEquals(0.7, WeatherForExtensions.fog(45, null), 0.0)
        assertEquals(0.9, WeatherForExtensions.fog(0, 0.1), 0.0)
        assertEquals(0.0, WeatherForExtensions.fog(0, 20.0), 0.0)
    }

    @Test
    fun theLocationIsRoundedToATenthOfADegree() {
        assertEquals(47.6, WeatherForExtensions.tenth(47.6062), 0.0)
        assertEquals(-122.3, WeatherForExtensions.tenth(-122.3321), 0.0)
        assertEquals(-0.1, WeatherForExtensions.tenth(-0.06), 0.0)
    }

    @Test
    fun aBadLocalTimeGivesNothing() {
        assertEquals(null, WeatherForExtensions.atMillis("later", 0))
    }
}
