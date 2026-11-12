package com.portal.tv.repo

import com.portal.tv.weather.WeatherDay
import java.util.Locale

/**
 * What the 7-day forecast says about a day beyond its icon, in short human sentences. Free of
 * Android types so it can be unit tested.
 */
object DayNote {

    const val WIND_KMH = 32.0
    const val UV = 6.0

    /** Less than this much rain (mm) or snow (cm) isn't worth giving an amount for. */
    private const val TRACE_MM = 0.2
    private const val TRACE_CM = 0.3

    /** True for the weather codes of drizzle, rain, snow, showers and thunderstorms: a day with precipitation in it. */
    fun wet(code: Int): Boolean = code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99

    private fun snowy(code: Int) = code in 71..77 || code in 85..86

    /**
     * The sentence for a day with rain or snow in it, such as "70% chance of light rain, about 0.25 in",
     * or null for a dry day. [kind] is what falls, in lower case ("light rain"). The amount is left off
     * when it is only a trace, and so is the chance when it is zero.
     */
    fun precipitation(day: WeatherDay, kind: String, metric: Boolean): String? {
        if (!wet(day.code)) return null
        val chance = if (day.rainChance > 0) "${day.rainChance}% chance of $kind" else kind.replaceFirstChar { it.uppercase() }
        val amount = if (snowy(day.code)) snow(day.snowCm, metric) else rain(day.precipMm, metric)
        return if (amount == null) chance else "$chance, about $amount"
    }

    private fun rain(mm: Double?, metric: Boolean): String? {
        if (mm == null || mm < TRACE_MM) return null
        return if (metric) {
            if (mm >= 10) "${Math.round(mm)} mm" else "%.1f mm".format(Locale.US, mm)
        } else {
            val inches = mm / 25.4
            if (inches >= 1) "%.1f in".format(Locale.US, inches) else "%.2f in".format(Locale.US, inches)
        }
    }

    private fun snow(cm: Double?, metric: Boolean): String? {
        if (cm == null || cm < TRACE_CM) return null
        return if (metric) {
            if (cm >= 10) "${Math.round(cm)} cm" else "%.1f cm".format(Locale.US, cm)
        } else {
            "%.1f in".format(Locale.US, cm / 2.54)
        }
    }

    /** A short note on a day with strong wind or one with strong sun, else null. (Rain and snow are in [precipitation].) */
    fun of(day: WeatherDay, metric: Boolean): String? {
        val wind = day.windMaxKmh
        if (wind != null && wind >= WIND_KMH) {
            return "Wind " + if (metric) "${Math.round(wind)} km/h" else "${Math.round(wind * 0.621371)} mph"
        }
        val uv = day.uvMax
        if (uv != null && uv >= UV) return "UV ${Math.round(uv)}"
        return null
    }
}
