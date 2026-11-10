package com.portal.tv.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Current conditions and forecast from Open-Meteo (free, no API key), for the TV's rough location. */
object WeatherRepository {

    private const val TAG = "Portal"

    data class Hour(
        val hour24: Int,
        val tempC: Double,
        val code: Int,
        val isDay: Boolean,
        /** Chance of rain or snow in this hour, percent, when the forecast gives one. */
        val rainChance: Int? = null
    )

    data class Day(
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

    data class Report(
        val city: String?,
        val tempC: Double,
        val feelsLikeC: Double,
        val humidity: Int,
        val windKmh: Double,
        val windDirection: Int,
        val code: Int,
        val isDay: Boolean,
        val hours: List<Hour>,
        val days: List<Day>,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        /** The place's offset from UTC, for turning local times into moments. */
        val utcOffsetSeconds: Int = 0,
        val gustKmh: Double? = null,
        val pressureHpa: Double? = null,
        val cloudCover: Int? = null,
        val visibilityKm: Double? = null,
        val uvIndex: Double? = null,
        /** US air quality index, from a separate service; null when it couldn't be fetched. */
        val aqi: Int? = null
    )

    /**
     * What a load produced. [report] is null only when nothing is available at all; otherwise a
     * failed refresh still returns the last saved report with [stale] set and the reason in
     * [error]. [updatedAt] is when [report] was fetched, in epoch millis.
     */
    data class Outcome(
        val report: Report?,
        val error: String?,
        val updatedAt: Long,
        val stale: Boolean
    )

    /** A place picked from a city search. */
    data class GeoResult(val name: String, val region: String?, val lat: Double, val lon: Double) {
        val label get() = listOfNotNull(name, region).joinToString(", ")
    }

    private data class Place(val lat: Double, val lon: Double, val city: String?)

    private const val PREFS = "portal_weather"

    private var cachedPlace: Place? = null

    /** Short reason for the most recent failed request, e.g. "No connection". */
    private var lastFailure: String? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The forecast service's address. Debug builds can point it at a test server, to try odd forecasts. */
    private fun forecastBase(context: Context): String {
        if (!com.portal.tv.BuildConfig.DEBUG) return "https://api.open-meteo.com"
        return context.getSharedPreferences("weather_debug", Context.MODE_PRIVATE)
            .getString("forecast_base", null)?.trimEnd('/') ?: "https://api.open-meteo.com"
    }

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
            .remove("body") // the saved report belongs to the old place
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
            .remove("body")
            .apply()
    }

    fun useAutomaticLocation(context: Context) {
        prefs(context).edit()
            .remove("manual_name").remove("manual_city").remove("manual_lat").remove("manual_lon")
            .remove("body")
            .apply()
    }

    /** City search for the Settings picker. Null on a network failure, empty when nothing matches. */
    suspend fun searchCities(query: String): List<GeoResult>? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
            java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val body = httpGet(url) ?: return@withContext null
        try {
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).map { i ->
                val o = results.getJSONObject(i)
                GeoResult(
                    name = o.getString("name"),
                    region = listOfNotNull(
                        o.optString("admin1").takeIf { it.isNotEmpty() },
                        o.optString("country_code").takeIf { it.isNotEmpty() }
                    ).joinToString(", ").takeIf { it.isNotEmpty() },
                    lat = o.getDouble("latitude"),
                    lon = o.getDouble("longitude")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Loading ----------

    /** When the last saved report was fetched, epoch millis (0 if there is none): a cheap way to tell it has changed. */
    fun cachedStamp(context: Context): Long = prefs(context).getLong("body_time", 0L)

    /** The last saved report, without touching the network. */
    fun cached(context: Context): Outcome? {
        val p = prefs(context)
        val body = p.getString("body", null) ?: return null
        return try {
            val aqi = p.getInt("body_aqi", -1).takeIf { it >= 0 }
            Outcome(parse(JSONObject(body), p.getString("body_city", null)).copy(aqi = aqi), null, p.getLong("body_time", 0L), true)
        } catch (e: Exception) {
            null
        }
    }

    /** Loads the forecast, falling back to the last saved one when the network or service fails. */
    suspend fun load(context: Context): Outcome = withContext(Dispatchers.IO) {
        lastFailure = null
        val fallback = cached(context)
        fun failed(reason: String) =
            fallback?.copy(error = reason) ?: Outcome(null, reason, 0L, true)
        try {
            val place = locate(context) ?: return@withContext failed(lastFailure ?: "Location unavailable")
            val url = "${forecastBase(context)}/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,cloud_cover,is_day" +
                "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,uv_index,visibility" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                "sunrise,sunset,uv_index_max,precipitation_sum,wind_speed_10m_max,snowfall_sum" +
                "&timezone=auto&forecast_days=7"
            val body = httpGet(url) ?: return@withContext failed(lastFailure ?: "Weather error")
            val aqi = airQuality(place)
            val report = parse(JSONObject(body), place.city).copy(aqi = aqi)
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putString("body", body).putString("body_city", place.city).putLong("body_time", now)
                .putInt("body_aqi", aqi ?: -1)
                .apply()
            Outcome(report, null, now, false)
        } catch (e: Exception) {
            Log.w(TAG, "Weather report failed: ${e.message}")
            failed("Weather error")
        }
    }

    /** The US air quality index here now, or null. Best effort: a failure never fails the weather. */
    private fun airQuality(place: Place): Int? {
        val failure = lastFailure
        val body = httpGet(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=us_aqi"
        )
        lastFailure = failure
        return try {
            body?.let { JSONObject(it).getJSONObject("current").optDouble("us_aqi", Double.NaN) }
                ?.takeIf { !it.isNaN() }?.let { Math.round(it).toInt() }
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.number(name: String): Double? =
        optDouble(name, Double.NaN).takeIf { !it.isNaN() }

    private fun org.json.JSONArray?.number(i: Int): Double? =
        this?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }

    internal fun parse(root: JSONObject, city: String?): Report {
        val cur = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        // Hourly arrays start at local midnight; begin at the current hour and take 12.
        val times = hourly.getJSONArray("time")
        val nowHour = cur.getString("time").substring(0, 13)
        var start = 0
        for (i in 0 until times.length()) {
            if (times.getString(i).startsWith(nowHour)) { start = i; break }
        }
        val hours = (start until minOf(start + 12, times.length())).map { i ->
            Hour(
                hour24 = times.getString(i).substring(11, 13).toInt(),
                tempC = hourly.getJSONArray("temperature_2m").getDouble(i),
                code = hourly.getJSONArray("weather_code").getInt(i),
                isDay = hourly.getJSONArray("is_day").getInt(i) == 1,
                rainChance = hourly.optJSONArray("precipitation_probability").number(i)?.toInt()
            )
        }

        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            Day(
                date = LocalDate.parse(dates.getString(i)),
                code = daily.getJSONArray("weather_code").getInt(i),
                highC = daily.getJSONArray("temperature_2m_max").getDouble(i),
                lowC = daily.getJSONArray("temperature_2m_min").getDouble(i),
                rainChance = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
                sunrise = daily.optJSONArray("sunrise")?.optString(i)?.takeIf { it.isNotEmpty() },
                sunset = daily.optJSONArray("sunset")?.optString(i)?.takeIf { it.isNotEmpty() },
                uvMax = daily.optJSONArray("uv_index_max").number(i),
                precipMm = daily.optJSONArray("precipitation_sum").number(i),
                windMaxKmh = daily.optJSONArray("wind_speed_10m_max").number(i),
                snowCm = daily.optJSONArray("snowfall_sum").number(i)
            )
        }

        return Report(
            city = city,
            tempC = cur.getDouble("temperature_2m"),
            feelsLikeC = cur.getDouble("apparent_temperature"),
            humidity = cur.getInt("relative_humidity_2m"),
            windKmh = cur.getDouble("wind_speed_10m"),
            windDirection = cur.getInt("wind_direction_10m"),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            hours = hours,
            days = days,
            latitude = root.optDouble("latitude", 0.0),
            longitude = root.optDouble("longitude", 0.0),
            utcOffsetSeconds = root.optInt("utc_offset_seconds", 0),
            gustKmh = cur.number("wind_gusts_10m"),
            pressureHpa = cur.number("pressure_msl"),
            cloudCover = cur.number("cloud_cover")?.toInt(),
            visibilityKm = hourly.optJSONArray("visibility").number(start)?.let { it / 1000.0 },
            uvIndex = hourly.optJSONArray("uv_index").number(start)
        )
    }

    /**
     * The city chosen in Settings, else a rough location from the connection's IP. If the lookup
     * fails the last good IP location is reused; null means no location is known at all.
     */
    private fun locate(context: Context): Place? {
        val p = prefs(context)
        p.getString("manual_name", null)?.let {
            return Place(
                p.getFloat("manual_lat", 0f).toDouble(),
                p.getFloat("manual_lon", 0f).toDouble(),
                p.getString("manual_city", null)
            )
        }
        cachedPlace?.let { return it }
        val json = httpGet("https://ipapi.co/json/")
        val fresh = try {
            val obj = JSONObject(json ?: "{}")
            val lat = obj.optDouble("latitude", 0.0)
            val lon = obj.optDouble("longitude", 0.0)
            if (json == null || (lat == 0.0 && lon == 0.0)) null
            else Place(lat, lon, obj.optString("city").takeIf { it.isNotEmpty() })
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
        return Place(p.getFloat("auto_lat", 0f).toDouble(), p.getFloat("auto_lon", 0f).toDouble(),
            p.getString("auto_city", null))
    }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Portal/1.0")
        try {
            if (conn.responseCode != 200) {
                lastFailure = "Server error"
                null
            } else {
                conn.inputStream.bufferedReader().readText()
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "HTTP GET $url failed: ${e.message}")
        lastFailure = if (e is java.net.SocketTimeoutException) "Timed out" else "No connection"
        null
    }
}
