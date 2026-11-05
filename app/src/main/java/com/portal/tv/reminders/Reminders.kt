package com.portal.tv.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A reminder belongs to the profile that set it, but when it is due it shows whoever is using the TV.
 * [atMillis] is the next time it is due (epoch milliseconds); a repeating reminder moves it forward each time.
 */
data class Reminder(
    val id: Int,
    val profileId: Int,
    val title: String,
    val atMillis: Long,
    val repeat: Repeat = Repeat.NONE,
    /** The sound it makes, a key from [ReminderTones]. */
    val tone: String = ReminderTones.DEFAULT,
    /** True: the card stays until someone dismisses it. False: it goes by itself after a while. */
    val manual: Boolean = false,
    /** Minutes a Snooze button puts it off for; 0 means the card has no Snooze button. */
    val snoozeMinutes: Int = 0
) {
    enum class Repeat(val label: String) {
        NONE("Once"), DAILY("Every day"), WEEKLY("Every week")
    }

    /** Whether the card for this one has buttons, and so has to take the remote. */
    val needsButtons: Boolean get() = manual || snoozeMinutes > 0
}

/** The sounds a reminder can make. The names here are stored with the reminder; playing them is [TonePlayer]'s job. */
object ReminderTones {
    const val NONE = "none"
    const val BEEPS = "beeps"
    const val CHIRP = "chirp"
    const val PROMPT = "prompt"
    const val ALERT = "alert"
    const val DEFAULT = BEEPS

    /** Prefix of a sound from the TV's own list: the rest is its address. */
    const val SYSTEM = "sys:"

    /** The built-in tones, in the order they are listed. They are made by the TV's tone generator, so every TV has them. */
    val builtIn = listOf(
        NONE to "No sound",
        BEEPS to "Beeps",
        CHIRP to "Chirp",
        PROMPT to "Prompt",
        ALERT to "Alert"
    )

    val snoozeChoices = listOf(0, 5, 10, 15, 30)

    fun snoozeLabel(minutes: Int) = if (minutes <= 0) "Off" else "$minutes min"
}

/** What to do with a reminder that came due while the TV was off or Portal wasn't running. */
enum class MissedPolicy(val key: String, val label: String, val hint: String) {
    ALWAYS("always", "Show it", "Shown when the TV is next on, however late it is."),
    HOUR("hour", "Show it if under an hour late", "Shown only if it is less than an hour past the time."),
    NEVER("never", "Skip it", "Dropped. Repeating reminders carry on with their next time.");

    companion object {
        fun fromKey(key: String?) = values().firstOrNull { it.key == key } ?: ALWAYS
    }
}

/** One reminder to show now. [late] says it was due earlier and is being shown after the fact. */
data class Firing(val reminder: Reminder, val late: Boolean)

/** The reminders due now, and what to keep: one-off ones that fired are gone, repeating ones moved on. */
data class Resolution(val fire: List<Firing>, val keep: List<Reminder>)

object ReminderLogic {

    /** Due this long ago or less still counts as on time (the service looks every few seconds). */
    const val ON_TIME_MS = 2 * 60_000L

    private const val HOUR_MS = 60 * 60_000L

    /**
     * The first time of [reminder] after [now]: its own time for a one-off (or one that isn't due yet), otherwise the
     * next day or week at the same wall-clock time, so a daily 8:00 stays 8:00 across a clock change.
     */
    fun nextAfter(reminder: Reminder, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (reminder.atMillis > now || reminder.repeat == Reminder.Repeat.NONE) return reminder.atMillis
        var at = Instant.ofEpochMilli(reminder.atMillis).atZone(zone).toLocalDateTime()
        val step = if (reminder.repeat == Reminder.Repeat.DAILY) 1L else 7L
        // Jump close to now in one go, then walk the last steps; a TV left off for a year shouldn't loop 365 times.
        val daysBehind = (now - reminder.atMillis) / (24 * HOUR_MS)
        if (daysBehind > step) at = at.plusDays(daysBehind / step * step)
        var millis = at.atZone(zone).toInstant().toEpochMilli()
        while (millis <= now) {
            at = at.plusDays(step)
            millis = at.atZone(zone).toInstant().toEpochMilli()
        }
        return millis
    }

    /**
     * Sorts [reminders] into what to show at [now] and what to keep. [exists] says whether a reminder's profile is
     * still there; those of deleted profiles are dropped.
     */
    fun resolve(
        reminders: List<Reminder>,
        now: Long,
        policy: MissedPolicy,
        zone: ZoneId = ZoneId.systemDefault(),
        exists: (Int) -> Boolean = { true }
    ): Resolution {
        val fire = mutableListOf<Firing>()
        val keep = mutableListOf<Reminder>()
        for (r in reminders) {
            if (!exists(r.profileId)) continue
            if (r.atMillis > now) { keep += r; continue }
            val lateBy = now - r.atMillis
            val late = lateBy > ON_TIME_MS
            val show = !late || when (policy) {
                MissedPolicy.ALWAYS -> true
                MissedPolicy.HOUR -> lateBy < HOUR_MS
                MissedPolicy.NEVER -> false
            }
            if (show) fire += Firing(r, late)
            if (r.repeat != Reminder.Repeat.NONE) keep += r.copy(atMillis = nextAfter(r, now, zone))
        }
        return Resolution(fire.sortedBy { it.reminder.atMillis }, keep)
    }

    /** "Today 8:00 PM", "Tomorrow 8:00 PM", or "Fri, Nov 7 8:00 PM" for a time further off or already gone by. */
    fun whenLabel(atMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(at)
        val day = when (at.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> {
                val date: LocalDate = at.toLocalDate()
                val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                val month = date.month.getDisplayName(TextStyle.SHORT, locale)
                "$weekday, $month ${date.dayOfMonth}"
            }
        }
        return "$day $time"
    }

    /** What Snooze does: the same reminder once more, [Reminder.snoozeMinutes] from [now], whether or not it repeats. */
    fun snoozed(reminder: Reminder, now: Long): Reminder =
        reminder.copy(atMillis = now + reminder.snoozeMinutes * 60_000L, repeat = Reminder.Repeat.NONE)
}
