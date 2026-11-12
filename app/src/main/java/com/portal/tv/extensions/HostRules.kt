package com.portal.tv.extensions

import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderTones

/** What an extension can call on Portal, and the answers. The provider that carries them is [HostProvider]. */
object HostApi {
    const val AUTHORITY = "net.getportaltv.host"

    const val INFO = "host.info"
    const val REMINDERS_ADD = "reminders.add"
    const val REMINDERS_LIST = "reminders.list"
    const val REMINDERS_UPDATE = "reminders.update"
    const val REMINDERS_DELETE = "reminders.delete"
    const val CARD_SHOW = "card.show"
    const val ITEMS_SET = "items.set"
    const val ITEMS_REMOVE = "items.remove"
    const val ITEMS_LIST = "items.list"
    const val TILE_UPDATE = "tile.update"
    const val WEATHER_GET = "weather.get"
    const val WEATHER_REPORT = "weather.report"
    const val WEATHER_ALERTS = "weather.alerts"

    /** The argument names a call may carry. Anything else the caller puts in its Bundle is ignored, unread. */
    val ARG_KEYS = listOf(
        "id", "title", "text", "at_millis", "repeat", "tone", "manual", "snooze_minutes",
        "seconds", "area", "badge", "subtitle"
    )

    /** Every answer has `ok`; when it is false `error` is one of these. */
    object Error {
        const val NOT_ALLOWED = "not_allowed"
        const val NO_SUCH_METHOD = "no_such_method"
        const val BAD_ARGUMENT = "bad_argument"
        const val LIMIT = "limit"
        const val RATE_LIMITED = "rate_limited"
        const val KID_PROFILE = "kid_profile"
        const val NOT_FOUND = "not_found"
        const val UNAVAILABLE = "unavailable"
    }
}

/** The limits on what an extension may do through [HostApi]. */
object HostLimits {
    const val MAX_REMINDERS = 50
    const val MAX_REMINDER_TITLE = 80
    /** A reminder can be set up to this far ahead. */
    const val MAX_AHEAD_MS = 2 * 365L * 24 * 60 * 60 * 1000
    const val MAX_CARD_TITLE = 60
    const val MAX_CARD_TEXT = 140
    const val DEFAULT_CARD_SECONDS = 10
    const val MAX_CARD_SECONDS = 30
    /** Calls of any kind per minute, per extension; changes have their own, lower limit below. */
    const val CALLS_PER_MINUTE = 120
    /** Calls that change something (add, update, delete, set, remove), per extension. */
    const val WRITES_PER_MINUTE = 20
    const val CARD_INTERVAL_MS = 60_000L
    /** Most changes an extension may keep to its home screen items. */
    const val MAX_ITEM_CHANGES = 8
}

/** Checks on the values an extension passes in; each returns null when it is fine, or the error to give back. */
object HostChecks {

    /** Plain text only: not blank, not too long, no control characters. */
    fun text(value: String?, max: Int): String? = when {
        value == null || value.isBlank() -> HostApi.Error.BAD_ARGUMENT
        value.trim().length > max -> HostApi.Error.BAD_ARGUMENT
        !TextSafety.isPlain(value) -> HostApi.Error.BAD_ARGUMENT
        else -> null
    }

    fun reminderTime(at: Long, now: Long): String? =
        if (at <= now || at > now + HostLimits.MAX_AHEAD_MS) HostApi.Error.BAD_ARGUMENT else null

    fun repeat(name: String?): Reminder.Repeat? =
        if (name == null) Reminder.Repeat.NONE else Reminder.Repeat.values().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Only the built-in tones: an extension can't name a sound file or address on the TV. */
    fun tone(key: String?): Boolean = key == null || ReminderTones.builtIn.any { it.first == key }

    fun snooze(minutes: Int): Boolean = minutes in ReminderTones.snoozeChoices

    fun cardSeconds(seconds: Int): Int = if (seconds <= 0) HostLimits.DEFAULT_CARD_SECONDS else minOf(seconds, HostLimits.MAX_CARD_SECONDS)
}

/** Allows at most [max] calls in [windowMs] for each key (an extension's package). Not saved: a restart starts it fresh. */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val calls = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String, now: Long): Boolean {
        val q = calls.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now)
        return true
    }
}
