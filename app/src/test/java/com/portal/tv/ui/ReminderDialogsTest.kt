package com.portal.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderDialogsTest {

    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2026, 11, 5, 10, 0)
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun laterToday_isToday() {
        assertEquals(millis(2026, 11, 5, 20), ReminderDialogs.nextTime(11, 5, 8, 0, true, zone, now))
    }

    @Test
    fun todayButTheTimeHasPassed_isRefused() {
        assertNull(ReminderDialogs.nextTime(11, 5, 9, 0, false, zone, now))
    }

    @Test
    fun aDateEarlierThisYear_meansNextYear() {
        assertEquals(millis(2027, 3, 14, 9), ReminderDialogs.nextTime(3, 14, 9, 0, false, zone, now))
    }

    @Test
    fun noonAndMidnightAreRightOnTheTwelveHourClock() {
        assertEquals(millis(2026, 11, 6, 12), ReminderDialogs.nextTime(11, 6, 12, 0, true, zone, now))
        assertEquals(millis(2026, 11, 6, 0), ReminderDialogs.nextTime(11, 6, 12, 0, false, zone, now))
    }

    @Test
    fun feb29_waitsForALeapYear() {
        assertEquals(millis(2028, 2, 29, 9), ReminderDialogs.nextTime(2, 29, 9, 0, false, zone, now))
    }
}
