package net.getportaltv.weather.page

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.portal.tv.weather.RadarIndex
import com.portal.tv.weather.WeatherAlert
import com.portal.tv.weather.WeatherJson
import com.portal.tv.weather.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the weather page gets everything: by asking Portal (`content://net.getportaltv.host`, see docs/EXTENSIONS.md). The page has
 * no weather service of its own. Portal gets the weather, the alerts and the radar pictures from whichever weather source is in
 * use, checks them, and hands them over here. Each call returns null or an error rather than throwing.
 */
internal object HostClient {

    private val URI = Uri.parse("content://net.getportaltv.host")

    /** An answer, or why there isn't one (words fit to show). */
    class Answer<T>(val data: T?, val error: String? = null)

    /** The weather as Portal holds it, and the choices Portal has that the page follows. */
    class PageData(
        val report: WeatherReport,
        val asOfMillis: Long,
        /** Portal couldn't get fresh weather lately, so this is old. */
        val stale: Boolean,
        /** The person shows °C and km/h. */
        val metric: Boolean,
        /** The person wants a scene behind the page. */
        val pageBackground: Boolean,
        /** The person wants alerts shown on the page. */
        val showAlerts: Boolean
    )

    private fun call(context: Context, method: String, fill: Bundle.() -> Unit = {}): Bundle? = try {
        context.contentResolver.call(URI, method, null, Bundle().apply(fill))
    } catch (e: Exception) {
        null
    }

    private fun why(b: Bundle?): String = when (b?.getString("error")) {
        null -> "Portal didn't answer"
        "not_allowed" -> "this page isn't allowed"
        "unavailable" -> "there is no weather yet"
        "rate_limited" -> "too many requests"
        else -> b.getString("error")!!.replace('_', ' ')
    }

    /** The weather, from what Portal holds; with [refresh], after Portal has asked its source for fresh weather. */
    suspend fun report(context: Context, refresh: Boolean): Answer<PageData> = withContext(Dispatchers.IO) {
        val b = call(context, "weather.report") { if (refresh) putBoolean("refresh", true) }
        if (b == null || !b.getBoolean("ok")) return@withContext Answer(null, why(b))
        try {
            Answer(
                PageData(
                    WeatherJson.decodeReport(b.getString("json")!!), b.getLong("as_of_millis"), b.getBoolean("stale"),
                    b.getBoolean("metric", true), b.getBoolean("page_background", true), b.getBoolean("show_alerts", true)
                )
            )
        } catch (e: Exception) {
            Answer(null, "Portal's answer couldn't be read")
        }
    }

    /** The alerts in force; empty when there are none or Portal didn't answer. */
    suspend fun alerts(context: Context): List<WeatherAlert> = withContext(Dispatchers.IO) {
        val b = call(context, "weather.alerts")
        if (b == null || !b.getBoolean("ok")) return@withContext emptyList()
        runCatching { WeatherJson.decodeAlerts(b.getString("json")!!) }.getOrDefault(emptyList())
    }

    /** The radar pictures there are around a place, or null if there is no radar. */
    suspend fun radarIndex(context: Context, lat: Double, lon: Double): RadarIndex? = withContext(Dispatchers.IO) {
        val b = call(context, "weather.radar") { putDouble("lat", lat); putDouble("lon", lon) }
        if (b == null || !b.getBoolean("ok")) return@withContext null
        runCatching { WeatherJson.decodeRadar(b.getString("json")!!) }.getOrNull()
    }

    /** One radar picture (PNG bytes) of the map window whose top left is ([x0], [y0]) at [zoom], or null. */
    suspend fun radarImage(context: Context, ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): ByteArray? = withContext(Dispatchers.IO) {
        val b = call(context, "weather.radar_image") {
            putString("ref", ref); putInt("z", zoom); putDouble("x0", x0); putDouble("y0", y0); putInt("w", w); putInt("h", h)
        }
        if (b == null || !b.getBoolean("ok")) null else b.getByteArray("image")
    }

    /** Asks Portal to use an exact spot as the place the weather is for. False if Portal refused (for instance on a kid profile). */
    suspend fun setSpot(context: Context, lat: Double, lon: Double): Boolean = withContext(Dispatchers.IO) {
        call(context, "weather.spot") { putDouble("lat", lat); putDouble("lon", lon) }?.getBoolean("ok") == true
    }
}
