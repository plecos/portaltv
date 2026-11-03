package com.portal.tv.background

import com.portal.tv.background.WeatherBackground.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WeatherBackgroundTest {

    private val day = LocalDate.of(2026, 10, 30)

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

    @Test
    fun aFileIsMatchedByTheStartOfItsName() {
        val files = listOf("default.mp4", "Rain_1.mp4", "snow-heavy.mp4", "notes.txt")
        assertEquals("Rain_1.mp4", WeatherBackground.pickFile(files, Kind.RAIN, true, day))
        assertEquals("snow-heavy.mp4", WeatherBackground.pickFile(files, Kind.SNOW, true, day))
        assertNull(WeatherBackground.pickFile(files, Kind.CLEAR, true, day))
    }

    @Test
    fun sunnyCountsAsClear() {
        assertEquals("sunny1.mp4", WeatherBackground.pickFile(listOf("sunny1.mp4"), Kind.CLEAR, true, day))
    }

    @Test
    fun nightFilesAreUsedAfterDarkAndDayFilesByDay() {
        val files = listOf("clear.mp4", "clear_night.mp4")
        assertEquals("clear.mp4", WeatherBackground.pickFile(files, Kind.CLEAR, true, day))
        assertEquals("clear_night.mp4", WeatherBackground.pickFile(files, Kind.CLEAR, false, day))
    }

    @Test
    fun aKindWithOnlyOneVersionUsesItAtAnyTime() {
        assertEquals("rain.mp4", WeatherBackground.pickFile(listOf("rain.mp4"), Kind.RAIN, false, day))
    }

    @Test
    fun missingKindsBorrowANearOne() {
        val files = listOf("overcast.mp4", "rain.mp4")
        assertEquals("overcast.mp4", WeatherBackground.pickFile(files, Kind.PARTLY_CLOUDY, true, day))
        assertEquals("overcast.mp4", WeatherBackground.pickFile(files, Kind.FOG, true, day))
        assertEquals("rain.mp4", WeatherBackground.pickFile(files, Kind.STORM, true, day))
    }

    @Test
    fun severalFilesTakeTurnsByDay() {
        val files = listOf("rain1.mp4", "rain2.mp4")
        val picks = (0 until 4).map { WeatherBackground.pickFile(files, Kind.RAIN, true, day.plusDays(it.toLong())) }
        assertEquals(setOf("rain1.mp4", "rain2.mp4"), picks.toSet())
    }
}
