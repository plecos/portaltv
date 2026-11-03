package com.portal.tv.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/**
 * Active weather alerts from the US National Weather Service (api.weather.gov), free and keyless.
 * It covers the United States only, so anywhere else the list is just empty. The place sent is the
 * same one the forecast uses. Alerts are cached for a few minutes and the last good answer is kept
 * if a refresh fails.
 */
object WeatherAlerts {

    private const val TAG = "Portal"
    private const val PREFS = "portal_alerts"
    private const val FRESH_MS = 10 * 60_000L

    enum class Severity(val rank: Int, val label: String) {
        EXTREME(4, "Extreme"), SEVERE(3, "Severe"), MODERATE(2, "Moderate"), MINOR(1, "Minor"), UNKNOWN(0, "")
    }

    data class Alert(
        val id: String,
        val event: String,
        val severity: Severity,
        val headline: String,
        val description: String,
        val instruction: String,
        val area: String,
        /** When it ends or expires, or null if the service didn't say. */
        val ends: OffsetDateTime?
    ) {
        /** Warnings are the serious ones; watches and advisories rank below them. */
        val isWarning get() = event.endsWith("Warning", ignoreCase = true)
        val isWatch get() = event.endsWith("Watch", ignoreCase = true)

        /**
         * Serious enough to interrupt: a warning rated Severe or Extreme (Tornado, Severe Thunderstorm,
         * Flash Flood and the like), or a Tornado Watch. Other warnings, watches and advisories are shown
         * in colour on the home screen but never cover an app.
         */
        val isSevere get() = (isWarning && severity.rank >= Severity.SEVERE.rank) ||
            event.equals("Tornado Watch", ignoreCase = true)
    }

    /** The alerts in a response from the service, most serious first; anything already over is left out. */
    internal fun parse(json: String, now: OffsetDateTime = OffsetDateTime.now()): List<Alert> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val alerts = (0 until features.length()).mapNotNull { i ->
            val p = features.getJSONObject(i).optJSONObject("properties") ?: return@mapNotNull null
            val ends = listOf("ends", "expires").firstNotNullOfOrNull { key ->
                p.optString(key).takeIf { it.isNotEmpty() && it != "null" }?.let {
                    runCatching { OffsetDateTime.parse(it) }.getOrNull()
                }
            }
            if (ends != null && ends.isBefore(now)) return@mapNotNull null
            Alert(
                id = p.optString("id"),
                event = p.optString("event").ifEmpty { "Weather alert" },
                severity = when (p.optString("severity")) {
                    "Extreme" -> Severity.EXTREME
                    "Severe" -> Severity.SEVERE
                    "Moderate" -> Severity.MODERATE
                    "Minor" -> Severity.MINOR
                    else -> Severity.UNKNOWN
                },
                headline = p.optString("headline"),
                description = p.optString("description").takeIf { it != "null" }.orEmpty(),
                instruction = p.optString("instruction").takeIf { it != "null" }.orEmpty(),
                area = p.optString("areaDesc"),
                ends = ends
            )
        }
        return alerts.sortedWith(
            compareByDescending<Alert> { it.isWarning }.thenByDescending { it.severity.rank }.thenBy { it.event }
        )
    }

    /** The alerts last saved, without the network. */
    fun cached(context: Context): List<Alert> {
        val body = prefs(context).getString("body", null) ?: return emptyList()
        return runCatching { parse(body) }.getOrDefault(emptyList())
    }

    /** Alerts for the weather location, from the cache when fresh (unless [force]), else from the service. */
    suspend fun load(context: Context, force: Boolean = false): List<Alert> = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val place = WeatherRepository.knownLocation(context) ?: return@withContext emptyList()
        val key = "%.2f,%.2f".format(java.util.Locale.US, place.first, place.second)
        val age = System.currentTimeMillis() - p.getLong("time", 0L)
        if (!force && p.getString("key", null) == key && age in 0 until FRESH_MS) return@withContext cached(context)
        try {
            val conn = URL("${base(context)}/alerts/active?point=$key").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Portal TV (https://github.com/plecos/portaltv)")
            conn.setRequestProperty("Accept", "application/geo+json")
            try {
                // A 404 means a place the service doesn't cover, which is simply no alerts.
                if (conn.responseCode == 404 || conn.responseCode == 400) {
                    p.edit().putString("body", "{}").putString("key", key).putLong("time", System.currentTimeMillis()).apply()
                    return@withContext emptyList()
                }
                if (conn.responseCode != 200) return@withContext cached(context)
                val body = conn.inputStream.bufferedReader().readText()
                val alerts = parse(body)
                p.edit().putString("body", body).putString("key", key).putLong("time", System.currentTimeMillis()).apply()
                alerts
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather alerts failed: ${e.message}")
            cached(context)
        }
    }

    /** The alerts service's address. Debug builds can point it at a test server, to try a severe alert. */
    private fun base(context: Context): String {
        if (!com.portal.tv.BuildConfig.DEBUG) return "https://api.weather.gov"
        return context.getSharedPreferences("alerts_debug", Context.MODE_PRIVATE)
            .getString("base", null)?.trimEnd('/') ?: "https://api.weather.gov"
    }

    // ---------- Severe alerts over apps ----------

    /** Whether a new severe alert covers whatever app is open. One choice for the whole TV. */
    fun overApps(context: Context): Boolean = prefs(context).getBoolean("over_apps", true)

    fun setOverApps(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("over_apps", on).apply()
    }

    /**
     * The severe alerts among [alerts] that have not been announced yet, most serious first. They are
     * remembered as announced, so each is only ever shown once.
     */
    fun takeNewSevere(context: Context, alerts: List<Alert>): List<Alert> {
        val p = prefs(context)
        val shown = p.getStringSet("announced", emptySet()).orEmpty()
        val fresh = alerts.filter { it.isSevere && it.id.isNotEmpty() && it.id !in shown }
        if (fresh.isNotEmpty()) {
            // Only the ones still active are worth remembering, so the list can't grow without end.
            val keep = (shown + fresh.map { it.id }).toList().takeLast(60).toSet()
            p.edit().putStringSet("announced", keep).apply()
        }
        return fresh
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
