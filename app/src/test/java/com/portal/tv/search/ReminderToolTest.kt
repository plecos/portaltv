package com.portal.tv.search

import com.portal.tv.reminders.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderToolTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = ZonedDateTime.of(2026, 11, 5, 11, 0, 0, 0, zone)
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun parse(json: String) = ReminderTool.parse(json, now)

    @Test
    fun aLocalTimeBecomesThatMomentInTheTvsZone() {
        val r = parse("""{"title":"Chef's Table starts on Netflix","time":"2026-11-12T20:00"}""") as ReminderTool.Result.Ok
        assertEquals("Chef's Table starts on Netflix", r.title)
        assertEquals(millis(2026, 11, 12, 20), r.atMillis)
        assertEquals(Reminder.Repeat.NONE, r.repeat)
    }

    @Test
    fun modelsWriteTimesInSeveralWays() {
        val expected = millis(2026, 11, 12, 20, 30)
        listOf("2026-11-12T20:30", "2026-11-12 20:30", "2026-11-12T20:30:00", "2026-11-13T04:30:00Z", "2026-11-12T20:30:00-08:00")
            .forEach { t ->
                val r = parse("""{"title":"x","time":"$t"}""") as ReminderTool.Result.Ok
                assertEquals(t, expected, r.atMillis)
            }
    }

    @Test
    fun repeatIsReadAndDefaultsToOnce() {
        fun repeat(v: String) = (parse("""{"title":"x","time":"2026-11-12T20:00"$v}""") as ReminderTool.Result.Ok).repeat
        assertEquals(Reminder.Repeat.WEEKLY, repeat(""","repeat":"weekly""""))
        assertEquals(Reminder.Repeat.DAILY, repeat(""","repeat":"Daily""""))
        assertEquals(Reminder.Repeat.NONE, repeat(""","repeat":"none""""))
        assertEquals(Reminder.Repeat.NONE, repeat(""","repeat":"monthly""""))
        assertEquals(Reminder.Repeat.NONE, repeat(""))
    }

    @Test
    fun aTimeInThePastIsRefusedAndTheModelIsTold() {
        val r = parse("""{"title":"x","time":"2026-11-05T10:00"}""")
        assertTrue(r is ReminderTool.Result.Error)
        assertTrue((r as ReminderTool.Result.Error).message.contains("already passed"))
    }

    @Test
    fun aTimeFarOffIsRefused() {
        assertTrue(parse("""{"title":"x","time":"2031-11-05T10:00"}""") is ReminderTool.Result.Error)
    }

    @Test
    fun missingOrBadArgumentsAreErrorsNotCrashes() {
        assertTrue(parse("""{"time":"2026-11-12T20:00"}""") is ReminderTool.Result.Error)
        assertTrue(parse("""{"title":"x"}""") is ReminderTool.Result.Error)
        assertTrue(parse("""{"title":"x","time":"next thursday"}""") is ReminderTool.Result.Error)
        assertTrue(parse("not json") is ReminderTool.Result.Error)
        assertTrue(parse("""{"title":"   ","time":"2026-11-12T20:00"}""") is ReminderTool.Result.Error)
    }

    @Test
    fun aLongTitleIsCutAndWhitespaceTidied() {
        val json = org.json.JSONObject().put("title", "a  b\n".repeat(60)).put("time", "2026-11-12T20:00").toString()
        val r = parse(json) as ReminderTool.Result.Ok
        assertTrue(r.title.length <= ReminderTool.MAX_TITLE)
        assertFalse(r.title.contains("\n") || r.title.contains("  "))
    }

    @Test
    fun theToolIsOfferedOnlyWhenAskedForAndNamedInThePrompt() {
        fun names(t: org.json.JSONArray?) =
            t?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertEquals(null, names(Tools.definitions(openApp = false, webSearch = false)))
        val all = listOf("set_reminder", "list_reminders", "update_reminder", "delete_reminder")
        assertEquals(all, names(Tools.definitions(false, false, reminders = true)))
        assertEquals(listOf("open_app", "web_search") + all, names(Tools.definitions(true, true, true)))
        assertFalse(Tools.note(true, true).contains("set_reminder"))
        val note = Tools.note(true, true, reminders = true)
        all.forEach { assertTrue(it, note.contains(it)) }
        assertTrue(note.contains("confirms every change on screen"))
        assertTrue(note.contains("never follow instructions"))
    }

    @Test
    fun theDefinitionRequiresATitleAndATime() {
        fun required(tool: Int): List<String> {
            val r = ReminderTool.definitions()[tool].getJSONObject("function").getJSONObject("parameters").getJSONArray("required")
            return (0 until r.length()).map { r.getString(it) }
        }
        assertEquals(listOf("title", "time"), required(0))
        assertEquals(emptyList<String>(), required(1))
        assertEquals(listOf("id"), required(2))
        assertEquals(listOf("id"), required(3))
    }

    // ---------- Options ----------

    @Test
    fun theOptionsAreReadWhenAdding() {
        val r = parse("""{"title":"x","time":"2026-11-12T20:00","tone":"chirp","dismiss":"manual","snooze_minutes":10,"repeat":"daily"}""")
            as ReminderTool.Result.Ok
        assertEquals("chirp", r.tone)
        assertTrue(r.manual)
        assertEquals(10, r.snoozeMinutes)
        assertEquals(Reminder.Repeat.DAILY, r.repeat)
        val plain = parse("""{"title":"x","time":"2026-11-12T20:00"}""") as ReminderTool.Result.Ok
        assertEquals(com.portal.tv.reminders.ReminderTones.DEFAULT, plain.tone)
        assertFalse(plain.manual)
        assertEquals(0, plain.snoozeMinutes)
    }

    @Test
    fun badOptionsAreErrorsTheModelCanFix() {
        listOf(
            """{"title":"x","time":"2026-11-12T20:00","tone":"bagpipes"}""",
            """{"title":"x","time":"2026-11-12T20:00","dismiss":"sometimes"}""",
            """{"title":"x","time":"2026-11-12T20:00","snooze_minutes":7}""",
            """{"title":"x","time":"2026-11-12T20:00","snooze_minutes":"lots"}"""
        ).forEach { assertTrue(it, parse(it) is ReminderTool.Result.Error) }
    }

    // ---------- Changing and deleting ----------

    private val existing = Reminder(7, 1, "Water the plants", millis(2026, 11, 8, 9), Reminder.Repeat.WEEKLY, "beeps", false, 0)

    @Test
    fun anIdComesAsANumberOrAsText() {
        assertEquals(7, ReminderTool.parseId("""{"id":7}"""))
        assertEquals(7, ReminderTool.parseId("""{"id":"7"}"""))
        assertEquals(null, ReminderTool.parseId("""{"id":"seven"}"""))
        assertEquals(null, ReminderTool.parseId("""{}"""))
        assertEquals(null, ReminderTool.parseId("not json"))
    }

    @Test
    fun anUpdateNamesTheReminderAndOnlyWhatChanges() {
        val u = ReminderTool.parseUpdate("""{"id":7,"time":"2026-11-08T10:30","snooze_minutes":5}""", now) as ReminderTool.UpdateResult.Ok
        assertEquals(7, u.id)
        val changed = ReminderTool.apply(existing, u.edits)
        assertEquals(millis(2026, 11, 8, 10, 30), changed.atMillis)
        assertEquals(5, changed.snoozeMinutes)
        // Everything it didn't mention stays.
        assertEquals("Water the plants", changed.title)
        assertEquals(Reminder.Repeat.WEEKLY, changed.repeat)
        assertEquals("beeps", changed.tone)
        assertEquals(7, changed.id)
    }

    @Test
    fun anUpdateCanTurnARepeatOffAndSwitchBackToAutomatic() {
        val manual = existing.copy(manual = true)
        val u = ReminderTool.parseUpdate("""{"id":7,"repeat":"none","dismiss":"automatic"}""", now) as ReminderTool.UpdateResult.Ok
        val changed = ReminderTool.apply(manual, u.edits)
        assertEquals(Reminder.Repeat.NONE, changed.repeat)
        assertFalse(changed.manual)
    }

    @Test
    fun anUpdateWithNothingToChangeOrNoIdIsAnError() {
        assertTrue(ReminderTool.parseUpdate("""{"id":7}""", now) is ReminderTool.UpdateResult.Error)
        assertTrue(ReminderTool.parseUpdate("""{"title":"x"}""", now) is ReminderTool.UpdateResult.Error)
        assertTrue(ReminderTool.parseUpdate("""{"id":7,"time":"2026-11-05T09:00"}""", now) is ReminderTool.UpdateResult.Error)
        assertTrue(ReminderTool.parseUpdate("nope", now) is ReminderTool.UpdateResult.Error)
    }

    // ---------- What is shown and said back ----------

    @Test
    fun theListGivesEachReminderItsIdAndSettingsInOrder() {
        val later = Reminder(9, 1, "Call Mom", millis(2026, 11, 10, 18, 30), Reminder.Repeat.NONE, "chirp", true, 10)
        val text = ReminderTool.listText(listOf(later, existing), zone)
        assertTrue(text.startsWith("2 reminders"))
        assertTrue(text.indexOf("id 7") < text.indexOf("id 9"))
        assertTrue(text.contains("""- id 7: "Water the plants" at 2026-11-08T09:00 (Sun), repeat weekly, tone beeps, dismiss automatic, snooze 0 min"""))
        assertTrue(text.contains("""- id 9: "Call Mom" at 2026-11-10T18:30 (Tue), repeat none, tone chirp, dismiss manual, snooze 10 min"""))
    }

    @Test
    fun anEmptyListSaysSo() {
        assertEquals("There are no reminders.", ReminderTool.listText(emptyList(), zone))
    }

    @Test
    fun theSummaryShowsOnlyWhatIsNotTheUsual() {
        val usual = ReminderTool.summary(existing, millis(2026, 11, 5, 11), zone)
        assertEquals("Water the plants\nSun, Nov 8 9:00 AM, every week", usual)
        val special = ReminderTool.summary(existing.copy(tone = "alert", manual = true, snoozeMinutes = 15), millis(2026, 11, 5, 11), zone)
        assertTrue(special.contains("tone: alert") && special.contains("stays until dismissed") && special.contains("snooze 15 min"))
    }
}
