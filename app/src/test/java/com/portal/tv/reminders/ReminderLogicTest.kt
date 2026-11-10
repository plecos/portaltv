package com.portal.tv.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ReminderLogicTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun reminder(at: Long, repeat: Reminder.Repeat = Reminder.Repeat.NONE, id: Int = 1, profile: Int = 1) =
        Reminder(id, profile, "Show starts", at, repeat)

    private fun resolve(list: List<Reminder>, now: Long, policy: MissedPolicy = MissedPolicy.ALWAYS, exists: (Int) -> Boolean = { true }) =
        ReminderLogic.resolve(list, now, policy, zone, exists)

    @Test
    fun notDueYet_isKeptAndNotShown() {
        val r = reminder(at(2026, 11, 5, 20))
        val out = resolve(listOf(r), at(2026, 11, 5, 19, 59))
        assertTrue(out.fire.isEmpty())
        assertEquals(listOf(r), out.keep)
    }

    @Test
    fun dueNow_isShownOnTimeAndOneOffsAreRemoved() {
        val out = resolve(listOf(reminder(at(2026, 11, 5, 20))), at(2026, 11, 5, 20, 0))
        assertEquals(1, out.fire.size)
        assertEquals(false, out.fire[0].late)
        assertTrue(out.keep.isEmpty())
    }

    @Test
    fun aMinuteLate_stillCountsAsOnTime() {
        val out = resolve(listOf(reminder(at(2026, 11, 5, 20))), at(2026, 11, 5, 20, 1))
        assertEquals(false, out.fire.single().late)
    }

    @Test
    fun missed_alwaysShowsHoweverLate() {
        val out = resolve(listOf(reminder(at(2026, 11, 1, 20))), at(2026, 11, 5, 9), MissedPolicy.ALWAYS)
        assertEquals(true, out.fire.single().late)
        assertTrue(out.keep.isEmpty())
    }

    @Test
    fun missed_hourPolicy_cutsOffAtAnHour() {
        val r = reminder(at(2026, 11, 5, 20))
        assertEquals(1, resolve(listOf(r), at(2026, 11, 5, 20, 59), MissedPolicy.HOUR).fire.size)
        val tooLate = resolve(listOf(r), at(2026, 11, 5, 21, 0), MissedPolicy.HOUR)
        assertTrue(tooLate.fire.isEmpty())
        assertTrue(tooLate.keep.isEmpty())
    }

    @Test
    fun missed_neverSkipsButOnTimeOnesStillShow() {
        val r = reminder(at(2026, 11, 5, 20))
        assertTrue(resolve(listOf(r), at(2026, 11, 5, 23), MissedPolicy.NEVER).fire.isEmpty())
        assertEquals(1, resolve(listOf(r), at(2026, 11, 5, 20, 1), MissedPolicy.NEVER).fire.size)
    }

    @Test
    fun repeatingReminder_movesToItsNextTimeAfterNow() {
        val daily = reminder(at(2026, 11, 5, 8), Reminder.Repeat.DAILY)
        val out = resolve(listOf(daily), at(2026, 11, 5, 8, 0))
        assertEquals(1, out.fire.size)
        assertEquals(at(2026, 11, 6, 8), out.keep.single().atMillis)
    }

    @Test
    fun repeatingReminder_missedForDays_showsOnceAndSkipsAhead() {
        val daily = reminder(at(2026, 11, 1, 8), Reminder.Repeat.DAILY)
        val out = resolve(listOf(daily), at(2026, 11, 5, 12))
        assertEquals(1, out.fire.size)
        assertEquals(at(2026, 11, 6, 8), out.keep.single().atMillis)
    }

    @Test
    fun repeatingReminder_skippedStillCarriesOn() {
        val weekly = reminder(at(2026, 10, 1, 20), Reminder.Repeat.WEEKLY)
        val out = resolve(listOf(weekly), at(2026, 11, 5, 21), MissedPolicy.NEVER)
        assertTrue(out.fire.isEmpty())
        // Thursdays: Oct 1, 8, 15, 22, 29, Nov 5 (an hour ago), so the next is Nov 12.
        assertEquals(at(2026, 11, 12, 20), out.keep.single().atMillis)
    }

    @Test
    fun dailyReminder_keepsItsClockTimeAcrossTheClockChange() {
        // Clocks go back on Nov 1, 2026 in New York.
        val daily = reminder(at(2026, 10, 31, 8), Reminder.Repeat.DAILY)
        assertEquals(at(2026, 11, 1, 8), ReminderLogic.nextAfter(daily, at(2026, 10, 31, 9), zone))
        assertEquals(at(2026, 11, 2, 8), ReminderLogic.nextAfter(daily, at(2026, 11, 1, 9), zone))
    }

    @Test
    fun aTvLeftOffForAYear_isCaughtUpInOneStep() {
        val daily = reminder(at(2025, 11, 5, 8), Reminder.Repeat.DAILY)
        val next = ReminderLogic.nextAfter(daily, at(2026, 11, 5, 12), zone)
        assertEquals(at(2026, 11, 6, 8), next)
    }

    @Test
    fun remindersOfDeletedProfilesAreDropped() {
        val out = resolve(listOf(reminder(at(2026, 11, 6, 8), profile = 7)), at(2026, 11, 5, 8), exists = { false })
        assertTrue(out.fire.isEmpty())
        assertTrue(out.keep.isEmpty())
    }

    @Test
    fun severalDue_comeOutOldestFirst() {
        val a = reminder(at(2026, 11, 5, 9), id = 1)
        val b = reminder(at(2026, 11, 5, 7), id = 2)
        assertEquals(listOf(2, 1), resolve(listOf(a, b), at(2026, 11, 5, 12)).fire.map { it.reminder.id })
    }

    @Test
    fun whenLabel_namesTodayAndTomorrow() {
        val now = at(2026, 11, 5, 10)
        assertEquals("Today 8:00 PM", ReminderLogic.whenLabel(at(2026, 11, 5, 20), now, zone, Locale.US))
        assertEquals("Tomorrow 8:00 AM", ReminderLogic.whenLabel(at(2026, 11, 6, 8), now, zone, Locale.US))
        assertEquals("Sat, Nov 7 8:30 PM", ReminderLogic.whenLabel(at(2026, 11, 7, 20, 30), now, zone, Locale.US))
    }

    @Test
    fun missedPolicy_keysRoundTripAndDefaultToAlways() {
        MissedPolicy.values().forEach { assertEquals(it, MissedPolicy.fromKey(it.key)) }
        assertEquals(MissedPolicy.ALWAYS, MissedPolicy.fromKey(null))
        assertEquals(MissedPolicy.ALWAYS, MissedPolicy.fromKey("nonsense"))
    }

    @Test
    fun cardHeader_saysWhichExtensionMadeTheReminder() {
        assertEquals("KEN  ·  REMINDER", ReminderLogic.cardHeader("Ken", false, "Today 8:00 PM", null))
        assertEquals(
            "KEN  ·  REMINDER  ·  FROM EXTENSION SAMPLE EXTENSION · NET.EXAMPLE.SAMPLE",
            ReminderLogic.cardHeader("Ken", false, "Today 8:00 PM", "Sample extension · net.example.sample"),
        )
        assertEquals(
            "REMINDER  ·  MISSED, WAS DUE TODAY 8:00 PM  ·  FROM EXTENSION NET.EXAMPLE.SAMPLE",
            ReminderLogic.cardHeader(null, true, "Today 8:00 PM", "net.example.sample"),
        )
    }
}
