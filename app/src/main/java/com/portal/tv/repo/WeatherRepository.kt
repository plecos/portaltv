package com.portal.tv.repo

import android.content.Context
import android.util.Log
import com.portal.tv.weather.ProviderResult
import com.portal.tv.weather.WeatherHttp
import com.portal.tv.weather.WeatherJson
import com.portal.tv.weather.WeatherPlace
import com.portal.tv.weather.WeatherProviders
import com.portal.tv.weather.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Portal's side of the weather: the chosen location, what was last received (kept so the screens have something to show
 * straight away and when the network is down), and when to ask the weather source ([WeatherProviders.active]) again. It
 * knows nothing about any service's format; that is the source's business.
 */
object WeatherRepository {

    private const val TAG = "Portal"

    /**
     * What a load produced. [report] is null only when nothing is available at all; otherwise a
     * failed refresh still returns the last saved report with [stale] set and the reason in
     * [error]. [updatedAt] is when [report] was fetched, in epoch millis.
     */
    data class Outcome(
        val report: WeatherReport?,
        val error: String?,
        val updatedAt: Long,
        val stale: Boolean
    )

    /** A place picked from a city search. */
    data class GeoResult(val name: String, val region: String?, val lat: Double, val lon: Double) {
        val label get() = listOfNotNull(name, region).joinToString(", ")
    }

    private const val PREFS = "portal_weather"

    /** The reason given when no weather provider is installed: the screens then show no weather at all. */
    const val NO_PROVIDER = "No weather provider"

    /** Where the saved report is kept, in Portal's own format (the older "body" key held one service's raw answer). */
    private const val REPORT_KEY = "report_json"

    private var cachedPlace: WeatherPlace? = null

    /** Short reason the last location lookup failed, e.g. "No connection". */
    private var lastFailure: String? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Latitude and longitude of the chosen city, else the last connection-based location; null if neither is known. */
    fun knownLocation(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (p.contains("manual_lat") && p.getString("manual_name", null) != null) {
            return p.getFloat("manual_lat", 0f).toDouble() to p.getFloat("manual_lon", 0f).toDouble()
        }
        if (p.contains("auto_lat")) return p.getFloat("auto_lat", 0f).toDouble() to p.getFloat("auto_lon", 0f).toDouble()
        return null
    }

    // ---------- Location choice ----------

    /** The city picked in Settings, or null when the location is automatic. */
    fun manualCity(context: Context): String? = prefs(context).getString("manual_name", null)

    fun setManualCity(context: Context, place: GeoResult) {
        prefs(context).edit()
            .putString("manual_name", place.label)
            .putString("manual_city", place.name)
            .putFloat("manual_lat", place.lat.toFloat())
            .putFloat("manual_lon", place.lon.toFloat())
            .remove(REPORT_KEY) // the saved report belongs to the old place
            .apply()
    }

    /**
     * Saves an exact spot, such as one picked on the radar map, as the location for the forecast,
     * radar and alerts. The page keeps showing the nearest known city's name.
     */
    fun setCustomSpot(context: Context, lat: Double, lon: Double) {
        val p = prefs(context)
        val city = p.getString("manual_city", null) ?: p.getString("auto_city", null)
        p.edit()
            .putString("manual_name", if (city != null) "$city (custom spot)" else "Custom spot")
            .putString("manual_city", city)
            .putFloat("manual_lat", lat.toFloat())
            .putFloat("manual_lon", lon.toFloat())
            .remove(REPORT_KEY)
            .apply()
    }

    fun useAutomaticLocation(context: Context) {
        prefs(context).edit()
            .remove("manual_name").remove("manual_city").remove("manual_lat").remove("manual_lon")
            .remove(REPORT_KEY)
            .apply()
    }

    /** City search for the Settings picker, answered by the weather source in use. Null if it couldn't search, empty when nothing matches. */
    suspend fun searchCities(context: Context, query: String): List<GeoResult>? = withContext(Dispatchers.IO) {
        when (val result = WeatherProviders.active(context).searchCities(query.trim())) {
            is ProviderResult.Failed -> null
            is ProviderResult.Ok -> result.value.map { GeoResult(it.name, it.region, it.lat, it.lon) }
        }
    }

    // ---------- Loading ----------

    /** When the last saved report was fetched, epoch millis (0 if there is none): a cheap way to tell it has changed. */
    fun cachedStamp(context: Context): Long = prefs(context).getLong("body_time", 0L)

    /** Forgets the saved report (it came from a weather source that is no longer the one in use). */
    fun forgetCached(context: Context) {
        prefs(context).edit().remove(REPORT_KEY).remove("body_time").apply()
    }

    /** The last saved report, without touching the network. */
    fun cached(context: Context): Outcome? {
        // With no weather provider installed what was kept isn't shown (and is forgotten).
        if (!com.portal.tv.weather.WeatherSource.installed(context)) { forgetCached(context); return null }
        val p = prefs(context)
        val body = p.getString(REPORT_KEY, null) ?: return null
        return try {
            Outcome(WeatherJson.decodeReport(body), null, p.getLong("body_time", 0L), true)
        } catch (e: Exception) {
            null
        }
    }

    /** Asks the weather source for the forecast, falling back to the last saved one when the network or the source fails. */
    suspend fun load(context: Context): Outcome = withContext(Dispatchers.IO) {
        lastFailure = null
        if (!com.portal.tv.weather.WeatherSource.installed(context)) {
            forgetCached(context)
            return@withContext Outcome(null, NO_PROVIDER, 0L, true)
        }
        val fallback = cached(context)
        fun failed(reason: String) =
            fallback?.copy(error = reason) ?: Outcome(null, reason, 0L, true)
        try {
            val place = locate(context) ?: return@withContext failed(lastFailure ?: "Location unavailable")
            when (val result = WeatherProviders.active(context).fetchReport(place)) {
                is ProviderResult.Failed -> failed(result.reason)
                is ProviderResult.Ok -> {
                    val now = System.currentTimeMillis()
                    prefs(context).edit()
                        .putString(REPORT_KEY, WeatherJson.encode(result.value)).putLong("body_time", now)
                        .apply()
                    Outcome(result.value, null, now, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather report failed: ${e.message}")
            failed("Weather error")
        }
    }

    /**
     * The city chosen in Settings, else a rough location from the connection's IP. If the lookup
     * fails the last good IP location is reused; null means no location is known at all.
     */
    private fun locate(context: Context): WeatherPlace? {
        val p = prefs(context)
        p.getString("manual_name", null)?.let {
            return WeatherPlace(
                p.getFloat("manual_lat", 0f).toDouble(),
                p.getFloat("manual_lon", 0f).toDouble(),
                p.getString("manual_city", null)
            )
        }
        cachedPlace?.let { return it }
        val response = WeatherHttp.get("https://ipapi.co/json/")
        lastFailure = response.failure
        val json = response.body
        val fresh = try {
            val obj = JSONObject(json ?: "{}")
            val lat = obj.optDouble("latitude", 0.0)
            val lon = obj.optDouble("longitude", 0.0)
            if (json == null || (lat == 0.0 && lon == 0.0)) null
            else WeatherPlace(lat, lon, obj.optString("city").takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            null
        }
        if (fresh != null) {
            cachedPlace = fresh
            p.edit().putFloat("auto_lat", fresh.lat.toFloat()).putFloat("auto_lon", fresh.lon.toFloat())
                .putString("auto_city", fresh.city).apply()
            return fresh
        }
        if (!p.contains("auto_lat")) return null
        return WeatherPlace(p.getFloat("auto_lat", 0f).toDouble(), p.getFloat("auto_lon", 0f).toDouble(),
            p.getString("auto_city", null))
    }
}
