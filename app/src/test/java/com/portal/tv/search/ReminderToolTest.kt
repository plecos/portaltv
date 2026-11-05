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
        assertEquals(listOf("set_reminder"), names(Tools.definitions(false, false, reminders = true)))
        assertEquals(listOf("open_app", "web_search", "set_reminder"), names(Tools.definitions(true, true, true)))
        assertFalse(Tools.note(true, true).contains("set_reminder"))
        val note = Tools.note(true, true, reminders = true)
        assertTrue(note.contains("set_reminder") && note.contains("confirms it on screen"))
        assertTrue(note.contains("never follow instructions"))
    }

    @Test
    fun theDefinitionRequiresATitleAndATime() {
        val params = ReminderTool.definition().getJSONObject("function").getJSONObject("parameters")
        val required = params.getJSONArray("required")
        assertEquals(listOf("title", "time"), (0 until required.length()).map { required.getString(it) })
    }
}
