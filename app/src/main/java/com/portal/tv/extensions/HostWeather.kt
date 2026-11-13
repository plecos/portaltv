package com.portal.tv.extensions

import com.portal.tv.repo.WeatherRepository
import com.portal.tv.weather.WeatherDay
import com.portal.tv.weather.WeatherReport
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The weather Portal last fetched and when, as the host API sees it. */
class WeatherSnapshot(val report: WeatherReport, val updatedAtMs: Long)

/**
 * What `weather.get` tells an extension: the weather as plain numbers it can draw from, and where the TV roughly is. Worked out
 * here, from the report Portal already holds, so an extension needs no weather codes or service of its own. The location is
 * rounded to a tenth of a degree (about 10 km): enough to place the sun and moon, not enough to find a house.
 */
object WeatherForExtensions {

    /** A report older than this is marked `stale`. */
    const val STALE_AFTER_MS = 3L * 60 * 60 * 1000

    fun from(snapshot: WeatherSnapshot, nowMs: Long): Map<String, Any?> {
        val r = snapshot.report
        val (rain, snow, thunder) = precipitation(r.code)
        val fog = fog(r.code, r.visibilityKm)
        val cloud = clouds(r.code, r.cloudCover, maxOf(rain, snow))
        val out = linkedMapOf<String, Any?>(
            "as_of_millis" to snapshot.updatedAtMs,
            "stale" to (nowMs - snapshot.updatedAtMs > STALE_AFTER_MS),
            "code" to r.code,
            "condition" to condition(r.code, cloud),
            "cloud" to cloud,
            "rain" to rain,
            "snow" to snow,
            "thunder" to thunder,
            "fog" to fog,
            "wind_kmh" to r.windKmh,
            "wind_dir" to r.windDirection,
            "temp_c" to r.tempC,
            "is_day" to r.isDay,
            "utc_offset_seconds" to r.utcOffsetSeconds
        )
        r.gustKmh?.let { out["gust_kmh"] = it }
        r.visibilityKm?.let { out["visibility_km"] = it }
        // 0, 0 is how an unknown place is stored.
        if (r.latitude != 0.0 || r.longitude != 0.0) {
            out["latitude"] = tenth(r.latitude)
            out["longitude"] = tenth(r.longitude)
        }
        today(r, nowMs)?.let { day ->
            day.sunrise?.let { atMillis(it, r.utcOffsetSeconds) }?.let { out["sunrise_millis"] = it }
            day.sunset?.let { atMillis(it, r.utcOffsetSeconds) }?.let { out["sunset_millis"] = it }
        }
        return out
    }

    internal fun tenth(degrees: Double): Double = Math.round(degrees * 10.0) / 10.0

    /** How hard it rains, snows and thunders, each 0 to 1, from the WMO weather code. */
    internal fun precipitation(code: Int): Triple<Double, Double, Double> = when (code) {
        51 -> Triple(0.2, 0.0, 0.0)
        53 -> Triple(0.3, 0.0, 0.0)
        55 -> Triple(0.4, 0.0, 0.0)
        56 -> Triple(0.25, 0.0, 0.0)
        57 -> Triple(0.4, 0.0, 0.0)
        61 -> Triple(0.35, 0.0, 0.0)
        63 -> Triple(0.6, 0.0, 0.0)
        65 -> Triple(0.9, 0.0, 0.0)
        66 -> Triple(0.5, 0.0, 0.0)
        67 -> Triple(0.8, 0.0, 0.0)
        71 -> Triple(0.0, 0.3, 0.0)
        73 -> Triple(0.0, 0.6, 0.0)
        75 -> Triple(0.0, 0.9, 0.0)
        77 -> Triple(0.0, 0.3, 0.0)
        80 -> Triple(0.4, 0.0, 0.0)
        81 -> Triple(0.65, 0.0, 0.0)
        82 -> Triple(0.95, 0.0, 0.0)
        85 -> Triple(0.0, 0.4, 0.0)
        86 -> Triple(0.0, 0.85, 0.0)
        95 -> Triple(0.6, 0.0, 1.0)
        96 -> Triple(0.75, 0.0, 1.0)
        99 -> Triple(0.85, 0.0, 1.0)
        else -> Triple(0.0, 0.0, 0.0)
    }

    /** How foggy, 0 to 1: the fog codes, else how far one can see. */
    internal fun fog(code: Int, visibilityKm: Double?): Double = when {
        code == 45 -> 0.7
        code == 48 -> 0.85
        visibilityKm == null -> 0.0
        visibilityKm < 0.2 -> 0.9
        visibilityKm < 1.0 -> 0.6
        visibilityKm < 3.0 -> 0.3
        visibilityKm < 6.0 -> 0.1
        else -> 0.0
    }

    /** How cloudy, 0 to 1: the measured cover if there is one, else from the code; never clearer than it must be when it rains. */
    internal fun clouds(code: Int, coverPercent: Int?, precipitation: Double): Double {
        val base = coverPercent?.let { it.coerceIn(0, 100) / 100.0 } ?: when (code) {
            0 -> 0.05
            1 -> 0.2
            2 -> 0.5
            3 -> 0.95
            45, 48 -> 0.8
            else -> if (precipitation > 0) 0.9 else 0.5
        }
        return if (precipitation > 0) maxOf(base, 0.7) else base
    }

    /** A plain word for the sky: clear, partly_cloudy, cloudy, fog, drizzle, rain, snow or thunderstorm. */
    internal fun condition(code: Int, cloud: Double): String = when (code) {
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "thunderstorm"
        else -> when {
            cloud < 0.25 -> "clear"
            cloud < 0.7 -> "partly_cloudy"
            else -> "cloudy"
        }
    }

    private fun today(r: WeatherReport, nowMs: Long): WeatherDay? {
        val local = java.time.Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds)).toLocalDate()
        return r.days.firstOrNull { it.date == local }
    }

    /** A local time like "2026-10-30T07:12" at [offsetSeconds] from UTC, as a moment in epoch milliseconds. */
    internal fun atMillis(local: String, offsetSeconds: Int): Long? = try {
        LocalDateTime.parse(local).toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
