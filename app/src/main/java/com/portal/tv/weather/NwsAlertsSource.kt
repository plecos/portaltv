package com.portal.tv.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Active weather alerts from the US National Weather Service (api.weather.gov), free and keyless. It covers the United
 * States only, so anywhere else the list is just empty. [base] is only different when a debug build points it at a test server.
 */
internal class NwsAlertsSource(private val base: String = DEFAULT_BASE) {

    suspend fun fetch(place: WeatherPlace): ProviderResult<List<WeatherAlert>> = withContext(Dispatchers.IO) {
        val point = "%.2f,%.2f".format(java.util.Locale.US, place.lat, place.lon)
        val response = WeatherHttp.get(
            "$base/alerts/active?point=$point",
            userAgent = "Portal TV (https://github.com/plecos/portaltv)",
            accept = "application/geo+json"
        )
        when {
            // A 404 or 400 means a place the service doesn't cover, which is simply no alerts.
            response.code == 404 || response.code == 400 -> ProviderResult.Ok(emptyList())
            !response.ok -> ProviderResult.Failed(response.failure ?: "Server error")
            else -> try {
                ProviderResult.Ok(parse(response.body!!))
            } catch (e: Exception) {
                ProviderResult.Failed("Weather error")
            }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.weather.gov"
        const val SOURCE = "National Weather Service"

        /**
         * Serious enough to interrupt: a warning rated Severe or Extreme (Tornado, Severe Thunderstorm, Flash Flood and the
         * like), or a Tornado Watch. Other warnings, watches and advisories are shown in colour but never cover an app.
         */
        internal fun interrupts(event: String, severity: AlertSeverity) =
            (event.endsWith("Warning", ignoreCase = true) && severity.rank >= AlertSeverity.SEVERE.rank) ||
                event.equals("Tornado Watch", ignoreCase = true)

        /** The alerts in a response from the service, most serious first; anything already over is left out. */
        internal fun parse(json: String, now: OffsetDateTime = OffsetDateTime.now()): List<WeatherAlert> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            val alerts = (0 until features.length()).mapNotNull { i ->
                val p = features.getJSONObject(i).optJSONObject("properties") ?: return@mapNotNull null
                fun time(key: String) = p.optString(key).takeIf { it.isNotEmpty() && it != "null" }?.let {
                    runCatching { OffsetDateTime.parse(it) }.getOrNull()
                }
                val ends = listOf("ends", "expires").firstNotNullOfOrNull { time(it) }
                if (ends != null && ends.isBefore(now)) return@mapNotNull null
                val event = p.optString("event").ifEmpty { "Weather alert" }
                val severity = when (p.optString("severity")) {
                    "Extreme" -> AlertSeverity.EXTREME
                    "Severe" -> AlertSeverity.SEVERE
                    "Moderate" -> AlertSeverity.MODERATE
                    "Minor" -> AlertSeverity.MINOR
                    else -> AlertSeverity.UNKNOWN
                }
                WeatherAlert(
                    id = p.optString("id"),
                    event = event,
                    severity = severity,
                    headline = p.optString("headline"),
                    description = p.optString("description").takeIf { it != "null" }.orEmpty(),
                    instruction = p.optString("instruction").takeIf { it != "null" }.orEmpty(),
                    area = p.optString("areaDesc"),
                    ends = ends,
                    onset = time("onset"),
                    source = SOURCE,
                    interrupts = interrupts(event, severity)
                )
            }
            return alerts.sortedWith(
                compareByDescending<WeatherAlert> { it.isWarning }.thenByDescending { it.severity.rank }.thenBy { it.event }
            )
        }
    }
}
