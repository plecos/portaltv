package com.portal.tv.weather

import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * What a weather source hands Portal, whoever it is. Portal's own screens, the screen saver, the weather
 * backgrounds and the severe-alert watcher read only these types, never a service's own format, so a source can
 * be replaced without touching them.
 *
 * Conditions are WMO weather interpretation codes (0 clear, 1-3 cloud, 45/48 fog, 51-57 drizzle, 61-67 rain,
 * 71-77 snow, 80-86 showers, 95-99 thunderstorm), the same numbers Open-Meteo reports. A source that speaks
 * another code maps it to the nearest of these.
 */

/** A place to fetch weather for. [city] is only a name to show; the forecast is for the coordinates. */
data class WeatherPlace(val lat: Double, val lon: Double, val city: String?)

data class WeatherHour(
    val hour24: Int,
    val tempC: Double,
    val code: Int,
    val isDay: Boolean,
    /** Chance of rain or snow in this hour, percent, when the source gives one. */
    val rainChance: Int? = null
)

data class WeatherDay(
    val date: LocalDate,
    val code: Int,
    val highC: Double,
    val lowC: Double,
    val rainChance: Int,
    /** Local sunrise and sunset as ISO times such as "2026-10-30T07:12". */
    val sunrise: String? = null,
    val sunset: String? = null,
    val uvMax: Double? = null,
    /** Total rain and snow melt expected, millimetres. */
    val precipMm: Double? = null,
    /** Strongest wind of the day, km/h, and snowfall, centimetres. */
    val windMaxKmh: Double? = null,
    val snowCm: Double? = null
)

data class WeatherReport(
    val city: String?,
    val tempC: Double,
    val feelsLikeC: Double,
    val humidity: Int,
    val windKmh: Double,
    val windDirection: Int,
    val code: Int,
    val isDay: Boolean,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** The place's offset from UTC, for turning local times into moments. */
    val utcOffsetSeconds: Int = 0,
    val gustKmh: Double? = null,
    val pressureHpa: Double? = null,
    val cloudCover: Int? = null,
    val visibilityKm: Double? = null,
    val uvIndex: Double? = null,
    /** US air quality index; null when the source has none. */
    val aqi: Int? = null
)

enum class AlertSeverity(val rank: Int, val label: String) {
    EXTREME(4, "Extreme"), SEVERE(3, "Severe"), MODERATE(2, "Moderate"), MINOR(1, "Minor"), UNKNOWN(0, "")
}

data class WeatherAlert(
    val id: String,
    val event: String,
    val severity: AlertSeverity,
    val headline: String,
    val description: String,
    val instruction: String,
    val area: String,
    /** When it ends or expires, or null if the source didn't say. */
    val ends: OffsetDateTime?,
    /** When it begins, if it is not already in force. */
    val onset: OffsetDateTime? = null,
    /** Who issued it, for showing, such as "National Weather Service". */
    val source: String = ""
) {
    /** Warnings are the serious ones; watches and advisories rank below them. */
    val isWarning get() = event.endsWith("Warning", ignoreCase = true)
    val isWatch get() = event.endsWith("Watch", ignoreCase = true)

    /**
     * Serious enough to interrupt: a warning rated Severe or Extreme (Tornado, Severe Thunderstorm,
     * Flash Flood and the like), or a Tornado Watch. Other warnings, watches and advisories are shown
     * in colour on the home screen but never cover an app.
     */
    val isSevere get() = (isWarning && severity.rank >= AlertSeverity.SEVERE.rank) ||
        event.equals("Tornado Watch", ignoreCase = true)
}

/** A source's answer: the value, or the short reason it could not be had (such as "No connection"). */
sealed class ProviderResult<out T> {
    data class Ok<T>(val value: T) : ProviderResult<T>()
    data class Failed(val reason: String) : ProviderResult<Nothing>()
}

/**
 * Where weather comes from. Portal asks the one chosen source on its own schedule and keeps what it gets; screens
 * never talk to a source directly. Implementations do their own network work and must not throw.
 */
interface WeatherProvider {
    /** Who the weather is from, for showing to an adult. */
    val name: String

    suspend fun fetchReport(place: WeatherPlace): ProviderResult<WeatherReport>

    /** Alerts in force at [place], most serious first. A place the source doesn't cover is [ProviderResult.Ok] with an empty list. */
    suspend fun fetchAlerts(place: WeatherPlace): ProviderResult<List<WeatherAlert>>
}
