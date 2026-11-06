package com.portal.tv.reminders

import android.content.Context

/**
 * Every profile's reminders, in one place: a reminder is set by a profile but has to fire whoever is using the TV, so
 * they can't live in the per-profile settings. The way missed reminders are handled is also here, for the whole TV.
 */
object ReminderStore {

    private const val PREFS = "portal_reminders"
    private const val KEY = "reminders"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_MISSED = "missed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All reminders, soonest first. */
    fun all(context: Context): List<Reminder> = ReminderJson.decode(prefs(context).getString(KEY, null))

    fun forProfile(context: Context, profileId: Int): List<Reminder> = all(context).filter { it.profileId == profileId }

    /** Adds a reminder; [like] carries the sound, dismissal and snooze settings over (its id, profile, title and time are ignored). */
    fun add(
        context: Context, profileId: Int, title: String, atMillis: Long, repeat: Reminder.Repeat, like: Reminder? = null
    ): Reminder {
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        val reminder = (like ?: Reminder(id, profileId, "", atMillis))
            .copy(id = id, profileId = profileId, title = title.trim(), atMillis = atMillis, repeat = repeat)
        persist(context, all(context) + reminder, id + 1)
        ReminderService.ensureRunning(context)
        return reminder
    }

    fun update(context: Context, reminder: Reminder) {
        persist(context, all(context).map { if (it.id == reminder.id) reminder else it }, null)
        ReminderService.ensureRunning(context)
    }

    fun remove(context: Context, id: Int) {
        persist(context, all(context).filter { it.id != id }, null)
    }

    /** The reminders made by the extension [source], whichever profile they belong to. */
    fun forSource(context: Context, source: String): List<Reminder> = all(context).filter { it.source == source }

    /** An extension was turned off for reminders, or removed: what it made goes with it. */
    fun clearForSource(context: Context, source: String) {
        if (all(context).any { it.source == source }) persist(context, all(context).filter { it.source != source }, null)
    }

    /** A profile was deleted, and its reminders go with it. */
    fun clearForProfile(context: Context, profileId: Int) {
        persist(context, all(context).filter { it.profileId != profileId }, null)
    }

    /** Replaces the whole list, for the service after it has shown what was due. */
    fun replace(context: Context, reminders: List<Reminder>) = persist(context, reminders, null)

    fun missedPolicy(context: Context): MissedPolicy = MissedPolicy.fromKey(prefs(context).getString(KEY_MISSED, null))

    fun setMissedPolicy(context: Context, policy: MissedPolicy) {
        prefs(context).edit().putString(KEY_MISSED, policy.key).apply()
    }

    private fun persist(context: Context, reminders: List<Reminder>, nextId: Int?) {
        prefs(context).edit().apply {
            putString(KEY, ReminderJson.encode(reminders))
            if (nextId != null) putInt(KEY_NEXT_ID, nextId)
        }.apply()
    }
}
