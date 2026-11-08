package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_BOTTOM
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * A covered bridge now and then in the deep forest: the road runs through it, and the river under it comes out of the distance
 * toward us and goes on in front of the road. The river runs straight away from the road, so it heads for the middle of the horizon
 * (leaning toward it a little), and swings slightly as the journey goes by.
 */
private const val SPAN = 3000.0
internal const val BRIDGE_WIDTH = 52
/** Where the river meets the horizon (the vanishing point is the middle of the screen). */
private const val HORIZON_Y = FIELD_TOP - 3
private const val RIVER_HALF_AT_ROAD = 21f

/** Works out the left edges of the covered bridges on screen now (once a frame: it is kept in [SceneState.bridges]). */
internal fun SceneState.findBridges(): IntArray {
    val camX = floor(cam)
    val first = floor((camX - BRIDGE_WIDTH - 200) / SPAN).toInt()
    val last = floor((camX + W + 200) / SPAN).toInt()
    var out = IntArray(0)
    for (k in first..last) {
        if (Noise.unit(k, 720) > 0.25f) continue
        val world = k * SPAN + 300 + Noise.unit(k, 721) * 2000
        if (Places.forest(world, placeMode) < 0.9f) continue
        out += (world - camX).toInt()
    }
    return out
}

/** The river's left and right edge at row [y] for the bridge at [left], or null if the river is not there. */
private fun riverSpan(left: Int, y: Int): IntArray? {
    val t = (ROAD_TOP - y) / (ROAD_TOP - HORIZON_Y).toFloat()
    if (t >= 1f) return null
    val half = (1f + (RIVER_HALF_AT_ROAD - 1f) * (1f - t)).coerceAtMost(40f)
    val xr = left + BRIDGE_WIDTH / 2f
    // It heads for the middle of the horizon, but only leans a little, so it still reads as one river under the bridge.
    val centre = xr + (W / 2f - xr) * t * 0.45f
    return intArrayOf((centre - half).toInt(), (centre + half).toInt())
}

/** Whether there is river or a bridge at [x] at row [y]: the trees stay out of its way. */
internal fun SceneState.riverCovers(x: Int, y: Int, margin: Int = 6): Boolean {
    for (left in bridges) {
        if (x in (left - margin)..(left + BRIDGE_WIDTH + margin) && y >= ROAD_TOP - 24) return true
        val span = riverSpan(left, y) ?: continue
        if (x in (span[0] - margin)..(span[1] + margin)) return true
    }
    return false
}

private fun PixelScene.water(s: SceneState): Int = lerp(solid(s, rgb(60, 118, 172), 0.06f), s.look.skyBottom, 0.3f)

private fun PixelScene.riverRows(s: SceneState, left: Int, from: Int, to: Int) {
    val water = water(s)
    val deep = lerp(water, rgb(20, 40, 70), 0.25f)
    val bank = solid(s, rgb(96, 82, 56), 0.06f)
    for (y in from until to) {
        val span = riverSpan(left, y) ?: continue
        val x0 = span[0]
        val x1 = span[1]
        fill(x0 - 1, y, x0, y + 1, bank)
        fill(x1, y, x1 + 1, y + 1, bank)
        fill(x0, y, x1, y + 1, if ((y + left) % 3 == 0) deep else water)
        // Light on the water, running slowly toward us.
        val sparkle = (sin(s.time * 1.7 + y * 0.9 + left * 0.3)).toFloat()
        if (sparkle > 0.55f && s.look.day > 0.25f) {
            val sx = x0 + ((y * 7 + left) % (x1 - x0).coerceAtLeast(2))
            fillAlpha(sx, y, sx + 2, y + 1, rgb(255, 255, 255), (sparkle - 0.55f) * 1.4f)
        }
    }
}

/** The river as it comes out of the distance, behind the road: drawn under the railway, so the track crosses it. */
internal fun PixelScene.drawRiverBehind(s: SceneState) {
    for (left in s.bridges) riverRows(s, left, FIELD_TOP, ROAD_TOP)
}

/** The river in front of the road, with a plank bridge for anyone walking along the verge. */
internal fun PixelScene.drawRiverFront(s: SceneState) {
    for (left in s.bridges) {
        riverRows(s, left, ROAD_BOTTOM + 2, H)
        val a = riverSpan(left, ROAD_BOTTOM + 8) ?: continue
        val b = riverSpan(left, H - 1) ?: continue
        val x0 = minOf(a[0], b[0]) - 3
        val x1 = maxOf(a[1], b[1]) + 3
        val plank = solid(s, rgb(130, 94, 60), 0.0f)
        val dark = solid(s, rgb(90, 62, 40), 0.0f)
        fill(x0, ROAD_BOTTOM + 8, x1, ROAD_BOTTOM + 11, plank)
        fill(x0, ROAD_BOTTOM + 10, x1, ROAD_BOTTOM + 11, dark)
        var px = x0
        while (px < x1) { fill(px, ROAD_BOTTOM + 8, px + 1, ROAD_BOTTOM + 11, dark); px += 4 }
    }
}

/** The covered bridge itself, over the cars: a roofed wooden box with a long dark opening at the height of the traffic. */
internal fun PixelScene.drawBridge(s: SceneState) {
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    val wall = solid(s, rgb(150, 54, 46), 0.0f)
    val wallDark = solid(s, rgb(118, 40, 36), 0.0f)
    val roof = solid(s, rgb(90, 62, 54), 0.0f)
    val ridge = solid(s, rgb(128, 90, 68), 0.0f)
    val stone = solid(s, rgb(128, 124, 118), 0.0f)
    val inside = rgb(18, 16, 22)
    for (left in s.bridges) {
        val right = left + BRIDGE_WIDTH
        // The wall above the opening, with boards.
        fill(left, ROAD_TOP - 6, right, ROAD_TOP, wall)
        var bx = left + 1
        while (bx < right) { fill(bx, ROAD_TOP - 6, bx + 1, ROAD_TOP, wallDark); bx += 3 }
        // The roof, overhanging at each end, with a lighter ridge.
        fill(left - 3, ROAD_TOP - 13, right + 3, ROAD_TOP - 6, roof)
        fill(left - 3, ROAD_TOP - 13, right + 3, ROAD_TOP - 12, ridge)
        var rx = left - 2
        while (rx < right + 3) { fill(rx, ROAD_TOP - 11, rx + 1, ROAD_TOP - 9, solid(s, rgb(70, 46, 40), 0.0f)); rx += 4 }
        // The opening: dark inside, so cars show dimly as they pass through, behind the posts.
        val open0 = ROAD_TOP
        val open1 = ROAD_BOTTOM
        fillAlpha(left + 3, open0, right - 3, open1, inside, 0.42f + 0.1f * (1f - night))
        if (night > 0.2f) {
            // A lantern in the middle.
            val mid = (left + right) / 2
            fillAlpha(mid - 5, open0, mid + 5, open0 + 4, rgb(255, 214, 130), night * 0.5f)
            fill(mid - 1, open0, mid + 1, open0 + 1, rgb(255, 236, 170))
        }
        var px = left
        while (px <= right - 2) { fill(px, open0, px + 2, open1 + 2, wallDark); px += 17 }
        fill(right - 2, open0, right, open1 + 2, wallDark)
        // The stone below, which the water runs under.
        fill(left, open1, right, open1 + 2, stone)
        fill(left, open1, right, open1 + 1, solid(s, rgb(150, 146, 140), 0.0f))
    }
}
