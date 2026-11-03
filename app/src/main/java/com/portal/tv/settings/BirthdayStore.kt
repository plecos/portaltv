package com.portal.tv.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * The household's birthdays. They are shared by every profile, since a birthday
 * is something the whole family sees. On the day, the home screen plays the
 * birthday video instead of the usual background.
 */
object BirthdayStore {

    private const val PREFS = "portal_birthdays"
    private const val KEY = "birthdays"
    private const val KEY_NEXT_ID = "next_id"

    data class Birthday(val id: Int, val name: String, val month: Int, val day: Int) {
        /** "Mar 14" */
        val dateLabel: String
            get() = "${Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())} $day"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All birthdays, soonest in the calendar year first. */
    fun all(context: Context): List<Birthday> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Birthday(o.getInt("id"), o.getString("name"), o.getInt("month"), o.getInt("day"))
            }.sortedWith(compareBy({ it.month }, { it.day }, { it.name.lowercase() }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, name: String, month: Int, day: Int) {
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        persist(context, all(context) + Birthday(id, name.trim(), month, day), id + 1)
    }

    fun remove(context: Context, id: Int) {
        persist(context, all(context).filter { it.id != id }, null)
    }

    /** Birthdays that fall on [date]. A Feb 29 birthday is marked on Feb 28 in non-leap years. */
    fun on(context: Context, date: LocalDate = LocalDate.now()): List<Birthday> =
        all(context).filter { b ->
            (b.month == date.monthValue && b.day == date.dayOfMonth) ||
                (b.month == 2 && b.day == 29 && !date.isLeapYear &&
                    date.monthValue == 2 && date.dayOfMonth == 28)
        }

    /** Days in [month], allowing Feb 29 so leap-day birthdays can be entered. */
    fun maxDay(month: Int): Int = Month.of(month).length(true)

    private fun persist(context: Context, list: List<Birthday>, nextId: Int?) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("month", it.month).put("day", it.day))
        }
        val editor = prefs(context).edit().putString(KEY, arr.toString())
        if (nextId != null) editor.putInt(KEY_NEXT_ID, nextId)
        editor.apply()
    }
}
