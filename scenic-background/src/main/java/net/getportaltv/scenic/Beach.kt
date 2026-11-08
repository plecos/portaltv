package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_BOTTOM
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The beach: the sea to the horizon behind the road, waves running in to a shore, sand, a boardwalk beside the road, and
 * umbrellas, palms, a lifeguard tower, boats and now and then a pier. Like every place it grows in gradually: the hills sink to
 * dunes, the sea comes in from the right, the grass turns to sand.
 */

private const val SEA_TOP = 120
private const val SHORE = 141
private val BOARD_TOP = ROAD_TOP - 5

// ---------- the pictures ----------

private fun picture(w: Int, h: Int, draw: (Bitmap) -> Unit): Sprite {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    draw(b)
    return Sprite.of(b)
}

private fun Bitmap.dot(x: Int, y: Int, c: Int) { if (x in 0 until width && y in 0 until height) setPixel(x, y, c) }
private fun Bitmap.rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) dot(x, y, c) }

private object BeachSprites {
    private val POLE = rgb(150, 120, 90)
    private val WHITE = rgb(246, 246, 244)

    val umbrellas by lazy {
        listOf(rgb(220, 70, 60) to WHITE, rgb(60, 130, 220) to rgb(250, 220, 90), rgb(240, 160, 50) to WHITE, rgb(70, 180, 120) to rgb(250, 240, 120), rgb(200, 80, 170) to WHITE)
            .map { (a, b) -> umbrella(a, b) }
    }

    private fun umbrella(c1: Int, c2: Int) = picture(13, 11) { bm ->
        val half = intArrayOf(2, 4, 5, 6)
        for (r in 0..3) for (x in 6 - half[r]..6 + half[r]) bm.dot(x, r, if ((x / 2) % 2 == 0) c1 else c2)
        bm.rect(6, 4, 7, 11, POLE)
    }

    private val SUITS = intArrayOf(rgb(230, 80, 90), rgb(60, 140, 220), rgb(250, 200, 60), rgb(90, 190, 130))
    private val sunbatherCache = HashMap<Int, Sprite>()

    /** Somebody lying on a towel, in a swimsuit [suit] (0 to 3), with their own [skin] tone and [hair]. */
    fun sunbather(suit: Int, skin: Int, hair: Int): Sprite = sunbatherCache.getOrPut((suit * 8 + skin) * People.HAIR_COUNT + hair) {
        val sk = People.SKIN[skin]
        picture(7, 2) { bm ->
            bm.dot(0, 0, People.hair(hair)); bm.dot(1, 0, sk)
            bm.rect(2, 0, 4, 1, SUITS[suit])
            bm.rect(4, 0, 7, 1, sk)
            bm.rect(1, 1, 7, 2, People.tanned(sk))
        }
    }

    private val surferCache = HashMap<Int, Sprite>()

    /** Somebody on a board, in a wetsuit, with their own [skin] tone. */
    fun surfer(skin: Int): Sprite = surferCache.getOrPut(skin) {
        val sk = People.SKIN[skin]
        picture(7, 6) { bm ->
            bm.rect(0, 5, 7, 6, rgb(250, 230, 90))
            bm.dot(3, 0, sk); bm.rect(3, 1, 4, 3, rgb(60, 130, 220)); bm.rect(3, 3, 4, 5, rgb(40, 50, 80))
            bm.dot(2, 2, sk); bm.dot(4, 2, sk)
        }
    }

    val palms by lazy { listOf(palm(0), palm(1)) }

    private fun palm(lean: Int) = picture(19, 25) { bm ->
        val trunk = rgb(132, 96, 62)
        val ring = rgb(96, 68, 44)
        val cx0 = 9
        val top = 9
        for (y in 24 downTo top) {
            val x = cx0 + (if (lean == 0) 1 else -1) * ((24 - y) * 2 / 15)
            bm.dot(x, y, if ((y % 3) == 0) ring else trunk)
            bm.dot(x + 1, y, if ((y % 3) == 0) trunk else ring)
        }
        val cx = cx0 + (if (lean == 0) 1 else -1) * 2
        val dirs = arrayOf(floatArrayOf(-1f, 0.6f), floatArrayOf(-1f, 0.05f), floatArrayOf(-0.7f, -0.6f), floatArrayOf(0f, -1f), floatArrayOf(0.7f, -0.6f), floatArrayOf(1f, 0.05f), floatArrayOf(1f, 0.6f))
        val leaf = rgb(52, 150, 66)
        val leafLight = rgb(104, 190, 84)
        for ((k, d) in dirs.withIndex()) {
            var t = 0f
            while (t < 8f) {
                val x = (cx + 1 + d[0] * t).toInt()
                val y = (top + d[1] * t + t * t * 0.09f).toInt()
                bm.dot(x, y, if (k % 2 == 0) leaf else leafLight)
                bm.dot(x, y + 1, leaf)
                t += 0.5f
            }
        }
        bm.dot(cx, top + 1, rgb(110, 76, 44)); bm.dot(cx + 2, top + 1, rgb(110, 76, 44))
    }

    val tower = picture(14, 17) { bm ->
        val wood = rgb(160, 118, 80)
        val dark = rgb(112, 80, 54)
        for (x in intArrayOf(1, 4, 9, 12)) bm.rect(x, 10, x + 1, 17, dark)
        bm.rect(0, 9, 14, 10, wood)
        bm.rect(2, 3, 12, 9, WHITE)
        bm.rect(2, 6, 12, 7, rgb(210, 60, 52))
        bm.rect(1, 1, 13, 3, rgb(210, 60, 52))
        bm.rect(4, 4, 8, 6, rgb(60, 80, 110))
        bm.rect(9, 7, 11, 9, dark)
    }

    val castle = picture(9, 6) { bm ->
        val sand = rgb(214, 190, 138)
        val shade = rgb(176, 150, 104)
        bm.rect(0, 3, 9, 6, sand)
        bm.rect(0, 1, 3, 6, sand); bm.rect(6, 1, 9, 6, sand); bm.rect(3, 0, 6, 6, sand)
        bm.dot(1, 1, 0); bm.dot(7, 1, 0); bm.dot(4, 0, rgb(210, 60, 52))
        bm.rect(0, 5, 9, 6, shade)
        bm.dot(4, 4, shade)
    }

    val net = picture(18, 12) { bm ->
        val post = rgb(236, 236, 232)
        bm.rect(0, 0, 1, 12, post); bm.rect(17, 0, 18, 12, post)
        for (y in 1..4) for (x in 1..16) if ((x + y) % 2 == 0) bm.dot(x, y, rgb(220, 220, 224))
        bm.rect(1, 1, 17, 2, post)
    }

    private val playerCache = HashMap<Int, Sprite>()

    /** A beach volleyball player in swimwear, standing ready or (with [up]) jumping with both arms up to hit the ball. */
    fun player(skin: Int, hair: Int, suit: Int, up: Boolean): Sprite = playerCache.getOrPut(((skin * People.HAIR_COUNT + hair) * 4 + suit) * 2 + (if (up) 1 else 0)) {
        val rows = if (up) listOf("s.h.s", "shhhs", ".sss.", ".bbb.", ".bbb.", ".ppp.", ".p.p.", "..p..")
        else listOf("..h..", ".hhh.", ".sss.", ".bbb.", "sbbbs", ".ppp.", ".p.p.", ".p.p.")
        Sprite(rows, mapOf('s' to People.SKIN[skin], 'h' to People.hair(hair), 'b' to SUITS[suit], 'p' to rgb(60, 70, 110)))
    }

    val sailboat = picture(10, 11) { bm ->
        bm.rect(4, 0, 5, 8, rgb(120, 100, 80))
        for (r in 1..7) bm.rect(5, r, 5 + (r + 1) / 2, r + 1, WHITE)
        for (r in 2..7) bm.rect(4 - (8 - r) / 3 - 1, r, 4, r + 1, rgb(240, 240, 250))
        bm.rect(0, 8, 10, 10, rgb(120, 66, 50))
        bm.rect(0, 8, 10, 9, rgb(236, 236, 232))
    }

    val ship = picture(24, 9) { bm ->
        bm.rect(0, 6, 24, 9, rgb(60, 50, 56))
        bm.rect(0, 6, 24, 7, rgb(170, 60, 50))
        bm.rect(3, 2, 9, 6, WHITE)
        bm.rect(4, 3, 8, 4, rgb(60, 80, 110))
        bm.rect(5, 0, 7, 2, rgb(200, 60, 50))
        val cols = intArrayOf(rgb(200, 80, 60), rgb(70, 120, 190), rgb(220, 180, 70), rgb(90, 160, 110))
        for (k in 0 until 5) bm.rect(11 + k * 2, 4, 13 + k * 2, 6, cols[k % cols.size])
        for (k in 0 until 4) bm.rect(12 + k * 2, 2, 14 + k * 2, 4, cols[(k + 1) % cols.size])
    }

    val lighthouse = picture(9, 24) { bm ->
        val white = rgb(240, 240, 238)
        val red = rgb(204, 58, 50)
        // The tower, narrowing toward the top, in red and white bands.
        for (y in 6..21) {
            val half = 2 + (y - 6) / 10
            val c = if (((y - 6) / 4) % 2 == 0) white else red
            bm.rect(4 - half, y, 5 + half, y + 1, c)
        }
        // The lantern room and a cap.
        bm.rect(2, 3, 7, 6, rgb(255, 232, 140))
        bm.rect(2, 2, 7, 3, rgb(60, 60, 70))
        bm.rect(3, 1, 6, 2, rgb(60, 60, 70)); bm.dot(4, 0, rgb(60, 60, 70))
        bm.rect(2, 6, 7, 7, rgb(60, 60, 70))
        // The rock it stands on.
        bm.rect(0, 22, 9, 24, rgb(92, 88, 92)); bm.rect(1, 21, 8, 22, rgb(92, 88, 92))
    }

    val cruise = picture(46, 16) { bm ->
        val white = rgb(246, 246, 246)
        val shade = rgb(206, 210, 220)
        val blue = rgb(40, 70, 130)
        val glass = rgb(70, 100, 140)
        // The hull, with a dark blue stripe at the waterline and a long white superstructure on top.
        bm.rect(1, 12, 45, 16, white)
        bm.rect(0, 12, 2, 13, white)
        bm.rect(2, 14, 44, 16, blue)
        for (deck in 0 until 3) {
            val y = 4 + deck * 3
            bm.rect(6 - deck, y, 40 + deck, y + 3, if (deck == 0) shade else white)
            var x = 8 - deck
            while (x < 38 + deck) { bm.dot(x, y + 1, glass); x += 2 }
        }
        bm.rect(30, 0, 34, 4, rgb(240, 190, 60)); bm.rect(30, 0, 34, 1, rgb(50, 50, 60))
        bm.rect(24, 1, 28, 4, rgb(240, 190, 60)); bm.rect(24, 1, 28, 2, rgb(50, 50, 60))
        for (x in 8 until 40 step 6) bm.dot(x, 13, rgb(230, 120, 40))
    }

    val gullUp = picture(5, 3) { bm -> bm.dot(0, 0, WHITE); bm.dot(4, 0, WHITE); bm.dot(1, 1, WHITE); bm.dot(3, 1, WHITE); bm.dot(2, 2, rgb(210, 210, 214)) }
    val gullDown = picture(5, 3) { bm -> bm.dot(2, 0, rgb(210, 210, 214)); bm.rect(1, 1, 4, 2, WHITE); bm.dot(0, 2, WHITE); bm.dot(4, 2, WHITE) }

}

// ---------- the sea and the sand ----------

/** Where the sun or the moon is across the sea (for the glitter path), as a screen column, or null. */
private fun SceneState.glitterX(): Pair<Float, Float>? {
    val sunUp = sunAlt > 1.5
    if (sunUp) return (((sunAz - 90.0) / 180.0 * W).toFloat()) to smoothstep(1.5f, 12f, sunAlt.toFloat())
    val m = moon ?: return null
    if (m.altDeg < 2 || look.day > 0.5f) return null
    return (((m.azDeg - 90.0) / 180.0 * W).toFloat()) to (m.illumination.toFloat() * 0.8f)
}

private fun smoothstep(a: Float, b: Float, x: Float): Float = Colors.smoothstep(a, b, x)

/**
 * How far out the sea reaches at the horizon beyond where it reaches at the shore, in pixels. The sea is a bay: at each end of the
 * beach the waterline curves away into the distance, so the sea narrows to nothing at the horizon rather than stopping with an edge.
 */
private const val BAY = 150

/** How far the waterline is pushed out on row [y] (SEA_TOP at the horizon, the shore at the front): a curve that runs off into the distance. */
private fun bayShift(y: Int): Int {
    val u = ((y - SEA_TOP) / (SHORE - SEA_TOP).toFloat()).coerceIn(0f, 1f)
    return (BAY * (1f - u) * (1f - u)).toInt()
}

/**
 * How much beach is on screen at column [x]: the beach where the journey is at that column, or within the bay's reach either side
 * of it. Far layers (mountains, hills) scroll slower, so asking them for the beach at their own place on the road put the dunes and the
 * missing mountains somewhere other than where the sea is; they ask for the screen instead.
 */
internal fun SceneState.beachOnScreen(x: Int): Float {
    if (x >= -SCREEN_PAD && x < W + SCREEN_PAD) return beachCols[x + SCREEN_PAD]
    val c = floor(cam).toInt()
    return maxOf(beachAt((c + x - BAY).toDouble(), 1.0), beachAt((c + x).toDouble(), 1.0), beachAt((c + x + BAY).toDouble(), 1.0))
}

/** How much of the land is built or sand (city or beach) at screen column [x]: where there is no room for wild country (trees, fences, farms). */
internal fun SceneState.hardOnScreen(x: Int): Float =
    if (x >= -SCREEN_PAD && x < W + SCREEN_PAD) maxOf(cityCols[x + SCREEN_PAD], beachCols[x + SCREEN_PAD])
    else maxOf(cityAt((floor(cam).toInt() + x).toDouble(), 1.0), beachOnScreen(x))

private const val SCREEN_PAD = 8

/** The city at screen column [x] (from what [prepareColumns] worked out this frame). */
internal fun SceneState.cityOnScreen(x: Int): Float = cityCols[(x + SCREEN_PAD).coerceIn(0, W + 2 * SCREEN_PAD - 1)]

/** The suburbs at screen column [x]. */
internal fun SceneState.suburbOnScreen(x: Int): Float = suburbCols[(x + SCREEN_PAD).coerceIn(0, W + 2 * SCREEN_PAD - 1)]

/** Built-up land of any kind at screen column [x] (city, beach or suburbs): no room for farms, fences, ponds or herds. */
internal fun SceneState.builtOnScreen(x: Int): Float = maxOf(hardOnScreen(x), 0.9f * suburbOnScreen(x))

/** The beach at screen column [x] itself, with none of the bay's reach either side. */
internal fun SceneState.beachExact(x: Int): Float = beachRaw[x + SCREEN_PAD + BAY]

/** Works out, once a frame, how much city and beach there is at each screen column (the far scenery asks for it many times over). */
internal fun SceneState.prepareColumns() {
    val c = floor(cam).toInt()
    if (placeMode != colsMode) { colsMode = placeMode; colsCam = Int.MIN_VALUE; beachRaw.reset(); cityRaw.reset(); suburbRaw.reset() }
    if (c == colsCam) return
    colsCam = c
    // The first world column of each strip: the beach reaches BAY either side of the screen, and SCREEN_PAD more for the spare columns.
    beachRaw.moveTo(c - BAY - SCREEN_PAD)
    cityRaw.moveTo(c - SCREEN_PAD)
    suburbRaw.moveTo(c - SCREEN_PAD)
    var beach = false
    var city = false
    var suburb = false
    for (i in 0 until W + 2 * SCREEN_PAD) {
        beachCols[i] = maxOf(beachRaw[i], beachRaw[i + BAY], beachRaw[i + 2 * BAY])
        cityCols[i] = cityRaw[i]
        suburbCols[i] = suburbRaw[i]
        if (suburbCols[i] > 0.01f) suburb = true
        if (beachCols[i] > 0.01f) beach = true
        if (cityCols[i] > 0.01f) city = true
    }
    anyBeach = beach
    anyCity = city
    anySuburb = suburb
}

/** Whether the sea is on screen at column [x], row [y]: the beach, with the waterline bending away at either end of it. */
private fun SceneState.seaAt(x: Int, y: Int): Boolean {
    val c = floor(cam).toInt()
    val sh = bayShift(y)
    return maxOf(beachAt((c + x - sh).toDouble(), 1.0), beachAt((c + x + sh).toDouble(), 1.0)) >= 0.5f
}

/** How much the beach has shut down for the cold, 0 in the warm months (and always in the tropics) to 1 in deep winter. */
private fun SceneState.offSeason(): Float = smoothstep(0.35f, 0.9f, season.winter)

/** The sea with its waves, the shore with foam, the sand and the boardwalk, over the field wherever there is beach. */
internal fun PixelScene.drawBeachGround(s: SceneState) {
    if (s.placeMode != Places.JOURNEY && s.placeMode != Places.BEACH) return
    if (!s.anyBeach) return
    val cam = floor(s.cam).toInt()
    val wind = min(1f, s.cond.wind / 30f)
    val l = s.look
    val horizon = lerp(l.skyBottom, solid(s, rgb(90, 140, 196), 0.0f), 0.5f)
    val cold = s.offSeason()
    // In winter the sea is a steely grey-green, with more white water.
    val far = lerp(solid(s, rgb(46, 116, 176), 0.1f), solid(s, rgb(58, 84, 108), 0.1f), cold * 0.8f)
    val near = lerp(solid(s, rgb(46, 156, 176), 0.04f), solid(s, rgb(66, 104, 122), 0.04f), cold * 0.8f)
    val sand = lit(s, rgb(222, 202, 154), 0.04f)
    val speck = lit(s, rgb(196, 174, 128), 0.04f)
    val wet = lit(s, rgb(176, 156, 116), 0.04f)
    val foam = solid(s, rgb(250, 252, 252), 0.0f)
    val wood = solid(s, rgb(152, 114, 78), 0.0f)
    val seam = solid(s, rgb(110, 80, 54), 0.0f)
    val rail = solid(s, rgb(236, 232, 224), 0.0f)
    val glitter = s.glitterX()
    val t = s.time
    // How much beach there is at every column, and BAY columns either side, found once so the curving waterline is cheap.
    val raw = s.beachRaw
    // (The strip starts SCREEN_PAD columns before the first column of the amounts.)
    val amounts = FloatArray(W + 2 * BAY) { raw[it + SCREEN_PAD] }
    // Where the sea is, row by row (rows SEA_TOP up to the shore), worked out once: the bay curves, so each row reaches a different distance.
    val rows = SHORE + 4 - SEA_TOP
    val mask = BooleanArray(rows * W)
    for (r in 0 until rows) {
        val sh = bayShift(SEA_TOP + r)
        for (x in 0 until W) mask[r * W + x] = maxOf(amounts[x + BAY - sh], amounts[x + BAY + sh]) >= 0.5f
    }
    fun seaOn(x: Int, y: Int): Boolean { val r = y - SEA_TOP; return r in 0 until rows && mask[r * W + x] }
    val shores = IntArray(W) { x -> SHORE + (sin(x * 0.045 + t * 0.6) * 1.5 + sin(x * 0.11 - t * 0.45) * 0.7).toInt() }
    for (x in 0 until W) {
        val shore = shores[x]
        // The sea in bands, from the haze at the horizon to the clear water at the shore, only where the bay reaches this column.
        var y = SEA_TOP
        while (y < shore) {
            val k = (y - SEA_TOP) / 4
            if (!seaOn(x, y)) { y++; continue }
            val bandEnd = min(SEA_TOP + (k + 1) * 4, shore)
            var y1 = y
            while (y1 < bandEnd && seaOn(x, y1)) y1++
            fill(x, y, x + 1, y1, if (k == 0) lerp(horizon, far, 0.35f) else lerp(far, near, (k / 5f).coerceAtMost(1f)))
            y = y1
        }
        // Waves: lines of lighter water that run along the sea, more and brighter in wind.
        for (r in 0 until 6) {
            val wy = SEA_TOP + 4 + r * 3
            if (wy >= shore - 1) break
            if (!seaOn(x, wy)) continue
            val run = sin((x + t * (6.0 + r * 2.0) * (if (r % 2 == 0) 1 else -1)) * 0.21 + r * 2.0)
            if (run > 0.82f - wind * 0.25f - cold * 0.2f) fillAlpha(x, wy, x + 1, wy + 1, lerp(near, foam, 0.55f), 0.35f + 0.4f * wind + 0.2f * cold)
        }
        // The glitter of the sun or the moon on the water.
        if (glitter != null) {
            val gx = glitter.first
            val spread = 3f + (shore - SEA_TOP) * 0.25f
            if (abs(x - gx) < spread) {
                for (r in 0 until shore - SEA_TOP step 2) {
                    if (!seaOn(x, SEA_TOP + r)) continue
                    val on = Noise.unit(x, r + floor(t * 2.5).toInt(), 862) < 0.22f * (1f - abs(x - gx) / spread)
                    if (on) fillAlpha(x, SEA_TOP + r, x + 1, SEA_TOP + r + 1, rgb(255, 252, 230), glitter.second * 0.8f)
                }
            }
        }
        // The sand and the boardwalk run where the beach itself does (the waterline bends away only out on the water).
        if (amounts[x + BAY] < 0.5f) continue
        // Foam along the shore and wet sand behind it.
        val pulse = 0.55f + 0.35f * sin(x * 0.07 - t * 1.1).toFloat()
        fillAlpha(x, shore - 1 - (if (cold > 0.3f && pulse > 0.75f) 1 else 0), x + 1, shore + 1, foam, pulse)
        fill(x, shore + 1, x + 1, shore + 3, wet)
        fill(x, shore + 3, x + 1, BOARD_TOP, sand)
        for (yy in shore + 3 until BOARD_TOP) if (Noise.unit(x + cam, yy, 861) < 0.07f) fill(x, yy, x + 1, yy + 1, speck)
        // The boardwalk beside the road, with a low rail along the sand side.
        fill(x, BOARD_TOP, x + 1, ROAD_TOP, wood)
        if (Math.floorMod(x + cam, 7) == 0) fill(x, BOARD_TOP, x + 1, ROAD_TOP, seam)
        fillAlpha(x, BOARD_TOP - 2, x + 1, BOARD_TOP - 1, rail, 0.9f)
        if (Math.floorMod(x + cam, 9) < 1) fillAlpha(x, BOARD_TOP - 3, x + 1, BOARD_TOP, rail, 0.9f)
    }
    // The beach curving away along the waterline: a strip of sand (with a line of foam against the water) beside the sea
    // wherever the bay's edge is, so each end of the beach runs off into the distance as a curve.
    val on = BooleanArray(W + 16)
    for (y in SEA_TOP until SHORE) {
        for (i in on.indices) { val x = i - 8; on[i] = x in 0 until W && y < shores[x] && seaOn(x, y) }
        val width = 2 + (y - SEA_TOP) / 6
        for (x in 0 until W) {
            if (on[x + 8]) continue
            var d = 0
            for (k in 1..width) { if (on[x + 8 - k] || on[x + 8 + k]) { d = k; break } }
            if (d == 0) continue
            fill(x, y, x + 1, y + 1, if (d == 1) foam else if (d == 2) wet else sand)
        }
    }
}

/** Boats and ships out on the sea, and gulls along the shore. */
internal fun PixelScene.drawSeaLife(s: SceneState) {
    // Out on the sea only (each boat checks the sea is under it), so nothing sails over the land at either end of the beach.
    if (s.beachAt(s.cam - BAY, 1.0) < 0.5f && s.beachAt(s.cam + W / 2.0, 1.0) < 0.5f && s.beachAt(s.cam + W + BAY, 1.0) < 0.5f) return
    val travelV = if (s.travelling) s.speed else 0f
    spritePaint.colorFilter = filter(s, 0.2f + s.look.haze * 0.4f)
    spritePaint.alpha = 255
    // A cargo ship at the horizon, very slowly.
    for (ev in LifeSchedule.events(s.time, 200.0, 260.0, 0.6f, 801, s.boost)) {
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val v = dir * 4f - travelV * 0.04f
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v > 0) -26f + v * age else W + 26f + v * age
        if (x < -26 || x > W + 26) continue
        if (!s.seaAt(x.toInt() + 11, SEA_TOP + 4)) continue
        sprite(BeachSprites.ship, x.toInt(), SEA_TOP + 4, flip = dir < 0)
    }
    // A cruise ship now and then, a little nearer than the cargo ships and bigger, lit up at night.
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    for (ev in LifeSchedule.events(s.time, 600.0, 330.0, 0.5f, 831, s.boost)) {
        if (ev.unit(6) < s.offSeason() * 0.7f) continue
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val v = dir * 3f - travelV * 0.06f
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v > 0) -48f + v * age else W + 48f + v * age
        if (x < -48 || x > W + 48) continue
        val bob = (sin(age * 0.6) * 0.6).toInt()
        if (!s.seaAt(x.toInt() + 23, SEA_TOP + 13)) continue
        sprite(BeachSprites.cruise, x.toInt(), SEA_TOP + 13 + bob, flip = dir < 0)
        if (night > 0.15f) {
            spritePaint.colorFilter = null
            for (deck in 0 until 3) {
                var wx = 8 - deck
                while (wx < 38 + deck) {
                    val lx = if (dir < 0) x.toInt() + 46 - 1 - wx else x.toInt() + wx
                    fillAlpha(lx, SEA_TOP + 13 + bob - 16 + 4 + deck * 3 + 1, lx + 1, SEA_TOP + 13 + bob - 16 + 4 + deck * 3 + 2, rgb(255, 224, 140), night * 0.9f * (spritePaint.alpha / 255f))
                    wx += 2
                }
            }
            spritePaint.colorFilter = filter(s, 0.2f + s.look.haze * 0.4f)
        }
    }
    // A lighthouse on a rock out on the water, very rarely, with its light turning at night.
    drawLighthouse(s, night)
    // Sailboats, nearer in, going with the wind.
    for (ev in LifeSchedule.events(s.time, 45.0, 90.0, 0.55f, 811, s.boost)) {
        if (ev.unit(6) < s.offSeason() * 0.9f) continue
        val dir = if (s.windDir > 0) 1 else -1
        val v = dir * (3f + ev.unit(1) * 3f) - travelV * 0.15f
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v > 0) -12f + v * age else W + 12f + v * age
        if (x < -12 || x > W + 12) continue
        val bob = (sin(age * 1.2) * 1.0).toInt()
        if (!s.seaAt(x.toInt() + 6, SEA_TOP + 14)) continue
        sprite(BeachSprites.sailboat, x.toInt(), SEA_TOP + 12 + (ev.unit(2) * 8).toInt() + bob, flip = dir < 0)
    }
    // Gulls gliding along the shore.
    if (s.stormy() < 0.5f) for (ev in LifeSchedule.events(s.time, 14.0, 40.0, 0.6f, 821, s.boost)) {
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val v = dir * (14f + ev.unit(2) * 10f) - travelV * 0.5f
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v > 0) -8f + v * age else W + 8f + v * age
        if (x < -8 || x > W + 8) continue
        val y = 104 + (ev.unit(3) * 24).toInt() + (sin(age * 0.8 + ev.unit(4) * 6) * 4).toInt()
        val flap = (age * 4f + ev.unit(5) * 3f).toInt() % 2 == 0
        spritePaint.colorFilter = filter(s, 0.05f)
        sprite(if (flap) BeachSprites.gullUp else BeachSprites.gullDown, x.toInt(), y)
        spritePaint.colorFilter = filter(s, 0.2f + s.look.haze * 0.4f)
    }
    spritePaint.alpha = 255
    spritePaint.colorFilter = null
}

private fun PixelScene.drawLighthouse(s: SceneState, night: Float) {
    val factor = 0.12
    val offset = floor(s.cam * factor).toInt()
    val cell = 900
    val first = Math.floorDiv(offset, cell) - 1
    for (i in first..first + W / cell + 2) {
        if (Noise.unit(i, 840) > 0.3f) continue
        val wx = i * cell + 120 + (Noise.unit(i, 841) * 600).toInt()
        val x = wx - offset
        if (x < -20 || x > W + 20) continue
        if (!s.seaAt(x - 6, SEA_TOP + 6) || !s.seaAt(x + 14, SEA_TOP + 6)) continue
        sprite(BeachSprites.lighthouse, x, SEA_TOP + 5)
        if (night > 0.2f) {
            // The beam sweeps round: seen edge on it stretches out to the side, and when it points at us the lamp flares.
            val angle = s.time * 0.9
            val side = cos(angle).toFloat()
            val lx = x + 4
            val ly = SEA_TOP + 5 - 24 + 4
            val reach = side * 110f
            val a = night * 0.28f * abs(side)
            if (a > 0.02f) {
                // The beam: a long thin wedge, wider at its far end.
                val lo = if (reach > 0) lx else (lx + reach).toInt()
                val hi = if (reach > 0) (lx + reach).toInt() else lx
                for (bx in lo until hi) {
                    val u = abs(bx - lx) / abs(reach)
                    fillAlpha(bx, (ly - 1f - 3f * u).toInt(), bx + 1, (ly + 1f + 3f * u).toInt() + 1, rgb(255, 240, 170), a)
                }
            }
            val flare = Math.pow(abs(sin(angle)).toDouble(), 6.0).toFloat()
            fillAlpha(lx - 3, ly - 3, lx + 4, ly + 3, rgb(255, 244, 190), night * 0.5f * flare)
        }
    }
}

private fun SceneState.stormy() = (cond.rain * 0.7f + cond.snow * 0.5f + cond.thunder).coerceIn(0f, 1f)

// ---------- on the sand ----------

private const val PIER_SPAN = 2600.0

/** The left edge of the pier's foot on the boardwalk for each pier on screen. */
private fun SceneState.piers(): IntArray {
    val camX = floor(cam)
    var out = IntArray(0)
    val first = floor((camX - 100) / PIER_SPAN).toInt()
    val last = floor((camX + W + 100) / PIER_SPAN).toInt()
    for (k in first..last) {
        if (Noise.unit(k, 870) > 0.3f) continue
        val world = k * PIER_SPAN + 300 + Noise.unit(k, 871) * 1800
        if (Places.beach(world, placeMode) < 0.9f) continue
        out += (world - camX).toInt()
    }
    return out
}

/** A pier: planks running out over the water toward the horizon, on posts, with a lamp at the end. */
private fun PixelScene.drawPier(s: SceneState, foot: Int) {
    val plank = solid(s, rgb(160, 120, 82), 0.04f)
    val plankDark = solid(s, rgb(120, 88, 60), 0.04f)
    val post = solid(s, rgb(92, 66, 46), 0.04f)
    val lastRow = SEA_TOP + 3
    for (y in BOARD_TOP downTo lastRow) {
        val t = (BOARD_TOP - y) / (BOARD_TOP - lastRow).toFloat()
        val half = 7f - 4.5f * t
        val centre = foot + (W / 2f - foot) * t * 0.35f
        val x0 = (centre - half).toInt()
        val x1 = (centre + half).toInt()
        fill(x0, y, x1, y + 1, if ((y / 2) % 2 == 0) plank else plankDark)
        if ((y % 4) == 0) { fill(x0 - 1, y, x0, y + 3, post); fill(x1, y, x1 + 1, y + 3, post) }
    }
    val endX = (foot + (W / 2f - foot) * 0.35f).toInt()
    fill(endX - 1, lastRow - 6, endX, lastRow, post)
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    fill(endX - 2, lastRow - 7, endX + 1, lastRow - 6, if (night > 0.2f) rgb(255, 236, 170) else solid(s, rgb(220, 220, 210), 0.0f))
    if (night > 0.2f) fillAlpha(endX - 5, lastRow - 10, endX + 4, lastRow - 3, rgb(255, 226, 150), night * 0.2f)
}

// Everything on the sand and boardwalk is fixed to the ground, so it slides past exactly with it, like the lamp posts.
private const val NEAR_FACTOR = 1.0
private const val CELL = 96

/** Umbrellas, sunbathers, a volleyball net, a lifeguard tower, sandcastles and swimmers on the beach, the palms along the boardwalk, and the pier. */
internal fun PixelScene.drawBeachFront(s: SceneState) {
    val beachHere = s.beachAt(s.cam + W / 2.0, 1.0)
    if (beachHere <= 0.01f && s.beachAt(s.cam, 1.0) <= 0.01f && s.beachAt(s.cam + W, 1.0) <= 0.01f) return
    val piers = s.piers()
    for (foot in piers) drawPier(s, foot)
    val offset = floor(s.cam * NEAR_FACTOR).toInt()
    val first = Math.floorDiv(offset, CELL) - 2
    val t = s.time
    val wind = min(1f, s.cond.wind / 30f)
    val crowd = 1f - s.offSeason()
    spritePaint.colorFilter = filter(s, 0.05f + s.look.haze * 0.3f)
    for (i in first..first + W / CELL + 4) {
        val left = i * CELL - offset
        if (s.beachAt(s.cam + left + CELL / 2.0, 1.0) < 0.75f) continue
        val r = Noise.unit(i, 880)
        val baseY = SHORE + 8
        fun clear(x: Int) = piers.none { abs(x - it - 4) < 16 }
        val open = Noise.unit(i, 905) < crowd
        when {
            r < 0.5f -> if (open) {
                // One or two umbrellas, each with a towel and somebody on it.
                val n = 1 + (Noise.unit(i, 881) * 2).toInt()
                for (u in 0 until n) {
                    val x = left + 6 + (Noise.unit(i, 882 + u) * (CELL - 24)).toInt()
                    if (!clear(x)) continue
                    val y = baseY + (Noise.unit(i, 884 + u) * 5).toInt()
                    val umb = BeachSprites.umbrellas[(Noise.unit(i, 886 + u) * BeachSprites.umbrellas.size).toInt().coerceIn(0, BeachSprites.umbrellas.size - 1)]
                    val towel = intArrayOf(rgb(230, 90, 80), rgb(80, 150, 220), rgb(250, 210, 80), rgb(110, 190, 140))[(Noise.unit(i, 888 + u) * 4).toInt().coerceIn(0, 3)]
                    fill(x - 2, y, x + 10, y + 2, solid(s, towel, 0.04f))
                    sprite(BeachSprites.sunbather((Noise.unit(i, 890 + u) * 4).toInt().coerceIn(0, 3), People.skinIndex(Noise.unit(i, 912 + u)), People.hairIndex(Noise.unit(i, 914 + u))), x, y)
                    sprite(umb, x - 3, y - 1)
                }
            }
            r < 0.62f -> if (open) {
                // A volleyball net with a ball going over it.
                val x = left + 20
                if (clear(x)) {
                    sprite(BeachSprites.net, x, baseY + 5)
                    val phase = (t * 0.8 + Noise.unit(i, 892) * 5) % 2.0
                    val bx = x + 9 + (kotlin.math.cos(phase * Math.PI) * 14).toInt()
                    val by = baseY - 4 - (abs(sin(phase * Math.PI)) * 9).toInt()
                    fill(bx, by, bx + 2, by + 2, solid(s, rgb(250, 250, 240), 0.0f))
                    // A player each side, jumping with both arms up each time the ball comes down to them: the ball is at the
                    // right-hand end of its flight when the phase is a whole number of cycles, and at the left-hand end halfway.
                    val toRight = minOf(phase, 2.0 - phase)
                    val toLeft = abs(phase - 1.0)
                    for (side in 0..1) {
                        val hits = if (side == 1) toRight < 0.14 else toLeft < 0.14
                        val skin = People.skinIndex(Noise.unit(i, 930 + side))
                        val hair = People.hairIndex(Noise.unit(i, 932 + side))
                        val suit = (Noise.unit(i, 934 + side) * 4).toInt().coerceIn(0, 3)
                        val px = if (side == 0) x - 7 else x + 21
                        sprite(BeachSprites.player(skin, hair, suit, hits), px, baseY + 5 - (if (hits) 2 else 0))
                    }
                }
            }
            r < 0.69f -> {
                val x = left + 30
                if (clear(x)) {
                    sprite(BeachSprites.tower, x, baseY + 4)
                    val wave = (sin(t * 3.0) * 1.2).toInt()
                    fill(x + 12, baseY - 18, x + 13, baseY - 8, solid(s, rgb(220, 220, 220), 0.0f))
                    fill(x + 13, baseY - 18, x + 17 + wave, baseY - 14, solid(s, rgb(240, 70, 60), 0.0f))
                }
            }
            r < 0.82f -> if (open) {
                val x = left + 14 + (Noise.unit(i, 893) * 60).toInt()
                if (clear(x)) sprite(BeachSprites.castle, x, baseY - (Noise.unit(i, 894) * 4).toInt())
            }
        }
        // Swimmers: a head and an arm showing above the water, now and then.
        if (Noise.unit(i, 895) < 0.4f * crowd * crowd) {
            val sx = left + 10 + (Noise.unit(i, 896) * 70).toInt()
            val sy = SHORE - 5 - (Noise.unit(i, 897) * 6).toInt() + (sin(t * 1.3 + i) * 1).toInt()
            val swimSkin = People.SKIN[People.skinIndex(Noise.unit(i, 917))]
            fill(sx, sy, sx + 2, sy + 1, solid(s, People.hair(People.hairIndex(Noise.unit(i, 918))), 0.04f))
            fill(sx, sy + 1, sx + 2, sy + 2, solid(s, swimSkin, 0.04f))
            val arm = if ((t * 2.0).toInt() % 2 == 0) 2 else 0
            fill(sx + 2, sy + arm, sx + 4, sy + arm + 1, solid(s, swimSkin, 0.04f))
        }
        // Out of season a few people walk the sand wrapped up warm, some with a dog.
        if (crowd < 0.7f && Noise.unit(i, 906) < 0.45f * (1f - crowd)) {
            val span = CELL - 14
            val dir = if (Noise.unit(i, 907) < 0.5f) 1 else -1
            val phase = ((t * 5.0 + Noise.unit(i, 908) * span) % span).toInt()
            val wx = left + 4 + (if (dir > 0) phase else span - phase)
            val wy = BOARD_TOP - 1 - (Noise.unit(i, 909) * 3).toInt()
            val step = (t * 4.0).toInt() % 2
            val person = personSprite((Noise.unit(i, 910) * 6).toInt().coerceIn(0, 5), People.skinIndex(Noise.unit(i, 919)), People.hairIndex(Noise.unit(i, 920)), step)
            sprite(person, wx, wy)
            if (Noise.unit(i, 911) < 0.5f) {
                val dx = wx + dir * 6 + (sin(t * 3.0 + i) * 2).toInt()
                fill(dx, wy - 3, dx + 3, wy - 1, solid(s, rgb(120, 84, 52), 0.04f))
                fill(dx + (if (dir > 0) 2 else 0), wy - 4, dx + (if (dir > 0) 3 else 1), wy - 3, solid(s, rgb(120, 84, 52), 0.04f))
            }
        }
        // A surfer on the water.
        if (Noise.unit(i, 898) < 0.18f * (1f - 0.8f * (1f - crowd))) {
            val sx = left + 20 + (Noise.unit(i, 899) * 60).toInt()
            sprite(BeachSprites.surfer(People.skinIndex(Noise.unit(i, 916))), sx, SHORE - 2 + (sin(t * 1.1 + i) * 1.5).toInt())
        }
    }
    // Palms along the boardwalk, swaying in the wind.
    val palmFactor = 1.0
    val palmOffset = floor(s.cam * palmFactor).toInt()
    val palmCell = 58
    val firstPalm = Math.floorDiv(palmOffset, palmCell) - 2
    for (i in firstPalm..firstPalm + W / palmCell + 4) {
        val wx = i * palmCell + (Noise.unit(i, 900) * 30).toInt()
        val x = wx - palmOffset
        if (s.beachAt(s.cam + x, 1.0) < 0.75f || Noise.unit(i, 901) > 0.45f) continue
        if (piers.any { abs(x - it - 4) < 18 }) continue
        val sway = (sin(t * 1.4 + i) * (0.4f + wind * 1.4f)).toInt()
        sprite(BeachSprites.palms[(Noise.unit(i, 902) * 2).toInt().coerceIn(0, 1)], x - 9 + sway, BOARD_TOP + 3)
    }
    spritePaint.colorFilter = null
}

/** Sand instead of grass on the verge in front of the road. */
internal fun PixelScene.drawBeachRoad(s: SceneState) {
    if (!s.anyBeach) return
    val sand = lit(s, rgb(214, 194, 146), 0.0f)
    val edge = lit(s, rgb(236, 220, 176), 0.0f)
    val speck = lit(s, rgb(186, 164, 120), 0.0f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        if (s.beachExact(x) < 0.5f) continue
        fill(x, ROAD_BOTTOM + 2, x + 1, H, sand)
        fill(x, ROAD_BOTTOM + 2, x + 1, ROAD_BOTTOM + 3, edge)
        for (y in ROAD_BOTTOM + 4 until H) if (Noise.unit(x + cam, y, 903) < 0.08f) fill(x, y, x + 1, y + 1, speck)
    }
}
