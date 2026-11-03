package com.portal.tv.repo

import com.portal.tv.repo.ScheduleLogic.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleLogicTest {

    private fun min(h: Int, m: Int = 0) = h * 60 + m

    // ---------- Bedtime window ----------

    @Test
    fun bedtime_overnightWindow() {
        val start = min(20, 30)
        val end = min(7)
        assertFalse(ScheduleLogic.isBedtime(min(20, 29), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(20, 30), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(23, 59), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(0), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(6, 59), start, end))
        assertFalse(ScheduleLogic.isBedtime(min(7), start, end))
        assertFalse(ScheduleLogic.isBedtime(min(12), start, end))
    }

    @Test
    fun bedtime_sameDayWindow() {
        // A nap-style window that doesn't cross midnight.
        val start = min(13)
        val end = min(15)
        assertFalse(ScheduleLogic.isBedtime(min(12, 59), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(13), start, end))
        assertTrue(ScheduleLogic.isBedtime(min(14, 59), start, end))
        assertFalse(ScheduleLogic.isBedtime(min(15), start, end))
    }

    @Test
    fun bedtime_offOrEmptyWindowNeverLocks() {
        assertFalse(ScheduleLogic.isBedtime(min(23), ScheduleLogic.OFF, ScheduleLogic.OFF))
        assertFalse(ScheduleLogic.isBedtime(min(23), min(20), ScheduleLogic.OFF))
        assertFalse(ScheduleLogic.isBedtime(min(23), ScheduleLogic.OFF, min(7)))
        assertFalse(ScheduleLogic.isBedtime(min(20), min(20), min(20)))
    }

    // ---------- Daily limit ----------

    private fun check(
        now: Int = min(15),
        bedStart: Int = ScheduleLogic.OFF,
        bedEnd: Int = ScheduleLogic.OFF,
        skipped: Boolean = false,
        limit: Int = 0,
        extra: Int = 0,
        usedMin: Int = 0
    ) = ScheduleLogic.check(now, bedStart, bedEnd, skipped, limit, extra, usedMin * 60_000L)

    @Test
    fun noLimitsAlwaysAllows() {
        assertEquals(Status.Allowed(null), check())
        assertEquals(Status.Allowed(null), check(usedMin = 500))
    }

    @Test
    fun limit_reportsTimeRemaining() {
        assertEquals(Status.Allowed(30 * 60_000L), check(limit = 60, usedMin = 30))
        assertEquals(Status.Allowed(60 * 60_000L), check(limit = 60, usedMin = 0))
    }

    @Test
    fun limit_isReachedExactlyWhenUsedUp() {
        assertEquals(Status.Allowed(60_000L), check(limit = 60, usedMin = 59))
        assertEquals(Status.LimitReached, check(limit = 60, usedMin = 60))
        assertEquals(Status.LimitReached, check(limit = 60, usedMin = 90))
    }

    @Test
    fun extraMinutes_extendTheLimit() {
        assertEquals(Status.LimitReached, check(limit = 30, usedMin = 40))
        assertEquals(Status.Allowed(5 * 60_000L), check(limit = 30, extra = 15, usedMin = 40))
        assertEquals(Status.LimitReached, check(limit = 30, extra = 15, usedMin = 45))
    }

    @Test
    fun extraMinutes_doNothingWithoutALimit() {
        assertEquals(Status.Allowed(null), check(limit = 0, extra = 30, usedMin = 500))
    }

    // ---------- Bedtime and limit together ----------

    @Test
    fun bedtime_winsOverTheLimit() {
        assertEquals(
            Status.Bedtime(min(7)),
            check(now = min(21), bedStart = min(20, 30), bedEnd = min(7), limit = 60, usedMin = 90)
        )
    }

    @Test
    fun skippedBedtime_fallsBackToTheLimit() {
        assertEquals(
            Status.LimitReached,
            check(now = min(21), bedStart = min(20, 30), bedEnd = min(7), skipped = true, limit = 60, usedMin = 90)
        )
        assertEquals(
            Status.Allowed(null),
            check(now = min(21), bedStart = min(20, 30), bedEnd = min(7), skipped = true)
        )
    }

    @Test
    fun outsideBedtime_theLimitStillApplies() {
        assertEquals(
            Status.Allowed(10 * 60_000L),
            check(now = min(16), bedStart = min(20, 30), bedEnd = min(7), limit = 60, usedMin = 50)
        )
    }

    // ---------- Sessions ----------

    @Test
    fun sessionTime_isClamped() {
        assertEquals(0L, ScheduleLogic.sessionTime(-5_000L))
        assertEquals(20 * 60_000L, ScheduleLogic.sessionTime(20 * 60_000L))
        assertEquals(ScheduleLogic.SESSION_CAP_MS, ScheduleLogic.sessionTime(12 * 60 * 60_000L))
    }
}

class ScheduleWarningTest {

    private fun sec(h: Int, m: Int = 0) = h * 3600 + m * 60

    @Test
    fun timeUntilLock_isTheEarlierOfLimitAndBedtime() {
        // 7:30 PM, bedtime at 8:00 PM (30 min away), 45 min of limit left.
        assertEquals(30 * 60_000L, ScheduleLogic.timeUntilLock(sec(19, 30), 20 * 60, 7 * 60, false, 45 * 60_000L))
        // 20 min of limit left beats the 30 min to bedtime.
        assertEquals(20 * 60_000L, ScheduleLogic.timeUntilLock(sec(19, 30), 20 * 60, 7 * 60, false, 20 * 60_000L))
    }

    @Test
    fun timeUntilLock_isNullWithNoLimits() {
        assertEquals(null, ScheduleLogic.timeUntilLock(sec(15), ScheduleLogic.OFF, ScheduleLogic.OFF, false, null))
    }

    @Test
    fun timeUntilLock_ignoresBedtimeWhileItIsRunningOrSkipped() {
        // 9 PM is inside bedtime already, so only the limit counts.
        assertEquals(10 * 60_000L, ScheduleLogic.timeUntilLock(sec(21), 20 * 60, 7 * 60, false, 10 * 60_000L))
        // A skipped bedtime is ignored.
        assertEquals(null, ScheduleLogic.timeUntilLock(sec(19, 30), 20 * 60, 7 * 60, true, null))
    }

    @Test
    fun timeUntilLock_bedtimeTomorrowWhenAlreadyPastStartToday() {
        // 6 AM is after the 7 AM wake? No: still bedtime. At 8 AM the next bedtime is 12 hours away.
        assertEquals(12 * 3600_000L, ScheduleLogic.timeUntilLock(sec(8), 20 * 60, 7 * 60, false, null))
    }

    @Test
    fun nextWarning_firesOncePerMilestone() {
        val five = ScheduleLogic.Warning.FIVE_MINUTES
        val one = ScheduleLogic.Warning.ONE_MINUTE
        assertEquals(null, ScheduleLogic.nextWarning(6 * 60_000L, null))
        assertEquals(five, ScheduleLogic.nextWarning(5 * 60_000L, null))
        assertEquals(null, ScheduleLogic.nextWarning(4 * 60_000L, five))
        assertEquals(one, ScheduleLogic.nextWarning(60_000L, five))
        assertEquals(null, ScheduleLogic.nextWarning(30_000L, one))
    }

    @Test
    fun nextWarning_skipsStraightToTheLatestWhenTimeJumpsDown() {
        assertEquals(ScheduleLogic.Warning.ONE_MINUTE, ScheduleLogic.nextWarning(50_000L, null))
    }

    @Test
    fun nextWarning_canFireAgainAfterAParentAddsTime() {
        // The caller resets lastWarned to null when the time goes back above a milestone.
        assertEquals(ScheduleLogic.Warning.FIVE_MINUTES, ScheduleLogic.nextWarning(4 * 60_000L, null))
    }

    // ---------- Time-left reminders ----------

    private fun mins(m: Long) = m * 60_000L

    @Test
    fun nextReminder_showsOnceWhenAnAppOpensThenEachHour() {
        // Opening with 2 h 40 min left shows once (the 3 hour mark), then at 2 hours, then at 1 hour.
        assertEquals(mins(180), ScheduleLogic.nextReminder(mins(160), null))
        assertEquals(null, ScheduleLogic.nextReminder(mins(150), mins(180)))
        assertEquals(null, ScheduleLogic.nextReminder(mins(121), mins(180)))
        assertEquals(mins(120), ScheduleLogic.nextReminder(mins(120), mins(180)))
        assertEquals(null, ScheduleLogic.nextReminder(mins(90), mins(120)))
        assertEquals(mins(60), ScheduleLogic.nextReminder(mins(60), mins(120)))
    }

    @Test
    fun nextReminder_thenThirtyFifteenAndFiveMinutes() {
        assertEquals(null, ScheduleLogic.nextReminder(mins(45), mins(60)))
        assertEquals(mins(30), ScheduleLogic.nextReminder(mins(30), mins(60)))
        assertEquals(null, ScheduleLogic.nextReminder(mins(20), mins(30)))
        assertEquals(mins(15), ScheduleLogic.nextReminder(mins(15), mins(30)))
        assertEquals(null, ScheduleLogic.nextReminder(mins(10), mins(15)))
        assertEquals(mins(5), ScheduleLogic.nextReminder(mins(5), mins(15)))
        assertEquals(null, ScheduleLogic.nextReminder(mins(3), mins(5)))
    }

    @Test
    fun nextReminder_theLastMinuteIsTheCountdownNotAReminder() {
        assertEquals(null, ScheduleLogic.nextReminder(ScheduleLogic.COUNTDOWN_MS, mins(5)))
        assertEquals(null, ScheduleLogic.nextReminder(30_000L, null))
    }

    @Test
    fun nextReminder_openingLateShowsTheNearestMilestoneOnce() {
        assertEquals(mins(15), ScheduleLogic.nextReminder(mins(12), null))
        assertEquals(mins(5), ScheduleLogic.nextReminder(mins(4), null))
        assertEquals(mins(60), ScheduleLogic.nextReminder(mins(50), null))
    }

    @Test
    fun nextReminder_skipsAMilestoneTheTimeJumpedPast() {
        // Down from 2 hours straight to 20 minutes shows the 30 minute mark, not the ones in between.
        assertEquals(mins(30), ScheduleLogic.nextReminder(mins(20), mins(120)))
    }

    // ---------- Pill colors ----------

    @Test
    fun tone_greenOverThirtyMinutes_yellowDownToTheLastMinute_redForTheCountdown() {
        assertEquals(ScheduleLogic.Tone.GREEN, ScheduleLogic.tone(mins(180)))
        assertEquals(ScheduleLogic.Tone.GREEN, ScheduleLogic.tone(mins(60)))
        assertEquals(ScheduleLogic.Tone.GREEN, ScheduleLogic.tone(mins(31)))
        assertEquals(ScheduleLogic.Tone.YELLOW, ScheduleLogic.tone(mins(30)))
        assertEquals(ScheduleLogic.Tone.YELLOW, ScheduleLogic.tone(mins(5)))
        assertEquals(ScheduleLogic.Tone.YELLOW, ScheduleLogic.tone(61_000L))
        assertEquals(ScheduleLogic.Tone.RED, ScheduleLogic.tone(60_000L))
        assertEquals(ScheduleLogic.Tone.RED, ScheduleLogic.tone(1_000L))
        assertEquals(ScheduleLogic.Tone.RED, ScheduleLogic.tone(0L))
    }
}
