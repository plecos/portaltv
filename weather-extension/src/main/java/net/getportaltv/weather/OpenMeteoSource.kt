package net.getportaltv.weather

import com.portal.tv.weather.WeatherDay
import com.portal.tv.weather.WeatherHour
import com.portal.tv.weather.WeatherPlace
import com.portal.tv.weather.WeatherReport
import org.json.JSONObject
import java.time.LocalDate

/** Current conditions and forecast from Open-Meteo (free, no API key). [base] is only different when a debug build points it at a test server. */
internal class OpenMeteoSource(private val base: String = DEFAULT_BASE) {

    fun fetch(place: WeatherPlace): Fetched<WeatherReport> {
        return try {
            val url = "$base/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,cloud_cover,is_day" +
                "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,uv_index,visibility" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                "sunrise,sunset,uv_index_max,precipitation_sum,wind_speed_10m_max,snowfall_sum" +
                "&timezone=auto&forecast_days=7"
            val response = Http.get(url)
            val body = response.body.takeIf { response.ok } ?: return Fetched.Failed(response.failure ?: "Weather error")
            Fetched.Ok(parse(JSONObject(body), place.city).copy(aqi = airQuality(place)))
        } catch (e: Exception) {
            Fetched.Failed("Weather error")
        }
    }

    /** The US air quality index here now, or null. Best effort: a failure never fails the weather. */
    private fun airQuality(place: WeatherPlace): Int? {
        val response = Http.get(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=us_aqi"
        )
        return try {
            response.body?.let { JSONObject(it).getJSONObject("current").optDouble("us_aqi", Double.NaN) }
                ?.takeIf { !it.isNaN() }?.let { Math.round(it).toInt() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.open-meteo.com"

        private fun JSONObject.number(name: String): Double? =
            optDouble(name, Double.NaN).takeIf { !it.isNaN() }

        private fun org.json.JSONArray?.number(i: Int): Double? =
            this?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }

        /** The hour (0 to 23) on [date] with the highest chance of rain or snow, the earliest if tied; null if none is above zero. */
        internal fun rainPeakHour(times: org.json.JSONArray, chances: org.json.JSONArray?, date: String): Int? {
            if (chances == null) return null
            var best = 0.0
            var hour: Int? = null
            for (i in 0 until minOf(times.length(), chances.length())) {
                val t = times.getString(i)
                if (!t.startsWith(date)) continue
                val c = chances.optDouble(i, Double.NaN)
                if (!c.isNaN() && c > best) { best = c; hour = t.substring(11, 13).toInt() }
            }
            return hour
        }

        internal fun parse(root: JSONObject, city: String?): WeatherReport {
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
            // The first hour is "Now", so it shows the current conditions: the hourly value for the top of the hour can be
            // up to an hour old and disagree (clouds in the strip under a clear sky, or night just after sunrise).
            val hours = (start until minOf(start + 12, times.length())).map { i ->
                val now = i == start
                WeatherHour(
                    hour24 = times.getString(i).substring(11, 13).toInt(),
                    tempC = if (now) cur.getDouble("temperature_2m") else hourly.getJSONArray("temperature_2m").getDouble(i),
                    code = if (now) cur.getInt("weather_code") else hourly.getJSONArray("weather_code").getInt(i),
                    isDay = (if (now) cur.optInt("is_day", 1) else hourly.getJSONArray("is_day").getInt(i)) == 1,
                    rainChance = hourly.optJSONArray("precipitation_probability").number(i)?.toInt()
                )
            }

            val dates = daily.getJSONArray("time")
            val chances = hourly.optJSONArray("precipitation_probability")
            val days = (0 until dates.length()).map { i ->
                WeatherDay(
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
                    snowCm = daily.optJSONArray("snowfall_sum").number(i),
                    rainPeakHour = rainPeakHour(times, chances, dates.getString(i))
                )
            }

            return WeatherReport(
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
    }
}
