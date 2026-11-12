package com.portal.tv.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The saved form of a report and of alerts: Portal's own format, not any service's, so what is kept on the TV reads back
 * the same whichever source produced it. Reading is strict about what must be there and forgiving about what may be missing.
 */
internal object WeatherJson {

    private fun JSONObject.number(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name, Double.NaN).takeIf { !it.isNaN() } else null

    private fun JSONObject.text(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

    // ---------- Report ----------

    fun encode(r: WeatherReport): String = JSONObject()
        .put("city", r.city).put("temp_c", r.tempC).put("feels_like_c", r.feelsLikeC)
        .put("humidity", r.humidity).put("wind_kmh", r.windKmh).put("wind_dir", r.windDirection)
        .put("code", r.code).put("is_day", r.isDay)
        .put("lat", r.latitude).put("lon", r.longitude).put("utc_offset_s", r.utcOffsetSeconds)
        .put("gust_kmh", r.gustKmh).put("pressure_hpa", r.pressureHpa).put("cloud_cover", r.cloudCover)
        .put("visibility_km", r.visibilityKm).put("uv_index", r.uvIndex).put("aqi", r.aqi)
        .put("hours", JSONArray(r.hours.map { h ->
            JSONObject().put("h", h.hour24).put("temp_c", h.tempC).put("code", h.code)
                .put("is_day", h.isDay).put("rain", h.rainChance)
        }))
        .put("days", JSONArray(r.days.map { d ->
            JSONObject().put("date", d.date.toString()).put("code", d.code).put("high_c", d.highC)
                .put("low_c", d.lowC).put("rain", d.rainChance).put("sunrise", d.sunrise).put("sunset", d.sunset)
                .put("uv_max", d.uvMax).put("precip_mm", d.precipMm).put("wind_max_kmh", d.windMaxKmh)
                .put("snow_cm", d.snowCm)
        }))
        .toString()

    fun decodeReport(json: String): WeatherReport {
        val o = JSONObject(json)
        val hours = o.getJSONArray("hours")
        val days = o.getJSONArray("days")
        return WeatherReport(
            city = o.text("city"),
            tempC = o.getDouble("temp_c"),
            feelsLikeC = o.getDouble("feels_like_c"),
            humidity = o.getInt("humidity"),
            windKmh = o.getDouble("wind_kmh"),
            windDirection = o.getInt("wind_dir"),
            code = o.getInt("code"),
            isDay = o.getBoolean("is_day"),
            hours = (0 until hours.length()).map { i ->
                val h = hours.getJSONObject(i)
                WeatherHour(h.getInt("h"), h.getDouble("temp_c"), h.getInt("code"), h.getBoolean("is_day"),
                    h.number("rain")?.toInt())
            },
            days = (0 until days.length()).map { i ->
                val d = days.getJSONObject(i)
                WeatherDay(
                    date = LocalDate.parse(d.getString("date")), code = d.getInt("code"),
                    highC = d.getDouble("high_c"), lowC = d.getDouble("low_c"), rainChance = d.optInt("rain", 0),
                    sunrise = d.text("sunrise"), sunset = d.text("sunset"), uvMax = d.number("uv_max"),
                    precipMm = d.number("precip_mm"), windMaxKmh = d.number("wind_max_kmh"), snowCm = d.number("snow_cm")
                )
            },
            latitude = o.optDouble("lat", 0.0), longitude = o.optDouble("lon", 0.0),
            utcOffsetSeconds = o.optInt("utc_offset_s", 0),
            gustKmh = o.number("gust_kmh"), pressureHpa = o.number("pressure_hpa"),
            cloudCover = o.number("cloud_cover")?.toInt(), visibilityKm = o.number("visibility_km"),
            uvIndex = o.number("uv_index"), aqi = o.number("aqi")?.toInt()
        )
    }

    // ---------- Alerts ----------

    fun encodeAlerts(alerts: List<WeatherAlert>): String = JSONArray(alerts.map { a ->
        JSONObject().put("id", a.id).put("event", a.event).put("severity", a.severity.name)
            .put("headline", a.headline).put("description", a.description).put("instruction", a.instruction)
            .put("area", a.area).put("ends", a.ends?.toString()).put("onset", a.onset?.toString())
            .put("source", a.source)
    }).toString()

    fun decodeAlerts(json: String): List<WeatherAlert> {
        val list = JSONArray(json)
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            WeatherAlert(
                id = a.optString("id"),
                event = a.optString("event").ifEmpty { "Weather alert" },
                severity = AlertSeverity.values().firstOrNull { it.name == a.optString("severity") } ?: AlertSeverity.UNKNOWN,
                headline = a.optString("headline"),
                description = a.optString("description"),
                instruction = a.optString("instruction"),
                area = a.optString("area"),
                ends = a.text("ends")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                onset = a.text("onset")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                source = a.optString("source")
            )
        }
    }
}
