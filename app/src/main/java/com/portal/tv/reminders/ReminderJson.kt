package com.portal.tv.reminders

import org.json.JSONArray
import org.json.JSONObject

/** How reminders are written to storage. Reminders saved before tones, dismissal and snooze existed read back with the defaults. */
object ReminderJson {

    fun encode(reminders: List<Reminder>): String {
        val arr = JSONArray()
        reminders.forEach {
            val o = JSONObject()
                .put("id", it.id).put("profile", it.profileId).put("title", it.title)
                .put("at", it.atMillis).put("repeat", it.repeat.name)
                .put("tone", it.tone).put("manual", it.manual).put("snooze", it.snoozeMinutes)
            it.source?.let { source -> o.put("source", source) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String?): List<Reminder> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reminder(
                    id = o.getInt("id"),
                    profileId = o.getInt("profile"),
                    title = o.getString("title"),
                    atMillis = o.getLong("at"),
                    repeat = Reminder.Repeat.values().firstOrNull { it.name == o.optString("repeat") } ?: Reminder.Repeat.NONE,
                    tone = o.optString("tone").ifEmpty { ReminderTones.DEFAULT },
                    manual = o.optBoolean("manual", false),
                    snoozeMinutes = o.optInt("snooze", 0).coerceAtLeast(0),
                    source = o.optString("source").ifEmpty { null }
                )
            }.sortedBy { it.atMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
