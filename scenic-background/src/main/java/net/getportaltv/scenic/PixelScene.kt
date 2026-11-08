package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the countryside journey into a small picture (320 by 180) and gives it a limited, dithered palette, the way old
 * computers did. The picture is then scaled up whole by the view, so every pixel here becomes a clean square on the TV.
 *
 * It is drawn back to front: sky, stars, sun and moon, clouds, mountains, hills, fields and buildings, the road and its
 * traffic, the foreground, then rain, snow, fog and lightning over everything. Every layer moves at its own speed as the
 * journey goes on (the near ones fast, the far ones slowly), which is what makes it feel deep. Nothing is stored between
 * frames beyond [SceneState]: the world, the traffic and the birds are worked out from the position and the time, so the
 * same place always looks the same.
 */
class PixelScene {

    /** The finished picture, which the view scales up. */
    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    /**
     * The picture as it is drawn: every pixel an opaque colour. Everything is drawn straight into this array rather than through a
     * Canvas, because a slow TV spends far longer setting up each Canvas call (thousands a frame) than the few pixels each one fills.
     */
    private val fb = IntArray(W * H)

    /** How sprites are drawn now: how opaque, and the light and haze they are given (see [filter]). */
    internal val spritePaint = SpritePaint()

    /** When set, drawing only reaches the columns that are true here (the train stays on the stretch of track that runs alongside). */
    internal var clipCols: BooleanArray? = null

    /** Levels per colour channel in the final picture: 6 gives 216 colours. */
    var levels = 10
    var dither = true
    /** 1 for everything, less to thin out rain and snow on a slow TV. */
    var particleScale = 1f

    // The pictures that are drawn many times, made once.
    private val clouds = listOf(34 to 12, 50 to 16, 70 to 20, 96 to 26, 130 to 32).mapIndexed { i, (w, h) -> Sprite.of(Sprites.cloud(w, h, 100 + i)) }
    private val carBodies = intArrayOf(rgb(214, 56, 56), rgb(60, 110, 200), rgb(236, 200, 60), rgb(240, 240, 240), rgb(70, 74, 84), rgb(70, 160, 96))
    internal val cars = carBodies.map { Sprites.car(it) }
    private val schoolBus = Sprites.schoolBus()
    private val taxi = Sprites.taxi(rgb(244, 196, 40))
    private val police = Sprites.police()
    private val ambulance = Sprites.ambulance()
    private val fireEngine = Sprites.fireEngine()
    private val buses = intArrayOf(rgb(46, 112, 192), rgb(200, 62, 52), rgb(60, 150, 96)).map { Sprites.bus(it) }
    private val trucks = intArrayOf(rgb(236, 236, 240), rgb(200, 90, 50), rgb(60, 120, 170)).map { Sprites.truck(it) }
    private val animals = listOf(Sprites.cow, Sprites.sheep, Sprites.horse)

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    // ---------- one frame ----------

    private var scene: SceneState? = null

    /** Times [block] under [name] when profiling is on (debug builds only; the clock reads are the only cost otherwise). */
    private inline fun phase(name: String, block: () -> Unit) {
        if (!Profile.on) { block(); return }
        val t0 = System.nanoTime()
        block()
        Profile.add(name, System.nanoTime() - t0)
    }

    fun render(s: SceneState) {
        scene = s
        val l = s.look
        val cam = s.cam
        s.prepareColumns()
        phase("findBridgesParks") { s.bridges = s.findBridges(); s.parks = s.findParks() }
        phase("drawSky") { drawSky(s) }
        phase("drawStars") { drawStars(s) }
        phase("drawSun") { drawSun(s) }
        phase("drawMoon") { drawMoon(s) }
        phase("drawSkyLife") { drawSkyLife(s) }
        phase("drawClouds") { drawClouds(s, 0) }
        phase("drawClouds") { drawClouds(s, 1) }
        phase("drawMountains") { drawMountains(s, cam) }
        phase("drawHills") { drawHills(s, cam, 0.10f, 124, 18f, 0.022f, 11, rgb(98, 148, 96), 0.55f, 0.62f) }
        phase("drawTreeLine") { drawTreeLine(s, cam, 0.10f, 0.10f) }
        phase("drawHills") { drawHills(s, cam, 0.26f, 134, 14f, 0.028f, 29, rgb(84, 148, 78), 0.34f, 0.40f) }
        phase("drawTreeLine") { drawTreeLine(s, cam, 0.26f, 0.06f) }
        phase("drawSkyline") { drawSkyline(s) }
        phase("drawField") { drawField(s) }
        phase("drawCityGround") { drawCityGround(s) }
        phase("drawBeachGround") { drawBeachGround(s) }
        phase("drawSuburbGround") { drawSuburbGround(s) }
        phase("drawSeaLife") { drawSeaLife(s) }
        phase("drawRiverBehind") { drawRiverBehind(s) }
        phase("drawRailway") { drawRailway(s) }
        phase("drawPonds") { drawPonds(s) }
        phase("drawFieldSeason") { drawFieldSeason(s) }
        phase("drawFieldAnimals") { drawFieldAnimals(s) }
        phase("drawFarm") { drawFarm(s, cam) }
        phase("drawSuburbBlocks") { drawSuburbBlocks(s) }
        phase("drawCityBlocks") { drawCityBlocks(s) }
        phase("drawStreetLamps") { drawStreetLamps(s) }
        phase("drawBeachFront") { drawBeachFront(s) }
        phase("drawSubwayEntrances") { drawSubwayEntrances(s) }
        phase("drawCityPeople") { drawCityPeople(s) }
        phase("drawSuburbPeople") { drawSuburbPeople(s) }
        phase("drawRoadsideTrees") { drawRoadsideTrees(s) }
        phase("drawCampsites") { drawCampsites(s) }
        phase("drawForestMist") { drawForestMist(s) }
        phase("drawDepthFog") { drawDepthFog(s) }
        phase("drawButterflies") { drawButterflies(s) }
        phase("drawFence") { drawFence(s, cam) }
        phase("drawRoad") { drawRoad(s, cam) }
        phase("drawCityRoad") { drawCityRoad(s) }
        phase("drawBeachRoad") { drawBeachRoad(s) }
        phase("drawRiverFront") { drawRiverFront(s) }
        phase("drawTraffic") { drawTraffic(s) }
        phase("drawBridge") { drawBridge(s) }
        phase("drawWalkers") { drawWalkers(s) }
        phase("drawBirds") { drawBirds(s) }
        phase("drawMigration") { drawMigration(s) }
        phase("drawBats") { drawBats(s) }
        phase("drawFireflies") { drawFireflies(s) }
        phase("drawFrontTrees") { drawFrontTrees(s) }
        phase("drawForeground") { drawForeground(s, cam) }
        phase("drawFallingLeaves") { drawFallingLeaves(s) }
        phase("drawRain") { drawRain(s) }
        phase("drawSnow") { drawSnow(s) }
        phase("drawFog") { drawFog(s) }
        if (s.flash > 0f) phase("drawLightning") { drawLightning(s) }
        phase("finish") { finish() }
    }

    // ---------- colours ----------

    /** [base] as it looks in this light, with [air] of the distance's haze mixed in. */
    internal fun lit(s: SceneState, base: Int, air: Float, snowy: Float = 0.97f): Int {
        val l = s.look
        var c = Colors.mul(base, l.tintR, l.tintG, l.tintB)
        if (s.snowCover > 0f) c = lerp(c, Colors.mul(rgb(236, 240, 248), l.tintR, l.tintG, l.tintB), s.snowCover * snowy)
        return lerp(c, airColor(s), air)
    }

    /** A colour like [lit] but for things snow does not settle on: walls, wires, posts, water, boards. */
    internal fun solid(s: SceneState, base: Int, air: Float): Int = lit(s, base, air, 0f)

    internal fun airColor(s: SceneState) = lerp(s.look.skyBottom, s.look.fogColor, s.look.haze * 0.8f)

    /** A filter that gives sprites the same light and haze. */
    internal fun filter(s: SceneState, air: Float, extraBright: Float = 1f, snowy: Boolean = true): Tint {
        val l = s.look
        // Snow settles on the trees and roofs too: lighter, and paler.
        val dust = if (snowy) s.snowCover * 70 else 0f
        val keep = 1f - air
        val mul = rgb((min(1f, l.tintR * extraBright) * keep * 255).toInt(), (min(1f, l.tintG * extraBright) * keep * 255).toInt(), (min(1f, l.tintB * extraBright) * keep * 255).toInt())
        val a = airColor(s)
        val add = rgb((Colors.r(a) * air + dust).toInt(), (Colors.g(a) * air + dust).toInt(), (Colors.b(a) * air + dust).toInt())
        return Tint(mul, add)
    }

    internal fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        Profile.draws++
        val xa = if (x0 < 0) 0 else x0
        val xb = if (x1 > W) W else x1
        val ya = if (y0 < 0) 0 else y0
        val yb = if (y1 > H) H else y1
        if (xa >= xb || ya >= yb) return
        val c = color or OPAQUE
        val clip = clipCols
        if (xb - xa == 1 && (clip == null || clip[xa])) {
            // A single column (the hills and mountains are drawn so): step down it without a call for each row.
            var i = ya * W + xa
            for (y in ya until yb) { fb[i] = c; i += W }
            return
        }
        for (y in ya until yb) {
            var i = y * W + xa
            if (clip == null) {
                java.util.Arrays.fill(fb, i, i + (xb - xa), c)
            } else {
                for (x in xa until xb) { if (clip[x]) fb[i] = c; i++ }
            }
        }
    }

    internal fun fillAlpha(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, alpha: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (a >= 255) { fill(x0, y0, x1, y1, color); return }
        Profile.draws++
        if (a <= 0) return
        val xa = if (x0 < 0) 0 else x0
        val xb = if (x1 > W) W else x1
        val ya = if (y0 < 0) 0 else y0
        val yb = if (y1 > H) H else y1
        if (xa >= xb || ya >= yb) return
        val clip = clipCols
        val sr = (color shr 16) and 0xFF
        val sg = (color shr 8) and 0xFF
        val sb = color and 0xFF
        val ia = 255 - a
        for (y in ya until yb) {
            var i = y * W + xa
            for (x in xa until xb) {
                if (clip == null || clip[x]) {
                    val d = fb[i]
                    fb[i] = OPAQUE or ((d255(sr * a + ((d shr 16) and 0xFF) * ia)) shl 16) or
                        ((d255(sg * a + ((d shr 8) and 0xFF) * ia)) shl 8) or (d255(sb * a + (d and 0xFF) * ia))
                }
                i++
            }
        }
    }

    /** [v] divided by 255 and rounded, for [v] up to 65534, without a division (which a slow TV is slow at). */
    private fun d255(v: Int): Int { val t = v + 128; return (t + (t shr 8)) shr 8 }

    /** One pixel, [color] over what is there at strength [a] (0 to 255). */
    private fun dot(x: Int, y: Int, color: Int, a: Int) {
        if (x < 0 || x >= W || y < 0 || y >= H) return
        val clip = clipCols
        if (clip != null && !clip[x]) return
        val i = y * W + x
        if (a >= 255) { fb[i] = color or OPAQUE; return }
        val d = fb[i]
        val ia = 255 - a
        fb[i] = OPAQUE or ((d255((color shr 16 and 0xFF) * a + ((d shr 16) and 0xFF) * ia)) shl 16) or
            ((d255((color shr 8 and 0xFF) * a + ((d shr 8) and 0xFF) * ia)) shl 8) or (d255((color and 0xFF) * a + (d and 0xFF) * ia))
    }

    internal fun fillCircle(cx: Float, cy: Float, r: Float, color: Int, alpha: Float) = fillOval(cx - r, cy - r, cx + r, cy + r, color, alpha)

    /** An ellipse inside the box (no smoothing: every pixel whose middle is inside it). */
    internal fun fillOval(left: Float, top: Float, right: Float, bottom: Float, color: Int, alpha: Float) {
        Profile.draws++
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (a <= 0) return
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        if (rx <= 0f || ry <= 0f) return
        val ya = floor(top).toInt().coerceAtLeast(0)
        val yb = kotlin.math.ceil(bottom).toInt().coerceAtMost(H - 1)
        val sr = (color shr 16) and 0xFF
        val sg = (color shr 8) and 0xFF
        val sb = color and 0xFF
        val ia = 255 - a
        val clip = clipCols
        for (y in ya..yb) {
            val dy = (y + 0.5f - cy) / ry
            val rest = 1f - dy * dy
            if (rest < 0f) continue
            val half = rx * sqrt(rest)
            val xa = kotlin.math.ceil(cx - half - 0.5f).toInt().coerceAtLeast(0)
            val xb = floor(cx + half - 0.5f).toInt().coerceAtMost(W - 1)
            var i = y * W + xa
            for (x in xa..xb) {
                if (clip == null || clip[x]) {
                    if (a >= 255) fb[i] = color or OPAQUE
                    else {
                        val d = fb[i]
                        fb[i] = OPAQUE or ((d255(sr * a + ((d shr 16) and 0xFF) * ia)) shl 16) or
                            ((d255(sg * a + ((d shr 8) and 0xFF) * ia)) shl 8) or (d255(sb * a + (d and 0xFF) * ia))
                    }
                }
                i++
            }
        }
    }

    /** A line one pixel wide from ([x0], [y0]) to ([x1], [y1]), [color] at strength [a] (0 to 255). */
    internal fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, a: Int = 255) {
        Profile.draws++
        val dx = x1 - x0
        val dy = y1 - y0
        val n = kotlin.math.max(abs(dx), abs(dy)).toInt().coerceAtLeast(1)
        for (k in 0..n) {
            val t = k.toFloat() / n
            dot(floor(x0 + dx * t).toInt(), floor(y0 + dy * t).toInt(), color, a)
        }
    }

    /** Draws a picture's pixels at ([x], [top]), mirrored if [flip], at the sprite paint's strength and light. */
    private fun blit(px: IntArray, sw: Int, sh: Int, x: Int, top: Int, flip: Boolean, alpha: Int) {
        if (alpha <= 0) return
        val tint = spritePaint.colorFilter
        val mr = if (tint == null) 255 else (tint.mul shr 16) and 0xFF
        val mg = if (tint == null) 255 else (tint.mul shr 8) and 0xFF
        val mb = if (tint == null) 255 else tint.mul and 0xFF
        val ar = if (tint == null) 0 else (tint.add shr 16) and 0xFF
        val ag = if (tint == null) 0 else (tint.add shr 8) and 0xFF
        val ab = if (tint == null) 0 else tint.add and 0xFF
        val clip = clipCols
        for (sy in 0 until sh) {
            val y = top + sy
            if (y < 0 || y >= H) continue
            for (sx in 0 until sw) {
                val dx = x + sx
                if (dx < 0 || dx >= W) continue
                if (clip != null && !clip[dx]) continue
                val c = px[sy * sw + (if (flip) sw - 1 - sx else sx)]
                val sa = c ushr 24
                if (sa == 0) continue
                val a = if (alpha >= 255) sa else d255(sa * alpha)
                if (a <= 0) continue
                val r = kotlin.math.min(255, d255(((c shr 16) and 0xFF) * mr) + ar)
                val g = kotlin.math.min(255, d255(((c shr 8) and 0xFF) * mg) + ag)
                val b = kotlin.math.min(255, d255((c and 0xFF) * mb) + ab)
                val i = y * W + dx
                if (a >= 255) { fb[i] = OPAQUE or (r shl 16) or (g shl 8) or b; continue }
                val d = fb[i]
                val ia = 255 - a
                fb[i] = OPAQUE or ((d255(r * a + ((d shr 16) and 0xFF) * ia)) shl 16) or
                    ((d255(g * a + ((d shr 8) and 0xFF) * ia)) shl 8) or (d255(b * a + (d and 0xFF) * ia))
            }
        }
    }

    internal fun sprite(sp: Sprite, x: Int, bottom: Int, flip: Boolean = false) {
        Profile.sprites++
        if (x > W || x + sp.width < 0) return
        val top = bottom - sp.height
        blit(sp.pixels, sp.width, sp.height, x, top, flip, spritePaint.alpha)
        // Evergreens grow white as snow builds up on them.
        val s = scene
        val frost = if (s != null && s.snowCover > 0.03f) Sprites.frosted(sp) else null
        if (frost != null) blit(frost.pixels, frost.width, frost.height, x, top, flip, (spritePaint.alpha * s!!.snowCover).toInt().coerceIn(0, 255))
    }

    // ---------- sky ----------

    private fun drawSky(s: SceneState) {
        val l = s.look
        for (y in 0 until HORIZON + 30) {
            val t = min(1f, y / (HORIZON + 4f))
            fill(0, y, W, y + 1, lerp(l.skyTop, l.skyBottom, t * t * 0.5f + t * 0.5f))
        }
        fill(0, HORIZON + 30, W, H, l.skyBottom)
        // Around a low sun the sky glows.
        if (l.glowStrength > 0.02f && s.sunAlt > -12) {
            val sx = skyX(s.sunAz)
            val sy = skyY(s.sunAlt)
            for (i in 6 downTo 1) {
                val a = (l.glowStrength * 38 * (7 - i) / 6f).toInt().coerceIn(0, 255)
                fillOval(sx - i * 20f, sy - i * 11f, sx + i * 20f, sy + i * 11f, l.glow, a / 255f)
            }
        }
    }

    private fun skyX(az: Double): Float = ((az - 90.0) / 180.0 * W).toFloat()

    private fun skyY(alt: Double): Float =
        if (alt >= 0) HORIZON - Math.pow((alt / 90.0).coerceAtMost(1.0), 0.8).toFloat() * (HORIZON - 14f)
        else HORIZON - (alt * 1.4).toFloat()

    private fun drawStars(s: SceneState) {
        val vis = s.look.starVisibility
        if (vis < 0.02f) return
        for (i in 0 until 90) {
            val x = (Noise.unit(i, 1) * W).toInt()
            val y = (Noise.unit(i, 2) * (HORIZON - 12)).toInt()
            val base = 0.35f + 0.65f * Noise.unit(i, 3)
            val twinkle = 0.75f + 0.25f * sin(s.time * (1.0 + Noise.unit(i, 4) * 2.0) + i).toFloat()
            val a = vis * base * twinkle
            val col = if (Noise.unit(i, 5) < 0.25f) rgb(255, 226, 190) else rgb(214, 226, 255)
            fillAlpha(x, y, x + 1, y + 1, col, a)
            if (base > 0.9f && a > 0.5f) {
                fillAlpha(x - 1, y, x + 2, y + 1, col, a * 0.4f)
                fillAlpha(x, y - 1, x + 1, y + 2, col, a * 0.4f)
            }
        }
        // A shooting star now and then.
        val slot = floor(s.time / 23.0).toInt()
        if (Noise.unit(slot, 61) < 0.35f) {
            val at = slot * 23.0 + 3 + Noise.unit(slot, 62) * 15
            val e = (s.time - at).toFloat()
            if (e in 0f..0.7f) {
                val x0 = 40f + Noise.unit(slot, 63) * 240f
                val y0 = 8f + Noise.unit(slot, 64) * 40f
                val p = e / 0.7f
                for (k in 0..5) {
                    val q = max(0f, p - k * 0.04f)
                    fillAlpha((x0 - q * 60).toInt(), (y0 + q * 22).toInt(), (x0 - q * 60).toInt() + 1, (y0 + q * 22).toInt() + 1, rgb(255, 255, 255), vis * (1f - k / 6f))
                }
            }
        }
    }

    // ---------- sun and moon ----------

    private fun drawSun(s: SceneState) {
        if (s.sunAlt < -4) return
        val l = s.look
        val x = skyX(s.sunAz)
        val y = skyY(s.sunAlt)
        if (x < -20 || x > W + 20) return
        val shine = ((1f - l.gloom * 0.9f) * (1f - l.haze * 0.7f) * (1f - l.deck)).coerceIn(0f, 1f)
        if (shine < 0.03f) return
        val core = lerp(rgb(255, 238, 170), rgb(255, 150, 80), l.twilight)
        for (i in 4 downTo 1) fillCircle(x, y, 8f + i * 5f, core, shine * 0.07f)
        fillCircle(x, y, 8f, core, shine)
    }

    private fun drawMoon(s: SceneState) {
        val m = s.moon ?: return
        if (m.altDeg < -4) return
        val l = s.look
        val cx = skyX(m.azDeg)
        val cy = skyY(m.altDeg)
        if (cx < -20 || cx > W + 20) return
        val shine = ((1f - l.gloom * 0.85f) * (1f - l.haze * 0.8f) * (1f - l.deck)).coerceIn(0f, 1f)
        if (shine < 0.03f) return
        val night = 1f - l.day
        val r = 6
        val sinI = sin(Math.toRadians(m.phaseAngleDeg)).toFloat()
        val cosI = cos(Math.toRadians(m.phaseAngleDeg)).toFloat()
        val lx = sinI * m.litX.toFloat()
        val ly = sinI * m.litY.toFloat()
        val lz = cosI
        if (night > 0.05f) fillCircle(cx, cy, r + 7f, rgb(180, 196, 240), night * 0.07f * shine * m.illumination.toFloat())
        for (py in -r..r) for (px in -r..r) {
            val nx = px / (r + 0.5f)
            val nyUp = -py / (r + 0.5f)
            val d2 = nx * nx + nyUp * nyUp
            if (d2 > 1f) continue
            val nz = sqrt(1f - d2)
            val lit = nx * lx + nyUp * ly + nz * lz > 0f
            val crater = Noise.hash(px + 20, py + 20, 7) and 7
            var col = if (lit) rgb(240, 238, 214) else lerp(rgb(34, 40, 62), rgb(120, 130, 160), l.day)
            if (lit && crater < 2) col = rgb(206, 204, 186)
            // In full daylight the moon is only a faint ghost; it shows properly as the sky darkens.
            val a = (if (lit) shine else shine * (0.55f * night + 0.15f)) * (0.22f + 0.78f * night)
            fillAlpha((cx + px).toInt(), (cy + py).toInt(), (cx + px).toInt() + 1, (cy + py).toInt() + 1, col, a)
        }
    }

    // ---------- clouds ----------

    private fun drawClouds(s: SceneState, layer: Int) {
        val cover = s.cond.cloud
        if (cover < 0.01f) return
        val l = s.look
        val low3 = layer == 2
        val slots = if (low3) 6 else if (layer == 0) 14 else 10
        val loop = if (low3) 700 else if (layer == 0) 880 else 760
        val parallax = if (low3) 0.05 else if (layer == 0) 0.03 else 0.07
        val speed = if (low3) 0.8f else if (layer == 0) 0.55f else 1f
        val lo = if (low3) 70 else if (layer == 0) 6 else 38
        val hi = if (low3) 92 else if (layer == 0) 52 else 92
        val c = l.cloudLit
        val mul = rgb(Colors.r(c), Colors.g(c), Colors.b(c))
        // The low ones are far away, so they are paler and hazier.
        val fogMix = (l.haze * 0.8f + if (low3) 0.3f else 0f).coerceIn(0f, 0.95f)
        val fogged = lerp(mul, l.fogColor, fogMix)
        spritePaint.colorFilter = Tint(fogged, rgb(0, 0, 0))
        val baseAlpha = 1f - l.haze * 0.55f
        // More cloud means more clouds and bigger ones, until the whole sky is covered. Each cloud has its own level of cover at
        // which it is there: below it the cloud is gone, above it the cloud is whole, and in between (a little more than a tenth
        // of the range) it is faded, so clouds fade in as the weather clouds over and fade out as it clears, never popping.
        val chance = if (low3) cover * 0.8f - 0.1f else cover * 1.25f - 0.05f
        for (i in 0 until slots) {
            val fade = Colors.smoothstep(0f, FADE_RANGE, chance - Noise.unit(i, layer, 41))
            if (fade <= 0f) continue
            // The size follows the cover continuously too: the two nearest sizes are blended, so a cloud grows instead of changing shape at once.
            val position = ((cover * 0.7f + Noise.unit(i, layer, 42) * 0.6f) * (if (low3) 0.6f else 1f) * (clouds.size - 1)).coerceIn(0f, clouds.size - 1f)
            val low = position.toInt().coerceAtMost(clouds.size - 2)
            val blend = (position - low).coerceIn(0f, 1f)
            val baseX = i * (loop.toFloat() / slots) + Noise.unit(i, layer, 43) * 40f
            val centerX = Math.floorMod((baseX - s.drift * speed - s.cam * parallax).toInt(), loop) - 65
            val centerY = lo + (Noise.unit(i, layer, 44) * (hi - lo)).toInt()
            for ((index, weight) in listOf((if (blend < 0.5f) low else low + 1) to 1f)) {
                if (weight <= 0f) continue
                val bmp = clouds[index]
                val left = centerX - bmp.width / 2
                if (left > W || left + bmp.width < 0) continue
                blit(bmp.pixels, bmp.width, bmp.height, left, centerY - bmp.height / 2, false, (baseAlpha * fade * weight * 255).toInt().coerceIn(0, 255))
            }
        }
        spritePaint.colorFilter = null
        spritePaint.alpha = 255
    }

    // ---------- land ----------

    private val tops = IntArray(W + 8)

    private val strips = HashMap<String, Strip>()

    /** The strip kept under [name] for the [mode] of place chosen, made with [compute] the first time. */
    private fun strip(name: String, mode: String, compute: (Int) -> Float): Strip = strips.getOrPut("$name/$mode") { Strip(W + 8, compute) }

    private fun drawMountains(s: SceneState, cam: Double) {
        mountainRange(s, cam, factor = 0.045, freq = 0.0075f, seed = 3, maxH = 44f, base = HORIZON + 3, air = 0.58f, snowLineBase = 0.72f)
        // Now and then a low cloud drifts in front of the far mountains (and behind the near ones).
        drawClouds(s, 2)
        mountainRange(s, cam, factor = 0.08, freq = 0.0115f, seed = 5, maxH = 52f, base = HORIZON + 7, air = 0.34f, snowLineBase = 0.66f)
    }

    /** A range of peaks: lit on the slopes that face left, shaded on the ones that face right, with snow on the highest. */
    /** Eases the height off toward 1 instead of cutting it flat, so the tallest summits are rounded rather than plateaus. */
    private fun softPeak(v: Float): Float = if (v < 0.6f) v else 0.6f + 0.4f * Math.tanh(((v - 0.6f) / 0.4f).toDouble()).toFloat()

    private fun mountainRange(s: SceneState, cam: Double, factor: Double, freq: Float, seed: Int, maxH: Float, base: Int, air: Float, snowLineBase: Float) {
        // Snow lies lower on the mountains in winter.
        val snowLine = snowLineBase - 0.3f * s.season.winter
        val a = air + s.look.haze * 0.4f
        val lit = lit(s, rgb(104, 118, 156), a)
        val shade = lit(s, rgb(72, 84, 124), a)
        val snow = lit(s, rgb(240, 244, 252), a * 0.7f)
        val snowShade = lit(s, rgb(196, 204, 226), a * 0.7f)
        // Whole pixels, so the land is sampled at the same places every frame and only slides, instead of shimmering.
        val offset = floor(cam * factor).toFloat()
        val heights = FloatArray(W + 8)
        // What does not change as the picture slides along (the peaks, and how much range there is here) is kept from the last frame.
        val shape = strip("m$seed", s.placeMode) { wx ->
            // In places there are no mountains at all, and they rise and sink gradually either side.
            softPeak(((Noise.mountain(wx * freq, seed) - 0.18f) * 1.5f).coerceAtLeast(0f)) * mountainsAt(wx.toDouble(), factor)
        }
        val city = strip("mc$seed", s.placeMode) { wx -> s.cityAt(wx.toDouble(), factor) }
        shape.moveTo(offset.toInt() - 4)
        city.moveTo(offset.toInt() - 4)
        for (i in 0 until W + 8) {
            heights[i] = shape[i] * (1f - maxOf(city[i], s.beachOnScreen(i - 4)))
            tops[i] = if (heights[i] < 0.02f) H else (base - heights[i] * maxH).toInt()
        }
        for (x in 0 until W) {
            val i = x + 4
            val top = tops[i]
            // Which way the slope faces: rising to the right faces left, into the light.
            val facingLeft = tops[i + 3] <= tops[i - 3]
            fill(x, top, x + 1, H, if (facingLeft) lit else shade)
            val h = heights[i]
            if (h > snowLine) {
                val depth = ((h - snowLine) * 34f).toInt() + 1 + (Noise.hash((x + offset.toInt()) / 2, seed, 9) and 1)
                fill(x, top, x + 1, top + depth, if (facingLeft) snow else snowShade)
            }
        }
    }

    private fun drawHills(s: SceneState, cam: Double, factor: Float, base: Int, amp: Float, freq: Float, seed: Int, color: Int, air: Float, airTop: Float) {
        val a = air + s.look.haze * 0.3f
        // The season's colour over the green: bright in spring, golden in autumn, dull in winter.
        val tinted = Colors.mul(color, s.season.hillR, s.season.hillG, s.season.hillB)
        val body = lit(s, tinted, a)
        val crest = lit(s, Colors.mul(tinted, 1.12f, 1.14f, 1.1f), a)
        // Under forest the hills are darker, bluer and softer along the top.
        val forestColor = Colors.mul(color, 0.6f, 0.76f, 0.72f)
        val forestBody = lit(s, forestColor, a)
        val cityHill = lit(s, rgb(116, 122, 134), a)
        val duneHill = lit(s, rgb(208, 188, 142), a)
        val forestCrest = lit(s, Colors.mul(forestColor, 1.1f, 1.12f, 1.08f), a)
        // Whole pixels, so the land is sampled at the same places every frame and only slides, instead of shimmering.
        val offset = floor(cam * factor).toFloat()
        val ridge = strip("hr$seed", s.placeMode) { wx -> Noise.ridge(wx * freq, seed, 3) }
        val forest = strip("hf$seed", s.placeMode) { wx -> s.forestAt(wx.toDouble(), factor.toDouble()) }
        val cityShare = strip("hc$seed", s.placeMode) { wx -> s.cityAt(wx.toDouble(), factor.toDouble()) }
        ridge.moveTo(offset.toInt() - 4); forest.moveTo(offset.toInt() - 4); cityShare.moveTo(offset.toInt() - 4)
        for (x in 0 until W) {
            val h = ridge[x + 4]
            val f = forest[x + 4]
            // Toward the city the hills flatten and go grey, hidden behind the towers.
            val c = cityShare[x + 4]
            // Toward the sea they flatten to low dunes.
            val b = s.beachOnScreen(x)
            val top = (base - h * amp * (1f - 0.85f * maxOf(c, b))).toInt()
            var bodyColor = if (f > 0f) lerp(body, forestBody, f) else body
            var crestColor = if (f > 0f) lerp(crest, forestCrest, f) else crest
            if (c > 0f) { bodyColor = lerp(bodyColor, cityHill, c); crestColor = lerp(crestColor, cityHill, c) }
            if (b > 0f) { bodyColor = lerp(bodyColor, duneHill, b); crestColor = lerp(crestColor, duneHill, b) }
            fill(x, top, x + 1, H, bodyColor)
            fill(x, top, x + 1, top + 1, crestColor)
        }
        // Fields on the slopes: a darker band, so the hills read as farmland.
        if (airTop < 0.5f) for (x in 0 until W step 2) {
            if (s.forestAt((x + offset).toDouble(), factor.toDouble()) > 0.5f || s.hardOnScreen(x) > 0.2f) continue
            val h = Noise.ridge((x + offset) * freq, seed, 3)
            val top = (base - h * amp).toInt() + 4
            if (Noise.unit(Math.floorDiv((x + offset).toInt(), 24), seed, 5) < 0.4f) fillAlpha(x, top, x + 2, top + 5, lit(s, Colors.mul(color, 0.82f, 0.9f, 0.78f), a), 0.55f)
        }
    }

    /** Small trees along the crest of the hills behind: [factor] must be the same as the hills they stand on, or they drift off them. */
    private fun drawTreeLine(s: SceneState, cam: Double, factor: Float, air: Float) {
        spritePaint.colorFilter = filter(s, air + 0.22f + s.look.haze * 0.4f)
        val offset = floor(cam * factor)
        val freq = if (factor < 0.2f) 0.022f else 0.028f
        val seed = if (factor < 0.2f) 11 else 29
        val base = if (factor < 0.2f) 124 else 134
        val amp = if (factor < 0.2f) 18f else 14f
        val start = floor(offset / 11.0).toInt() - 1
        for (i in start..start + W / 11 + 2) for (extra in 0..1) {
            val worldX = i * 11 + (Noise.unit(i, seed, 72 + extra * 40) * 8).toInt() + extra * 5
            // In forest the trees crowd together (a second one beside most of them) and are mostly firs.
            val f = s.forestAt(worldX.toDouble(), factor.toDouble())
            if (extra == 1 && Noise.unit(i, seed, 74) > f * 0.9f) continue
            if (Noise.unit(i, seed, 71 + extra * 40) > 0.55f + 0.4f * f) continue
            val x = (worldX - offset).toInt()
            if (Noise.unit(i, seed, 76 + extra) < s.hardOnScreen(x) * 1.2f) continue
            val h = Noise.ridge(worldX.toFloat() * freq, seed, 3)
            // They stand on the hill as drawn: lower where it sinks toward the city or the sea.
            val y = (base - h * amp * (1f - 0.85f * maxOf(s.cityAt(worldX.toDouble(), factor.toDouble()), s.beachOnScreen(x)))).toInt() + 3
            val firs = 0.5f + 0.45f * f
            sprite(if (Noise.unit(i, seed, 73 + extra) < firs) (if (f > 0.5f && Noise.unit(i, seed, 75) < 0.4f) Sprites.pine else Sprites.pineSmall) else Sprites.roundSmallYear.at(s.season, worldX * 3 + extra), x, y)
        }
        spritePaint.colorFilter = null
    }

    private fun drawField(s: SceneState) {
        // The near field: flat ground between the hills and the road, in stripes of crops and meadow.
        val grass = lit(s, s.season.fieldA, 0.10f + s.look.haze * 0.3f)
        val dark = lit(s, s.season.fieldB, 0.10f + s.look.haze * 0.3f)
        fill(0, FIELD_TOP, W, ROAD_TOP, grass)
        var y = FIELD_TOP + 2
        var k = 0
        while (y < ROAD_TOP) {
            fill(0, y, W, y + 1, dark)
            y += 3 + k % 2
            k++
        }
        // Under trees the ground is darker, with fallen leaves and needles instead of crops.
        val floorColor = lit(s, rgb(58, 82, 48), 0.1f + s.look.haze * 0.3f)
        val litter = lit(s, rgb(84, 70, 46), 0.1f + s.look.haze * 0.3f)
        for (x in 0 until W) {
            val f = s.forestAt(x + floor(s.cam), 1.0)
            if (f <= 0.01f) continue
            fillAlpha(x, FIELD_TOP, x + 1, ROAD_TOP, floorColor, f * 0.85f)
            if (Noise.unit(Math.floorMod(x + floor(s.cam).toInt(), 7), 611) < 0.3f) fillAlpha(x, FIELD_TOP + 3 + (Noise.unit(x + floor(s.cam).toInt(), 612) * 14).toInt(), x + 2, FIELD_TOP + 4 + (Noise.unit(x + floor(s.cam).toInt(), 612) * 14).toInt(), litter, f * 0.7f)
        }
    }

    // ---------- the near field: trees, buildings, animals ----------

    private fun drawFarm(s: SceneState, cam: Double) {
        val factor = 0.5
        val offset = cam * factor
        spritePaint.colorFilter = filter(s, 0.06f + s.look.haze * 0.3f)
        val chunk = 170
        val first = floor(offset / chunk).toInt() - 1
        val sway = (min(1f, s.cond.wind / 30f))
        // Two passes so the nearer things (lower on the screen) cover the farther.
        for (pass in 0..1) for (i in first..first + W / chunk + 2) {
            val left = (i * chunk - offset).toInt()
            // Some stretches are open country: a few trees, and no buildings or grazing herds.
            val open = openCountry(Math.floorDiv(i, 6), 90)
            // How much of this stretch is forest: it brings more trees, and takes the farms away.
            val forest = s.forestAt(i * chunk + chunk / 2.0, factor)
            val city = s.builtOnScreen(left + chunk / 2)
            // Trees: 3 to 6 a chunk, at their own depths (fewer in open country), and in forest up to eleven more, each there once the forest is thick enough for it.
            // A log cabin in a clearing, very rarely, deep in the forest.
            val cabin = forest > 0.6f && Noise.unit(i, 700) < 0.08f
            val cabinX = left + 40 + (Noise.unit(i, 701) * 70).toInt()
            val farmTrees = if (open) 1 + (Noise.unit(i, 1) * 2).toInt() else 3 + (Noise.unit(i, 1) * 4).toInt()
            val trees = if (forest > 0f) farmTrees + 20 else farmTrees
            for (t in 0 until trees) {
                val wood = t >= farmTrees
                if (wood && Noise.unit(i, 400 + t) > forest * 1.1f) continue
                val depth = Noise.unit(i, if (t < 7) 10 + t else 300 + t)
                if ((depth > 0.5f) != (pass == 1)) continue
                val x = left + (Noise.unit(i, if (t < 7) 20 + t else 320 + t) * chunk).toInt()
                // Judged where the tree itself stands, not the middle of the stretch it belongs to, so none is left on the sand or the water.
                val hard = s.builtOnScreen(x)
                if (hard > 0.5f || !wood && Noise.unit(i, 500 + t) < hard * 1.3f) continue
                val y = FIELD_TOP + 6 + (depth * 12f).toInt()
                if (cabin && kotlin.math.abs(x - cabinX - 10) < 30) continue
                if (wood && s.riverCovers(x, y, 8)) continue
                val kind = Noise.unit(i, if (t < 7) 30 + t else 340 + t)
                val swing = (sin(s.time * 1.7 + x * 0.1) * sway * 1.1).roundToInt()
                when {
                    wood && kind < 0.5f -> sprite(Sprites.firs[(Noise.unit(i, 360 + t) * Sprites.firs.size).toInt().coerceIn(0, Sprites.firs.size - 1)], x + swing, y)
                    wood && kind < 0.85f -> sprite(Sprites.broadsYear[(Noise.unit(i, 360 + t) * Sprites.broads.size).toInt().coerceIn(0, Sprites.broads.size - 1)].at(s.season, i * 37 + t), x + swing, y)
                    wood -> sprite(Sprites.bushYear.at(s.season, i * 37 + t), x, y)
                    kind < 0.4f -> sprite(Sprites.pine, x + swing, y)
                    kind < 0.8f -> sprite(Sprites.roundTreeYear.at(s.season, i * 37 + t), x + swing, y)
                    else -> sprite(Sprites.bushYear.at(s.season, i * 37 + t), x, y)
                }
            }
            if (pass == 1 && cabin) {
                sprite(Sprites.cabin, cabinX, FIELD_TOP + 14)
                spritePaint.colorFilter = null
                litWindows(s, cabinX, FIELD_TOP + 14 - Sprites.cabin.height, Sprites.cabinWindows)
                smoke(s, cabinX + 15, FIELD_TOP + 14 - Sprites.cabin.height)
                spritePaint.colorFilter = filter(s, 0.06f + s.look.haze * 0.3f)
            }
            if (pass == 1 && !open && forest < 0.3f && city < 0.2f) {
                // A building now and then, with its windows lit at night and smoke from the chimney.
                val b = Noise.unit(i, 50)
                val bx = left + 40 + (Noise.unit(i, 51) * 70).toInt()
                if (b < 0.22f) {
                    sprite(Sprites.barn, bx, FIELD_TOP + 14)
                } else if (b < 0.42f) {
                    sprite(Sprites.house, bx, FIELD_TOP + 14)
                    spritePaint.colorFilter = null
                    litWindows(s, bx, FIELD_TOP + 14 - Sprites.house.height, Sprites.houseWindows)
                    smoke(s, bx + 7, FIELD_TOP + 14 - Sprites.house.height)
                    spritePaint.colorFilter = filter(s, 0.06f + s.look.haze * 0.3f)
                } else if (b < 0.30f + 0.04f) {
                    windmill(s, bx, FIELD_TOP + 12)
                }
                // Animals grazing in a group.
                if (Noise.unit(i, 60) < 0.5f) {
                    val kind = animals[(Noise.unit(i, 61) * animals.size).toInt().coerceIn(0, animals.size - 1)]
                    val n = 2 + (Noise.unit(i, 62) * 3).toInt()
                    val ax = left + 10 + (Noise.unit(i, 63) * 120).toInt()
                    for (a in 0 until n) sprite(kind, ax + a * (kind.width + 5), FIELD_TOP + 18 + (Noise.unit(i, 64 + a) * 4).toInt(), Noise.unit(i, 70 + a) < 0.5f)
                }
            }
        }
        spritePaint.colorFilter = null
    }

    /** Whether this stretch of land is open country, with no farm or fence: about two in five are. */
    private fun openCountry(block: Int, key: Int) = Noise.unit(block, key) < 0.4f

    private fun Double.roundToInt() = Math.round(this).toInt()

    private fun litWindows(s: SceneState, x: Int, y: Int, windows: List<IntArray>) {
        val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
        if (night <= 0f) return
        for (w in windows) fillAlpha(x + w[0], y + w[1], x + w[0] + w[2], y + w[1] + w[3], rgb(255, 214, 120), night)
    }

    private fun smoke(s: SceneState, x: Int, y: Int) {
        val dir = s.windDir
        val sideways = min(1f, s.cond.wind / 20f) * 1.5f + 0.3f
        for (k in 0 until 7) {
            val age = (((s.time * 0.22) + k / 7.0) % 1.0).toFloat()
            val px = x + (dir * sideways * age * 22f + sin(s.time + k) * 1.2).toInt()
            val py = y - (age * 22f).toInt()
            val size = 1 + (age * 2.4f).toInt()
            fillAlpha(px, py, px + size, py + size, lit(s, rgb(206, 206, 210), 0.1f), (1f - age) * 0.65f)
        }
    }

    private fun windmill(s: SceneState, x: Int, bottom: Int) {
        val tower = lit(s, rgb(150, 150, 156), 0.1f)
        // The tower, wider at the foot.
        for (r in 0 until 22) {
            val half = 1 + (22 - r) / 9
            fill(x - half, bottom - 22 + r, x + half + 1, bottom - 21 + r, tower)
        }
        val hubX = x.toFloat() + 0.5f
        val hubY = (bottom - 24).toFloat()
        val angle = s.time * (0.25 + s.cond.wind * 0.045)
        val blade = lit(s, rgb(236, 236, 240), 0.08f)
        for (b in 0 until 4) {
            val a = angle + b * Math.PI / 2
            val ex = hubX + (cos(a) * 12).toFloat()
            val ey = hubY + (sin(a) * 12).toFloat()
            line(hubX, hubY, ex, ey, blade)
        }
        fill(x - 1, bottom - 25, x + 2, bottom - 22, lit(s, rgb(90, 90, 100), 0.1f))
    }

    private fun drawFence(s: SceneState, cam: Double) {
        val offset = cam * 1.0
        val post = lit(s, rgb(120, 86, 56), 0.02f)
        val rail = lit(s, rgb(150, 112, 76), 0.02f)
        val y = ROAD_TOP - 1
        val first = floor(offset / 160).toInt() - 1
        for (i in first..first + W / 160 + 2) {
            if (Noise.unit(i, 80) < 0.28f || openCountry(Math.floorDiv(i, 6), 91) || s.forestAt(i * 160 + 80.0, 1.0) > 0.3f || s.builtOnScreen((i * 160 - offset).toInt() + 80) > 0.2f) continue
            val x0 = (i * 160 - offset).toInt()
            val len = 90 + (Noise.unit(i, 81) * 60).toInt()
            fill(x0, y - 4, x0 + len, y - 3, rail)
            fill(x0, y - 7, x0 + len, y - 6, rail)
            var px = x0
            while (px <= x0 + len) {
                fill(px, y - 9, px + 1, y, post)
                px += 14
            }
        }
    }

    // ---------- the road ----------

    private fun drawRoad(s: SceneState, cam: Double) {
        val asphalt = lit(s, rgb(74, 78, 90), 0.02f, 0.25f)
        val shoulder = lit(s, rgb(150, 138, 112), 0.02f, 0.8f)
        val line = lit(s, rgb(236, 232, 214), 0.02f, 0.1f)
        fill(0, ROAD_TOP, W, ROAD_TOP + 1, shoulder)
        fill(0, ROAD_TOP + 1, W, ROAD_BOTTOM, asphalt)
        fill(0, ROAD_BOTTOM, W, ROAD_BOTTOM + 2, shoulder)
        fill(0, ROAD_TOP + 2, W, ROAD_TOP + 3, line)
        fill(0, ROAD_BOTTOM - 2, W, ROAD_BOTTOM - 1, line)
        // The dashes down the middle move with the journey.
        val mid = (ROAD_TOP + ROAD_BOTTOM) / 2
        val shift = Math.floorMod(cam.toInt(), 20)
        var x = -shift
        while (x < W) {
            fill(x, mid, x + 10, mid + 1, line)
            x += 20
        }
        // Below the road: the verge in front.
        fill(0, ROAD_BOTTOM + 2, W, H, lit(s, rgb(70, 128, 58), 0.0f))
        fill(0, ROAD_BOTTOM + 2, W, ROAD_BOTTOM + 3, lit(s, rgb(104, 160, 76), 0.0f))
        if (s.cond.rain > 0.3f) fillAlpha(0, ROAD_TOP + 1, W, ROAD_BOTTOM, rgb(150, 170, 200), 0.10f * s.cond.rain)
    }

    private fun drawTraffic(s: SceneState) {
        val travelV = if (s.travelling) s.speed else 0f
        spritePaint.colorFilter = filter(s, 0.0f, snowy = false)
        val period = 3.5
        val slot = floor(s.time / period).toInt()
        val night = s.look.day < 0.45f
        // Two passes, far lane first: the cars coming toward us (facing left) are in the upper lane and are drawn behind the ones
        // going our way (facing right) in the lane nearer the front, whatever order they set off in.
        // A car stays for as long as it takes to cross, so the look-back covers the slowest (MIN_SCREEN_SPEED) with room to spare;
        // a shorter one would drop a slow car in the middle of the road.
        // A second stream of cars, only in the city, makes it busy: each is faded in as the city comes (so none pops out of nowhere).
        // Cars in our lane go the way we do, so they linger on the screen; the ones coming toward us rush past. To show about as many
        // of each, the oncoming lane gets more cars in proportion (in steps, so the traffic does not reshuffle as the speed changes).
        val ourMean = (0..4).map { kotlin.math.max(kotlin.math.abs(14f + 46f * it / 4f - travelV), MIN_SCREEN_SPEED) }.average().toFloat()
        val ratio = Math.round(((55f + travelV) / ourMean).coerceIn(1f, 2f) * 2f) / 2f
        for (stream in 0..1) for (pass in 0..1) {
          val ourLane = pass == 1
          val laneSlot = if (ourLane) period else period / ratio
          val laneNow = floor(s.time / laneSlot).toInt()
          val lookBack = kotlin.math.ceil((if (ourLane) 49.0 else 20.0) / laneSlot).toInt()
          for (j in laneNow - lookBack..laneNow) {
            val k = j + stream * 9973 + pass * 5003
            // Each lane takes half of what the slot would give, so the lanes add up to the same traffic as before.
            val roll = Noise.unit(k, 101) * 2f
            val spawn = j * laneSlot + Noise.unit(k, 102) * laneSlot
            val e = (s.time - spawn).toFloat()
            if (e < 0f) continue
            // How much city there was where the car came in decides it for its whole drive: it is never half there, and doesn't
            // pop in or out as the city comes and goes.
            val where = s.cam - travelV.toDouble() * e + W / 2.0
            val cityThen = s.cityAt(where, 1.0)
            // The suburbs are busier than the country but not as busy as the city.
            val suburbThen = s.suburbAt(where, 1.0)
            val townThen = maxOf(cityThen, 0.55f * suburbThen)
            val cityCar = stream == 1 || roll > 0.30f
            if (stream == 1) {
                if (townThen < 0.05f || roll > 0.7f * townThen) continue
            } else if (roll > 0.30f + 0.4f * townThen || townThen < 0.05f && cityCar) continue
            spritePaint.alpha = 255
            // Seen from the road: our own lane's cars go the way we do (slower or faster than us), the other lane's come toward us.
            val speed = if (ourLane) 14f + Noise.unit(k, 104) * 46f else 30f + Noise.unit(k, 104) * 50f
            val raw = if (ourLane) speed - travelV else -speed - travelV
            // Never nearly still on the screen (a car going our speed would sit there for ever), so it always crosses in time.
            val vScreen = if (kotlin.math.abs(raw) < MIN_SCREEN_SPEED) (if (raw < 0f) -MIN_SCREEN_SPEED else MIN_SCREEN_SPEED) else raw
            val truck = Noise.unit(k, 105) < 0.22f
            // In the city some of the extra vehicles are taxis and buses.
            val kind = Noise.unit(k, 107)
            // In the suburbs, by day, an occasional school bus.
            val school = cityCar && suburbThen > 0.5f && cityThen < 0.2f && s.look.day > 0.4f && kind < 0.12f
            val suburbRoll = cityCar && suburbThen > 0.35f && cityThen < 0.35f
            val sp = if (school) schoolBus else if (suburbRoll && kind >= 0.12f && kind < 0.18f) ambulance else if (suburbRoll && kind >= 0.18f && kind < 0.24f) fireEngine else if (cityCar && cityThen > 0.35f && kind < 0.22f) taxi else if (cityCar && cityThen > 0.35f && kind < 0.30f) police else if (cityCar && cityThen > 0.35f && kind < 0.42f) buses[(Noise.unit(k, 108) * buses.size).toInt().coerceIn(0, buses.size - 1)] else if (truck) trucks[(Noise.unit(k, 106) * trucks.size).toInt().coerceIn(0, trucks.size - 1)] else cars[(Noise.unit(k, 106) * cars.size).toInt().coerceIn(0, cars.size - 1)]
            val x = if (vScreen < 0) W + 30 + vScreen * e else -sp.width - 30 + vScreen * e
            val movingRight = ourLane
            val y = if (ourLane) ROAD_BOTTOM - 2 else ROAD_TOP + 9
            sprite(sp, x.toInt(), y, !movingRight)
            if (sp === police || sp === ambulance || sp === fireEngine) {
                // The lights flash red and blue, a little out of step from one vehicle to the next.
                val flip = ((s.time * 4.0).toInt() + k) % 2 == 0
                val c0 = if (sp === police) 5 else if (sp === ambulance) 12 else 20
                val bx = x.toInt() + (if (movingRight) c0 else sp.width - c0 - 4)
                fill(bx, y - 8, bx + 2, y - 7, if (flip) rgb(255, 50, 50) else rgb(60, 80, 255))
                fill(bx + 2, y - 8, bx + 4, y - 7, if (flip) rgb(60, 80, 255) else rgb(255, 50, 50))
                if (night || s.look.day < 0.7f) fillAlpha(bx - 4, y - 11, bx + 8, y - 6, if (flip) rgb(255, 60, 60) else rgb(70, 90, 255), 0.18f)
            }
            if (night) {
                val front = if (movingRight) x.toInt() + sp.width else x.toInt() - 14
                val tail = if (movingRight) x.toInt() - 1 else x.toInt() + sp.width
                fillAlpha(front, y - 4, front + 14, y - 2, rgb(255, 240, 170), 0.35f)
                fill(tail, y - 4, tail + 1, y - 3, rgb(230, 50, 40))
            }
          }
        }
        spritePaint.alpha = 255
        spritePaint.colorFilter = null
    }

    // ---------- birds ----------

    private fun drawBirds(s: SceneState) {
        val l = s.look
        val calm = (1f - s.cond.rain - s.cond.snow - s.cond.fog * 1.3f - s.cond.thunder).coerceIn(0f, 1f)
        val daylight = Colors.smoothstep(-2f, 8f, s.sunAlt.toFloat())
        if (calm * daylight < 0.1f) return
        spritePaint.colorFilter = filter(s, 0.12f)
        val slotLen = 14.0
        val slot = floor(s.time / slotLen).toInt()
        for (k in slot - 2..slot) {
            if (Noise.unit(k, 111) > 0.55f * calm * daylight) continue
            val start = k * slotLen + Noise.unit(k, 112) * slotLen
            val speed = 16f + Noise.unit(k, 113) * 14f
            val right = Noise.unit(k, 114) < 0.5f
            val y0 = 18 + (Noise.unit(k, 115) * 44).toInt()
            val n = 3 + (Noise.unit(k, 116) * 4).toInt()
            val e = (s.time - start).toFloat()
            if (e < 0f) continue
            val lead = if (right) -20f + speed * e else W + 20f - speed * e
            for (b in 0 until n) {
                val back = ((b + 1) / 2) * 7
                val side = if (b % 2 == 0) 1 else -1
                val x = lead + (if (right) -back else back)
                val y = y0 + side * ((b + 1) / 2) * 2 + (sin(s.time * 1.3 + k).toFloat() * 2).toInt()
                val flap = (floor(s.time * 5.0 + b * 0.37).toInt() % 2) == 0
                sprite(if (flap) Sprites.birdUp else Sprites.birdDown, x.toInt(), y)
            }
        }
        spritePaint.colorFilter = null
    }

    // ---------- the foreground ----------

    /** Whether the power line runs from pole [i] to the next: in stretches of four poles, three in five of them. */
    private fun powerSpan(i: Int) = Noise.unit(Math.floorDiv(i, 4), 97) < 0.6f

    private fun drawForeground(s: SceneState, cam: Double) {
        val offset = cam * 1.7
        val wood = solid(s, rgb(86, 62, 44), 0.0f)
        val wire = solid(s, rgb(40, 40, 50), 0.0f)
        val spacing = 520
        val first = floor(offset / spacing).toInt() - 1
        // Poles with wires between them.
        for (i in first..first + W / spacing + 2) {
            val x = (i * spacing - offset).toInt()
            // A pole off the left edge still has wires running on to the next one, so keep it until the whole span is gone.
            if (x + spacing < -10 || x > W + 10) continue
            // The line runs in stretches of a few spans with none between: a pole stands wherever a span starts or ends.
            val wired = powerSpan(i)
            if (!wired && !powerSpan(i - 1)) continue
            // City streets and the seafront have their own lamps; a country pole and its wires would cut across the skyline,
            // the sea and the road.
            val a = i * spacing.toDouble()
            val b = (i + 1) * spacing.toDouble()
            if (s.cityAt(a, 1.7) > 0.3f || s.cityAt(b, 1.7) > 0.3f || s.beachAt(a, 1.7) > 0.3f || s.beachAt(b, 1.7) > 0.3f) continue
            fill(x, 112, x + 3, H, wood)
            fill(x - 6, 116, x + 9, 118, wood)
            fill(x - 4, 124, x + 7, 125, wood)
            if (!wired) continue
            val nx = x + spacing
            for (w in intArrayOf(116, 124)) {
                var px = x + 4
                while (px < nx) {
                    val t = (px - x).toFloat() / spacing
                    val sag = (sin(t * Math.PI) * 14f).toInt()
                    if (px in 0 until W) fill(px, w + sag, px + 1, w + sag + 1, wire)
                    px++
                }
            }
        }
        // Tufts of grass along the bottom.
        val grassDark = lit(s, Colors.mul(rgb(54, 108, 46), s.season.hillR, s.season.hillG, s.season.hillB), 0.0f)
        val grassLight = lit(s, Colors.mul(rgb(110, 172, 80), s.season.hillR, s.season.hillG, s.season.hillB), 0.0f)
        val tuftOffset = cam * 2.0
        val firstTuft = floor(tuftOffset / 13).toInt() - 1
        for (i in firstTuft..firstTuft + W / 13 + 2) {
            val x = (i * 13 + Noise.unit(i, 90) * 9 - tuftOffset).toInt()
            if (s.riverCovers(x, H - 3, 2) || s.builtOnScreen(x + 6) > 0.3f) continue
            val h = 2 + (Noise.unit(i, 91) * 4).toInt()
            val sway = (sin(s.time * 2.0 + i) * min(1f, s.cond.wind / 25f)).roundToInt()
            fill(x + sway, H - h - 1, x + 1 + sway, H - 1, grassDark)
            fill(x + 1, H - h, x + 2, H - 1, grassLight)
            fill(x - 1, H - h + 1, x, H - 1, grassLight)
        }
        fill(0, H - 1, W, H, grassDark)
    }

    // ---------- weather over everything ----------

    private fun drawRain(s: SceneState) {
        val rain = s.cond.rain
        if (rain < 0.03f) return
        val l = s.look
        val n = (rain * 340 * particleScale).toInt()
        val slant = s.windDir * min(1f, s.cond.wind / 30f) * 0.55f
        val color = lerp(rgb(110, 124, 156), rgb(190, 206, 232), l.day)
        for (i in 0 until n) {
            val u1 = Noise.unit(i, 11)
            val u2 = Noise.unit(i, 12)
            val far = u1 < 0.55f
            val speed = if (far) 150f else 230f
            val len = if (far) 5 else 9
            val y = ((u2 * H + speed * s.time) % (H + 10)).toFloat() - 5f
            val x = ((Noise.unit(i, 13) * (W + 60) + slant * speed * s.time) % (W + 60)).toFloat() - 30f + y * slant * 0.5f
            line(x, y, x + slant * len, y + len, color, if (far) 140 else 210)
        }
        // Splashes on the road.
        val bucket = floor(s.time * 8).toInt()
        for (i in 0 until (rain * 18).toInt()) {
            val x = (Noise.unit(bucket, i, 14) * W).toInt()
            val y = ROAD_TOP + 3 + (Noise.unit(bucket, i, 15) * (ROAD_BOTTOM - ROAD_TOP - 4)).toInt()
            fillAlpha(x, y, x + 2, y + 1, color, 0.6f)
        }
    }

    private fun drawSnow(s: SceneState) {
        val snow = s.cond.snow
        if (snow < 0.03f) return
        val n = (snow * 230 * particleScale).toInt()
        val drift = s.windDir * min(1f, s.cond.wind / 30f) * 22f
        val bright = 0.55f + 0.45f * s.look.day
        val color = Colors.scale(rgb(255, 255, 255), bright)
        for (i in 0 until n) {
            val u1 = Noise.unit(i, 21)
            val u2 = Noise.unit(i, 22)
            val fall = 14f + u1 * 20f
            val y = ((u2 * H + fall * s.time) % H).toFloat()
            val x = ((Noise.unit(i, 23) * (W + 80) + drift * s.time * (0.5f + u1)) % (W + 80)).toFloat() - 40f + (sin(s.time * (0.8 + u1) + i) * 5f).toFloat()
            val size = if (u1 > 0.7f) 2 else 1
            fillAlpha(x.toInt(), y.toInt(), x.toInt() + size, y.toInt() + size, color, 0.55f + 0.45f * u1)
        }
    }

    /**
     * Thick fog closes in from the distance: the sky, mountains, hills, towers and sea are gone altogether, and the land behind the
     * road is lost a little more with every step back from it, so only what is nearest stays clear.
     */
    private fun drawDepthFog(s: SceneState) {
        val fog = s.cond.fog
        if (fog < 0.05f) return
        val color = s.look.fogColor
        val far = (fog * 1.2f).coerceAtMost(1f)
        fillAlpha(0, 0, W, FIELD_TOP + 2, color, far)
        var y = FIELD_TOP + 2
        while (y < ROAD_TOP) {
            val t = (y - FIELD_TOP) / (ROAD_TOP - FIELD_TOP).toFloat()
            fillAlpha(0, y, W, minOf(y + 2, ROAD_TOP), color, fog * (1f - 0.6f * t) * 0.95f)
            y += 2
        }
    }

    private fun drawFog(s: SceneState) {
        val fog = s.cond.fog
        if (fog < 0.03f) return
        val color = s.look.fogColor
        // Banks of mist along the ground that thin and thicken as they drift past.
        for (i in 0 until 7) {
            val y = HORIZON + 8 + i * 9
            val wave = 0.75f + 0.25f * sin(s.time * 0.25 + i * 1.7).toFloat()
            fillAlpha(0, y, W, y + 9, color, fog * 0.22f * wave)
        }
        fillAlpha(0, 0, W, H, color, fog * 0.10f)
    }

    private fun drawLightning(s: SceneState) {
        fillAlpha(0, 0, W, H, rgb(255, 255, 255), s.flash * 0.28f)
        // The bolt, jagged, for the strong first flash.
        val slot = floor(s.time / 7.0).toInt()
        val e = s.time - (slot * 7.0 + Noise.unit(slot, 92) * 6.0)
        if (s.flash > 0.6f && e in 0.0..0.15) {
            var x = 30f + Noise.unit(slot, 93) * 260f
            var y = 30f
            val bolt = rgb(250, 250, 255)
            var step = 0
            while (y < ROAD_TOP - 4) {
                val nx = x + (Noise.unit(slot, 94, step) - 0.5f) * 14f
                val ny = y + 7f + Noise.unit(slot, 95, step) * 5f
                line(x, y, nx, ny, bolt)
                x = nx; y = ny; step++
            }
        }
    }

    // ---------- the old-computer palette ----------

    private var lut = IntArray(0)
    private var lutLevels = 0
    private var lutDither = false

    /** For each of the 16 dither thresholds and each colour value, the value it becomes: the limited palette as a table, so drawing it is lookups. */
    private fun buildLut() {
        val n = (levels - 1).coerceAtLeast(1)
        val step = 255 / n
        lut = IntArray(16 * 256)
        for (t in 0 until 16) {
            val off = if (dither) ((bayer[t] - 8) * step) / 36 else 0
            for (v in 0 until 256) lut[(t shl 8) + v] = quant(v + off, n)
        }
        lutLevels = levels
        lutDither = dither
    }

    /** Dithers the picture and limits it to [levels] steps per colour, in place. */
    private fun finish() {
        if (lutLevels != levels || lutDither != dither || lut.isEmpty()) buildLut()
        val pixels = fb
        val table = lut
        for (y in 0 until H) {
            val row = (y and 3) * 4
            var i = y * W
            for (x in 0 until W) {
                val c = pixels[i]
                val base = (row + (x and 3)) shl 8
                pixels[i] = (0xFF shl 24) or (table[base + ((c shr 16) and 0xFF)] shl 16) or (table[base + ((c shr 8) and 0xFF)] shl 8) or table[base + (c and 0xFF)]
                i++
            }
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H)
    }

    private fun quant(v: Int, n: Int): Int {
        val c = if (v < 0) 0 else if (v > 255) 255 else v
        return ((c * n + 127) / 255) * 255 / n
    }

    companion object {
        private const val OPAQUE = 0xFF shl 24
        /** How much more cover than a cloud needs makes it fully there (it is faded in between). */
        private const val FADE_RANGE = 0.14f
        const val W = 320
        const val H = 180
        /** Where the distant land meets the sky. */
        const val HORIZON = 112
        const val FIELD_TOP = 132
        /** The slowest a car moves across the screen, in pixels a second. */
        const val MIN_SCREEN_SPEED = 12f
        const val ROAD_TOP = 154
        const val ROAD_BOTTOM = 168
    }
}

/** A light and haze for sprites: each colour channel is multiplied by [mul] (out of 255) and then [add] is added. */
class Tint(val mul: Int, val add: Int)

/** What sprites are drawn with: how opaque, and the light and haze they are given. */
class SpritePaint {
    var alpha = 255
    var colorFilter: Tint? = null
}
