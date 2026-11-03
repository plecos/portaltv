package com.portal.tv.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SeasonalVideosTest {

    private fun holiday(y: Int, m: Int, d: Int) = SeasonalVideos.holidayFor(LocalDate.of(y, m, d))

    @Test
    fun halloween_isTheDayBeforeAndTheDay() {
        assertNull(holiday(2026, 10, 29))
        assertEquals("halloween", holiday(2026, 10, 30))
        assertEquals("halloween", holiday(2026, 10, 31))
        assertNull(holiday(2026, 11, 1))
    }

    @Test
    fun thanksgiving_followsTheFourthThursday() {
        // Fourth Thursday: 2025-11-27, 2026-11-26, 2027-11-25, 2028-11-23.
        assertEquals("thanksgiving", holiday(2025, 11, 26))
        assertEquals("thanksgiving", holiday(2025, 11, 27))
        assertNull(holiday(2025, 11, 25))
        assertNull(holiday(2025, 11, 28))

        assertNull(holiday(2026, 11, 24))
        assertEquals("thanksgiving", holiday(2026, 11, 25))
        assertEquals("thanksgiving", holiday(2026, 11, 26))
        assertNull(holiday(2026, 11, 27))

        assertEquals("thanksgiving", holiday(2027, 11, 25))
        assertEquals("thanksgiving", holiday(2028, 11, 22))
        assertEquals("thanksgiving", holiday(2028, 11, 23))
    }

    @Test
    fun christmas_isTheDayBeforeAndTheDay() {
        assertNull(holiday(2026, 12, 23))
        assertEquals("christmas", holiday(2026, 12, 24))
        assertEquals("christmas", holiday(2026, 12, 25))
        assertNull(holiday(2026, 12, 26))
    }

    @Test
    fun newYear_crossesTheYearBoundary() {
        assertNull(holiday(2026, 12, 30))
        assertEquals("newyear", holiday(2026, 12, 31))
        assertEquals("newyear", holiday(2027, 1, 1))
        assertNull(holiday(2027, 1, 2))
    }

    @Test
    fun ordinaryDays_haveNoHoliday() {
        assertNull(holiday(2026, 3, 15))
        assertNull(holiday(2026, 7, 4))
        assertNull(holiday(2024, 2, 29)) // leap day
    }

    @Test
    fun pickFile_matchesHolidayByNamePrefix() {
        val files = listOf("halloween1.mp4", "christmas1.mp4", "newyears1.mp4", "thanksgiving1.mp4")
        val day = LocalDate.of(2026, 12, 31)
        assertEquals("newyears1.mp4", SeasonalVideos.pickFile(files, "newyear", day))
        assertEquals("halloween1.mp4", SeasonalVideos.pickFile(files, "halloween", day))
    }

    @Test
    fun pickFile_ignoresCaseDashesAndUnderscores() {
        val day = LocalDate.of(2027, 1, 1)
        for (name in listOf("New-Years.mp4", "new_year_2.MP4", "NEWYEAR.mp4", "new-years1.mp4")) {
            assertEquals(name, SeasonalVideos.pickFile(listOf(name), "newyear", day))
        }
    }

    @Test
    fun pickFile_returnsNullWhenThereIsNoMatchingVideo() {
        val day = LocalDate.of(2026, 10, 31)
        assertNull(SeasonalVideos.pickFile(emptyList(), "halloween", day))
        assertNull(SeasonalVideos.pickFile(listOf("christmas1.mp4"), "halloween", day))
        // Not a video, so it doesn't count.
        assertNull(SeasonalVideos.pickFile(listOf("halloween1.png", "halloween.txt"), "halloween", day))
    }

    @Test
    fun pickFile_variesByDayButIsStableWithinADay() {
        val files = listOf("halloween1.mp4", "halloween2.mp4", "halloween3.mp4")
        val day = LocalDate.of(2026, 10, 31)
        assertEquals(
            SeasonalVideos.pickFile(files, "halloween", day),
            SeasonalVideos.pickFile(files.reversed(), "halloween", day)
        )
        val picks = (24..31).map { SeasonalVideos.pickFile(files, "halloween", LocalDate.of(2026, 10, it)) }
        assertEquals(files.toSet(), picks.toSet())
    }

    @Test
    fun aFileBelongsToTheHolidayItsNameStartsWith() {
        assertEquals("halloween", SeasonalVideos.holidayOfFile("halloween2.mp4"))
        assertEquals("halloween", SeasonalVideos.holidayOfFile("Halloween_1.mp4"))
        assertEquals("newyear", SeasonalVideos.holidayOfFile("new-years.mp4"))
        assertEquals("christmas", SeasonalVideos.holidayOfFile("christmas1.mp4"))
        assertEquals(null, SeasonalVideos.holidayOfFile("space1.mp4"))
    }
}
