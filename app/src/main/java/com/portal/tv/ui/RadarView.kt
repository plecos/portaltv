package com.portal.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import com.portal.tv.R
import com.portal.tv.repo.RadarFeed
import com.portal.tv.repo.RadarTiles
import com.portal.tv.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * A looping rain radar over a dark map, centred on one place. The map comes from Esri and the rain
 * from RainViewer. Each radar picture is built once into a single bitmap the size of the view, so
 * playing the loop is cheap. OK pauses or resumes it, and [zoomBy] changes how much map is shown.
 * Pictures are fetched again while the view is on screen: every five minutes for the NWS mosaic,
 * which updates every couple of minutes, and every ten for RainViewer.
 */
class RadarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /**
     * A picture of the map window. [zoom] is the map zoom its pixels are cut for, and ([cx], [cy]) is
     * the pixel that lies under the place it was cut around. Drawing scales it to the current zoom
     * and shifts it to the current centre, so a picture cut for another zoom or centre still lines
     * up, only softer, smaller or offset until a better one arrives.
     */
    private class Pic(
        val bitmap: Bitmap, val zoom: Int, val cx: Double, val cy: Double,
        /** The place the picture was cut around, which sat at ([cx], [cy]). */
        val lat: Double, val lon: Double
    )

    private class Layer(val time: Long, val pic: Pic)

    var zoom = 6
        private set
    /** The place the radar is for (the pin). */
    private var latitude = 0.0
    private var longitude = 0.0

    /** The middle of the view: the place, until the map is moved. */
    var centerLat = 0.0
        private set
    var centerLon = 0.0
        private set
    var panning = false
        private set
    private val awayFromHome get() = abs(centerLat - latitude) > 1e-6 || abs(centerLon - longitude) > 1e-6

    /** Called when moving mode starts or stops. */
    var onPanningChanged: (() -> Unit)? = null

    /** Called when the middle of the view changes, so the save button can show whether it applies. */
    var onCenterChanged: (() -> Unit)? = null
    /** True once [show] has been given a place. */
    val isLocated get() = located
    /** The place the radar is for, for opening it in another view. */
    val home get() = latitude to longitude
    private var zone: ZoneId = ZoneId.systemDefault()
    private var located = false

    private var base: Pic? = null
    private var labels: Pic? = null
    private val matrix = Matrix()
    private val layers = mutableListOf<Layer>()
    private var current = 0
    private var playing = true
    private var failed = false
    private var source = RadarTiles.Source.RAINVIEWER

    /** The map under the rain; changed with [cycleMapStyle] and remembered per profile. */
    var mapStyle = runCatching { RadarTiles.MapStyle.valueOf(AppSettings.getRadarMap(context)) }
        .getOrDefault(RadarTiles.MapStyle.DARK)
        private set
    private var styleNotice: String? = null
    private var styling: Job? = null

    /**
     * The closest the map can zoom. The radar is only cut that fine up to its source's limit
     * ([RadarTiles.Source.detailZoom]); beyond it the same pictures are stretched with smoothing.
     */
    val maxZoom get() = MAX_ZOOM

    /** The zoom the radar pictures are fetched at: the current one, up to what the source can show. */
    private val radarZoom get() = zoom.coerceAtMost(source.detailZoom)

    /** Called when [maxZoom] changes, so zoom buttons can be updated. */
    var onZoomLimitChanged: (() -> Unit)? = null

    /** Called when the loop is paused or resumed. */
    var onPlayingChanged: (() -> Unit)? = null
    private var loadedFor: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loading: Job? = null
    private val limit = Semaphore(6)

    private val density get() = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 215 }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C63FF.toInt(); style = Paint.Style.STROKE }
    private val pinOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt(); style = Paint.Style.STROKE }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    private val next = object : Runnable {
        override fun run() {
            if (!playing || layers.size < 2) return
            current = (current + 1) % layers.size
            invalidate()
            // Rest on the newest picture before looping back to the oldest.
            postDelayed(this, if (current == layers.size - 1) 1800L else 550L)
        }
    }
    private val refresh = Runnable { load(force = true) }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { setPlaying(!playing) }
    }

    /**
     * Points the radar at a place; [zone] is used to show picture times in local time. It opens as
     * the zoom last chosen on this profile (else as close as the radar source can show), or at
     * [startZoom] when given.
     */
    fun show(latitude: Double, longitude: Double, zone: ZoneId, startZoom: Int? = null, center: Pair<Double, Double>? = null) {
        if (located && abs(latitude - this.latitude) < 1e-4 && abs(longitude - this.longitude) < 1e-4 && zone == this.zone) return
        this.latitude = latitude
        this.longitude = longitude
        centerLat = center?.first ?: latitude
        centerLon = center?.second ?: longitude
        this.zone = zone
        located = true
        setSource(if (RadarTiles.inContiguousUs(latitude, longitude)) RadarTiles.Source.NWS else RadarTiles.Source.RAINVIEWER)
        zoom = (startZoom ?: AppSettings.getRadarZoom(context).takeIf { it > 0 } ?: source.maxZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        onZoomLimitChanged?.invoke()
        load(force = true)
        onCenterChanged?.invoke()
    }

    /**
     * Wires round icon buttons to this radar: zoom in and out (dimmed at their limits), pause or
     * play (the icon shows what pressing it will do), the next map style, moving the map (arrow
     * keys while it is on), and saving the middle of the map with [onSaveSpot] as the place.
     */
    fun bindControls(
        zoomIn: View, zoomOut: View, playPause: ImageView, layers: View,
        move: View, pin: View, onSaveSpot: (Double, Double) -> Unit
    ) {
        fun update() {
            pin.alpha = if (awayFromHome) 1f else 0.4f
            move.alpha = if (panning) 1f else 0.85f
            zoomIn.alpha = if (zoom >= maxZoom) 0.4f else 1f
            zoomOut.alpha = if (zoom <= MIN_ZOOM) 0.4f else 1f
            playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        zoomIn.setOnClickListener { zoomBy(1); update() }
        zoomOut.setOnClickListener { zoomBy(-1); update() }
        playPause.setOnClickListener { setPlaying(!playing) }
        layers.setOnClickListener { cycleMapStyle() }
        move.setOnClickListener { setPanning(!panning) }
        pin.setOnClickListener {
            if (awayFromHome) {
                val lat = centerLat
                val lon = centerLon
                // The pin moves here too, since this is now the place the radar is for.
                latitude = lat
                longitude = lon
                onSaveSpot(lat, lon)
                showNotice("Saved as your location")
                update()
            } else {
                showNotice("Move the map first, then save the spot")
            }
        }
        onPanningChanged = {
            if (panning) requestFocus() else move.requestFocus()
            update()
        }
        onCenterChanged = { update() }
        onZoomLimitChanged = { update() }
        onPlayingChanged = { update() }
        update()
    }

    private fun setSource(next: RadarTiles.Source) {
        if (next == source) return
        source = next
        onZoomLimitChanged?.invoke()
    }

    companion object {
        const val MIN_ZOOM = 4
        const val MAX_ZOOM = 14
    }

    private fun setPlaying(on: Boolean) {
        playing = on
        removeCallbacks(next)
        if (on) postDelayed(next, 550L)
        invalidate()
        onPlayingChanged?.invoke()
    }

    private fun load(force: Boolean) {
        if (!located || width == 0 || height == 0 || !isAttachedToWindow) return
        loadMap(force = false)
        val key = "$centerLat,$centerLon,$width,$height,$radarZoom"
        if (!force && key == loadedFor && layers.isNotEmpty()) return
        loadedFor = key
        loading?.cancel()
        removeCallbacks(refresh)
        failed = false
        val w = width
        val h = height
        val z = zoom
        val clat = centerLat
        val clon = centerLon
        loading = scope.launch {
            val index = RadarFeed.index(clat, clon)
            if (index == null) {
                failed = layers.isEmpty()
                invalidate()
                schedule()
                return@launch
            }
            if (index.source != source) {
                // The preferred source was down, so the picture comes from the other one.
                setSource(index.source)
                layers.clear()
            }
            // Radar pictures are cut at the source's own finest zoom at most; past that they are
            // small and get stretched when drawn.
            val rz = z.coerceAtMost(source.detailZoom)
            val scale = 2.0.pow(z - rz)
            val rw = ceil(w / scale).toInt().coerceAtLeast(1)
            val rh = ceil(h / scale).toInt().coerceAtLeast(1)
            val x0 = RadarTiles.worldX(clon, rz) - w / (2 * scale)
            val y0 = RadarTiles.worldY(clat, rz) - h / (2 * scale)

            // All the pictures are fetched together, a few at a time, rather than one after another.
            val fresh = coroutineScope {
                index.frames.map { frame ->
                    async {
                        val bitmap = if (index.source == RadarTiles.Source.NWS) {
                            // One picture of the whole window, fetched in a single request.
                            limit.withPermit { RadarFeed.tile(RadarTiles.wmsUrl(frame, rz, x0, y0, rw, rh)) }
                        } else {
                            compose(rw, rh, x0, y0, rz) { x, y -> RadarTiles.radarUrl(index, frame, rz, x, y) }
                        }
                        bitmap?.let { Layer(frame.time, Pic(it, rz, w / (2 * scale), h / (2 * scale), clat, clon)) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (layers.isEmpty() && fresh.isNotEmpty()) { layers += fresh; current = 0; invalidate() }
            if (fresh.isEmpty()) {
                failed = layers.isEmpty()
            } else {
                val wasLast = current >= layers.size - 1
                layers.clear()
                layers += fresh
                current = if (wasLast) layers.size - 1 else current.coerceAtMost(layers.size - 1)
                if (playing) { removeCallbacks(next); postDelayed(next, 550L) }
            }
            invalidate()
            schedule()
        }
    }

    private var mapKey: String? = null

    /**
     * Fetches the map (and its place names, for styles that have them) at the current zoom unless it
     * is already there. With [force] it fetches again, for a new style. The rain pictures are not touched.
     */
    private fun loadMap(force: Boolean) {
        if (!located || width == 0 || height == 0 || !isAttachedToWindow) return
        val w = width
        val h = height
        val z = zoom
        val style = mapStyle
        val clat = centerLat
        val clon = centerLon
        fun fits(pic: Pic?) = pic != null && pic.zoom == z && pic.bitmap.width == w && pic.bitmap.height == h &&
            pic.lat == clat && pic.lon == clon
        val baseOk = fits(base)
        val labelsOk = style.reference == null || fits(labels)
        if (!force && baseOk && labelsOk) return
        val key = "$clat,$clon,$w,$h,$z,${style.name},$force"
        if (styling?.isActive == true && key == mapKey) return
        mapKey = key
        styling?.cancel()
        styling = scope.launch {
            val x0 = RadarTiles.worldX(clon, z) - w / 2.0
            val y0 = RadarTiles.worldY(clat, z) - h / 2.0
            if (force || !baseOk) {
                val picture = compose(w, h, x0, y0, z) { x, y -> RadarTiles.baseUrl(z, x, y, style) }
                if (picture != null && zoom == z && mapStyle == style) {
                    base = Pic(picture, z, w / 2.0, h / 2.0, clat, clon)
                    invalidate()
                }
            }
            if (style.reference != null) {
                val picture = compose(w, h, x0, y0, z) { x, y -> RadarTiles.labelsUrl(z, x, y, style) }
                if (picture != null && zoom == z && mapStyle == style) {
                    labels = Pic(picture, z, w / 2.0, h / 2.0, clat, clon)
                    invalidate()
                }
            } else {
                labels = null
            }
        }
    }

    private fun schedule() {
        removeCallbacks(refresh)
        postDelayed(refresh, if (source == RadarTiles.Source.NWS) 5 * 60_000L else 10 * 60_000L)
    }

    /** Draws the tiles that cover the view into one bitmap, or null if none of them could be fetched. */
    private suspend fun compose(w: Int, h: Int, x0: Double, y0: Double, zoom: Int, url: (Int, Int) -> String?): Bitmap? {
        val size = RadarTiles.TILE
        val columns = Math.floorDiv(x0.toInt(), size)..Math.floorDiv((x0 + w - 1).toInt(), size)
        val rows = Math.floorDiv(y0.toInt(), size).coerceAtLeast(0)..
            Math.floorDiv((y0 + h - 1).toInt(), size).coerceAtMost((1 shl zoom) - 1)
        val wanted = columns.flatMap { x -> rows.map { y -> x to y } }
        val tiles = coroutineScope {
            wanted.map { (x, y) -> async { limit.withPermit { url(x, y)?.let { RadarFeed.tile(it) } } } }.awaitAll()
        }
        if (tiles.all { it == null }) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        wanted.forEachIndexed { i, (x, y) ->
            val tile = tiles[i] ?: return@forEachIndexed
            canvas.drawBitmap(tile, (x * size - x0).toFloat(), (y * size - y0).toFloat(), bitmapPaint)
            tile.recycle()
        }
        return out
    }

    /** Switches to the next map under the rain. The rain pictures stay; only the map is fetched again. */
    fun cycleMapStyle() {
        val style = mapStyle.next()
        mapStyle = style
        AppSettings.setRadarMap(context, style.name)
        labels = null
        showNotice("Map: ${style.label}")
        loadMap(force = true)
    }

    private val clearNotice = Runnable { styleNotice = null; invalidate() }

    /** A brief caption in the corner, such as the name of the map just chosen. */
    private fun showNotice(text: String) {
        styleNotice = text
        removeCallbacks(clearNotice)
        postDelayed(clearNotice, 2500L)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) load(force = false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        load(force = false)
        if (playing) postDelayed(next, 550L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(next)
        removeCallbacks(refresh)
        removeCallbacks(clearNotice)
        removeCallbacks(panReload)
        loading?.cancel()
        styling?.cancel()
        mapKey = null
        // Pictures are rebuilt when the page comes back; don't hold their memory meanwhile.
        loadedFor = null
        super.onDetachedFromWindow()
    }

    /** Zooms one level in (positive) or out (negative) within [MIN_ZOOM] and [maxZoom]; false if already at the limit. */
    fun zoomBy(change: Int): Boolean {
        val next = (zoom + change).coerceIn(MIN_ZOOM, maxZoom)
        if (next == zoom) return false
        val before = radarZoom
        zoom = next
        AppSettings.setRadarZoom(context, next)
        // The pictures on show are redrawn scaled to the new zoom straight away, then replaced by
        // sharper ones. Zooming in, past the radar source's detail, only stretches the rain, so it isn't fetched again.
        loadMap(force = false)
        // Zooming out shows more than the rain pictures cover, so they are cut again for the wider view.
        if (radarZoom != before || change < 0) load(force = true)
        invalidate()
        return true
    }

    private val panReload = Runnable {
        loadMap(force = false)
        load(force = true)
    }

    /** Starts or stops moving the map with the arrow keys. */
    fun setPanning(on: Boolean) {
        if (panning == on) return
        panning = on
        invalidate()
        onPanningChanged?.invoke()
    }

    /** Moves the middle of the view by ([dx], [dy]) screen pixels, then fetches pictures for the new place. */
    private fun pan(dx: Double, dy: Double) {
        val x = RadarTiles.worldX(centerLon, zoom) + dx
        val limit = RadarTiles.TILE * (1 shl zoom).toDouble()
        val y = (RadarTiles.worldY(centerLat, zoom) + dy).coerceIn(0.0, limit)
        centerLon = RadarTiles.lonFromWorldX(x, zoom)
        centerLat = RadarTiles.latFromWorldY(y, zoom)
        invalidate()
        onCenterChanged?.invoke()
        removeCallbacks(panReload)
        postDelayed(panReload, 500L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!panning) return super.onKeyDown(keyCode, event)
        val stepX = width / 4.0
        val stepY = height / 4.0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> pan(-stepX, 0.0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> pan(stepX, 0.0)
            KeyEvent.KEYCODE_DPAD_UP -> pan(0.0, -stepY)
            KeyEvent.KEYCODE_DPAD_DOWN -> pan(0.0, stepY)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> setPanning(false)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Draws a picture scaled from the zoom it was cut for to the current zoom, about the place shown. */
    private fun drawPic(canvas: Canvas, pic: Pic, paint: Paint) {
        val k = 2.0.pow(zoom - pic.zoom)
        // Where the picture's centre pixel falls on screen, given how far the view's centre has moved from it.
        val sx = width / 2.0 + RadarTiles.worldX(pic.lon, zoom) - RadarTiles.worldX(centerLon, zoom)
        val sy = height / 2.0 + RadarTiles.worldY(pic.lat, zoom) - RadarTiles.worldY(centerLat, zoom)
        matrix.setScale(k.toFloat(), k.toFloat())
        matrix.postTranslate((sx - pic.cx * k).toFloat(), (sy - pic.cy * k).toFloat())
        canvas.drawBitmap(pic.bitmap, matrix, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF10131C.toInt())
        base?.let { drawPic(canvas, it, bitmapPaint) }
        layers.getOrNull(current)?.let { drawPic(canvas, it.pic, radarPaint) }
        // Place names only when cut for this zoom; stretched, they would be blurry and the wrong size.
        labels?.takeIf { it.zoom == zoom }?.let { drawPic(canvas, it, bitmapPaint) }

        // The place being shown, wherever the map has been moved to.
        val px = w / 2 + (RadarTiles.worldX(longitude, zoom) - RadarTiles.worldX(centerLon, zoom)).toFloat()
        val py = h / 2 + (RadarTiles.worldY(latitude, zoom) - RadarTiles.worldY(centerLat, zoom)).toFloat()
        if (px in 0f..w && py in 0f..h) {
            // A dark outline first, so the pin shows on light maps too.
            pinOutline.strokeWidth = 5f * density
            canvas.drawCircle(px, py, 5f * density, pinOutline)
            pinRing.strokeWidth = 2f * density
            canvas.drawCircle(px, py, 5f * density, pinRing)
            canvas.drawCircle(px, py, 2f * density, pinFill)
        }
        if (panning || awayFromHome) {
            // Crosshair at the middle of the view: the spot that "Use this spot" saves.
            val arm = 9f * density
            focusRing.strokeWidth = 2f * density
            canvas.drawLine(w / 2 - arm, h / 2, w / 2 + arm, h / 2, focusRing)
            canvas.drawLine(w / 2, h / 2 - arm, w / 2, h / 2 + arm, focusRing)
        }

        text.textSize = 15f * density
        val pad = 10f * density
        val message = when {
            layers.isEmpty() && failed -> "Radar unavailable"
            layers.isEmpty() -> "Loading radar…"
            else -> null
        }
        if (message != null) {
            val tw = text.measureText(message)
            canvas.drawText(message, (w - tw) / 2, h / 2 + 40f * density, text)
        } else {
            layers.getOrNull(current)?.let { layer ->
                var label = Instant.ofEpochSecond(layer.time).atZone(zone).format(timeFormat)
                if (!playing) label += "  ·  paused"
                drawPill(canvas, label, pad, pad)
            }
            // A row of dots, one per picture, with the one on show lit.
            val dot = 3.5f * density
            val spacing = 12f * density
            val startX = w - pad - (layers.size - 1) * spacing - dot
            val strip = RectF(startX - dot - 8f * density, pad, w - pad + 8f * density, pad + 24f * density)
            canvas.drawRoundRect(strip, 12f * density, 12f * density, pill)
            for (i in layers.indices) {
                pinFill.alpha = if (i == current) 255 else 90
                canvas.drawCircle(startX + i * spacing, pad + 12f * density, dot, pinFill)
            }
            pinFill.alpha = 255
        }

        text.textSize = 11f * density
        val credit = "${source.credit}  ·  Map: ${mapStyle.credit}"
        val cw = text.measureText(credit)
        val r = RectF(w - cw - 2 * pad, h - 24f * density, w, h)
        canvas.drawRect(r, pill)
        canvas.drawText(credit, r.left + pad, r.bottom - 7f * density, text)

        val caption = if (panning) "Arrows move the map  ·  OK or Back to stop" else styleNotice
        caption?.let { drawPill(canvas, it, pad, h - 24f * density - 38f * density) }

        if (isFocused) {
            focusRing.strokeWidth = 3f * density
            canvas.drawRect(1.5f * density, 1.5f * density, w - 1.5f * density, h - 1.5f * density, focusRing)
        }
    }

    private fun drawPill(canvas: Canvas, label: String, left: Float, top: Float) {
        val pad = 10f * density
        val tw = text.measureText(label)
        val r = RectF(left, top, left + tw + 2 * pad, top + 28f * density)
        canvas.drawRoundRect(r, 14f * density, 14f * density, pill)
        canvas.drawText(label, r.left + pad, r.bottom - 8f * density, text)
    }
}
