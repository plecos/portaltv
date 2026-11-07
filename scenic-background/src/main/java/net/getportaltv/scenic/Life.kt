package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import android.graphics.Path
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_BOTTOM
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Things that come and go, worked out from the time alone: when each starts, and a number to vary it by. Nothing is stored. */
object LifeSchedule {

    class Event(val spawn: Double, /** A number from 0 up to 1 that is the same every time for this event, to vary it. */ val seed: Int) {
        fun unit(extra: Int) = Noise.unit(seed, extra)
    }

    /**
     * The events of one kind happening at [time]. Time is cut into slots of [slotSeconds]; each slot has an event with chance
     * [probability], starting somewhere inside it, and the event lasts up to [lifetime] seconds. [key] tells kinds apart.
     * With [boost] every slot has one.
     */
    fun events(time: Double, slotSeconds: Double, lifetime: Double, probability: Float, key: Int, boost: Boolean = false): List<Event> {
        val out = ArrayList<Event>(2)
        val last = floor(time / slotSeconds).toInt()
        val first = floor((time - lifetime) / slotSeconds).toInt()
        for (k in first..last) {
            if (!boost && Noise.unit(k, key) > probability) continue
            val spawn = k * slotSeconds + Noise.unit(k, key + 1) * slotSeconds
            val age = time - spawn
            if (age < 0 || age > lifetime) continue
            out += Event(spawn, Noise.hash(k, key))
        }
        return out
    }

    /** Back and forth between 0 and [length] as [t] grows: 0, up to length, down again. */
    fun pingPong(t: Double, length: Double): Double {
        val phase = ((t % (2 * length)) + 2 * length) % (2 * length)
        return if (phase < length) phase else 2 * length - phase
    }
}

private fun SceneState.day() = look.day
private fun SceneState.stormy() = (cond.rain * 0.7f + cond.snow * 0.5f + cond.thunder).coerceIn(0f, 1f)

// ---------- people and animals on the verge ----------

private val SHIRTS = intArrayOf(rgb(206, 70, 60), rgb(70, 110, 200), rgb(230, 190, 60), rgb(80, 160, 100), rgb(220, 120, 170), rgb(240, 240, 240))
private val UMBRELLAS = intArrayOf(rgb(206, 60, 60), rgb(60, 100, 200), rgb(236, 190, 60), rgb(70, 150, 100))
private val walkerSprites by lazy {
    // [shirt][frame] without an umbrella, and with.
    Array(SHIRTS.size) { c -> Array(2) { f -> LifeSprites.person(SHIRTS[c], rgb(52, 60, 96), f, null) } }
}
private val umbrellaSprites by lazy {
    Array(SHIRTS.size) { c -> Array(UMBRELLAS.size) { u -> Array(2) { f -> LifeSprites.person(SHIRTS[c], rgb(52, 60, 96), f, UMBRELLAS[u]) } } }
}
private val cyclistSprites by lazy { Array(SHIRTS.size) { c -> Array(2) { f -> LifeSprites.cyclist(SHIRTS[c], f) } } }

/** Somebody walking or cycling along the verge now and then, with an umbrella in the rain, and sometimes a dog. */
internal fun PixelScene.drawWalkers(s: SceneState) {
    if (s.day() < 0.25f || s.stormy() > 0.8f || s.cond.thunder > 0.3f) return
    val rainy = s.cond.rain > 0.2f || s.cond.snow > 0.3f
    val travelV = if (s.travelling) Director.SPEED else 0f
    spritePaint.colorFilter = filter(s, 0f)
    for (ev in LifeSchedule.events(s.time, 10.0, 80.0, 0.34f, 201, s.boost)) {
        val cycling = ev.unit(1) < 0.35f
        if (cycling && s.cond.rain > 0.55f) continue
        val dir = if (ev.unit(2) < 0.5f) 1 else -1
        val speed = if (cycling) 16f + ev.unit(3) * 8f else 5f + ev.unit(3) * 3f
        val vScreen = dir * speed - travelV
        if (abs(vScreen) < 0.5f) continue
        val age = (s.time - ev.spawn).toFloat()
        val x = if (vScreen < 0) W + 24 + vScreen * age else -24 + vScreen * age
        if (x < -30 || x > W + 30) continue
        val shirt = (ev.unit(4) * SHIRTS.size).toInt().coerceIn(0, SHIRTS.size - 1)
        val frame = (floor((age * (if (cycling) 6.0 else 3.2)).toDouble()).toInt()) % 2
        val bottom = ROAD_BOTTOM + 9
        if (cycling) {
            sprite(cyclistSprites[shirt][frame], x.toInt(), bottom - 1, flip = dir < 0)
        } else {
            val sp = if (rainy) umbrellaSprites[shirt][(ev.unit(5) * UMBRELLAS.size).toInt().coerceIn(0, UMBRELLAS.size - 1)][frame] else walkerSprites[shirt][frame]
            sprite(sp, x.toInt(), bottom, flip = false)
            if (ev.unit(6) < 0.4f) {
                // A dog a few steps behind.
                val behind = if (dir > 0) -9 else 9
                sprite(if (frame == 0) LifeSprites.dog else LifeSprites.dogStep, x.toInt() + behind, bottom, flip = dir < 0)
            }
        }
    }
    // A rabbit hops across now and then, in the day.
    if (s.stormy() < 0.4f) for (ev in LifeSchedule.events(s.time, 16.0, 70.0, 0.22f, 211, s.boost)) {
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val vScreen = dir * 14f - travelV
        val age = (s.time - ev.spawn).toFloat()
        val x = if (vScreen < 0) W + 10 + vScreen * age else -10 + vScreen * age
        if (x < -10 || x > W + 10) continue
        val hop = abs(sin(age * 6.0)).toFloat() * 4f
        sprite(LifeSprites.rabbit, x.toInt(), (ROAD_BOTTOM + 10 - hop).toInt(), flip = dir < 0)
    }
    spritePaint.colorFilter = null
}

// ---------- the field: pond and ducks, deer, tractor ----------

/** A pond now and then, with ducks, reflecting the sky. */
internal fun PixelScene.drawPonds(s: SceneState) {
    val offset = s.cam * 0.5
    val chunk = 170
    val first = floor(offset / chunk).toInt() - 1
    val l = s.look
    for (i in first..first + W / chunk + 2) {
        if (Noise.unit(i, 140) > 0.30f || s.cityAt(i * chunk + chunk / 2.0, 0.5) > 0.2f) continue
        val left = (i * chunk - offset).toInt()
        val cx = left + 30 + (Noise.unit(i, 141) * 100).toInt()
        val halfW = 20 + (Noise.unit(i, 142) * 8).toInt()
        val top = FIELD_TOP + 9
        val water = lit(s, rgb(60, 120, 176), 0.04f)
        val sky = lerp(l.skyBottom, rgb(80, 140, 200), 0.25f)
        for (row in 0 until 7) {
            // An oval: narrower in the top and bottom rows.
            val shrink = if (row == 0 || row == 6) 6 else if (row == 1 || row == 5) 2 else 0
            val x0 = cx - halfW + shrink
            val x1 = cx + halfW - shrink
            val shimmer = 0.5f + 0.5f * sin(s.time * 1.4 + row * 1.9 + i).toFloat()
            fill(x0, top + row, x1, top + row + 1, lerp(water, sky, 0.35f + 0.25f * shimmer * (1f - row / 7f)))
        }
        // The bank, reeds and a few sparkles.
        fill(cx - halfW + 2, top + 7, cx + halfW - 2, top + 8, lit(s, rgb(76, 108, 60), 0.04f))
        for (r in 0 until 5) {
            val rx = cx - halfW + 3 + r * (halfW * 2 - 6) / 4
            fill(rx, top - 2 + (r % 2), rx + 1, top + 1, lit(s, rgb(86, 120, 60), 0.04f))
        }
        for (sp in 0 until 3) {
            val on = sin(s.time * 2.0 + sp * 2.1 + i * 3).toFloat()
            if (on > 0.6f && l.day > 0.3f) fillAlpha(cx - halfW / 2 + sp * 12, top + 2 + sp % 2, cx - halfW / 2 + sp * 12 + 1, top + 3 + sp % 2, rgb(255, 255, 255), (on - 0.6f) * 2f)
        }
        // Two ducks, going back and forth.
        spritePaint.colorFilter = filter(s, 0.04f)
        for (d in 0 until 2) {
            val travel = LifeSchedule.pingPong(s.time * (2.0 + d * 0.7) + d * 9, (halfW * 2 - 16).toDouble()).toInt()
            val toRight = ((s.time * (2.0 + d * 0.7) + d * 9) % (2 * (halfW * 2 - 16))) < (halfW * 2 - 16)
            sprite(LifeSprites.duck, cx - halfW + 4 + travel, top + 4 + d, flip = !toRight)
        }
        spritePaint.colorFilter = null
    }
}

/** Deer grazing at the edge of the field, now and then looking up, and a tractor working a field in the day. */
internal fun PixelScene.drawFieldAnimals(s: SceneState) {
    val offset = s.cam * 0.5
    val chunk = 170
    val first = floor(offset / chunk).toInt() - 1
    spritePaint.colorFilter = filter(s, 0.07f + s.look.haze * 0.3f)
    for (i in first..first + W / chunk + 2) {
        val left = (i * chunk - offset).toInt()
        val forest = s.forestAt(i * chunk + chunk / 2.0, 0.5)
        if (s.cityAt(i * chunk + chunk / 2.0, 0.5) > 0.2f) continue
        if (Noise.unit(i, 150) < 0.24f + forest * 0.4f) {
            val n = 1 + (Noise.unit(i, 151) * 3).toInt()
            val dx = left + 10 + (Noise.unit(i, 152) * 120).toInt()
            for (d in 0 until n) {
                val bucket = floor(s.time / 6.0).toInt()
                val up = Noise.unit(i * 7 + d, bucket, 153) < 0.4f
                sprite(if (up) LifeSprites.deerUp else LifeSprites.deerGraze, dx + d * 15, FIELD_TOP + 12 + (Noise.unit(i, 154 + d) * 3).toInt(), flip = Noise.unit(i, 160 + d) < 0.5f)
            }
        }
        if (forest < 0.2f && s.day() > 0.5f && s.stormy() < 0.3f && Noise.unit(i, 170) < 0.14f) {
            // The tractor goes up and down its field, turning at each end.
            val run = 70.0
            val phase = (s.time * 10.0 + Noise.unit(i, 171) * 200)
            val x = left + 30 + LifeSchedule.pingPong(phase, run).toInt()
            val right = (phase % (2 * run)) < run
            sprite(LifeSprites.tractor, x, FIELD_TOP + 16, flip = !right)
            // Dust behind it.
            for (k in 0 until 4) {
                val age = (((s.time * 1.5) + k / 4.0) % 1.0).toFloat()
                val back = if (right) -1 else 1
                val px = x + (if (right) 0 else LifeSprites.tractor.width) + back * (2 + (age * 10).toInt())
                fillAlpha(px, FIELD_TOP + 14 - (age * 5).toInt(), px + 2, FIELD_TOP + 16 - (age * 5).toInt(), lit(s, rgb(176, 150, 110), 0.1f), (1f - age) * 0.5f)
            }
        }
    }
    spritePaint.colorFilter = null
}

// ---------- the railway ----------

private val BOX_COLORS = intArrayOf(rgb(150, 70, 50), rgb(70, 100, 140), rgb(120, 120, 60), rgb(110, 60, 60), rgb(60, 110, 90))
private val COACH_COLORS = intArrayOf(rgb(60, 100, 170), rgb(180, 60, 60), rgb(70, 130, 90))
private val boxcars by lazy { BOX_COLORS.map { LifeSprites.boxcar(it) } }
private val coaches by lazy { COACH_COLORS.map { LifeSprites.coach(it) } }

/** How much track there is at [worldX] along the railway: 1 where it runs alongside, 0 where there is none, and in between where it curves away into the distance or in from it. */
internal fun trackAt(worldX: Double): Float {
    val block = floor(worldX / TRACK_BLOCK).toInt()
    if (!trackBlock(block)) return 0f
    val into = (worldX - block * TRACK_BLOCK).toFloat()
    val before = if (trackBlock(block - 1)) 1f else min(1f, into / TRACK_CURVE)
    val after = if (trackBlock(block + 1)) 1f else min(1f, (TRACK_BLOCK.toFloat() - into) / TRACK_CURVE)
    return smoothstep(0f, 1f, min(before, after))
}

private const val TRACK_BLOCK = 900.0
private const val TRACK_CURVE = 130f
private fun trackBlock(block: Int) = Noise.unit(block, 95) < 0.6f

/** A line of track across the far field, in stretches (it curves away into the distance and comes back from it), and a train along it every so often. */
internal fun PixelScene.drawRailway(s: SceneState) {
    val y = FIELD_TOP + 5
    val offset = s.cam * 0.3
    val haze = 0.18f + s.look.haze * 0.3f
    val ballast = lit(s, rgb(122, 112, 100), haze)
    val rail = lit(s, rgb(176, 176, 184), haze)
    val sleeper = lit(s, rgb(96, 70, 50), haze)
    val bed = lit(s, rgb(70, 72, 82), haze)
    val onTrack = Path()
    var any = false
    var runStart = -1
    for (x in 0..W) {
        // In the city the line is a subway: it only comes up into the daylight in the parks.
        val park = x < W && s.inPark(x)
        val t = if (park) 1f else if (x < W) trackAt(x + offset) * (1f - s.cityAt(x + offset, 0.3)) else 0f
        val full = t >= 0.98f
        if (full && runStart < 0) runStart = x
        if (!full && runStart >= 0) { onTrack.addRect(runStart.toFloat(), 0f, x.toFloat(), H.toFloat(), Path.Direction.CW); runStart = -1; any = true }
        if (x >= W || t < 0.04f) continue
        // Curving away, the track climbs toward the horizon, gets thinner and fades into the haze.
        val c = 1f - t
        val top = y - (c * 4f).toInt()
        val thick = if (t > 0.66f) 3 else if (t > 0.33f) 2 else 1
        val far = c * 0.55f
        fill(x, top + 1, x + 1, top + 1 + thick, if (park) bed else if (far > 0f) lerp(ballast, airColor(s), far) else ballast)
        if (t > 0.66f) {
            val worldX = (x + offset).toInt()
            if (Math.floorMod(worldX, 5) < 2) fill(x, top + 2, x + 1, top + 3, sleeper)
            fill(x, top + 1, x + 1, top + 2, rail)
            fill(x, top + 3, x + 1, top + 4, rail)
        } else {
            fill(x, top + 1, x + 1, top + 2, lerp(rail, airColor(s), far))
        }
    }
    if (!any) return

    val travelV = if (s.travelling) Director.SPEED * 0.3f else 0f
    spritePaint.colorFilter = filter(s, 0.14f + s.look.haze * 0.3f)
    // A train is only where the track runs alongside; where it curves away the train goes with it.
    canvas.save()
    canvas.clipPath(onTrack)
    for (ev in LifeSchedule.events(s.time, 55.0, 75.0, 0.55f, 301, s.boost)) {
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val passenger = ev.unit(2) < 0.4f
        val cars = 4 + (ev.unit(3) * 5).toInt()
        val speed = 18f + ev.unit(4) * 10f
        val v = dir * speed - travelV
        val length = 16 + cars * 13
        val age = (s.time - ev.spawn).toFloat()
        // The front of the train enters from one side and the tail leaves at the other.
        val front = if (v > 0) -10f + v * age + length * 0f else W + 10f + v * age
        val rear = if (v > 0) front - length else front + length
        if (max(front, rear) < -20 || min(front, rear) > W + 20) continue
        val bottom = y + 4
        val night = s.look.day < 0.45f
        // The engine at the front, the cars behind it.
        val engineLeft = if (dir > 0) front.toInt() - LifeSprites.locomotive.width else front.toInt()
        sprite(LifeSprites.locomotive, engineLeft, bottom, flip = dir < 0)
        for (c in 0 until cars) {
            val car = if (passenger) coaches[(ev.unit(10) * coaches.size).toInt().coerceIn(0, coaches.size - 1)]
            else if (ev.unit(20 + c) < 0.25f) LifeSprites.tanker else boxcars[(ev.unit(30 + c) * boxcars.size).toInt().coerceIn(0, boxcars.size - 1)]
            val carLeft = if (dir > 0) (front - LifeSprites.locomotive.width - (c + 1) * 13).toInt() else (front + LifeSprites.locomotive.width + c * 13).toInt() + 1
            sprite(car, carLeft, bottom, flip = false)
            if (passenger && night) {
                spritePaint.colorFilter = null
                for (w in LifeSprites.coachWindows) fillAlpha(carLeft + w[0], bottom - car.height + w[1], carLeft + w[0] + w[2], bottom - car.height + w[1] + w[3], rgb(255, 224, 140), 0.9f)
                spritePaint.colorFilter = filter(s, 0.14f + s.look.haze * 0.3f)
            }
        }
        if (night) {
            val hx = if (dir > 0) front.toInt() + 1 else front.toInt() - 14
            fillAlpha(hx, bottom - 5, hx + 14, bottom - 3, rgb(255, 244, 190), 0.35f)
        }
        // Smoke from the engine, trailing back over the cars.
        for (k in 0 until 5) {
            val a = (((s.time * 1.2) + k / 5.0) % 1.0).toFloat()
            val sx = (if (dir > 0) front - 14 else front + 14) - dir * a * 22f
            fillAlpha(sx.toInt(), bottom - 8 - (a * 7).toInt(), sx.toInt() + 2 + (a * 2).toInt(), bottom - 6 - (a * 7).toInt(), lit(s, rgb(206, 206, 210), 0.1f), (1f - a) * 0.55f)
        }
    }
    canvas.restore()
    spritePaint.colorFilter = null
}

// ---------- the sky: a plane and a balloon ----------

private val balloons by lazy {
    listOf(
        LifeSprites.balloon(rgb(220, 70, 60), rgb(250, 230, 120)),
        LifeSprites.balloon(rgb(70, 120, 210), rgb(250, 250, 250)),
        LifeSprites.balloon(rgb(80, 170, 100), rgb(240, 200, 70))
    )
}

internal fun PixelScene.drawSkyLife(s: SceneState) {
    val l = s.look
    val day = s.day()
    // A plane, a long way up, with a trail behind it; at night just its lights.
    if (s.cond.cloud < 0.85f) for (ev in LifeSchedule.events(s.time, 100.0, 50.0, 0.45f, 401, s.boost)) {
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val age = (s.time - ev.spawn).toFloat()
        val v = dir * 11f
        val x = if (v > 0) -8f + v * age else W + 8f + v * age
        val y = 16 + (ev.unit(2) * 24).toInt()
        if (x < -90 || x > W + 90) continue
        if (day > 0.35f) {
            // The trail: a line of faint white behind, fading with distance.
            for (k in 1..70 step 2) {
                val tx = (x - dir * (6 + k)).toInt()
                if (tx < 0 || tx >= W) continue
                fillAlpha(tx, y + 1, tx + 2, y + 2, rgb(255, 255, 255), (1f - k / 70f) * 0.5f * (1f - l.haze))
            }
            sprite(LifeSprites.plane, x.toInt() - 4, y + 3, flip = dir < 0)
        } else {
            val blink = if ((s.time * 1.5).toInt() % 2 == 0) 1f else 0.25f
            fillAlpha(x.toInt(), y, x.toInt() + 1, y + 1, rgb(255, 80, 70), 0.9f * blink * l.starVisibility.coerceAtLeast(0.4f))
            fillAlpha(x.toInt() + 3, y + 1, x.toInt() + 4, y + 2, rgb(240, 240, 255), 0.7f * (1f - blink * 0.3f))
        }
    }
    // A hot air balloon drifting on a calm, clear day.
    // It is rare, about once every ten minutes, and only in full daylight.
    if (day > 0.7f && s.sunAlt > 12.0 && s.cond.wind < 25f && s.stormy() < 0.1f && s.cond.fog < 0.3f && s.cond.cloud < 0.7f) {
        for (ev in LifeSchedule.events(s.time, if (s.boost) 150.0 else 600.0, 130.0, if (s.boost) 0.4f else 1f, 411, s.boost)) {
            val dir = if (s.windDir > 0) 1 else -1
            val age = (s.time - ev.spawn).toFloat()
            val v = dir * (3.5f + s.cond.wind * 0.12f)
            val x = if (v > 0) -16f + v * age else W + 16f + v * age
            if (x < -20 || x > W + 20) continue
            val y = 58 + (ev.unit(1) * 16).toInt() + (sin(age * 0.25) * 3).toInt()
            spritePaint.colorFilter = filter(s, 0.1f + l.haze * 0.4f)
            sprite(balloons[(ev.unit(2) * balloons.size).toInt().coerceIn(0, balloons.size - 1)], x.toInt(), y + 21)
            spritePaint.colorFilter = null
        }
    }
}

// ---------- creatures of the day and of the night ----------

/** Butterflies over the field on a warm, calm day. */
internal fun PixelScene.drawButterflies(s: SceneState) {
    if (s.cityNow() > 0.5f || s.day() < 0.6f || s.cond.rain > 0.1f || s.cond.snow > 0.05f || s.cond.tempC < 9f || s.cond.wind > 24f || s.cond.fog > 0.5f) return
    val offset = s.cam * 0.5
    val chunk = 170
    val first = floor(offset / chunk).toInt() - 1
    val colors = intArrayOf(rgb(250, 200, 70), rgb(250, 250, 250), rgb(240, 150, 190), rgb(120, 170, 240))
    for (i in first..first + W / chunk + 2) for (b in 0 until 3) {
        val seed = Noise.hash(i, b, 501)
        val phase = Noise.unit(seed) * 20.0
        val x = i * chunk - offset + Noise.unit(seed, 1) * chunk + sin(s.time * 0.6 + phase) * 16
        val y = FIELD_TOP + 8 + Noise.unit(seed, 2) * 22 + sin(s.time * 1.3 + phase) * 4 - abs(sin(s.time * 9 + phase)) * 1.5
        if (x < -4 || x > W + 4) continue
        val color = lit(s, colors[(Noise.unit(seed, 3) * colors.size).toInt().coerceIn(0, colors.size - 1)], 0.04f)
        val open = (s.time * 9 + phase).toInt() % 2 == 0
        if (open) fill(x.toInt(), y.toInt(), x.toInt() + 3, y.toInt() + 1, color) else fill(x.toInt() + 1, y.toInt() - 1, x.toInt() + 2, y.toInt() + 1, color)
    }
}

/** Fireflies over the field on a warm night. */
internal fun PixelScene.drawFireflies(s: SceneState) {
    if (s.cityNow() > 0.5f || s.day() > 0.25f || s.cond.rain > 0.1f || s.cond.snow > 0.05f || s.cond.tempC < 6f || s.cond.wind > 26f) return
    val offset = s.cam * 0.5
    val chunk = 170
    val first = floor(offset / chunk).toInt() - 1
    for (i in first..first + W / chunk + 2) for (b in 0 until 7) {
        val seed = Noise.hash(i, b, 511)
        val phase = Noise.unit(seed) * 30.0
        val x = i * chunk - offset + Noise.unit(seed, 1) * chunk + sin(s.time * 0.5 + phase) * 12
        val y = FIELD_TOP + 6 + Noise.unit(seed, 2) * 34 + sin(s.time * 0.8 + phase * 2) * 5
        if (x < -4 || x > W + 4) continue
        val pulse = max(0.0, sin(s.time * 1.3 + phase)).let { it * it }.toFloat()
        if (pulse < 0.05f) continue
        fillAlpha(x.toInt() - 2, y.toInt() - 2, x.toInt() + 3, y.toInt() + 3, rgb(200, 255, 120), pulse * 0.28f)
        fillAlpha(x.toInt() - 1, y.toInt() - 1, x.toInt() + 2, y.toInt() + 2, rgb(220, 255, 140), pulse * 0.55f)
        fillAlpha(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1, rgb(250, 255, 200), pulse)
    }
}

/** Bats in the sky at dusk and dawn. */
internal fun PixelScene.drawBats(s: SceneState) {
    val alt = s.sunAlt.toFloat()
    val window = smoothstep(-14f, -4f, alt) * (1f - smoothstep(2f, 8f, alt))
    if (window < 0.1f || s.stormy() > 0.3f || s.cond.fog > 0.6f) return
    spritePaint.colorFilter = null
    for (b in 0 until 5) {
        val phase = Noise.unit(b, 521) * 40.0
        val dir = if (b % 2 == 0) 1 else -1
        val x = ((Noise.unit(b, 522) * W + dir * s.time * (14 + b * 3) + sin(s.time * 1.7 + phase) * 10) % (W + 40) + (W + 40)) % (W + 40) - 20
        val y = 26 + Noise.unit(b, 523) * 36 + sin(s.time * 0.9 + phase) * 9
        val flap = (s.time * 7 + phase).toInt() % 2 == 0
        spritePaint.alpha = (window * 255).toInt()
        sprite(if (flap) LifeSprites.bat1 else LifeSprites.bat2, x.toInt(), y.toInt() + 3)
    }
    spritePaint.alpha = 255
}
