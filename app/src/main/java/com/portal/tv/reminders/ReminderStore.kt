package com.portal.tv.reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    fun all(context: Context): List<Reminder> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    id = o.getInt("id"),
                    profileId = o.getInt("profile"),
                    title = o.getString("title"),
                    atMillis = o.getLong("at"),
                    repeat = Reminder.Repeat.values().firstOrNull { it.name == o.optString("repeat") } ?: Reminder.Repeat.NONE
                )
            }.sortedBy { it.atMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun forProfile(context: Context, profileId: Int): List<Reminder> = all(context).filter { it.profileId == profileId }

    fun add(context: Context, profileId: Int, title: String, atMillis: Long, repeat: Reminder.Repeat): Reminder {
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        val reminder = Reminder(id, profileId, title.trim(), atMillis, repeat)
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
        val arr = JSONArray()
        reminders.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("profile", it.profileId).put("title", it.title)
                    .put("at", it.atMillis).put("repeat", it.repeat.name)
            )
        }
        prefs(context).edit().apply {
            putString(KEY, arr.toString())
            if (nextId != null) putInt(KEY_NEXT_ID, nextId)
        }.apply()
    }
}
