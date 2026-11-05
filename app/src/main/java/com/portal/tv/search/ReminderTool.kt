package com.portal.tv.search

import com.portal.tv.reminders.Reminder
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * The tool that lets the model set a reminder for the person using the TV, for example for when a show they asked
 * about airs. The model only proposes it: Portal shows the reminder and the person confirms before anything is saved.
 * Free of Android types so it can be unit tested.
 */
object ReminderTool {

    /** What the system prompt says about the tool (it follows "You can "). */
    const val NOTE = "set a reminder with ${Tools.SET_REMINDER} when the user asks to be reminded, or when they want to " +
        "know when a show or event is on and a reminder would help; the user confirms it on screen before it is saved. " +
        "Give the time as the user's local time like 2026-11-12T20:00, and search the web first if you don't know a date"

    const val MAX_TITLE = 100
    private const val MAX_DAYS_AHEAD = 800L

    fun definition(): JSONObject {
        fun str(description: String) = JSONObject().put("type", "string").put("description", description)
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", Tools.SET_REMINDER)
                .put("description", "Set a reminder that shows on the TV screen at a given time, for the person using it.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("title", str("Short text to show, for example \"Chef's Table starts on Netflix\""))
                        .put("time", str("When to remind, as the user's local date and time like 2026-11-12T20:00 (24 hour clock)"))
                        .put("repeat", JSONObject().put("type", "string").put("enum", JSONArray(listOf("none", "daily", "weekly")))
                            .put("description", "How often to repeat; leave out for a one-time reminder")))
                    .put("required", JSONArray().put("title").put("time"))))
    }

    sealed class Result {
        data class Ok(val title: String, val atMillis: Long, val repeat: Reminder.Repeat) : Result()
        /** [message] goes back to the model so it can fix the call or tell the user. */
        data class Error(val message: String) : Result()
    }

    /** Reads a call's JSON. [now] and [zone] are the TV's, since the model writes the user's local time. */
    fun parse(arguments: String, now: ZonedDateTime = ZonedDateTime.now()): Result {
        val json = try { JSONObject(arguments) } catch (e: Exception) { return Result.Error("The reminder wasn't valid.") }
        val title = Tools.argument(arguments, "title")?.replace(Regex("\\s+"), " ")?.take(MAX_TITLE)
            ?: return Result.Error("No reminder text was given.")
        val timeText = Tools.argument(arguments, "time") ?: return Result.Error("No time was given.")
        val at = parseTime(timeText, now.zone)
            ?: return Result.Error("The time \"$timeText\" wasn't understood. Use a local date and time like 2026-11-12T20:00.")
        if (!at.isAfter(now)) return Result.Error("That time has already passed. The current time is ${now.toLocalDateTime().withNano(0)}.")
        if (at.isAfter(now.plusDays(MAX_DAYS_AHEAD))) return Result.Error("That is too far ahead for a reminder.")
        val repeat = when (json.optString("repeat").lowercase()) {
            "daily" -> Reminder.Repeat.DAILY
            "weekly" -> Reminder.Repeat.WEEKLY
            else -> Reminder.Repeat.NONE
        }
        return Result.Ok(title, at.toInstant().toEpochMilli(), repeat)
    }

    /**
     * The local date and time in [text], which models write in a few ways: 2026-11-12T20:00, 2026-11-12 20:00, with
     * seconds, or with a zone or offset (then that moment, shown in the TV's zone).
     */
    internal fun parseTime(text: String, zone: ZoneId): ZonedDateTime? {
        val t = text.trim().replace(' ', 'T')
        try { return ZonedDateTime.parse(t).withZoneSameInstant(zone) } catch (_: DateTimeParseException) {}
        try { return OffsetDateTime.parse(t).atZoneSameInstant(zone) } catch (_: DateTimeParseException) {}
        return try { LocalDateTime.parse(t).atZone(zone) } catch (_: DateTimeParseException) { null }
    }
}
