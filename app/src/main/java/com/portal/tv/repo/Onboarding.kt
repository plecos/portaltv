package com.portal.tv.repo

import android.content.Context

/** Whether the first-run setup has been completed on this TV. */
object Onboarding {
    private const val PREFS = "portal_onboarding"
    private const val KEY_DONE = "done"
    private const val KEY_STEP = "step_name"
    private const val KEY_ANSWERED = "answered"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDone(context: Context) = prefs(context).getBoolean(KEY_DONE, false)

    /** Where the first-run setup was when it was left, so a restart carries on from there. */
    fun savedStep(context: Context): String? = prefs(context).getString(KEY_STEP, null)

    fun savedAnswered(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ANSWERED, emptySet()) ?: emptySet()

    fun saveProgress(context: Context, step: String, answered: Set<String>) =
        prefs(context).edit().putString(KEY_STEP, step).putStringSet(KEY_ANSWERED, answered).apply()

    fun markDone(context: Context) = prefs(context).edit()
        .putBoolean(KEY_DONE, true).remove(KEY_STEP).remove(KEY_ANSWERED).apply()

    /** A TV that already has profiles was set up before this flow existed, so it isn't asked again. */
    fun migrate(context: Context) {
        if (!isDone(context) && ProfileManager.getAll(context).isNotEmpty()) markDone(context)
    }
}
