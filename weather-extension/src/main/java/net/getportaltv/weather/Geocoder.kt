package net.getportaltv.weather

import com.portal.tv.weather.WeatherCity
import org.json.JSONObject

/** City search by name or zip, from Open-Meteo's geocoding service (free, no API key). */
internal class Geocoder(private val base: String = DEFAULT_BASE) {

    /** Places matching [query], or why the search couldn't be made. An empty list means nothing matched. */
    fun search(query: String): Fetched<List<WeatherCity>> {
        val response = Http.get("$base/v1/search?count=8&language=en&format=json&name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
        val body = response.body.takeIf { response.ok } ?: return Fetched.Failed(response.failure ?: "Search failed")
        return try {
            Fetched.Ok(parse(body))
        } catch (e: Exception) {
            Fetched.Failed("Search failed")
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://geocoding-api.open-meteo.com"

        internal fun parse(body: String): List<WeatherCity> {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).map { i ->
                val o = results.getJSONObject(i)
                WeatherCity(
                    name = o.getString("name"),
                    region = listOfNotNull(
                        o.optString("admin1").takeIf { it.isNotEmpty() },
                        o.optString("country_code").takeIf { it.isNotEmpty() }
                    ).joinToString(", ").takeIf { it.isNotEmpty() },
                    lat = o.getDouble("latitude"),
                    lon = o.getDouble("longitude")
                )
            }
        }
    }
}
