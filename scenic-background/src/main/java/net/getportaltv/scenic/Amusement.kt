package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The amusement park: a big car park running in from either side (so the suburbs give way to tarmac and parked cars), and in the
 * middle of it a gate, a merry-go-round, a Ferris wheel, a roller coaster with a lift hill, a loop and a couple of humps, a drop
 * tower, a swing ride and some stalls, with visitors strolling among them.
 */

private const val LAYER = 0.5
private val STRETCH_LAYER = (Places.STRETCH * LAYER).toInt()
private const val BASE = FIELD_TOP + 9
private const val ROW_A = FIELD_TOP + 15
private const val ROW_B = ROAD_TOP - 2

// Where each ride stands along its stretch (layer pixels from the stretch's start), and how wide it is.
private const val GATE_X = 700
private const val GATE_W = 64
private const val CAROUSEL_X = 840
private const val FERRIS_X = 940
private const val COASTER_X = 1040
private const val TOWER_X = 1420
private const val SWING_X = 1486
private val STALLS = intArrayOf(782, 808, 904, 1010, 1388, 1452, 1466)
private const val PARK_FROM = 640
private const val PARK_TO = 1580

private fun SceneState.dark() = (1f - look.day * 1.6f).coerceIn(0f, 1f)

/** How open the park is: the rides stop turning in a storm or a gale. */
private fun SceneState.running(): Float = 1f - smoothstep(0.3f, 0.65f, maxOf(cond.rain, cond.thunder, cond.snow, cond.wind / 45f))

private val HALLOWEEN_LIGHTS = intArrayOf(rgb(255, 140, 30), rgb(170, 80, 230))
private val XMAS_LIGHTS = intArrayOf(rgb(235, 60, 60), rgb(70, 200, 90), rgb(250, 250, 240), rgb(250, 210, 70))

/** The colour of light number [k] on the rides: orange and purple at Halloween, red, green and white at Christmas, otherwise every colour. */
private fun SceneState.lightPal(k: Int): Int =
    if (season.halloween > 0.3f) HALLOWEEN_LIGHTS[k % 2]
    else if (season.festive > 0.3f) XMAS_LIGHTS[k % XMAS_LIGHTS.size]
    else GONDOLAS[k % GONDOLAS.size]

private fun SceneState.parkStretch(k: Int): Boolean = placeMode == Places.AMUSEMENT || (placeMode == Places.JOURNEY && Places.placeOf(k) == Places.AMUSEMENT)

/** The tarmac of the car park under everything, in lines of bays, wherever the park reaches (more of it the nearer the middle). */
internal fun PixelScene.drawParkGround(s: SceneState) {
    if (!s.anyPark) return
    val tarmac = lit(s, rgb(86, 88, 96), 0.04f)
    val line = lit(s, rgb(214, 214, 206), 0.04f)
    val kerb = lit(s, rgb(206, 204, 196), 0.04f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.parkOnScreen(x) * (1f - maxOf(s.suburbOnScreen(x), s.suburbAt((floor(s.cam * LAYER) + x), LAYER)))
        if (a <= 0.01f) continue
        fillAlpha(x, BASE - 1, x + 1, ROAD_TOP - 1, tarmac, a)
        fillAlpha(x, ROAD_TOP - 1, x + 1, ROAD_TOP, kerb, a)
        val inRides = Math.floorMod(x + cam, Places.STRETCH.toInt()) in (2 * PARK_FROM)..(2 * PARK_TO)
        if (a > 0.5f && !inRides && Math.floorMod(x + cam, 16) == 0) {
            fillAlpha(x, ROW_A - 8, x + 1, ROW_A + 1, line, 0.7f)
            fillAlpha(x, ROW_B - 7, x + 1, ROW_B + 1, line, 0.7f)
        }
    }
}

/** The rides, stalls and gate in the middle of every amusement park stretch. */
internal fun PixelScene.drawParkRides(s: SceneState) {
    if (!s.anyPark) return
    if (s.placeMode != Places.JOURNEY && s.placeMode != Places.AMUSEMENT) return
    val dt = (s.time - s.rideLast).coerceIn(0.0, 0.1)
    s.rideLast = s.time
    s.rideTime += dt * s.running()
    val offset = floor(s.cam * LAYER).toInt()
    val first = Math.floorDiv(offset + PARK_FROM, STRETCH_LAYER)
    val last = Math.floorDiv(offset + W + PARK_TO, STRETCH_LAYER)
    for (k in first..last) {
        if (!s.parkStretch(k)) continue
        val left = k * STRETCH_LAYER - offset
        if (left + PARK_TO < -10 || left + PARK_FROM > W + 10) continue
        spritePaint.colorFilter = filter(s, 0.04f + s.look.haze * 0.3f)
        for ((i, x) in STALLS.withIndex()) stall(s, left + x, i)
        gate(s, left + GATE_X)
        carousel(s, left + CAROUSEL_X)
        ferris(s, left + FERRIS_X)
        coaster(s, left + COASTER_X)
        dropTower(s, left + TOWER_X)
        swings(s, left + SWING_X)
        spritePaint.colorFilter = null
    }
}

private fun PixelScene.c(s: SceneState, color: Int) = solid(s, color, 0.04f + s.look.haze * 0.3f)

private val GONDOLAS = intArrayOf(rgb(230, 70, 60), rgb(70, 140, 230), rgb(250, 210, 70), rgb(90, 200, 120), rgb(230, 120, 200), rgb(250, 150, 50))

private fun PixelScene.stall(s: SceneState, x: Int, i: Int) {
    if (x < -20 || x > W + 4) return
    val color = GONDOLAS[i % GONDOLAS.size]
    val wood = c(s, rgb(150, 112, 78))
    fill(x, BASE - 5, x + 14, BASE, wood)
    fill(x + 1, BASE - 4, x + 13, BASE - 2, lerp(wood, rgb(20, 20, 26), 0.5f))
    // A striped awning.
    for (k in 0 until 14 step 2) {
        fill(x + k, BASE - 9, x + k + 1, BASE - 5, c(s, color))
        fill(x + k + 1, BASE - 9, x + k + 2, BASE - 5, c(s, rgb(246, 246, 240)))
    }
    fill(x - 1, BASE - 10, x + 15, BASE - 9, c(s, color))
    if (hiRes) {
        // A scalloped edge under the awning, plank lines on the counter and a shadow under the awning.
        for (k in 0 until 14 step 2) fillOval(x + k.toFloat(), BASE - 5.7f, x + k + 2f, BASE - 4.3f, c(s, if ((k / 2) % 2 == 0) color else rgb(246, 246, 240)), 1f)
        eaveShadow(x, x + 14, BASE - 4)
        sidingTexture(x, BASE - 4, x + 14, BASE)
    }
    // Balloons tied to some of them.
    if (i % 3 == 0) for (b in 0..2) {
        val bx = x + 2 + b * 4
        val by = BASE - 18 - (b % 2) * 3 + (sin(s.time * 1.2 + b + i) * 1).toInt()
        if (hiRes) {
            val bc = c(s, GONDOLAS[(i + b * 2) % GONDOLAS.size])
            fillOval(bx - 0.6f, by - 0.5f, bx + 2.6f, by + 3.2f, bc, 1f)
            fillOval(bx - 0.1f, by.toFloat(), bx + 0.7f, by + 0.9f, rgb(255, 255, 255), 0.55f)
            fill(bx + 1, by + 3, bx + 2, BASE - 10, c(s, rgb(200, 200, 200)))
        } else {
            fill(bx, by, bx + 2, by + 3, c(s, GONDOLAS[(i + b * 2) % GONDOLAS.size]))
            fill(bx, by + 3, bx + 1, BASE - 10, c(s, rgb(200, 200, 200)))
        }
    }
}

private fun PixelScene.gate(s: SceneState, x: Int) {
    if (x + GATE_W < 0 || x > W) return
    val night = s.dark()
    val pillar = c(s, rgb(150, 70, 160))
    val band = c(s, rgb(236, 190, 60))
    val dark = c(s, rgb(46, 30, 60))
    // Two towers either side of the entrance with a big sign across the top.
    for (side in 0..1) {
        val px = if (side == 0) x else x + GATE_W - 12
        fill(px, BASE - 30, px + 12, BASE, pillar)
        fill(px + 1, BASE - 34, px + 11, BASE - 30, band)
        fill(px + 5, BASE - 40, px + 7, BASE - 34, band)
        fill(px + 7, BASE - 40, px + 11, BASE - 37, c(s, rgb(220, 60, 60)))
        fill(px + 3, BASE - 12, px + 9, BASE, dark)
    }
    fill(x + 6, BASE - 28, x + GATE_W - 6, BASE - 16, dark)
    fill(x + 6, BASE - 28, x + GATE_W - 6, BASE - 27, band)
    fill(x + 6, BASE - 17, x + GATE_W - 6, BASE - 16, band)
    // Some pretend lettering on the sign, and bulbs chasing round it.
    for (k in 0 until 10) fill(x + 12 + k * 4, BASE - 24, x + 14 + k * 4, BASE - 20, if (night > 0.2f) rgb(255, 230, 120) else c(s, rgb(250, 230, 160)))
    for (k in 0 until (GATE_W - 12) step 3) {
        val on = ((s.time * 3).toInt() + k / 3) % 2 == 0
        val color = if (night > 0.2f && on && s.running() > 0.3f) s.lightPal(k / 3).let { if (s.season.halloween > 0.3f || s.season.festive > 0.3f) it else rgb(255, 240, 160) } else c(s, rgb(220, 200, 150))
        fill(x + 6 + k, BASE - 28, x + 7 + k, BASE - 27, color)
        fill(x + 6 + k, BASE - 17, x + 7 + k, BASE - 16, color)
        if (hiRes && night > 0.2f && on && s.running() > 0.3f) {
            fillOval(x + 5f + k, BASE - 29.4f, x + 9f + k, BASE - 25.6f, color, 0.22f)
            fillOval(x + 5f + k, BASE - 18.4f, x + 9f + k, BASE - 14.6f, color, 0.22f)
        }
    }
    // Seasonal touches: pumpkins at Halloween, lit trees at Christmas, and snow along the sign when it lies.
    if (s.season.halloween > 0.3f) for (k in 0 until 4) pumpkin(s, x + 14 + k * 9, BASE + 3 + (k % 2), night, 0.04f)
    if (s.season.festive > 0.3f) for (side in 0..1) {
        val tx = if (side == 0) x - 14 else x + GATE_W + 3
        sprite(Sprites.pine, tx, BASE + 3)
        treeLights(s, tx, BASE + 3, Sprites.pine.width, Sprites.pine.height, 40 + side, night)
    }
    if (s.snowCover > 0.03f) {
        fillAlpha(x + 5, BASE - 29, x + GATE_W - 5, BASE - 28, rgb(240, 244, 252), s.snowCover)
        fillAlpha(x, BASE - 31, x + 12, BASE - 30, rgb(240, 244, 252), s.snowCover)
        fillAlpha(x + GATE_W - 12, BASE - 31, x + GATE_W, BASE - 30, rgb(240, 244, 252), s.snowCover)
    }
    // A walkway of paving between the towers.
    fill(x + 12, BASE, x + GATE_W - 12, BASE + 2, c(s, rgb(196, 176, 140)))
}

private fun PixelScene.carousel(s: SceneState, x: Int) {
    if (x + 50 < 0 || x > W) return
    val night = s.dark()
    val cx = x + 25
    val floorC = c(s, rgb(120, 70, 50))
    fill(x + 1, BASE - 3, x + 49, BASE, floorC)
    fill(x, BASE - 4, x + 50, BASE - 3, c(s, rgb(236, 190, 60)))
    // The back horses, then the roof's poles, then the front horses.
    for (pass in 0..1) {
        for (i in 0 until 6) {
            val a = s.rideTime * 0.8 + i * PI / 3
            val front = sin(a) > 0
            if (front != (pass == 1)) continue
            val hx = cx + (cos(a) * 19).toInt()
            val bob = (sin(s.rideTime * 2.2 + i * 1.7) * 2).toInt()
            val hy = BASE - 9 + bob
            val coat = if (i % 2 == 0) c(s, rgb(240, 236, 230)) else c(s, rgb(140, 96, 60))
            fill(hx, BASE - 4, hx + 1, hy + 1, c(s, rgb(236, 210, 120)))
            val dir = if (-sin(a) > 0) 1 else -1
            if (hiRes) {
                // A horse built from rounded pieces: a body, an arched neck and head with a mane and an eye, and four legs.
                val dark = Colors.scale(coat, 0.72f)
                fillOval(hx - 2.8f, hy - 2.6f, hx + 3.4f, hy + 0.5f, coat, 1f)
                val nx = if (dir > 0) hx + 1.6f else hx - 3.6f
                fillOval(nx, hy - 4.6f, nx + 2f, hy - 1f, coat, 1f)
                val hxh = if (dir > 0) hx + 2.2f else hx - 4.6f
                fillOval(hxh, hy - 4.9f, hxh + 2.4f, hy - 3.6f, coat, 1f)
                fill(if (dir > 0) hx + 2 else hx - 3, hy - 5, if (dir > 0) hx + 3 else hx - 2, hy - 4, dark)
                fillAlpha(hx - 2, hy - 2, hx + 3, hy - 1, rgb(255, 255, 255), 0.18f)
                fill(hx - 2, hy, hx - 1, hy + 2, dark); fill(hx + 2, hy, hx + 3, hy + 2, dark)
                fillPhys((hx - 2) * scale + 2, hy * scale + 1, (hx - 2) * scale + 4, hy * scale + 3, coat); fillPhys((hx + 2) * scale - 1, hy * scale + 1, (hx + 2) * scale + 1, hy * scale + 3, coat)
            } else {
                fill(hx - 2, hy - 2, hx + 3, hy, coat)
                fill(if (dir > 0) hx + 2 else hx - 3, hy - 4, if (dir > 0) hx + 4 else hx - 1, hy - 1, coat)
                fill(hx - 2, hy, hx - 1, hy + 2, coat)
                fill(hx + 2, hy, hx + 3, hy + 2, coat)
            }
        }
        if (pass == 0) {
            // The central pillar and the canopy: a striped cone with a scalloped edge.
            fill(cx - 1, BASE - 22, cx + 2, BASE - 4, c(s, rgb(236, 210, 120)))
        }
    }
    for (r in 0 until 10) {
        val half = 3 + r * 5 / 2
        val y = BASE - 28 + r
        for (px in cx - half until cx + half + 1) {
            val stripe = Math.floorMod(px - cx + 100, 8) < 4
            fill(px, y, px + 1, y + 1, if (stripe) c(s, rgb(220, 56, 60)) else c(s, rgb(246, 246, 240)))
        }
    }
    if (hiRes) {
        // The canopy's edge scalloped, and a shadow under it.
        val half = 3 + 9 * 5 / 2
        var sx = cx - half
        while (sx < cx + half) { fillOval(sx.toFloat(), BASE - 19.3f, sx + 4f, BASE - 17.6f, if (Math.floorMod(sx - cx + 100, 8) < 4) c(s, rgb(220, 56, 60)) else c(s, rgb(246, 246, 240)), 1f); sx += 4 }
        eaveShadow(cx - half, cx + half, BASE - 18)
    }
    if (s.snowCover > 0.03f) fillAlpha(cx - 4, BASE - 29, cx + 5, BASE - 27, rgb(240, 244, 252), s.snowCover)
    fill(cx, BASE - 33, cx + 1, BASE - 28, c(s, rgb(240, 200, 60)))
    fill(cx + 1, BASE - 33, cx + 4, BASE - 31, c(s, rgb(226, 60, 60)))
    for (k in 0 until 11) {
        val bx = cx - 26 + k * 5
        fill(bx, BASE - 18, bx + 2, BASE - 17, if (night > 0.2f && ((s.time * 2).toInt() + k) % 2 == 0) s.lightPal(k).let { if (s.season.halloween > 0.3f || s.season.festive > 0.3f) it else rgb(255, 240, 160) } else c(s, rgb(240, 200, 60)))
    }
}

private fun PixelScene.ferris(s: SceneState, x: Int) {
    val r = 24
    if (x + 2 * r + 10 < 0 || x > W) return
    val night = s.dark()
    val cx = x + 29
    val cy = BASE - r - 6
    val frame = c(s, rgb(206, 206, 214))
    val dark = c(s, rgb(90, 94, 110))
    // Two A-shaped legs.
    for (side in -1..1 step 2) {
        line((cx + side * 13).toFloat(), BASE.toFloat(), cx.toFloat(), cy.toFloat(), dark)
        line((cx + side * 13 + 1).toFloat(), BASE.toFloat(), (cx + 1).toFloat(), cy.toFloat(), dark)
    }
    val turn = s.rideTime * 0.16
    // Rim, inner ring and spokes.
    if (hiRes) {
        // The rim and inner ring on the finer grid: 300 and 220 points, so the circles are round, not stepped.
        val sc = scale
        for (k in 0 until 300) {
            val a = k * 2 * PI / 300
            val px = ((cx + cos(a) * r) * sc).toInt(); val py = ((cy + sin(a) * r) * sc).toInt()
            fillPhys(px, py, px + 2, py + 2, frame)
            if (k % 3 != 0 && k < 220) {
                val b = k * 2 * PI / 220
                val qx = ((cx + cos(b) * (r - 8)) * sc).toInt(); val qy = ((cy + sin(b) * (r - 8)) * sc).toInt()
                fillPhys(qx, qy, qx + 1 + sc / 2, qy + 1 + sc / 2, frame)
            }
        }
    } else for (k in 0 until 96) {
        val a = k * 2 * PI / 96
        fill((cx + cos(a) * r).toInt(), (cy + sin(a) * r).toInt(), (cx + cos(a) * r).toInt() + 1, (cy + sin(a) * r).toInt() + 1, frame)
        if (k % 2 == 0) fill((cx + cos(a) * (r - 8)).toInt(), (cy + sin(a) * (r - 8)).toInt(), (cx + cos(a) * (r - 8)).toInt() + 1, (cy + sin(a) * (r - 8)).toInt() + 1, frame)
    }
    for (k in 0 until 12) {
        val a = turn + k * PI / 6
        line(cx.toFloat(), cy.toFloat(), (cx + cos(a) * r).toFloat(), (cy + sin(a) * r).toFloat(), frame)
    }
    // Gondolas hanging from the rim, always upright.
    for (k in 0 until 12) {
        val a = turn + k * PI / 6
        val gx = (cx + cos(a) * r).toInt()
        val gy = (cy + sin(a) * r).toInt()
        fill(gx, gy, gx + 1, gy + 2, dark)
        fill(gx - 2, gy + 2, gx + 3, gy + 5, c(s, GONDOLAS[k % GONDOLAS.size]))
        fill(gx - 2, gy + 2, gx + 3, gy + 3, c(s, rgb(250, 250, 244)))
        if (night > 0.2f) fillAlpha(gx - 3, gy + 1, gx + 4, gy + 6, rgb(255, 230, 150), night * 0.18f)
    }
    if (hiRes) { fillOval(cx - 2.4f, cy - 2.4f, cx + 3.4f, cy + 3.4f, dark, 1f); fillOval(cx - 1f, cy - 1f, cx + 2f, cy + 2f, frame, 1f) } else fill(cx - 2, cy - 2, cx + 3, cy + 3, dark)
    // At night the rim is a ring of coloured lights, chasing round.
    if (night > 0.15f) for (k in 0 until 48) {
        val a = k * 2 * PI / 48
        val on = ((s.rideTime * 4).toInt() + k) % 3 == 0
        val color = if (on) s.lightPal(k) else rgb(90, 80, 70)
        fill((cx + cos(a) * (r + 1)).toInt(), (cy + sin(a) * (r + 1)).toInt(), (cx + cos(a) * (r + 1)).toInt() + 1, (cy + sin(a) * (r + 1)).toInt() + 1, lerp(rgb(40, 40, 50), color, night))
    }
}

/** The shape of the roller coaster's track, worked out once: points one pixel of track apart, as a height above the ground, and how long a train takes to reach each. */
private object CoasterTrack {
    const val WIDTH = 345
    val xs: FloatArray
    val hs: FloatArray
    /** Seconds from the start of the track to each point, for a train running on its own weight after the lift. */
    val times: FloatArray
    val liftEnd: Int

    init {
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        fun add(x: Float, h: Float) { px.add(x); py.add(h) }
        // The station, the lift hill (straight up, slowly), and then the long drop.
        var x = 0f
        while (x < 14f) { add(x, 2f); x += 0.5f }
        while (x < 92f) { add(x, 2f + 48f * (x - 14f) / 78f); x += 0.5f }
        val liftPoints = px.size
        while (x < 140f) { add(x, 3f + 47f * 0.5f * (1f + cos(PI.toFloat() * (x - 92f) / 48f)).toFloat()); x += 0.5f }
        // The vertical loop: a teardrop, round at the top.
        val r = 14f
        val a = 6f
        var t = 0f
        while (t < 2f * PI) {
            add(140f + a * t + r * sin(t), 3f + r * (1f - cos(t)))
            t += 0.03f
        }
        val loopEnd = 140f + a * 2f * PI.toFloat()
        x = loopEnd
        while (x < 252f) { add(x, 3f + 11f * (1f - cos(2f * PI.toFloat() * (x - loopEnd) / (252f - loopEnd)))); x += 0.5f }
        while (x < 304f) { add(x, 3f + 7f * (1f - cos(2f * PI.toFloat() * (x - 252f) / 52f))); x += 0.5f }
        while (x < WIDTH) { add(x, 3f - 1f * (x - 304f) / 41f); x += 0.5f }
        // Resample so that the points are a pixel apart along the track itself.
        val ox = ArrayList<Float>()
        val oh = ArrayList<Float>()
        ox.add(px[0]); oh.add(py[0])
        var carry = 0f
        for (i in 1 until px.size) {
            var dx = px[i] - px[i - 1]
            var dy = py[i] - py[i - 1]
            var len = sqrt(dx * dx + dy * dy)
            var sx = px[i - 1]
            var sy = py[i - 1]
            while (carry + len >= 1f) {
                val step = 1f - carry
                sx += dx / len * step; sy += dy / len * step
                ox.add(sx); oh.add(sy)
                len -= step; carry = 0f
            }
            carry += len
        }
        xs = ox.toFloatArray()
        hs = oh.toFloatArray()
        // The lift pulls at a steady crawl; after the crest the train runs faster the further it has fallen.
        var liftIdx = 0
        while (liftIdx < xs.size && xs[liftIdx] < 92f) liftIdx++
        liftEnd = liftIdx
        times = FloatArray(xs.size)
        for (i in 1 until xs.size) {
            val v = if (i < liftEnd) 9f else 14f + 5.2f * sqrt(maxOf(0f, 52f - hs[i]))
            times[i] = times[i - 1] + 1f / v
        }
    }

    /** How far along the track (in pixels) a train is [t] seconds after leaving the station. */
    fun distanceAt(t: Float): Float {
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo.toFloat()
    }
}

private fun PixelScene.coaster(s: SceneState, x: Int) {
    if (x + CoasterTrack.WIDTH + 4 < 0 || x > W) return
    val night = s.dark()
    val track = c(s, rgb(210, 62, 54))
    val rail = c(s, rgb(150, 36, 36))
    val support = c(s, rgb(176, 178, 190))
    val xs = CoasterTrack.xs
    val hs = CoasterTrack.hs
    // Supports first, then the track on top of them.
    for (i in xs.indices step 6) {
        val px = x + xs[i].toInt()
        if (px < 0 || px >= W) continue
        val top = BASE - hs[i].toInt() + 2
        if (top < BASE) fill(px, top, px + 1, BASE, support)
    }
    if (hiRes) {
        // The track as one smooth curve on the finer grid: the running surface and a darker rail under it.
        for (i in 0 until xs.size - 1) {
            val x0 = x + xs[i]; val x1 = x + xs[i + 1]
            if (x1 < 0f || x0 >= W) continue
            line(x0, BASE - hs[i], x1, BASE - hs[i + 1], track)
            line(x0, BASE - hs[i] + 1f, x1, BASE - hs[i + 1] + 1f, rail)
        }
    } else for (i in xs.indices) {
        val px = x + xs[i].toInt()
        if (px < 0 || px >= W) continue
        val py = BASE - hs[i].toInt()
        fill(px, py, px + 1, py + 1, track)
        fill(px, py + 1, px + 1, py + 2, rail)
    }
    // The chain of the lift hill, ticking along.
    for (i in 0 until CoasterTrack.liftEnd step 4) {
        val k = (i + (s.time * 6).toInt()) % 8
        if (k < 2) {
            val px = x + xs[i].toInt()
            if (px in 0 until W) fill(px, BASE - hs[i].toInt() - 1, px + 1, BASE - hs[i].toInt(), c(s, rgb(60, 60, 70)))
        }
    }
    // Station platform and the sign.
    fill(x - 2, BASE - 3, x + 14, BASE, c(s, rgb(196, 170, 130)))
    fill(x + 4, BASE - 12, x + 6, BASE - 3, c(s, rgb(236, 190, 60)))
    fill(x + 2, BASE - 14, x + 12, BASE - 12, c(s, rgb(220, 60, 60)))
    // The train: waits in the station, climbs, runs the whole track, and is gone for a moment before the next.
    val travel = CoasterTrack.times.last()
    val cycle = 3f + travel + 2f
    val t = if (s.running() < 0.15f) -1f else (s.rideTime.toFloat() % cycle) - 3f
    val head = if (t < 0f) 9f else minOf(CoasterTrack.distanceAt(t), xs.size - 1f) + 9f
    if (t <= travel) {
        val trainColors = intArrayOf(rgb(250, 210, 70), rgb(70, 140, 230), rgb(230, 70, 60), rgb(90, 200, 120))
        for (carN in 0 until 4) {
            val idx = (head - carN * 6f).toInt().coerceIn(0, xs.size - 1)
            val px = x + xs[idx].toInt()
            val py = BASE - hs[idx].toInt()
            if (px < -4 || px >= W) continue
            if (hiRes) {
                val bodyC = c(s, trainColors[carN])
                fillOval(px - 2.8f, py - 3.6f, px + 3.8f, py + 0.3f, bodyC, 1f)
                fillAlpha(px - 2, py - 3, px + 3, py - 2, rgb(255, 255, 255), 0.25f)
                fillOval(px - 1.2f, py - 5.2f, px + 0.4f, py - 3.6f, c(s, rgb(240, 200, 168)), 1f)
                fillOval(px + 0.8f, py - 5.2f, px + 2.4f, py - 3.6f, c(s, rgb(224, 176, 140)), 1f)
                fillPhys(px * scale - 1, py * scale, px * scale + 3, py * scale + 2, c(s, rgb(40, 40, 48)))
                fillPhys(px * scale + 5, py * scale, px * scale + 9, py * scale + 2, c(s, rgb(40, 40, 48)))
            } else {
                fill(px - 2, py - 3, px + 3, py, c(s, trainColors[carN]))
                fill(px - 1, py - 4, px + 2, py - 3, c(s, rgb(250, 220, 190)))
            }
            if (night > 0.2f) fillAlpha(px - 3, py - 5, px + 4, py + 1, rgb(255, 230, 150), night * 0.2f)
        }
    }
    // Lights along the track at night.
    if (night > 0.2f) for (i in xs.indices step 9) {
        val px = x + xs[i].toInt()
        if (px !in 0 until W) continue
        val on = ((s.time * 3).toInt() + i / 9) % 3 == 0
        fill(px, BASE - hs[i].toInt() - 2, px + 1, BASE - hs[i].toInt() - 1, lerp(rgb(60, 50, 50), s.lightPal(i / 9), if (on) night else night * 0.3f))
    }
}

private fun PixelScene.dropTower(s: SceneState, x: Int) {
    if (x + 18 < 0 || x > W) return
    val night = s.dark()
    val steel = c(s, rgb(110, 130, 190))
    val stripe = c(s, rgb(236, 236, 244))
    fill(x + 4, BASE - 60, x + 10, BASE, steel)
    for (y in BASE - 58 until BASE step 6) fill(x + 4, y, x + 10, y + 2, stripe)
    if (hiRes) {
        // Lattice braces down the tower and a lit edge.
        for (y in BASE - 58 until BASE - 6 step 6) { line(x + 4f, y.toFloat() + 2f, x + 10f, y + 6f, Colors.scale(steel, 0.7f)); line(x + 10f, y.toFloat() + 2f, x + 4f, y + 6f, Colors.scale(steel, 0.7f)) }
        fillAlphaPhys((x + 4) * scale, (BASE - 60) * scale, (x + 4) * scale + 1, BASE * scale, rgb(255, 255, 255), 0.25f)
    }
    fill(x + 2, BASE - 64, x + 12, BASE - 60, c(s, rgb(220, 60, 60)))
    // The seat ring climbs slowly, hangs a moment, then drops and bounces.
    val p = ((s.rideTime) % 14.0).toFloat()
    val f = when {
        p < 6f -> p / 6f
        p < 8f -> 1f
        p < 9f -> 1f - ((p - 8f) * (p - 8f))
        p < 9.8f -> 0.0f + 0.05f * sin((p - 9f) * 8f)
        else -> 0f
    }.coerceIn(0f, 1f)
    val y = BASE - 8 - (f * 46f).toInt()
    fill(x, y, x + 14, y + 4, c(s, rgb(250, 200, 60)))
    for (k in 0 until 4) fill(x + 1 + k * 3, y - 2, x + 2 + k * 3, y, c(s, rgb(240, 200, 170)))
    if (night > 0.2f) {
        val on = (s.rideTime * 4).toInt() % 2 == 0
        fill(x + 6, BASE - 66, x + 8, BASE - 64, if (on) rgb(255, 80, 70) else rgb(120, 40, 40))
        fillAlpha(x - 2, y - 3, x + 16, y + 5, rgb(255, 230, 150), night * 0.18f)
    }
}

private fun PixelScene.swings(s: SceneState, x: Int) {
    if (x + 50 < 0 || x > W) return
    val night = s.dark()
    val cx = x + 23
    val steel = c(s, rgb(150, 150, 170))
    fill(cx - 2, BASE - 34, cx + 3, BASE, steel)
    // The ring at the top turns, and the chairs swing out on their chains: further out the faster it spins.
    val spin = s.rideTime * 1.3
    val flare = (0.35 + 0.65 * (0.5 + 0.5 * sin(s.rideTime * 0.25))).toFloat()
    val topY = BASE - 36
    fill(cx - 20, topY, cx + 21, topY + 3, c(s, rgb(220, 60, 90)))
    fill(cx - 17, topY - 2, cx + 18, topY, c(s, rgb(250, 200, 60)))
    fill(cx - 3, topY - 5, cx + 4, topY - 2, c(s, rgb(60, 140, 220)))
    for (pass in 0..1) for (i in 0 until 8) {
        val a = spin + i * PI / 4
        val front = sin(a) > 0
        if (front != (pass == 1)) continue
        val rx = cos(a)
        val ex = cx + (rx * 18).toInt()
        val sx = cx + (rx * (18 + 9 * flare)).toInt()
        val sy = topY + 16 - (flare * 4).toInt() + (sin(a) * 2).toInt()
        line(ex.toFloat(), (topY + 3).toFloat(), sx.toFloat(), sy.toFloat(), c(s, rgb(220, 220, 226)))
        fill(sx - 1, sy, sx + 2, sy + 3, c(s, GONDOLAS[i % GONDOLAS.size]))
        if (night > 0.2f) fillAlpha(sx - 3, sy - 2, sx + 4, sy + 5, rgb(255, 230, 150), night * 0.16f)
    }
}

/** Cars parked in rows across the lot, and the visitors walking up to the gate. */
internal fun PixelScene.drawParkFront(s: SceneState) {
    if (!s.anyPark) return
    val cam = floor(s.cam).toInt()
    val cell = 16
    spritePaint.colorFilter = filter(s, 0.02f + s.look.haze * 0.25f)
    val firstCell = Math.floorDiv(cam, cell) - 1
    for (ci in firstCell..firstCell + W / cell + 2) {
        val x = ci * cell - cam + 1
        val a = s.parkOnScreen(x.coerceIn(0, W - 1))
        if (a <= 0.05f) continue
        // No parked car where a house still stands (the houses are on a slower layer than the road, so ask where they are).
        if (s.suburbAt(floor(s.cam * LAYER) + x.coerceIn(-8, W + 8), LAYER) > 0.02f || s.suburbOnScreen(x.coerceIn(0, W - 1)) > 0.02f) continue
        // A clear way up to the gate.
        val into = Math.floorMod(ci * cell, (Places.STRETCH).toInt())
        if (into in (2 * PARK_FROM - 20)..(2 * PARK_TO + 20)) continue
        for (row in 0..1) {
            // In the thick of the park only the front row is filled (the rides and visitors need the room); out at the ends, both.
            if (row == 0 && a > 0.85f) continue
            if (Noise.unit(ci, row, 1510) > 0.6f * a) continue
            val idx = (Noise.unit(ci, row, 1511) * cars.size).toInt().coerceIn(0, cars.size - 1)
            sprite(cars[idx], x, if (row == 0) ROW_A else ROW_B, flip = Noise.unit(ci, row, 1512) < 0.5f)
        }
    }
    // Visitors strolling along the front of the rides.
    val offset = floor(s.cam * LAYER).toInt()
    val first = Math.floorDiv(offset + PARK_FROM, STRETCH_LAYER)
    val last = Math.floorDiv(offset + W + PARK_TO, STRETCH_LAYER)
    spritePaint.colorFilter = filter(s, 0f, snowy = false)
    if (s.placeMode == Places.JOURNEY || s.placeMode == Places.AMUSEMENT) for (k in first..last) {
        if (!s.parkStretch(k)) continue
        val left = k * STRETCH_LAYER - offset
        val storm = maxOf(s.cond.rain, s.cond.snow * 0.7f, s.cond.thunder)
        val rainy = s.cond.rain > 0.2f || s.cond.snow > 0.3f
        val crowd = (36 * (1f - 0.75f * storm)).toInt()
        for (g in 0 until crowd) {
            val home = GATE_X + 20 + (Noise.unit(k, g, 1520) * (SWING_X + 40 - GATE_X - 20)).toInt()
            val sway = sin(s.time * 0.25 + g * 1.9) * 14
            val x = left + home + sway.toInt()
            if (x < -8 || x > W + 2) continue
            val frame = floor(s.time * 2.2 + g).toInt() % 2
            val shirt = (Noise.unit(k, g, 1521) * 6).toInt().coerceIn(0, 5)
            val skin = People.skinIndex(Noise.unit(k, g, 1522))
            val hair = People.hairIndex(Noise.unit(k, g, 1523))
            sprite(personSprite(shirt, skin, hair, frame, if (rainy) g % 4 else -1), x, BASE + 3 + (Noise.unit(k, g, 1524) * 4).toInt(), flip = cos(s.time * 0.25 + g * 1.9) < 0)
        }
    }
    spritePaint.colorFilter = null
}
