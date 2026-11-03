package com.portal.tv.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads for the radar card: the list of radar pictures from RainViewer (free, no key) and map tiles. */
object RadarFeed {

    private const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"

    /** The pictures to show for a place: the NWS mosaic in the contiguous US, else (or if that is down) RainViewer. */
    suspend fun index(lat: Double, lon: Double): RadarTiles.Index? = withContext(Dispatchers.IO) {
        val nws = if (RadarTiles.inContiguousUs(lat, lon)) {
            runCatching { RadarTiles.parseWmsTimes(get(RadarTiles.WMS_CAPABILITIES).toString(Charsets.UTF_8)) }.getOrNull()
        } else null
        nws ?: runCatching { RadarTiles.parseIndex(get(INDEX_URL).toString(Charsets.UTF_8)) }.getOrNull()
    }

    /** One tile as a picture, or null if it couldn't be fetched. */
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
