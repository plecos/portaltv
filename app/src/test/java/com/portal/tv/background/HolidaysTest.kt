package com.portal.tv.background

import com.portal.tv.background.Holidays.Holiday
import com.portal.tv.background.Holidays.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {

    private val builtIns = Holidays.builtIns.map { it.copy(videos = listOf("special/${it.id}1.mp4")) }
    private fun active(y: Int, m: Int, d: Int) = Holidays.active(builtIns, LocalDate.of(y, m, d))?.id

    @Test
    fun halloween_isTheDayBeforeAndTheDay() {
        assertNull(active(2026, 10, 29))
        assertEquals("halloween", active(2026, 10, 30))
        assertEquals("halloween", active(2026, 10, 31))
        assertNull(active(2026, 11, 1))
    }

    @Test
    fun thanksgiving_followsTheFourthThursday() {
        // Fourth Thursday: 2025-11-27, 2026-11-26, 2027-11-25, 2028-11-23.
        assertEquals("thanksgiving", active(2025, 11, 26))
        assertEquals("thanksgiving", active(2025, 11, 27))
        assertNull(active(2025, 11, 25))
        assertNull(active(2025, 11, 28))
        assertNull(active(2026, 11, 24))
        assertEquals("thanksgiving", active(2026, 11, 25))
        assertEquals("thanksgiving", active(2026, 11, 26))
        assertNull(active(2026, 11, 27))
        assertEquals("thanksgiving", active(2027, 11, 25))
        assertEquals("thanksgiving", active(2028, 11, 23))
    }

    @Test
    fun christmas_isTheDayBeforeAndTheDay() {
        assertNull(active(2026, 12, 23))
        assertEquals("christmas", active(2026, 12, 24))
        assertEquals("christmas", active(2026, 12, 25))
        assertNull(active(2026, 12, 26))
    }

    @Test
    fun newYear_crossesTheYearBoundary() {
        assertNull(active(2026, 12, 30))
        assertEquals("newyear", active(2026, 12, 31))
        assertEquals("newyear", active(2027, 1, 1))
        assertNull(active(2027, 1, 2))
    }

    @Test
    fun ordinaryDays_haveNoHoliday() {
        assertNull(active(2026, 3, 15))
        assertNull(active(2026, 7, 4))
        assertNull(active(2024, 2, 29)) // leap day
    }

    @Test
    fun aHolidayWithNoVideosIsNotShown() {
        val bare = Holidays.builtIns // no videos
        assertNull(Holidays.active(bare, LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun startingEarlier_widensTheWindow() {
        val week = Holiday("x", "X", Rule.Fixed(5, 20), daysBefore = 7, videos = listOf("a.mp4"))
        assertFalse(Holidays.isActive(week, LocalDate.of(2026, 5, 12)))
        assertTrue(Holidays.isActive(week, LocalDate.of(2026, 5, 13)))
        assertTrue(Holidays.isActive(week, LocalDate.of(2026, 5, 20)))
        assertFalse(Holidays.isActive(week, LocalDate.of(2026, 5, 21)))
        val onTheDay = week.copy(daysBefore = 0)
        assertFalse(Holidays.isActive(onTheDay, LocalDate.of(2026, 5, 19)))
        assertTrue(Holidays.isActive(onTheDay, LocalDate.of(2026, 5, 20)))
    }

    @Test
    fun theNearerHolidayWinsWhenWindowsOverlap() {
        val a = Holiday("a", "A", Rule.Fixed(6, 10), 3, listOf("a.mp4"))
        val b = Holiday("b", "B", Rule.Fixed(6, 12), 3, listOf("b.mp4"))
        // On 9 June both are due; A comes first.
        assertEquals("a", Holidays.active(listOf(b, a), LocalDate.of(2026, 6, 9))?.id)
        // After A is over, only B is left.
        assertEquals("b", Holidays.active(listOf(b, a), LocalDate.of(2026, 6, 11))?.id)
    }

    @Test
    fun weekdayRules_findTheRightDay() {
        // Mother's Day: second Sunday of May. Memorial Day: last Monday of May. Labor Day: first Monday of September.
        assertEquals(LocalDate.of(2026, 5, 10), Holidays.dateIn(Rule.Nth(2, 7, 5), 2026))
        assertEquals(LocalDate.of(2026, 5, 25), Holidays.dateIn(Rule.Nth(5, 1, 5), 2026))
        assertEquals(LocalDate.of(2026, 9, 7), Holidays.dateIn(Rule.Nth(1, 1, 9), 2026))
        assertEquals(LocalDate.of(2027, 5, 31), Holidays.dateIn(Rule.Nth(5, 1, 5), 2027))
    }

    @Test
    fun easter_isWorkedOutEachYear() {
        assertEquals(LocalDate.of(2025, 4, 20), Holidays.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), Holidays.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Holidays.easter(2027))
        assertEquals(LocalDate.of(2038, 4, 25), Holidays.easter(2038))
    }

    @Test
    fun aLeapDayHoliday_fallsOnThe28thInOtherYears() {
        assertEquals(LocalDate.of(2028, 2, 29), Holidays.dateIn(Rule.Fixed(2, 29), 2028))
        assertEquals(LocalDate.of(2027, 2, 28), Holidays.dateIn(Rule.Fixed(2, 29), 2027))
    }

    @Test
    fun nextDate_isTodayOrLater() {
        val christmas = Rule.Fixed(12, 25)
        assertEquals(LocalDate.of(2026, 12, 25), Holidays.nextDate(christmas, LocalDate.of(2026, 12, 25)))
        assertEquals(LocalDate.of(2027, 12, 25), Holidays.nextDate(christmas, LocalDate.of(2026, 12, 26)))
    }

    @Test
    fun pick_variesByDayAndHandlesNone() {
        assertNull(Holidays.pick(emptyList(), LocalDate.of(2026, 10, 31)))
        val files = listOf("a.mp4", "b.mp4", "c.mp4")
        assertEquals("a.mp4", Holidays.pick(listOf("a.mp4"), LocalDate.of(2026, 10, 31)))
        val picks = (28..31).map { Holidays.pick(files, LocalDate.of(2026, 10, it)) }
        assertEquals(3, picks.toSet().size)
    }

    @Test
    fun saving_roundTripsAndSkipsWhatCantBeRead() {
        val list = listOf(
            Holiday("a", "Alpha", Rule.Fixed(3, 4), 2, listOf("x.mp4", "special/y.mp4")),
            Holiday("b", "Beta", Rule.Nth(5, 1, 5), 0),
            Holiday("c", "Gamma", Rule.Easter, 7, listOf("dl_1.mp4"))
        )
        assertEquals(list, Holidays.fromJson(Holidays.toJson(list)))
        assertNull(Holidays.fromJson("not json"))
        // One bad entry doesn't lose the rest.
        val damaged = """[{"id":"a","name":"A","rule":{"type":"fixed","month":1,"day":2},"before":1,"videos":[]},{"id":"b"}]"""
        assertEquals(listOf("a"), Holidays.fromJson(damaged)?.map { it.id })
    }

    @Test
    fun words_describeTheDate() {
        assertEquals("Every Dec 25", Holidays.ruleText(Rule.Fixed(12, 25)))
        assertEquals("The 4th Thursday of November", Holidays.ruleText(Rule.Nth(4, 4, 11)))
        assertEquals("The last Monday of May", Holidays.ruleText(Rule.Nth(5, 1, 5)))
        val h = Holiday("t", "T", Rule.Fixed(11, 26), 1)
        assertEquals("Plays Nov 25 to Nov 26", Holidays.playsText(h, LocalDate.of(2026, 11, 4)))
        assertEquals("Plays on Nov 26", Holidays.playsText(h.copy(daysBefore = 0), LocalDate.of(2026, 11, 4)))
    }
}
