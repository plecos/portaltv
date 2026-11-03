package com.portal.tv.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyPickTest {

    private val videos = listOf("default.mp4", "aurora1.mp4", "aurora2.mp4", "space1.mp4", "space2.mp4", "space3.mp4")

    @Test
    fun noVideosGivesNothing() {
        assertNull(DailyPick.pick(emptyList(), LocalDate.of(2026, 10, 30)))
    }

    @Test
    fun aSingleVideoIsAlwaysChosen() {
        assertEquals("only.mp4", DailyPick.pick(listOf("only.mp4"), LocalDate.of(2026, 10, 30)))
    }

    @Test
    fun theSameDayGivesTheSameVideo_whateverTheListOrder() {
        val day = LocalDate.of(2026, 10, 30)
        assertEquals(DailyPick.pick(videos, day), DailyPick.pick(videos.reversed(), day))
        assertEquals(DailyPick.pick(videos, day), DailyPick.pick(videos, day))
    }

    @Test
    fun anAnswerIsAlwaysOneOfTheVideos() {
        for (offset in 0L..400L) {
            assertTrue(DailyPick.pick(videos, LocalDate.of(2026, 1, 1).plusDays(offset)) in videos)
        }
    }

    @Test
    fun noDayRepeatsTheDayBefore() {
        var day = LocalDate.of(2026, 1, 1)
        repeat(1000) {
            assertNotEquals(DailyPick.pick(videos, day), DailyPick.pick(videos, day.plusDays(1)))
            day = day.plusDays(1)
        }
    }

    @Test
    fun overAYearEveryVideoGetsPlayed() {
        val seen = (0L..365L).map { DailyPick.pick(videos, LocalDate.of(2026, 1, 1).plusDays(it)) }.toSet()
        assertEquals(videos.toSet(), seen)
    }

    @Test
    fun theChoiceIsReasonablyEvenOverAYear() {
        val counts = (0L..729L).groupingBy { DailyPick.pick(videos, LocalDate.of(2025, 1, 1).plusDays(it)) }.eachCount()
        // 730 days over 6 videos is about 122 each; allow a wide margin.
        assertTrue(counts.values.all { it in 70..180 })
    }

    @Test
    fun everyVideoIsPlayedOncePerCycleBeforeAnyRepeats() {
        // Cycles are as long as the list, so any run of six consecutive days starting on a cycle
        // boundary holds each video exactly once.
        val start = LocalDate.ofEpochDay((LocalDate.of(2026, 1, 1).toEpochDay() / 6) * 6)
        repeat(60) { cycle ->
            val days = (0L until 6L).map { DailyPick.pick(videos, start.plusDays(cycle * 6 + it)) }
            assertEquals(videos.toSet(), days.toSet())
        }
    }

    @Test
    fun addingAVideoDoesNotCrashAndStillNeverRepeatsDayToDay() {
        val more = videos + "special-extra.mp4"
        var day = LocalDate.of(2026, 5, 1)
        repeat(500) {
            assertNotEquals(DailyPick.pick(more, day), DailyPick.pick(more, day.plusDays(1)))
            day = day.plusDays(1)
        }
    }

    @Test
    fun twoVideosAlternate() {
        val two = listOf("a.mp4", "b.mp4")
        var day = LocalDate.of(2026, 3, 1)
        repeat(50) {
            assertNotEquals(DailyPick.pick(two, day), DailyPick.pick(two, day.plusDays(1)))
            day = day.plusDays(1)
        }
    }
}
