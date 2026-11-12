package net.getportaltv.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import com.portal.tv.weather.RadarFrame
import com.portal.tv.weather.RadarIndex
import com.portal.tv.weather.RadarTiles
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Radar from the National Weather Service's national mosaic in the contiguous US, and from RainViewer (free, no key) elsewhere or
 * when the mosaic is down. It answers two questions: which pictures there are for a place, and one picture of a window of the map.
 *
 * A picture's `ref` comes back from the page by way of Portal, so it is never trusted: the hosts are fixed here and the rest of a
 * ref must match a strict pattern before it is used to build an address.
 */
internal class RadarSource {

    private val pool = Executors.newFixedThreadPool(6)

    /** The radar pictures for [lat], [lon]: the mosaic where it covers, else RainViewer. */
    fun index(lat: Double, lon: Double): Fetched<RadarIndex> {
        val nws = if (RadarTiles.inContiguousUs(lat, lon)) {
            Http.get(RadarTiles.WMS_CAPABILITIES).body?.let { runCatching { RadarTiles.parseWmsTimes(it) }.getOrNull() }
        } else null
        if (nws != null) {
            return Fetched.Ok(
                RadarIndex(
                    RadarTiles.Source.NWS.credit, RadarTiles.Source.NWS.maxZoom, RadarTiles.Source.NWS.detailZoom, 300,
                    nws.frames.map { RadarFrame(it.time, NWS + it.path) }
                )
            )
        }
        val rainviewer = Http.get(INDEX_URL).body?.let { runCatching { RadarTiles.parseIndex(it) }.getOrNull() }
            ?.takeIf { it.host == RAINVIEWER_HOST }
            ?: return Fetched.Failed("No radar")
        return Fetched.Ok(
            RadarIndex(
                RadarTiles.Source.RAINVIEWER.credit, RadarTiles.Source.RAINVIEWER.maxZoom, RadarTiles.Source.RAINVIEWER.detailZoom, 600,
                rainviewer.frames.mapNotNull { f -> if (PATH.matches(f.path)) RadarFrame(f.time, RV + f.path) else null }
            )
        ).takeIf { it.value.frames.isNotEmpty() } ?: Fetched.Failed("No radar")
    }

    /** One PNG of the radar for [ref], of the window ([w] by [h] pixels) whose top left is ([x0], [y0]) in zoom-[zoom] world pixels. */
    fun image(ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): Fetched<ByteArray> {
        if (zoom !in 0..12 || w !in 1..SIDE || h !in 1..SIDE || x0.isNaN() || y0.isNaN() || Math.abs(x0) > 1e8 || Math.abs(y0) > 1e8) {
            return Fetched.Failed("Bad window")
        }
        val bytes: ByteArray? = when {
            ref.startsWith(NWS) -> {
                val iso = ref.removePrefix(NWS)
                if (!ISO.matches(iso)) return Fetched.Failed("Bad picture")
                Http.bytes(RadarTiles.wmsUrl(RadarTiles.Frame(0L, iso), zoom, x0, y0, w, h))
            }
            ref.startsWith(RV) -> {
                val path = ref.removePrefix(RV)
                if (!PATH.matches(path)) return Fetched.Failed("Bad picture")
                compose(path, zoom, x0, y0, w, h)
            }
            else -> return Fetched.Failed("Bad picture")
        }
        return if (bytes == null) Fetched.Failed("No picture") else Fetched.Ok(bytes)
    }

    /** RainViewer's tiles drawn into one picture of the window, as PNG; null if none of them could be fetched. */
    private fun compose(path: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): ByteArray? {
        val size = RadarTiles.TILE
        val columns = Math.floorDiv(x0.toInt(), size)..Math.floorDiv((x0 + w - 1).toInt(), size)
        val rows = Math.floorDiv(y0.toInt(), size).coerceAtLeast(0)..Math.floorDiv((y0 + h - 1).toInt(), size).coerceAtMost((1 shl zoom) - 1)
        val wanted = columns.flatMap { x -> rows.map { y -> x to y } }
        if (wanted.isEmpty() || wanted.size > MAX_TILES) return null
        val index = RadarTiles.Index(RAINVIEWER_HOST, emptyList())
        val frame = RadarTiles.Frame(0L, path)
        val tiles = pool.invokeAll(wanted.map { (x, y) ->
            Callable { Http.bytes(RadarTiles.radarUrl(index, frame, zoom, x, y), 500_000)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        }).map { runCatching { it.get() }.getOrNull() }
        if (tiles.all { it == null }) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        wanted.forEachIndexed { i, (x, y) ->
            val tile = tiles[i] ?: return@forEachIndexed
            canvas.drawBitmap(tile, (x * size - x0).toFloat(), (y * size - y0).toFloat(), paint)
            tile.recycle()
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it); out.recycle() }.toByteArray()
    }

    fun shutdown() = pool.shutdown()

    companion object {
        private const val NWS = "nws:"
        private const val RV = "rv:"
        private const val RAINVIEWER_HOST = "https://tilecache.rainviewer.com"
        private const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
        private const val SIDE = 2048
        private const val MAX_TILES = 64

        /** A time as the mosaic service writes it, such as 2026-11-12T20:00:00.000Z. */
        internal val ISO = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z")

        /** The path of a RainViewer picture, such as /v2/radar/1700000000. */
        internal val PATH = Regex("/[A-Za-z0-9_-]{1,40}(/[A-Za-z0-9_-]{1,40}){0,4}")
    }
}
