package com.portal.tv.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.portal.tv.R
import com.portal.tv.data.Profile
import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderStore
import com.portal.tv.reminders.ReminderTones
import com.portal.tv.reminders.TonePlayer
import com.portal.tv.settings.BirthdayStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The dialog for adding or changing a reminder. Month, day, hour, minute and repeat are plain buttons that open a list,
 * which works with a remote's up, down and OK, unlike wheel pickers. A date that has already gone by this year means
 * next year, so a reminder is never in the past.
 */
object ReminderDialogs {

    private val MINUTES = (0..55 step 5).toList()

    /** Adds a reminder for [profile], or changes [existing]. [onChanged] runs after it is saved or deleted. */
    fun edit(context: Context, profile: Profile, existing: Reminder?, onChanged: () -> Unit) {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val zone = ZoneId.systemDefault()
        val start = existing?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.atMillis), zone) }
            ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        var month = start.monthValue
        var day = start.dayOfMonth
        var hour12 = if (start.hour % 12 == 0) 12 else start.hour % 12
        var minute = start.minute
        var pm = start.hour >= 12
        var repeat = existing?.repeat ?: Reminder.Repeat.NONE
        var tone = existing?.tone ?: ReminderTones.DEFAULT
        var manual = existing?.manual ?: false
        var snooze = existing?.snoozeMinutes ?: 0

        val title = EditText(context).apply {
            hint = "What to remind you of"
            setSingleLine(true)
            setText(existing?.title.orEmpty())
            setPadding(px(16), px(16), px(16), px(16))
        }

        // The date and time share one row, so their buttons are tighter.
        fun chip(compact: Boolean = false) = TextView(context).apply {
            textSize = 18f
            typeface = context.resources.getFont(R.font.inter)
            setTextColor(context.resources.getColor(R.color.text_primary, null))
            gravity = Gravity.CENTER
            setPadding(px(if (compact) 14 else 24), 0, px(if (compact) 14 else 24), 0)
            minHeight = px(48)
            setBackgroundResource(R.drawable.bg_settings_tile)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = px(if (compact) 8 else 12) }
        }
        val monthChip = chip(compact = true)
        val dayChip = chip(compact = true)
        val hourChip = chip(compact = true)
        val minuteChip = chip(compact = true)
        val ampmChip = chip(compact = true)
        val repeatChip = chip()
        val toneChip = chip()
        val dismissChip = chip()
        val snoozeChip = chip()
        val monthNames = java.time.Month.values().map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }

        fun show() {
            monthChip.text = monthNames[month - 1]
            dayChip.text = day.toString()
            hourChip.text = hour12.toString()
            minuteChip.text = "%02d".format(minute)
            ampmChip.text = if (pm) "PM" else "AM"
            repeatChip.text = repeat.label
            toneChip.text = "Tone: " + TonePlayer.label(context, tone)
            dismissChip.text = if (manual) "Dismiss: manual" else "Dismiss: automatic"
            snoozeChip.text = "Snooze: " + ReminderTones.snoozeLabel(snooze)
        }

        fun pick(heading: String, labels: List<String>, current: Int, chosen: (Int) -> Unit) {
            PortalDialog.Builder(context)
                .setTitle(heading)
                .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                    chosen(which)
                    show()
                    dialog.dismiss()
                }
                .show()
        }
        monthChip.setOnClickListener {
            pick("Month", monthNames, month - 1) { month = it + 1; day = minOf(day, BirthdayStore.maxDay(month)) }
        }
        dayChip.setOnClickListener {
            pick("Day", (1..BirthdayStore.maxDay(month)).map { it.toString() }, day - 1) { day = it + 1 }
        }
        hourChip.setOnClickListener { pick("Hour", (1..12).map { it.toString() }, hour12 - 1) { hour12 = it + 1 } }
        minuteChip.setOnClickListener {
            // Steps of five, plus the reminder's own minute if it is off that grid, so changing it doesn't move it.
            val choices = (MINUTES + minute).distinct().sorted()
            pick("Minute", choices.map { "%02d".format(it) }, choices.indexOf(minute)) { minute = choices[it] }
        }
        ampmChip.setOnClickListener { pick("AM or PM", listOf("AM", "PM"), if (pm) 1 else 0) { pm = it == 1 } }
        repeatChip.setOnClickListener {
            val all = Reminder.Repeat.values().toList()
            pick("Repeat", all.map { it.label }, all.indexOf(repeat)) { repeat = all[it] }
        }
        toneChip.setOnClickListener {
            val all = TonePlayer.choices(context)
            val at = all.indexOfFirst { it.key == tone }.coerceAtLeast(0)
            // Choosing one plays it, so you hear what you picked.
            pick("Tone", all.map { it.label }, at) { tone = all[it].key; TonePlayer.play(context, tone) }
        }
        dismissChip.setOnClickListener {
            pick("The card on the screen", listOf("Goes away by itself after 30 seconds", "Stays until I dismiss it"), if (manual) 1 else 0) {
                manual = it == 1
            }
        }
        snoozeChip.setOnClickListener {
            val all = ReminderTones.snoozeChoices
            pick("Snooze button", all.map { if (it == 0) "No snooze button" else "Snooze for ${it} minutes" }, all.indexOf(snooze).coerceAtLeast(0)) {
                snooze = all[it]
            }
        }
        show()

        fun row(vararg views: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(12), 0, 0)
            views.forEach { addView(it) }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
            addView(title)
            addView(row(monthChip, dayChip, hourChip, minuteChip, ampmChip))
            addView(row(repeatChip, toneChip))
            addView(row(dismissChip, snoozeChip))
        }

        val builder = PortalDialog.Builder(context)
            .setTitle(if (existing == null) "Add reminder" else "Change reminder")
            .setView(box)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                val what = title.text.toString().trim()
                val at = nextTime(month, day, hour12, minute, pm, zone)
                when {
                    what.isEmpty() -> Toast.makeText(context, "Say what to remind you of", Toast.LENGTH_SHORT).show()
                    at == null -> Toast.makeText(context, "That time has already passed", Toast.LENGTH_SHORT).show()
                    existing == null -> {
                        val like = Reminder(0, profile.id, what, at, repeat, tone, manual, snooze)
                        ReminderStore.add(context, profile.id, what, at, repeat, like = like)
                        onChanged()
                    }
                    else -> {
                        ReminderStore.update(context, existing.copy(title = what, atMillis = at, repeat = repeat, tone = tone, manual = manual, snoozeMinutes = snooze))
                        onChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) builder.setNeutralButton("Delete") { _, _ ->
            ReminderStore.remove(context, existing.id)
            onChanged()
        }
        builder.show()
    }

    /**
     * The moment on [month]/[day] at the given time: this year, or the next one that has the date (a Feb 29 date waits
     * for a leap year) when it has already gone by. Null when that is today and the time has passed.
     */
    internal fun nextTime(
        month: Int, day: Int, hour12: Int, minute: Int, pm: Boolean, zone: ZoneId,
        now: LocalDateTime = LocalDateTime.now()
    ): Long? {
        val time = LocalTime.of(hour12 % 12 + if (pm) 12 else 0, minute)
        for (year in now.year..now.year + 4) {
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            if (date.isBefore(now.toLocalDate())) continue
            val at = LocalDateTime.of(date, time)
            return if (at.isAfter(now)) at.atZone(zone).toInstant().toEpochMilli() else null
        }
        return null
    }
}
