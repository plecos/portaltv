package net.getportaltv.weather

import com.portal.tv.weather.WeatherCity
import org.json.JSONObject

/**
 * City search by name or zip. A US ZIP code or Canadian postal code is looked up on Zippopotam.us, which has the
 * position of that very zip (Open-Meteo's geocoder only knows the middle of the city, and the same one for every
 * zip in it, and can match a different country's postcodes). Everything else is searched on Open-Meteo's geocoding
 * service. Both are free and need no API key.
 */
internal class Geocoder(
    private val base: String = DEFAULT_BASE,
    private val zipBase: String = ZIP_BASE
) {

    /** Places matching [query], or why the search couldn't be made. An empty list means nothing matched. */
    fun search(query: String): Fetched<List<WeatherCity>> {
        postalPath(query)?.let { path ->
            val zip = Http.get("$zipBase/$path")
            // Not a zip anyone has (404) or the service is down: fall through to the name search.
            if (zip.ok) parseZip(zip.body!!)?.let { return Fetched.Ok(listOf(it)) }
        }
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
        const val ZIP_BASE = "https://api.zippopotam.us"

        private val US_ZIP = Regex("""^(\d{5})(?:-\d{4})?$""")
        private val CA_POSTAL = Regex("""^([A-Za-z]\d[A-Za-z])[ -]?\d[A-Za-z]\d$""")

        /** The Zippopotam.us path ("us/97205", "ca/V6B") for a ZIP or postal code typed as a search, or null if it isn't one. */
        internal fun postalPath(query: String): String? {
            val q = query.trim()
            US_ZIP.matchEntire(q)?.let { return "us/${it.groupValues[1]}" }
            CA_POSTAL.matchEntire(q)?.let { return "ca/${it.groupValues[1].uppercase()}" }
            return null
        }

        /** The place for a Zippopotam.us answer, or null if it has none. The zip is in the region, to tell zips of one city apart. */
        internal fun parseZip(body: String): WeatherCity? = try {
            val root = JSONObject(body)
            val place = root.getJSONArray("places").getJSONObject(0)
            val zip = root.optString("post code").takeIf { it.isNotEmpty() }
            val state = place.optString("state").takeIf { it.isNotEmpty() }
            val country = root.optString("country abbreviation").takeIf { it.isNotEmpty() }
            WeatherCity(
                name = place.getString("place name"),
                region = listOfNotNull(
                    listOfNotNull(state, country).joinToString(", ").takeIf { it.isNotEmpty() },
                    zip?.let { "($it)" }
                ).joinToString(" ").takeIf { it.isNotEmpty() },
                lat = place.getString("latitude").toDouble(),
                lon = place.getString("longitude").toDouble()
            )
        } catch (e: Exception) {
            null
        }

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
