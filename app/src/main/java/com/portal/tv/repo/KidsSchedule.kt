package com.portal.tv.repo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bedtime and daily time limit for kid profiles, stored per profile.
 *
 * Portal can only see time spent in apps it launched: a stretch starts when a kid opens an app
 * and ends when Portal is back on screen. It can't close an app that's already running, so the
 * limits apply when a kid opens an app or returns to the home screen.
 */
object KidsSchedule {

    private const val PREFS = "kids_schedule"

    data class Config(val limitMinutes: Int, val bedStart: Int, val bedEnd: Int) {
        val hasBedtime get() = bedStart != ScheduleLogic.OFF && bedEnd != ScheduleLogic.OFF
        val isEmpty get() = limitMinutes <= 0 && !hasBedtime
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Settings ----------

    fun config(context: Context, profileId: Int): Config {
        val p = prefs(context)
        return Config(
            limitMinutes = p.getInt("limit_$profileId", 0),
            bedStart = p.getInt("bed_start_$profileId", ScheduleLogic.OFF),
            bedEnd = p.getInt("bed_end_$profileId", ScheduleLogic.OFF)
        )
    }

    fun setLimit(context: Context, profileId: Int, minutes: Int) {
        prefs(context).edit().putInt("limit_$profileId", minutes).apply()
    }

    fun setBedtime(context: Context, profileId: Int, start: Int, end: Int) {
        prefs(context).edit().putInt("bed_start_$profileId", start).putInt("bed_end_$profileId", end).apply()
    }

    fun clear(context: Context, profileId: Int) {
        val e = prefs(context).edit()
        prefs(context).all.keys.filter { it.endsWith("_$profileId") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---------- Today's usage ----------

    private fun today(now: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    /** Time used today by [profileId], including a stretch still in progress. */
    fun usedMs(context: Context, profileId: Int, now: Long = System.currentTimeMillis()): Long {
        val p = prefs(context)
        val stored = if (p.getString("day_$profileId", null) == today(now)) p.getLong("used_$profileId", 0L) else 0L
        return stored + currentSession(context, profileId, now)
    }

    private fun currentSession(context: Context, profileId: Int, now: Long): Long {
        val p = prefs(context)
        if (p.getInt("session_profile", -1) != profileId) return 0L
        return ScheduleLogic.sessionTime(now - p.getLong("session_start", now))
    }

    /** A kid just opened an app. */
    fun startSession(context: Context, profileId: Int, now: Long = System.currentTimeMillis()) {
        endSession(context, now)
        prefs(context).edit().putInt("session_profile", profileId).putLong("session_start", now).apply()
    }

    /** Portal is back on screen: add the finished stretch to today's total. */
    fun endSession(context: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val id = p.getInt("session_profile", -1)
        if (id == -1) return
        val elapsed = ScheduleLogic.sessionTime(now - p.getLong("session_start", now))
        val sameDay = p.getString("day_$id", null) == today(now)
        val total = (if (sameDay) p.getLong("used_$id", 0L) else 0L) + elapsed
        p.edit().putString("day_$id", today(now)).putLong("used_$id", total)
            .remove("session_profile").remove("session_start").apply()
    }

    // ---------- Parent overrides ----------

    /** Adds [minutes] to today's limit. */
    fun addMinutes(context: Context, profileId: Int, minutes: Int, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val sameDay = p.getString("extra_day_$profileId", null) == today(now)
        val extra = (if (sameDay) p.getInt("extra_$profileId", 0) else 0) + minutes
        p.edit().putString("extra_day_$profileId", today(now)).putInt("extra_$profileId", extra).apply()
    }

    private fun extraMinutes(context: Context, profileId: Int, now: Long): Int {
        val p = prefs(context)
        return if (p.getString("extra_day_$profileId", null) == today(now)) p.getInt("extra_$profileId", 0) else 0
    }

    /** Ignores bedtime until the next wake-up time. */
    fun skipBedtime(context: Context, profileId: Int, now: Long = System.currentTimeMillis()) {
        val end = config(context, profileId).bedEnd
        if (end == ScheduleLogic.OFF) return
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, end / 60); set(Calendar.MINUTE, end % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        prefs(context).edit().putLong("skip_until_$profileId", cal.timeInMillis).apply()
    }

    // ---------- Checks ----------

    fun check(context: Context, profileId: Int, now: Long = System.currentTimeMillis()): ScheduleLogic.Status {
        val c = config(context, profileId)
        if (c.isEmpty) return ScheduleLogic.Status.Allowed(null)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return ScheduleLogic.check(
            nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            bedStart = c.bedStart,
            bedEnd = c.bedEnd,
            bedtimeSkipped = now < prefs(context).getLong("skip_until_$profileId", 0L),
            limitMinutes = c.limitMinutes,
            extraMinutes = extraMinutes(context, profileId, now),
            usedMs = usedMs(context, profileId, now)
        )
    }

    /** Milliseconds until [profileId] is locked out (limit or bedtime, whichever is first), or null. */
    fun timeUntilLock(context: Context, profileId: Int, now: Long = System.currentTimeMillis()): Long? {
        val c = config(context, profileId)
        if (c.isEmpty) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val limitLeft = if (c.limitMinutes > 0)
            (c.limitMinutes + extraMinutes(context, profileId, now)) * 60_000L - usedMs(context, profileId, now)
        else null
        return ScheduleLogic.timeUntilLock(
            nowSecond = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND),
            bedStart = c.bedStart,
            bedEnd = c.bedEnd,
            bedtimeSkipped = now < prefs(context).getLong("skip_until_$profileId", 0L),
            limitRemainingMs = limitLeft
        )
    }

    /** The app a kid opened, so it can be stopped when time runs out. */
    fun sessionPackage(context: Context): String? = prefs(context).getString("session_package", null)

    fun setSessionPackage(context: Context, pkg: String?) {
        prefs(context).edit().apply { if (pkg == null) remove("session_package") else putString("session_package", pkg) }.apply()
    }

    // ---------- Wording ----------

    /** "8:30 PM" for a minute-of-day. */
    fun timeLabel(minute: Int): String {
        val hour = minute / 60
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(h12, minute % 60, if (hour < 12) "AM" else "PM")
    }

    fun limitLabel(minutes: Int): String = when {
        minutes <= 0 -> "No daily limit"
        minutes % 60 == 0 -> "${minutes / 60} hr a day"
        else -> "$minutes min a day"
    }

    fun summary(c: Config): String {
        if (c.isEmpty) return "No limits"
        val parts = mutableListOf<String>()
        if (c.limitMinutes > 0) parts += limitLabel(c.limitMinutes)
        if (c.hasBedtime) parts += "bed ${timeLabel(c.bedStart)} to ${timeLabel(c.bedEnd)}"
        return parts.joinToString(" · ")
    }

    /** "45 min left" / "1 hr 10 min left". */
    /** The last minute as a clock, for example "0:42". */
    fun countdownLabel(ms: Long): String {
        val seconds = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun remainingLabel(ms: Long): String {
        val minutes = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60} hr" + (if (minutes % 60 > 0) " ${minutes % 60} min" else "") + " left"
        else "$minutes min left"
    }
}
