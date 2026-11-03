package com.portal.tv.repo

import android.content.Context

/**
 * Tracks app launch counts using SharedPreferences.
 *
 * Counts are scoped to the active profile so each person sees their own
 * "Frequently Used" row. When no profile is active, data falls back to a
 * shared "default" namespace.
 */
object UsageTracker {

    private const val BASE_PREFS = "app_usage"

    /** The namespace for the currently active profile. */
    private fun scope(context: Context): String =
        "profile_${ProfileManager.getActiveId(context) ?: "default"}"

    private fun prefs(context: Context) =
        context.getSharedPreferences("$BASE_PREFS.${scope(context)}", Context.MODE_PRIVATE)

    /** Increment the launch count for an app (for the active profile). */
    fun recordLaunch(context: Context, packageName: String) {
        val p = prefs(context)
        p.edit()
            .putInt(packageName, p.getInt(packageName, 0) + 1)
            .apply()
    }

    /**
     * Returns the top N most-launched apps as a list of package names,
     * sorted by count descending. Only includes apps with count > 0.
     */
    fun topApps(context: Context, count: Int): List<String> {
        val p = prefs(context)
        return p.all.entries
            .filter { it.value is Int && (it.value as Int) > 0 }
            .sortedByDescending { it.value as Int }
            .take(count)
            .map { it.key }
    }

    /** Get the launch count for a specific app (for the active profile). */
    fun getCount(context: Context, packageName: String): Int =
        prefs(context).getInt(packageName, 0)

    /** Wipe usage data for a profile (used when a profile is deleted). */
    fun clearForProfile(context: Context, profileId: Int) {
        context.getSharedPreferences(
            "$BASE_PREFS.profile_$profileId", Context.MODE_PRIVATE
        ).edit().clear().apply()
    }
}
