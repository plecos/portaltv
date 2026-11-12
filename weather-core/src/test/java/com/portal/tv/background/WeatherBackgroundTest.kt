package com.portal.tv.background

import com.portal.tv.background.WeatherBackground.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherBackgroundTest {

    @Test
    fun codesBecomeKinds() {
        assertEquals(Kind.CLEAR, WeatherBackground.kindFor(0))
        assertEquals(Kind.CLEAR, WeatherBackground.kindFor(1))
        assertEquals(Kind.PARTLY_CLOUDY, WeatherBackground.kindFor(2))
        assertEquals(Kind.OVERCAST, WeatherBackground.kindFor(3))
        assertEquals(Kind.FOG, WeatherBackground.kindFor(45))
        assertEquals(Kind.RAIN, WeatherBackground.kindFor(61))
        assertEquals(Kind.RAIN, WeatherBackground.kindFor(82))
        assertEquals(Kind.SNOW, WeatherBackground.kindFor(73))
        assertEquals(Kind.STORM, WeatherBackground.kindFor(95))
        assertEquals(Kind.OVERCAST, WeatherBackground.kindFor(1234))
    }
}
