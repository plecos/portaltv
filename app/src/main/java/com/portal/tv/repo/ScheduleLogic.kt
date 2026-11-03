package com.portal.tv.repo

/** The rules for a kid profile's bedtime and daily time limit, free of Android types so they can be unit tested. */
object ScheduleLogic {

    sealed interface Status {
        /** Apps may be opened. [remainingMs] is the time left today, or null when there is no daily limit. */
        data class Allowed(val remainingMs: Long?) : Status

        /** Inside the bedtime window; apps are off until [wakeMinute] (minutes after midnight). */
        data class Bedtime(val wakeMinute: Int) : Status

        /** The daily limit is used up. */
        data object LimitReached : Status
    }

    const val OFF = -1

    /** The longest a single stretch in an app counts, so a TV left on overnight doesn't use the whole day. */
    const val SESSION_CAP_MS = 3 * 60 * 60 * 1000L

    /**
     * True when [nowMinute] (minutes after midnight) falls in the bedtime window from [start] to
     * [end]. A window that ends earlier than it starts runs overnight (22:00 to 06:00).
     */
    fun isBedtime(nowMinute: Int, start: Int, end: Int): Boolean {
        if (start == OFF || end == OFF || start == end) return false
        return if (start < end) nowMinute in start until end
        else nowMinute >= start || nowMinute < end
    }

    /**
     * What a kid may do right now. Bedtime wins over the daily limit. [bedtimeSkipped] is a
     * parent's one-night override; [limitMinutes] 0 means no limit, and [extraMinutes] is time a
     * parent added for today.
     */
    fun check(
        nowMinute: Int,
        bedStart: Int,
        bedEnd: Int,
        bedtimeSkipped: Boolean,
        limitMinutes: Int,
        extraMinutes: Int,
        usedMs: Long
    ): Status {
        if (!bedtimeSkipped && isBedtime(nowMinute, bedStart, bedEnd)) return Status.Bedtime(bedEnd)
        if (limitMinutes <= 0) return Status.Allowed(null)
        val remaining = (limitMinutes + extraMinutes) * 60_000L - usedMs
        return if (remaining <= 0) Status.LimitReached else Status.Allowed(remaining)
    }

    /** One stretch in an app, never negative and never more than [SESSION_CAP_MS]. */
    fun sessionTime(elapsedMs: Long): Long = elapsedMs.coerceIn(0L, SESSION_CAP_MS)

    /** Warnings shown over an app as the time runs down, in the order they happen. */
    enum class Warning(val ms: Long) {
        FIVE_MINUTES(5 * 60_000L),
        ONE_MINUTE(60_000L)
    }

    /** How urgent the time left is, which the pill over an app shows as a color. */
    enum class Tone { GREEN, YELLOW, RED }

    /** Green while more than 30 minutes are left, yellow from 30 minutes down to the last minute, red for the countdown. */
    fun tone(remainingMs: Long): Tone = when {
        remainingMs <= COUNTDOWN_MS -> Tone.RED
        remainingMs <= 30 * 60_000L -> Tone.YELLOW
        else -> Tone.GREEN
    }

    /** The last minute is a second-by-second countdown rather than a milestone. */
    const val COUNTDOWN_MS = 60_000L

    /**
     * The time-left reminder to show now, or null. Over an app, the time left is only shown now and then: once
     * for each whole hour while more than an hour is left, then at 1 hour, 30, 15 and 5 minutes. The final minute
     * is the [COUNTDOWN_MS] countdown instead, so it returns null at or below that. The result is the milestone
     * in milliseconds; [lastShown] is the one already shown. The caller starts again from null if the time
     * goes back above [lastShown] (a parent added time), and the first check after an app opens shows once.
     */
    fun nextReminder(remainingMs: Long, lastShown: Long?): Long? {
        if (remainingMs <= COUNTDOWN_MS) return null
        val hour = 60 * 60_000L
        val milestone = when {
            remainingMs > hour -> (remainingMs + hour - 1) / hour * hour   // the whole hour at or just above it
            remainingMs > 30 * 60_000L -> hour
            remainingMs > 15 * 60_000L -> 30 * 60_000L
            remainingMs > 5 * 60_000L -> 15 * 60_000L
            else -> 5 * 60_000L
        }
        return if (lastShown == null || milestone < lastShown) milestone else null
    }

    /** Seconds from [nowSecond] (of the day) until the bedtime window starts, or null if there is none or it's running. */
    private fun secondsUntilBedtime(nowSecond: Int, start: Int, end: Int): Long? {
        if (start == OFF || end == OFF || start == end) return null
        if (isBedtime(nowSecond / 60, start, end)) return null
        val diff = start * 60 - nowSecond
        return (if (diff > 0) diff else diff + 24 * 3600).toLong()
    }

    /**
     * Milliseconds until a kid is locked out, whichever comes first: the daily limit running out or
     * bedtime starting. Null when neither applies. A skipped bedtime doesn't count.
     */
    fun timeUntilLock(
        nowSecond: Int,
        bedStart: Int,
        bedEnd: Int,
        bedtimeSkipped: Boolean,
        limitRemainingMs: Long?
    ): Long? {
        val bed = if (bedtimeSkipped) null
        else secondsUntilBedtime(nowSecond, bedStart, bedEnd)?.times(1000L)
        return listOfNotNull(limitRemainingMs, bed).minOrNull()
    }

    /**
     * The warning to show now, or null. [lastWarned] is the one already shown, so each fires once
     * as time runs down; if the time goes back up (a parent added minutes) it can fire again.
     */
    fun nextWarning(remainingMs: Long, lastWarned: Warning?): Warning? {
        val due = Warning.entries.filter { remainingMs <= it.ms }.lastOrNull() ?: return null
        return if (lastWarned == null || due.ordinal > lastWarned.ordinal) due else null
    }
}
