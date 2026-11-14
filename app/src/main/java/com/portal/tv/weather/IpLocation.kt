package com.portal.tv.weather

import org.json.JSONObject

/**
 * Where the connection's IP address is, for the automatic weather location. Several free services are tried in turn
 * (each limits how often it answers, and a lookup that fails or is refused just moves on to the next), and the first
 * usable answer wins. An IP address is only good to about a city, so this is a rough place, never a precise one.
 */
internal object IpLocation {

    /** A service's address and how to read its answer. */
    class Service(val url: String, val parse: (String) -> WeatherPlace?)

    val SERVICES = listOf(
        Service("https://ipwho.is/", ::parseIpwho),
        Service("https://ipinfo.io/json", ::parseIpinfo),
        Service("https://ipapi.co/json/", ::parseIpapi)
    )

    /** The first place any of [services] gives, or null; [fetch] returns a service's answer body, or null if it had none. */
    fun lookup(fetch: (String) -> String?, services: List<Service> = SERVICES): WeatherPlace? {
        for (s in services) {
            val place = fetch(s.url)?.let { runCatching { s.parse(it) }.getOrNull() }
            if (place != null) return place
        }
        return null
    }

    internal fun parseIpwho(body: String): WeatherPlace? {
        val o = JSONObject(body)
        if (!o.optBoolean("success", true)) return null
        return place(o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN), o.optString("city"))
    }

    internal fun parseIpinfo(body: String): WeatherPlace? {
        val o = JSONObject(body)
        val loc = o.optString("loc").split(",")
        if (loc.size != 2) return null
        return place(loc[0].trim().toDoubleOrNull() ?: Double.NaN, loc[1].trim().toDoubleOrNull() ?: Double.NaN, o.optString("city"))
    }

    internal fun parseIpapi(body: String): WeatherPlace? {
        val o = JSONObject(body)
        if (o.optBoolean("error", false)) return null // such as {"error":true,"reason":"RateLimited"}
        return place(o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN), o.optString("city"))
    }

    /** The place for these coordinates, or null if they aren't a real position (missing, out of range, or the 0,0 "unknown"). */
    private fun place(lat: Double, lon: Double, city: String): WeatherPlace? {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return WeatherPlace(lat, lon, city.takeIf { it.isNotEmpty() })
    }
}
