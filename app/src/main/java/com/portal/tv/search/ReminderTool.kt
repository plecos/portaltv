package com.portal.tv.search

import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderLogic
import com.portal.tv.reminders.ReminderTones
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/**
 * The tools that let the model look after the reminders of the person using the TV: set one (for example for when a
 * show airs), list them, change one and delete one. It only sees the active profile's reminders. Anything that changes
 * something is only proposed: Portal shows it and the person confirms before it happens. Free of Android types so it
 * can be unit tested.
 */
object ReminderTool {

    /** What the system prompt says about the tools (it follows "You can "). */
    const val NOTE = "manage the user's reminders: ${Tools.SET_REMINDER} to add one, ${Tools.LIST_REMINDERS} to see them, " +
        "${Tools.UPDATE_REMINDER} and ${Tools.DELETE_REMINDER} to change or remove one by its id (list them first to find it); " +
        "use them when the user asks to be reminded or asks about their reminders, or when a reminder would clearly help, " +
        "for example for when a show is on. The user confirms every change on screen before it is made. " +
        "Give times as the user's local time like 2026-11-12T20:00, and search the web first if you don't know a date"

    const val MAX_TITLE = 100
    private const val MAX_DAYS_AHEAD = 800L

    // ---------- What the model is told about the tools ----------

    private fun str(description: String) = JSONObject().put("type", "string").put("description", description)

    private fun optionProperties(): List<Pair<String, JSONObject>> = listOf(
        "repeat" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("none", "daily", "weekly")))
            .put("description", "How often to repeat; leave out for a one-time reminder"),
        "tone" to JSONObject().put("type", "string").put("enum", JSONArray(ReminderTones.builtIn.map { it.first }))
            .put("description", "The sound; leave out for the default (beeps)"),
        "dismiss" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("automatic", "manual")))
            .put("description", "automatic: the card goes away by itself after 30 seconds. manual: it stays until dismissed."),
        "snooze_minutes" to JSONObject().put("type", "integer").put("enum", JSONArray(ReminderTones.snoozeChoices))
            .put("description", "Minutes the Snooze button puts it off for; 0 for no Snooze button")
    )

    private fun function(name: String, description: String, required: List<String>, vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                    .put("required", JSONArray(required))))

    /** All four tool definitions, in the order they are offered. */
    fun definitions(): List<JSONObject> {
        val time = "time" to str("When to remind, as the user's local date and time like 2026-11-12T20:00 (24 hour clock)")
        val id = "id" to JSONObject().put("type", "integer").put("description", "The reminder's id, from ${Tools.LIST_REMINDERS}")
        return listOf(
            function(
                Tools.SET_REMINDER, "Add a reminder that shows on the TV screen at a given time, for the person using it.",
                listOf("title", "time"),
                "title" to str("Short text to show, for example \"Chef's Table starts on Netflix\""), time, *optionProperties().toTypedArray()
            ),
            function(
                Tools.LIST_REMINDERS, "List the reminders the person using the TV has set, with their ids, times and settings.", emptyList()
            ),
            function(
                Tools.UPDATE_REMINDER, "Change a reminder. Give its id and only the things to change.", listOf("id"),
                id, "title" to str("New text"), time, *optionProperties().toTypedArray()
            ),
            function(Tools.DELETE_REMINDER, "Delete a reminder, by its id.", listOf("id"), id)
        )
    }

    // ---------- Reading a call ----------

    /** The things a call sets; null means it didn't say. */
    data class Edits(
        val title: String? = null, val atMillis: Long? = null, val repeat: Reminder.Repeat? = null,
        val tone: String? = null, val manual: Boolean? = null, val snoozeMinutes: Int? = null
    ) {
        val isEmpty get() = title == null && atMillis == null && repeat == null && tone == null && manual == null && snoozeMinutes == null
    }

    sealed class Result {
        /** A reminder to add. */
        data class Ok(
            val title: String, val atMillis: Long, val repeat: Reminder.Repeat,
            val tone: String = ReminderTones.DEFAULT, val manual: Boolean = false, val snoozeMinutes: Int = 0
        ) : Result()
        /** [message] goes back to the model so it can fix the call or tell the user. */
        data class Error(val message: String) : Result()
    }

    sealed class UpdateResult {
        data class Ok(val id: Int, val edits: Edits) : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    private fun json(arguments: String): JSONObject? = try { JSONObject(arguments) } catch (e: Exception) { null }

    /** The reminder id in a call, which models send as a number or as text. */
    fun parseId(arguments: String): Int? = json(arguments)?.opt("id")?.toString()?.trim()?.toIntOrNull()

    /** A call to add a reminder. [now] and its zone are the TV's, since the model writes the user's local time. */
    fun parse(arguments: String, now: ZonedDateTime = ZonedDateTime.now()): Result {
        val o = json(arguments) ?: return Result.Error("The reminder wasn't valid.")
        val e = when (val r = edits(o, arguments, now)) {
            is Parsed.Bad -> return Result.Error(r.message)
            is Parsed.Good -> r.edits
        }
        val title = e.title ?: return Result.Error("No reminder text was given.")
        val at = e.atMillis ?: return Result.Error("No time was given.")
        return Result.Ok(title, at, e.repeat ?: Reminder.Repeat.NONE, e.tone ?: ReminderTones.DEFAULT, e.manual ?: false, e.snoozeMinutes ?: 0)
    }

    /** A call to change a reminder: its id and what to change. */
    fun parseUpdate(arguments: String, now: ZonedDateTime = ZonedDateTime.now()): UpdateResult {
        val o = json(arguments) ?: return UpdateResult.Error("The change wasn't valid.")
        val id = parseId(arguments) ?: return UpdateResult.Error("No reminder id was given. Use ${Tools.LIST_REMINDERS} to find it.")
        val e = when (val r = edits(o, arguments, now)) {
            is Parsed.Bad -> return UpdateResult.Error(r.message)
            is Parsed.Good -> r.edits
        }
        if (e.isEmpty) return UpdateResult.Error("Nothing to change was given.")
        return UpdateResult.Ok(id, e)
    }

    private sealed class Parsed {
        class Good(val edits: Edits) : Parsed()
        class Bad(val message: String) : Parsed()
    }

    private fun edits(o: JSONObject, arguments: String, now: ZonedDateTime): Parsed {
        val title = Tools.argument(arguments, "title")?.replace(Regex("\\s+"), " ")?.take(MAX_TITLE)
        val timeText = Tools.argument(arguments, "time")
        var at: Long? = null
        if (timeText != null) {
            val parsed = parseTime(timeText, now.zone)
                ?: return Parsed.Bad("The time \"$timeText\" wasn't understood. Use a local date and time like 2026-11-12T20:00.")
            if (!parsed.isAfter(now)) return Parsed.Bad("That time has already passed. The current time is ${now.toLocalDateTime().withNano(0)}.")
            if (parsed.isAfter(now.plusDays(MAX_DAYS_AHEAD))) return Parsed.Bad("That is too far ahead for a reminder.")
            at = parsed.toInstant().toEpochMilli()
        }
        val repeat = if (!o.has("repeat")) null else when (o.optString("repeat").lowercase()) {
            "daily" -> Reminder.Repeat.DAILY
            "weekly" -> Reminder.Repeat.WEEKLY
            else -> Reminder.Repeat.NONE
        }
        var tone: String? = null
        if (o.has("tone")) {
            val key = o.optString("tone").lowercase()
            tone = ReminderTones.builtIn.firstOrNull { it.first == key }?.first
                ?: return Parsed.Bad("Unknown tone \"$key\". Choose from: ${ReminderTones.builtIn.joinToString(", ") { it.first }}.")
        }
        val manual = if (!o.has("dismiss")) null else when (o.optString("dismiss").lowercase()) {
            "manual" -> true
            "automatic", "auto" -> false
            else -> return Parsed.Bad("dismiss must be \"automatic\" or \"manual\".")
        }
        var snooze: Int? = null
        if (o.has("snooze_minutes")) {
            val minutes = o.opt("snooze_minutes")?.toString()?.trim()?.toIntOrNull()
            if (minutes == null || minutes !in ReminderTones.snoozeChoices) {
                return Parsed.Bad("snooze_minutes must be one of ${ReminderTones.snoozeChoices.joinToString(", ")}.")
            }
            snooze = minutes
        }
        return Parsed.Good(Edits(title, at, repeat, tone, manual, snooze))
    }

    /** [reminder] with what [edits] sets changed. */
    fun apply(reminder: Reminder, edits: Edits): Reminder = reminder.copy(
        title = edits.title ?: reminder.title,
        atMillis = edits.atMillis ?: reminder.atMillis,
        repeat = edits.repeat ?: reminder.repeat,
        tone = edits.tone ?: reminder.tone,
        manual = edits.manual ?: reminder.manual,
        snoozeMinutes = edits.snoozeMinutes ?: reminder.snoozeMinutes
    )

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

    // ---------- What is shown and said back ----------

    private fun toneLabel(key: String) =
        ReminderTones.builtIn.firstOrNull { it.first == key }?.second ?: "a sound from the TV"

    /** One line for the person to read: what it says, when, and any settings that aren't the usual. */
    fun summary(r: Reminder, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf(ReminderLogic.whenLabel(r.atMillis, now, zone) +
            if (r.repeat == Reminder.Repeat.NONE) "" else ", " + r.repeat.label.lowercase())
        if (r.tone != ReminderTones.DEFAULT) parts += "tone: " + toneLabel(r.tone).lowercase()
        if (r.manual) parts += "stays until dismissed"
        if (r.snoozeMinutes > 0) parts += "snooze ${r.snoozeMinutes} min"
        return r.title + "\n" + parts.joinToString("  ·  ")
    }

    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** What ${Tools.LIST_REMINDERS} returns to the model: every reminder with its id, in the format it uses for times. */
    fun listText(reminders: List<Reminder>, zone: ZoneId = ZoneId.systemDefault()): String {
        if (reminders.isEmpty()) return "There are no reminders."
        val lines = reminders.sortedBy { it.atMillis }.map { r ->
            val at = java.time.Instant.ofEpochMilli(r.atMillis).atZone(zone)
            val day = at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
            "- id ${r.id}: \"${r.title}\" at ${LOCAL.format(at)} ($day), repeat ${r.repeat.name.lowercase()}, " +
                "tone ${r.tone}, dismiss ${if (r.manual) "manual" else "automatic"}, snooze ${r.snoozeMinutes} min"
        }
        return "${reminders.size} reminder${if (reminders.size == 1) "" else "s"} (local times):\n" + lines.joinToString("\n")
    }
}
