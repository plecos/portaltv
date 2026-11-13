package com.portal.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.portal.tv.weather.WeatherAlert
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How weather alerts look: red for warnings, orange for watches, yellow for advisories and statements. */
object AlertStyle {

    fun color(alert: WeatherAlert): Int = when {
        alert.isWarning -> Color.parseColor("#E5484D")
        alert.isWatch -> Color.parseColor("#F5A524")
        else -> Color.parseColor("#F2C94C")
    }

    /** Dark text on the yellow and orange, white on the red. */
    fun textColor(alert: WeatherAlert): Int = if (alert.isWarning) Color.WHITE else Color.parseColor("#1B1B1B")

    fun background(alert: WeatherAlert, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color(alert)); cornerRadius = radiusPx }

    /** "until 12:00 PM", or "until Sat 12:00 PM" when it isn't today; empty without an end time. */
    fun until(context: Context, alert: WeatherAlert, now: OffsetDateTime = OffsetDateTime.now()): String {
        val ends = alert.ends?.atZoneSameInstant(java.time.ZoneId.systemDefault()) ?: return ""
        val sameDay = ends.toLocalDate() == now.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
        val pattern = if (sameDay) "h:mm a" else "EEE h:mm a"
        return "until " + ends.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    /** The one-line text for the home badge: the most serious alert, and how many more there are. */
    fun badgeText(alerts: List<WeatherAlert>): String =
        alerts.first().event + if (alerts.size > 1) "  +${alerts.size - 1}" else ""
}
