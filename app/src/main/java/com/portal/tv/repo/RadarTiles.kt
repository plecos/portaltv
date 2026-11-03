package com.portal.tv.repo

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The map arithmetic and address formats behind the radar card. Free of Android types so it can be
 * unit tested. Tiles are the usual 256 pixel web map squares: zoom 0 is the whole world in one tile,
 * and each zoom level doubles the count across and down.
 */
object RadarTiles {

    const val TILE = 256

    /** One radar picture: [time] is when it was taken, in epoch seconds; [path] finds its tiles. */
    data class Frame(val time: Long, val path: String)

    /** Where the rain pictures come from. */
    enum class Source(val credit: String, val maxZoom: Int, val detailZoom: Int) {
        /** RainViewer, worldwide. Its free tiles stop at zoom 7, which is also where they have all their detail. */
        RAINVIEWER("Radar: RainViewer", 7, 7),
        /**
         * The National Weather Service's national mosaic (MRMS), US only, in 1 km cells. [maxZoom] 10 is
         * as close as the map is worth opening at. The rain is fetched at [detailZoom] 7, where a cell is
         * about one pixel, and stretched with smoothing: asked for more pixels, the server would just
         * hand back bigger squares.
         */
        NWS("Radar: NOAA / National Weather Service", 10, 7)
    }

    /** For [Source.NWS] a frame's [Frame.path] is its ISO time, as the service wants it, and [host] is unused. */
    data class Index(val host: String, val frames: List<Frame>, val source: Source = Source.RAINVIEWER)

    /** True when a place is in the contiguous United States, where the NWS mosaic has coverage. */
    fun inContiguousUs(lat: Double, lon: Double) = lat in 24.0..50.0 && lon in -125.5..-66.0

    private const val WMS = "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows"
    private const val EARTH_HALF = 20037508.342789244

    /**
     * Picture times from the service's capabilities. They come about every two minutes, so this
     * keeps every [stride]th one counting back from the newest, [count] of them, oldest first.
     */
    fun parseWmsTimes(xml: String, count: Int = 12, stride: Int = 5): Index? {
        val list = Regex("<Dimension name=\"time\"[^>]*>([^<]*)</Dimension>").find(xml)?.groupValues?.get(1) ?: return null
        val times = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { iso ->
            runCatching { java.time.Instant.parse(iso) }.getOrNull()?.let { Frame(it.epochSecond, iso) }
        }.sortedBy { it.time }
        if (times.isEmpty()) return null
        val picked = (0 until count).map { times.size - 1 - it * stride }.filter { it >= 0 }.map { times[it] }.reversed()
        return Index("", picked, Source.NWS)
    }

    /** One picture, [w] by [h] pixels, of the map window whose top left is at ([x0], [y0]) in zoom-[zoom] world pixels. */
    fun wmsUrl(frame: Frame, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): String {
        val world = TILE.toDouble() * (1 shl zoom)
        fun mx(px: Double) = (px / world * 2 - 1) * EARTH_HALF
        fun my(py: Double) = (1 - py / world * 2) * EARTH_HALF
        return "$WMS?service=WMS&version=1.3.0&request=GetMap&layers=conus_bref_qcd&styles=&format=image/png" +
            "&transparent=true&crs=EPSG:3857&width=$w&height=$h&time=${frame.path}" +
            "&bbox=${mx(x0)},${my(y0 + h)},${mx(x0 + w)},${my(y0)}"
    }

    const val WMS_CAPABILITIES = "$WMS?service=WMS&version=1.3.0&request=GetCapabilities"

    /** Pixels east of the map's left edge at [zoom] for a longitude in degrees. */
    fun worldX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * TILE * (1 shl zoom)

    /** Pixels south of the map's top edge at [zoom] for a latitude in degrees (web mercator). */
    fun worldY(lat: Double, zoom: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0511, 85.0511)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * TILE * (1 shl zoom)
    }

    /** The longitude in degrees (folded into -180..180) at [x] pixels east of the map's left edge at [zoom]. */
    fun lonFromWorldX(x: Double, zoom: Int): Double {
        val lon = x / (TILE * (1 shl zoom)) * 360.0
        return ((lon % 360.0) + 360.0) % 360.0 - 180.0
    }

    /** The latitude in degrees at [y] pixels south of the map's top edge at [zoom], the inverse of [worldY]. */
    fun latFromWorldY(y: Double, zoom: Int): Double {
        val n = PI - 2 * PI * y / (TILE * (1 shl zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** A tile column folded back into 0 until 2^zoom, since the map repeats sideways. */
    fun wrapX(x: Int, zoom: Int): Int = Math.floorMod(x, 1 shl zoom)

    private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services"

    /**
     * The maps the radar can sit on, all Esri's keyless tiles. The dark and light grey ones have a
     * separate [reference] layer of place names, drawn over the rain; the others carry their own.
     */
    enum class MapStyle(val label: String, val service: String, val reference: String?, val credit: String) {
        DARK("Dark", "Canvas/World_Dark_Gray_Base", "Canvas/World_Dark_Gray_Reference", "Esri, HERE, Garmin, OpenStreetMap contributors"),
        LIGHT("Light", "Canvas/World_Light_Gray_Base", "Canvas/World_Light_Gray_Reference", "Esri, HERE, Garmin, OpenStreetMap contributors"),
        SATELLITE("Satellite", "World_Imagery", null, "Esri, Maxar, Earthstar Geographics"),
        STREETS("Streets", "World_Street_Map", null, "Esri, HERE, Garmin, OpenStreetMap contributors"),
        TOPO("Topo", "World_Topo_Map", null, "Esri, HERE, Garmin, USGS, NGA, OpenStreetMap contributors");

        /** The next style in the list, wrapping round. */
        fun next() = entries[(ordinal + 1) % entries.size]
    }

    /** The map's land, water and roads. Note the row comes before the column. */
    fun baseUrl(zoom: Int, x: Int, y: Int, style: MapStyle = MapStyle.DARK) =
        "$ESRI/${style.service}/MapServer/tile/$zoom/$y/${wrapX(x, zoom)}"

    /** The matching names of places for styles that have a separate layer of them, else null. */
    fun labelsUrl(zoom: Int, x: Int, y: Int, style: MapStyle = MapStyle.DARK): String? =
        style.reference?.let { "$ESRI/$it/MapServer/tile/$zoom/$y/${wrapX(x, zoom)}" }

    /** Smoothed radar tiles in RainViewer's "universal" colours, snow included. */
    fun radarUrl(index: Index, frame: Frame, zoom: Int, x: Int, y: Int) =
        "${index.host}${frame.path}/$TILE/$zoom/${wrapX(x, zoom)}/$y/2/1_1.png"

    /** The newest [count] past frames, oldest first, from RainViewer's weather-maps.json; null if it isn't what we expect. */
    fun parseIndex(json: String, count: Int = 9): Index? {
        val root = JSONObject(json)
        val host = root.optString("host").takeIf { it.startsWith("https://") } ?: return null
        val past = root.optJSONObject("radar")?.optJSONArray("past") ?: return null
        val frames = (0 until past.length()).mapNotNull { i ->
            val f = past.optJSONObject(i) ?: return@mapNotNull null
            val path = f.optString("path")
            if (path.isEmpty() || f.optLong("time") <= 0) null else Frame(f.getLong("time"), path)
        }.sortedBy { it.time }.takeLast(count)
        return if (frames.isEmpty()) null else Index(host, frames)
    }
}
