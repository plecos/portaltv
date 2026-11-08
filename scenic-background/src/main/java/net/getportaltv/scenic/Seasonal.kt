package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Things the year brings to the open country: wildflowers in spring, haystacks and pumpkin fields in summer and autumn, falling leaves and migrating birds. */

private const val FACTOR = 0.5
private const val CHUNK = 160

/** Whether the field at [worldX] (on the farm's layer) is open country: no town, beach or wood. */
private fun SceneState.openField(worldX: Double): Boolean = hardAt(worldX, FACTOR) < 0.05f && forestAt(worldX, FACTOR) < 0.25f

/** Wildflowers in drifts across the meadow in spring, haystacks and round bales through the summer harvest, and pumpkins in autumn. */
internal fun PixelScene.drawFieldSeason(s: SceneState) {
    val season = s.season
    if (season.day <= 0f) return
    val bloom = season.bloom
    val hay = smoothstep(195f, 225f, season.day) * (1f - smoothstep(300f, 325f, season.day))
    val pumpkins = season.autumn * (1f - season.winter) * (1f - smoothstep(305f, 322f, season.day))
    if (bloom < 0.05f && hay < 0.05f && pumpkins < 0.05f) return
    val offset = floor(s.cam * FACTOR).toInt()
    val first = Math.floorDiv(offset, CHUNK) - 1
    for (i in first..first + W / CHUNK + 2) {
        val left = i * CHUNK - offset
        if (!s.openField((i * CHUNK + CHUNK / 2).toDouble())) continue
        if (bloom >= 0.05f) flowers(s, i, left, bloom)
        if (hay >= 0.05f && Noise.unit(i, 1301) < 0.4f * hay) haystack(s, i, left)
        if (pumpkins >= 0.05f && Noise.unit(i, 1311) < 0.55f * pumpkins) pumpkinPatch(s, i, left)
    }
}

private fun PixelScene.flowers(s: SceneState, chunk: Int, left: Int, bloom: Float) {
    val colors = intArrayOf(rgb(250, 250, 244), rgb(250, 226, 70), rgb(236, 110, 170), rgb(150, 120, 220), rgb(240, 120, 70))
    // Drifts of one or two colours, thicker in the middle: patches rather than a sprinkle everywhere.
    for (patch in 0..1) {
        if (Noise.unit(chunk, patch, 1320) > 0.7f) continue
        val cx = left + (Noise.unit(chunk, patch, 1321) * CHUNK).toInt()
        val cy = FIELD_TOP + 4 + (Noise.unit(chunk, patch, 1322) * 14).toInt()
        val a = colors[(Noise.unit(chunk, patch, 1323) * colors.size).toInt().coerceIn(0, colors.size - 1)]
        val b = colors[(Noise.unit(chunk, patch, 1324) * colors.size).toInt().coerceIn(0, colors.size - 1)]
        for (k in 0 until 44) {
            if (Noise.unit(chunk, patch * 40 + k, 1325) > bloom) continue
            val fx = cx + ((Noise.unit(chunk, patch * 40 + k, 1326) - 0.5f) * 36f).toInt()
            val fy = cy + ((Noise.unit(chunk, patch * 40 + k, 1327) - 0.5f) * 9f).toInt()
            if (fx < -2 || fx >= W || fy < FIELD_TOP + 2 || fy >= ROAD_TOP - 6) continue
            val c = solid(s, if (k % 3 == 0) b else a, 0.06f)
            fill(fx, fy, fx + 2, fy + 1, c)
            if (k % 2 == 0) fill(fx, fy - 1, fx + 1, fy, c)
            if (k % 4 == 0) fill(fx, fy + 1, fx + 1, fy + 2, solid(s, rgb(60, 130, 50), 0.06f))
        }
    }
}

private fun PixelScene.haystack(s: SceneState, chunk: Int, left: Int) {
    val x = left + 20 + (Noise.unit(chunk, 1302) * (CHUNK - 50)).toInt()
    val base = FIELD_TOP + 9 + (Noise.unit(chunk, 1303) * 12).toInt()
    if (x < -14 || x > W) return
    val straw = solid(s, rgb(214, 184, 96), 0.06f)
    val shade = solid(s, rgb(176, 146, 70), 0.06f)
    val light = solid(s, rgb(236, 210, 130), 0.06f)
    if (Noise.unit(chunk, 1304) < 0.5f) {
        // A conical stack with a pole through the top.
        for (r in 0 until 8) {
            val half = 1 + r * 4 / 8 + (if (r > 4) 1 else 0)
            fill(x + 5 - half, base - 8 + r, x + 6 + half, base - 7 + r, if (r % 3 == 2) shade else straw)
        }
        fill(x + 5, base - 10, x + 6, base - 8, solid(s, rgb(110, 80, 50), 0.06f))
        fill(x + 2, base - 1, x + 10, base, shade)
    } else {
        // Round bales lying in a row (a pair, or a pair with one on top), seen from the side.
        for (b in 0..1) {
            val bx = x + b * 8
            fill(bx + 1, base - 5, bx + 6, base, straw)
            fill(bx, base - 4, bx + 7, base - 1, straw)
            fill(bx + 1, base - 5, bx + 6, base - 4, light)
            fill(bx + 3, base - 4, bx + 4, base - 1, shade)
            fill(bx + 1, base - 1, bx + 6, base, shade)
        }
        if (Noise.unit(chunk, 1305) < 0.5f) fill(x + 4, base - 9, x + 9, base - 5, straw)
    }
}

private fun PixelScene.pumpkinPatch(s: SceneState, chunk: Int, left: Int) {
    val x0 = left + 10 + (Noise.unit(chunk, 1312) * (CHUNK - 70)).toInt()
    val y0 = FIELD_TOP + 5 + (Noise.unit(chunk, 1313) * 8).toInt()
    if (x0 < -50 || x0 > W) return
    val vine = solid(s, rgb(54, 110, 44), 0.06f)
    val orange = solid(s, rgb(238, 122, 30), 0.06f)
    val deep = solid(s, rgb(196, 90, 24), 0.06f)
    // A grid of low pumpkins on their vines, a little off true so it looks planted and not printed.
    for (row in 0 until 3) for (col in 0 until 8) {
        val jx = (Noise.unit(chunk * 29 + row, col, 1314) * 3f).toInt()
        val jy = (Noise.unit(chunk * 29 + row, col, 1315) * 2f).toInt()
        val px = x0 + col * 7 + jx
        val py = y0 + row * 6 + jy
        if (px < -4 || px >= W || py >= ROAD_TOP - 5) continue
        fill(px - 2, py + 1, px + 5, py + 2, vine)
        fill(px, py - 1, px + 3, py + 1, orange)
        fill(px + 2, py, px + 3, py + 1, deep)
        fill(px + 1, py - 2, px + 2, py - 1, vine)
    }
}

/** Autumn leaves drifting down through the air wherever there are trees, red, orange and yellow, swaying as they fall. */
internal fun PixelScene.drawFallingLeaves(s: SceneState) {
    val amount = s.season.autumn * (1f - s.season.winter) * (1f - smoothstep(300f, 322f, s.season.day)) * (1f - (s.cond.rain * 0.7f + s.cond.snow * 0.5f + s.cond.thunder).coerceIn(0f, 1f))
    if (s.season.day <= 0f || amount < 0.08f) return
    val town = s.cityAt(s.cam + W / 2.0, 1.0)
    val gate = amount * (1f - town) * (1f - s.beachAt(s.cam + W / 2.0, 1.0))
    if (gate < 0.08f) return
    val colors = intArrayOf(rgb(214, 80, 38), rgb(232, 150, 40), rgb(222, 190, 60), rgb(170, 70, 36), rgb(150, 110, 54))
    val wind = min(1f, s.cond.wind / 30f)
    val count = (64 * gate).toInt()
    for (i in 0 until count) {
        val fall = 7f + Noise.unit(i, 1330) * 6f
        val cycle = (H + 20f) / fall
        val t = ((s.time + Noise.unit(i, 1331) * cycle) % cycle).toFloat()
        val y = -6f + t * fall
        val drift = (4f + wind * 14f) * t + sin(t * 1.8f + i) * 5f
        val layer = 0.5f + Noise.unit(i, 1332) * 0.5f
        val x = Math.floorMod((Noise.unit(i, 1333) * W + drift - s.cam.toFloat() * layer).toInt(), W + 20) - 10
        val flutter = (sin(t * 6f + i * 1.7f) > 0f)
        val c = colors[(Noise.unit(i, 1334) * colors.size).toInt().coerceIn(0, colors.size - 1)]
        if (flutter) fill(x, y.toInt(), x + 2, y.toInt() + 1, c) else fill(x, y.toInt(), x + 1, y.toInt() + 2, c)
    }
}

/** Migrating geese in a V high up, in autumn and again in spring, flapping slowly across the sky. */
internal fun PixelScene.drawMigration(s: SceneState) {
    val season = s.season
    if (season.day <= 0f) return
    val autumn = season.autumn * (1f - smoothstep(320f, 345f, season.day)) * smoothstep(205f, 230f, season.day)
    val spring = season.bloom
    val amount = maxOf(autumn, spring)
    val calm = (1f - s.cond.rain - s.cond.snow - s.cond.fog * 1.3f - s.cond.thunder).coerceIn(0f, 1f)
    val daylight = Colors.smoothstep(-2f, 8f, s.sunAlt.toFloat())
    if (amount * calm * daylight < 0.1f) return
    val slotLen = 40.0
    val slot = floor(s.time / slotLen).toInt()
    spritePaint.colorFilter = filter(s, 0.2f)
    for (k in slot - 1..slot) {
        if (Noise.unit(k, 1340) > 0.7f * amount * calm * daylight) continue
        val start = k * slotLen + Noise.unit(k, 1341) * slotLen * 0.5
        val e = (s.time - start).toFloat()
        if (e < 0f) continue
        val right = autumn >= spring
        val speed = 11f
        val lead = if (right) -30f + speed * e else W + 30f - speed * e
        val y0 = 14 + (Noise.unit(k, 1342) * 22).toInt()
        val n = 7 + (Noise.unit(k, 1343) * 6).toInt()
        for (b in 0 until n) {
            val back = ((b + 1) / 2) * 6
            val side = if (b % 2 == 0) 1 else -1
            val x = lead + (if (right) -back else back)
            val y = y0 + side * ((b + 1) / 2) * 3 + (sin(s.time * 1.0 + b * 0.5) * 1.2).toInt()
            if (x < -6 || x > W + 2) continue
            val up = (floor(s.time * 2.5 + b * 0.31).toInt() % 2) == 0
            sprite(if (up) Sprites.birdUp else Sprites.birdDown, x.toInt(), y)
        }
    }
    spritePaint.colorFilter = null
}
