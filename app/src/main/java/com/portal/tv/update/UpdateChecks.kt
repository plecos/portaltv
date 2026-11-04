package com.portal.tv.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The daily look for a new version. When Portal's home screen comes up and a check is due, it asks GitHub once; the
 * result is kept, and the home screen shows an Update button if there is a newer version. It never downloads or
 * installs on its own. The person can turn it off in Settings, System, Updates.
 */
object UpdateChecks {
    private const val PREFS = "portal_updates"
    private const val KEY_AUTO = "auto_check"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 60L * 60 * 1000

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    }

    /**
     * Whether a check is due: switched on, the last good one was over a day ago, and no attempt (good or failed) in the
     * last hour, so being offline doesn't mean trying every time the home screen shows.
     */
    internal fun isDue(enabled: Boolean, checkedAt: Long, attemptedAt: Long, now: Long): Boolean =
        enabled && now - checkedAt >= DAY_MS && now - attemptedAt >= RETRY_MS

    /** Checks now if one is due. Returns true if it asked GitHub. Quiet about failures: the next one is soon enough. */
    suspend fun checkIfDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        if (!isDue(enabled(app), Updates.lastCheckedAt(app), Updates.lastAttemptAt(app), now)) return false
        Updates.markAttempt(app, now)
        runCatching { withContext(Dispatchers.IO) { UpdateClient.latest(app) } }
            .onSuccess { Updates.saveCheck(app, it, now) }
        return true
    }
}
