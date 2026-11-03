package com.portal.tv.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsScheduleLabelsTest {

    @Test
    fun timeLabel_formatsMinutesAfterMidnight() {
        assertEquals("12:00 AM", KidsSchedule.timeLabel(0))
        assertEquals("7:00 AM", KidsSchedule.timeLabel(7 * 60))
        assertEquals("12:00 PM", KidsSchedule.timeLabel(12 * 60))
        assertEquals("8:30 PM", KidsSchedule.timeLabel(20 * 60 + 30))
        assertEquals("11:30 PM", KidsSchedule.timeLabel(23 * 60 + 30))
    }

    @Test
    fun limitLabel_readsNaturally() {
        assertEquals("No daily limit", KidsSchedule.limitLabel(0))
        assertEquals("30 min a day", KidsSchedule.limitLabel(30))
        assertEquals("1 hr a day", KidsSchedule.limitLabel(60))
        assertEquals("90 min a day", KidsSchedule.limitLabel(90))
        assertEquals("2 hr a day", KidsSchedule.limitLabel(120))
    }

    @Test
    fun remainingLabel_roundsUpAndSwitchesToHours() {
        assertEquals("1 min left", KidsSchedule.remainingLabel(1_000L))
        assertEquals("5 min left", KidsSchedule.remainingLabel(5 * 60_000L))
        assertEquals("5 min left", KidsSchedule.remainingLabel(4 * 60_000L + 1_000L))
        assertEquals("1 hr left", KidsSchedule.remainingLabel(60 * 60_000L))
        assertEquals("1 hr 10 min left", KidsSchedule.remainingLabel(70 * 60_000L))
    }

    @Test
    fun summary_describesWhatIsSet() {
        val none = KidsSchedule.Config(0, ScheduleLogic.OFF, ScheduleLogic.OFF)
        assertTrue(none.isEmpty)
        assertEquals("No limits", KidsSchedule.summary(none))

        val limitOnly = KidsSchedule.Config(60, ScheduleLogic.OFF, ScheduleLogic.OFF)
        assertFalse(limitOnly.isEmpty)
        assertEquals("1 hr a day", KidsSchedule.summary(limitOnly))

        val bedOnly = KidsSchedule.Config(0, 20 * 60 + 30, 7 * 60)
        assertEquals("bed 8:30 PM to 7:00 AM", KidsSchedule.summary(bedOnly))

        val both = KidsSchedule.Config(90, 20 * 60, 7 * 60)
        assertEquals("90 min a day · bed 8:00 PM to 7:00 AM", KidsSchedule.summary(both))
    }

    @Test
    fun countdownLabel_isAClockForTheLastMinute() {
        assertEquals("1:00", KidsSchedule.countdownLabel(60_000L))
        assertEquals("0:59", KidsSchedule.countdownLabel(58_500L))
        assertEquals("0:01", KidsSchedule.countdownLabel(1L))
        assertEquals("0:00", KidsSchedule.countdownLabel(0L))
    }
}
