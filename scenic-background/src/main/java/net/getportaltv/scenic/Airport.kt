package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.floor

/** The airliner on a far runway: facing right, with its wheels down (taking off or landing) or tucked away. */
private object JetSprites {
    private val WHITE = rgb(240, 242, 246)
    private val BELLY = rgb(186, 192, 204)
    private val TAIL = rgb(46, 96, 184)
    private val WING = rgb(206, 210, 220)
    private val ENGINE = rgb(120, 126, 140)
    private val GLASS = rgb(40, 56, 90)
    private val TYRE = rgb(34, 34, 40)

    private fun bitmap(gear: Boolean): Sprite {
        val b = Bitmap.createBitmap(14, 5, Bitmap.Config.ARGB_8888)
        fun p(x: Int, y: Int, c: Int) { if (x in 0 until 14 && y in 0 until 5) b.setPixel(x, y, c) }
        for (x in 1..11) { p(x, 2, WHITE); p(x, 3, if (x > 1) BELLY else WHITE) }
        p(12, 2, WHITE); p(12, 3, BELLY); p(13, 2, WHITE); p(12, 1, GLASS)
        p(1, 1, TAIL); p(1, 0, TAIL); p(2, 1, TAIL); p(0, 1, TAIL)
        for (x in 5..9) p(x, 4, WING)
        p(7, 4, ENGINE)
        if (gear) { p(3, 4, TYRE); p(10, 4, TYRE) }
        return Sprite.of(b)
    }

    private fun hi(sp: Sprite, gear: Boolean): Sprite = Fine(sp, blank = true).apply {
        // Fuselage with a pointed nose, a belly in shadow, windows and cockpit glass; tail fin with a stabiliser; a swept wing and an engine pod.
        rect(3, 3, 24, 8, WHITE); rect(24, 4, 26, 8, WHITE); px(26, 5, WHITE); px(26, 6, WHITE); px(27, 6, WHITE); rect(23, 3, 25, 4, WHITE)
        rect(3, 6, 25, 8, BELLY); px(2, 4, WHITE); px(2, 5, WHITE)
        rect(23, 3, 25, 5, GLASS)
        for (x in 6..21 step 2) px(x, 4, GLASS)
        rect(3, 0, 5, 3, TAIL); rect(3, 1, 6, 3, TAIL); rect(3, 2, 7, 3, TAIL); px(2, 3, TAIL); rect(1, 3, 6, 4, WING); px(0, 3, WING)
        for (i in 0..5) rect(11 + i, 8 + (i / 2), 18, 9 + (i / 2), WING)
        rect(13, 9, 17, 10, ENGINE); px(17, 9, WHITE)
        if (gear) { rect(7, 8, 9, 10, TYRE); rect(20, 8, 22, 10, TYRE); px(8, 8, WING); px(21, 8, WING) }
    }.done()

    val clean: Sprite by lazy { hi(bitmap(false), false) }
    val down: Sprite by lazy { hi(bitmap(true), true) }
}

/**
 * An airport far off beyond the suburbs: a runway lying along the horizon with its centre dashes and, at night, its lights; a terminal, a
 * control tower with a beacon and a couple of hangars behind it. Every so often an airliner either takes off (it runs along the runway,
 * lifts and climbs away) or lands (comes down out of the sky, touches down and rolls to a stop).
 */
internal fun PixelScene.drawAirport(s: SceneState) {
    val factor = 0.18
    val offset = floor(s.cam * factor).toInt()
    val cell = 1000
    val first = Math.floorDiv(offset, cell) - 1
    val haze = 0.28f + s.look.haze * 0.3f
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    for (i in first..first + W / cell + 2) {
        if (Noise.unit(i, 985) > 0.55f) continue
        val wx = i * cell + 60 + (Noise.unit(i, 986) * 400).toInt()
        if (s.suburbAt(wx.toDouble(), factor) < 0.6f || s.cityAt(wx.toDouble(), factor) > 0.2f) continue
        val x = wx - offset
        if (x < -170 || x > W + 20) continue
        val len = 150
        val ground = FIELD_TOP - 6
        val runwayY = ground - 1
        val concrete = lit(s, rgb(150, 152, 160), haze)
        val dark = lit(s, rgb(96, 100, 112), haze)
        val glass = lit(s, rgb(96, 130, 170), haze)
        val roofC = lit(s, rgb(196, 200, 208), haze)
        val white = lit(s, rgb(240, 240, 236), haze)
        // The buildings behind the runway: a long terminal, hangars with rounded roofs, and the control tower.
        fill(x + 36, ground - 7, x + 82, ground - 1, roofC)
        fill(x + 36, ground - 8, x + 82, ground - 7, dark)
        fill(x + 38, ground - 5, x + 80, ground - 2, glass)
        var mx = x + 44
        while (mx < x + 80) { fill(mx, ground - 5, mx + 1, ground - 2, dark); mx += 8 }
        fill(x + 92, ground - 18, x + 95, ground - 1, roofC)
        fill(x + 90, ground - 22, x + 97, ground - 18, glass)
        fill(x + 89, ground - 23, x + 98, ground - 22, dark)
        fill(x + 93, ground - 27, x + 94, ground - 23, dark)
        for (h in 0..1) {
            val hx = x + 108 + h * 18
            for (r in 0 until 5) fill(hx + r, ground - 6 + r, hx + 16 - r, ground - 5 + r, if (r == 0) dark else roofC)
            fill(hx, ground - 1, hx + 16, ground, roofC)
        }
        if (hiRes) {
            val sc = scale
            val ink = rgb(8, 10, 24); val whiteI = rgb(255, 255, 255)
            fillAlphaPhys((x + 36) * sc, (ground - 8) * sc, (x + 82) * sc, (ground - 8) * sc + 1, whiteI, 0.3f)
            eaveShadow(x + 36, x + 82, ground - 7)
            fillAlphaPhys((x + 92) * sc, (ground - 18) * sc, (x + 92) * sc + 1, (ground - 1) * sc, whiteI, 0.25f)
            fillAlphaPhys((x + 95) * sc - 1, (ground - 18) * sc, (x + 95) * sc, (ground - 1) * sc, ink, 0.25f)
            for (k in 0..4) fillAlphaPhys((x + 90) * sc + k * 3 + 1, (ground - 22) * sc, (x + 90) * sc + k * 3 + 2, (ground - 18) * sc, ink, 0.3f)
        }
        // The tower's beacon, and a windsock by the terminal.
        if (night > 0.2f && (s.time * 0.9).toInt() % 2 == 0) { fillAlpha(x + 92, ground - 29, x + 96, ground - 26, rgb(255, 70, 60), 0.9f) }
        fill(x + 30, ground - 8, x + 31, ground, dark); fill(x + 26, ground - 8, x + 30, ground - 6, lit(s, rgb(240, 130, 50), haze))
        // The runway: a wide grey strip with a dashed white centre line, and lights along it at night.
        fill(x, runwayY, x + len, runwayY + 3, concrete)
        fill(x, runwayY, x + len, runwayY + 1, lerp(concrete, rgb(255, 255, 255), 0.15f))
        var dx = x + 4
        while (dx < x + len - 6) { fill(dx, runwayY + 1, dx + 5, runwayY + 2, white); dx += 10 }
        fill(x, runwayY + 3, x + len, runwayY + 4, dark)
        if (night > 0.2f) {
            var lx = x
            while (lx < x + len) {
                val a = night * (if ((lx / 6) % 2 == 0) 0.95f else 0.6f)
                fillAlpha(lx, runwayY, lx + 1, runwayY + 1, rgb(255, 250, 220), a)
                fillAlpha(lx, runwayY + 3, lx + 1, runwayY + 4, rgb(120, 190, 255), a * 0.8f)
                lx += 4
            }
            fillAlpha(x - 2, runwayY - 2, x + len + 2, runwayY + 5, rgb(255, 240, 200), night * 0.06f)
        }
        // Airliners waiting at the terminal for their passengers, with a baggage cart and a fuel truck alongside.
        val parked = if (Noise.unit(i, 990) < 0.6f) 2 else 1
        for (k in 0 until parked) {
            val px = x + 38 + k * 22
            spritePaint.alpha = 255
            spritePaint.colorFilter = filter(s, 0.12f + s.look.haze * 0.3f)
            sprite(JetSprites.down, px, ground)
            spritePaint.colorFilter = null
            // The jet bridge from the terminal to the door, and the little service vehicle under the wing.
            fill(px + 4, ground - 4, px + 7, ground - 3, dark)
            val cartC = if (k == 0) lit(s, rgb(240, 200, 60), haze) else white
            fill(px + 8, ground - 2, px + 12, ground - 1, cartC)
            fill(px + 8, ground - 1, px + 9, ground, dark); fill(px + 11, ground - 1, px + 12, ground, dark)
            if (night > 0.2f) fillAlpha(px + 3, ground - 5, px + 11, ground - 4, rgb(255, 235, 170), 0.7f * night)
        }
        // The airliner: one pass at a time, taking off or landing.
        for (ev in LifeSchedule.events(s.time, 55.0, 24.0, 0.85f, 4701, s.boost)) {
            val t = (s.time - ev.spawn).toFloat()
            val landing = ev.unit(1) < 0.5f
            var jx: Float
            var jy: Float
            var wheels = true
            var fade = 1f
            val y0 = runwayY.toFloat() - 4f   // the jet's feet on the runway
            if (landing) {
                val tTouch = 6f
                val xTouch = x + len * 0.12f
                if (t < tTouch) {
                    val u = t / tTouch
                    jx = (xTouch - 70f) + u * 70f
                    jy = y0 - 26f * (1f - u) * (1f - 0.15f * u)
                } else {
                    val r = t - tTouch
                    val v0 = 14f
                    jx = xTouch + v0 * r - 0.5f * 1.9f * r * r
                    jx = minOf(jx, xTouch + v0 * v0 / (2f * 1.9f))
                    jy = y0
                    // Once it has stopped it taxis off to the terminal: it fades out rather than sitting there.
                    fade = (1f - (r - v0 / 1.9f - 0.5f) / 2f).coerceIn(0f, 1f)
                }
            } else {
                val tLift = 8f
                val a = 2f * (len * 0.7f - 8f) / (tLift * tLift)
                fade = (t / 1.5f).coerceIn(0f, 1f)
                if (t < tLift) {
                    jx = x + 8f + 0.5f * a * t * t
                    jy = y0
                } else {
                    val c = t - tLift
                    val v = a * tLift
                    jx = x + 8f + 0.5f * a * tLift * tLift + v * c
                    jy = y0 - 2.4f * c - 0.30f * c * c
                    wheels = c < 1.2f
                }
            }
            if (jx < -30f || jx > W + 30f || fade <= 0.02f) continue
            spritePaint.alpha = (fade * 255).toInt()
            spritePaint.colorFilter = filter(s, 0.12f + s.look.haze * 0.3f)
            sprite(if (wheels) JetSprites.down else JetSprites.clean, jx.toInt(), jy.toInt() + 5)
            spritePaint.colorFilter = null
            spritePaint.alpha = 255
            // Engine smoke along the runway on take-off, a puff of tyre smoke on touching down, and lights at night.
            if (!landing && t < 10f) for (k in 1..6) fillAlpha((jx - 4 - k * 3).toInt(), (y0 + 4 - k * 0.4f).toInt(), (jx - 3 - k * 3).toInt(), (y0 + 5 - k * 0.4f).toInt(), rgb(214, 214, 220), 0.35f * (1f - k / 7f))
            if (landing && t in 6f..9f) for (k in 0..3) fillAlpha((jx + 4 - k * 2).toInt(), (y0 + 3).toInt(), (jx + 6 - k * 2).toInt(), (y0 + 5).toInt(), rgb(220, 220, 226), 0.4f * (1f - (t - 6f) / 3f))
            if (night > 0.2f) {
                val strobe = (s.time * 3).toInt() % 2 == 0
                if (strobe) fillAlpha(jx.toInt() + 7, jy.toInt() + 1, jx.toInt() + 9, jy.toInt() + 2, rgb(255, 255, 255), 0.9f * night)
                fillAlpha(jx.toInt() + 12, jy.toInt() + 3, jx.toInt() + 28, jy.toInt() + 6, rgb(255, 244, 200), 0.12f * night)
            }
        }
    }
}

/** Whether an airport lies within [reach] camera units of [cam], so that other things (the water tower) can keep clear of it. */
internal fun PixelScene.airportNear(s: SceneState, cam: Double, reach: Double): Boolean {
    val factor = 0.18
    val cell = 1000
    val centre = Math.floorDiv((cam * factor).toInt(), cell)
    val span = (reach * factor / cell).toInt() + 2
    for (i in centre - span..centre + span) {
        if (Noise.unit(i, 985) > 0.55f) continue
        val wx = i * cell + 60 + (Noise.unit(i, 986) * 400).toInt()
        if (s.suburbAt(wx.toDouble(), factor) < 0.6f || s.cityAt(wx.toDouble(), factor) > 0.2f) continue
        if (kotlin.math.abs(wx / factor - cam) < reach) return true
    }
    return false
}
