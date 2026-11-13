package net.getportaltv.weather.page

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The map under the radar. It is the page's own business, not weather, so the page downloads it itself, from Esri's keyless tile
 * service (see RadarTiles.MapStyle). The rain on top of it always comes through Portal.
 */
internal object MapTiles {

    private const val USER_AGENT = "Portal TV Weather (https://github.com/plecos/portaltv)"

    /** One map tile as a picture, or null if it couldn't be fetched. */
    suspend fun tile(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { get(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }

    private fun get(address: String): ByteArray {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/** What the person chose on the radar (which map, how far in), kept by the page itself. */
internal object RadarPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("radar", Context.MODE_PRIVATE)

    fun map(context: Context): String = prefs(context).getString("map", "DARK") ?: "DARK"
    fun setMap(context: Context, name: String) = prefs(context).edit().putString("map", name).apply()

    /** The zoom the person last chose, or 0 when they haven't. */
    fun zoom(context: Context): Int = prefs(context).getInt("zoom", 0)
    fun setZoom(context: Context, zoom: Int) = prefs(context).edit().putInt("zoom", zoom).apply()
}
