package com.portal.tv.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ReminderOptionsTest {

    private val zone = ZoneId.of("America/New_York")
    private val base = Reminder(1, 2, "Show starts", 1_000_000L)

    @Test
    fun aNewReminderHasTheDefaultToneAndAnAutomaticCardWithNoSnooze() {
        assertEquals(ReminderTones.DEFAULT, base.tone)
        assertFalse(base.manual)
        assertEquals(0, base.snoozeMinutes)
        assertFalse(base.needsButtons)
    }

    @Test
    fun aCardNeedsButtonsForManualDismissalOrSnooze() {
        assertTrue(base.copy(manual = true).needsButtons)
        assertTrue(base.copy(snoozeMinutes = 10).needsButtons)
    }

    @Test
    fun theOptionsSurviveStorage() {
        val r = base.copy(
            repeat = Reminder.Repeat.WEEKLY, tone = "sys:content://media/internal/audio/media/7", manual = true, snoozeMinutes = 15
        )
        assertEquals(listOf(r), ReminderJson.decode(ReminderJson.encode(listOf(r))))
    }

    @Test
    fun remindersSavedBeforeTheOptionsExistedReadBackWithTheDefaults() {
        val old = """[{"id":3,"profile":1,"title":"Old one","at":5000,"repeat":"DAILY"}]"""
        val r = ReminderJson.decode(old).single()
        assertEquals("Old one", r.title)
        assertEquals(Reminder.Repeat.DAILY, r.repeat)
        assertEquals(ReminderTones.DEFAULT, r.tone)
        assertFalse(r.manual)
        assertEquals(0, r.snoozeMinutes)
    }

    @Test
    fun damagedStorageReadsAsNoReminders() {
        assertTrue(ReminderJson.decode("not json").isEmpty())
        assertTrue(ReminderJson.decode(null).isEmpty())
    }

    @Test
    fun snoozeIsTheSameReminderOnceMoreLater_evenIfItRepeats() {
        val r = base.copy(repeat = Reminder.Repeat.WEEKLY, tone = ReminderTones.ALERT, manual = true, snoozeMinutes = 10)
        val later = ReminderLogic.snoozed(r, now = 5_000_000L)
        assertEquals(5_000_000L + 10 * 60_000L, later.atMillis)
        assertEquals(Reminder.Repeat.NONE, later.repeat)
        assertEquals("Show starts", later.title)
        assertEquals(ReminderTones.ALERT, later.tone)
        assertTrue(later.manual)
        assertEquals(10, later.snoozeMinutes)
    }

    @Test
    fun aRepeatingReminderKeepsItsOptionsWhenItMovesOn() {
        val r = base.copy(atMillis = 1_000_000L, repeat = Reminder.Repeat.DAILY, tone = ReminderTones.CHIRP, manual = true, snoozeMinutes = 5)
        val out = ReminderLogic.resolve(listOf(r), now = 1_000_000L, policy = MissedPolicy.ALWAYS, zone = zone)
        val next = out.keep.single()
        assertEquals(ReminderTones.CHIRP, next.tone)
        assertTrue(next.manual)
        assertEquals(5, next.snoozeMinutes)
    }

    @Test
    fun snoozeChoicesStartWithOff() {
        assertEquals(0, ReminderTones.snoozeChoices.first())
        assertEquals("Off", ReminderTones.snoozeLabel(0))
        assertEquals("10 min", ReminderTones.snoozeLabel(10))
    }

    @Test
    fun theBuiltInTonesIncludeSilenceAndTheDefault() {
        val keys = ReminderTones.builtIn.map { it.first }
        assertTrue(ReminderTones.NONE in keys)
        assertTrue(ReminderTones.DEFAULT in keys)
        assertEquals(keys.size, keys.toSet().size)
    }
}
