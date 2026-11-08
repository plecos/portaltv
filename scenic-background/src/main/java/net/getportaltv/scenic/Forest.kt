package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_BOTTOM
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.floor
import kotlin.math.sin

/** Things that only the forest has: the big trees along the road and mist on the ground. */

private val bigFirs by lazy { listOf(Sprites.fir(8), Sprites.fir(10), Sprites.fir(12)) }
private val bigBroads by lazy { listOf(Sprites.broadleaf(10, 4), Sprites.broadleaf(12, 5), Sprites.broadleaf(14, 6)).map { SeasonalTree(it) } }

private val frontFirs by lazy { listOf(Sprites.fir(9), Sprites.fir(11), Sprites.fir(13)) }
private val frontBroads by lazy { listOf(Sprites.broadleaf(12, 7), Sprites.broadleaf(14, 8), Sprites.broadleaf(17, 9)).map { SeasonalTree(it) } }
private val midFirs by lazy { listOf(Sprites.fir(6), Sprites.fir(8)) }
private val midBroads by lazy { listOf(Sprites.broadleaf(8, 10), Sprites.broadleaf(10, 11)).map { SeasonalTree(it) } }

/**
 * Trees on the near side of the road, in front of it and the traffic, so the road runs through the wood: a row of smaller ones
 * close to the road, and in front of them big ones whose crowns hang low over the lanes.
 */
internal fun PixelScene.drawFrontTrees(s: SceneState) {
    spritePaint.colorFilter = filter(s, 0f)
    for (row in 0..1) {
        val factor = if (row == 0) 1.15 else 1.3
        val offset = floor(s.cam * factor)
        val cell = if (row == 0) 30 else 42
        val first = floor(offset / cell).toInt() - 2
        for (i in first..first + W / cell + 5) {
            val worldX = i * cell + (Noise.unit(i, 640 + row * 10) * cell * 0.9f).toInt()
            val f = s.forestAt(worldX.toDouble(), factor)
            if (f < 0.5f || Noise.unit(i, 641 + row * 10) > f * (if (row == 0) 0.65f else 0.8f)) continue
            val x = (worldX - offset).toInt()
            if (s.riverCovers(x, H - 3, 16)) continue
            val sway = (sin(s.time * 1.2 + i + row) * minOf(1f, s.cond.wind / 30f)).toFloat().let { Math.round(it) }
            val base = if (row == 0) ROAD_BOTTOM + 5 + (Noise.unit(i, 642) * 4).toInt() else H - 1 - (Noise.unit(i, 642) * 5).toInt()
            val fir = Noise.unit(i, 643 + row * 10) < 0.6f
            val sprite = if (row == 0) {
                if (fir) midFirs[(Noise.unit(i, 644) * midFirs.size).toInt().coerceIn(0, midFirs.size - 1)]
                else midBroads[(Noise.unit(i, 644) * midBroads.size).toInt().coerceIn(0, midBroads.size - 1)].at(s.season, i * 17)
            } else {
                if (fir) frontFirs[(Noise.unit(i, 654) * frontFirs.size).toInt().coerceIn(0, frontFirs.size - 1)]
                else frontBroads[(Noise.unit(i, 654) * frontBroads.size).toInt().coerceIn(0, frontBroads.size - 1)].at(s.season, i * 13)
            }
            sprite(sprite, x - sprite.width / 2 + sway, base)
        }
    }
    spritePaint.colorFilter = null
}

/** The tall trees standing right beside the road, in front of the rest of the wood. */
internal fun PixelScene.drawRoadsideTrees(s: SceneState) {
    val factor = 0.9
    val offset = floor(s.cam * factor)
    val cell = 30
    val first = floor(offset / cell).toInt() - 2
    spritePaint.colorFilter = filter(s, 0.02f + s.look.haze * 0.25f)
    for (i in first..first + W / cell + 5) {
        val worldX = i * cell + (Noise.unit(i, 621) * 20).toInt()
        val f = s.forestAt(worldX.toDouble(), factor)
        if (f <= 0f || Noise.unit(i, 622) > f * 0.85f) continue
        if (nearCamp(s, worldX)) continue
        val x = (worldX - offset).toInt()
        if (s.riverCovers(x, ROAD_TOP - 2, 12)) continue
        val tree = if (Noise.unit(i, 623) < 0.6f) bigFirs[(Noise.unit(i, 624) * bigFirs.size).toInt().coerceIn(0, bigFirs.size - 1)]
        else bigBroads[(Noise.unit(i, 624) * bigBroads.size).toInt().coerceIn(0, bigBroads.size - 1)].at(s.season, i * 11)
        val sway = (sin(s.time * 1.3 + i) * minOf(1f, s.cond.wind / 30f)).toFloat().let { Math.round(it) }
        sprite(tree, x - tree.width / 2 + sway, ROAD_TOP - 1 - (Noise.unit(i, 625) * 3).toInt())
    }
    spritePaint.colorFilter = null
}

/** Mist lying along the ground in the woods, thicker at dawn and dusk and when it is damp. */
internal fun PixelScene.drawForestMist(s: SceneState) {
    val forest = s.forestAt(s.cam + W / 2.0, 1.0)
    if (forest < 0.05f) return
    val low = 1f - smoothstep(6f, 24f, s.sunAlt.toFloat())
    val damp = maxOf(s.cond.rain, s.cond.fog)
    val amount = forest * (0.18f + 0.5f * low + 0.4f * damp).coerceAtMost(0.8f)
    val color = lerp(s.look.fogColor, rgb(220, 230, 235), 0.4f * (1f - low))
    for (band in 0 until 4) {
        val y = FIELD_TOP + band * 4 - 2
        val drift = (s.time * (1.2 + band * 0.4) + band * 40) % W
        for (seg in 0 until 3) {
            val x0 = ((seg * (W / 3f) + drift) % (W + 60)).toInt() - 60
            fillAlpha(x0, y, x0 + 90, y + 3, color, amount * (0.22f - band * 0.03f))
        }
    }
}

private const val CAMP_CELL = 420
private const val CAMP_FACTOR = 0.9

/** The world position of the campsite in cell [i], or -1 if there is none: one in three or so cells deep in the forest. */
private fun SceneState.campIn(i: Int): Int {
    if (Noise.unit(i, 660) > 0.34f) return -1
    val x = i * CAMP_CELL + 90 + (Noise.unit(i, 661) * (CAMP_CELL - 180)).toInt()
    return if (forestAt(x.toDouble(), CAMP_FACTOR) > 0.8f) x else -1
}

/** Whether [worldX] (on the roadside trees' layer) is in a campsite's clearing. */
private fun nearCamp(s: SceneState, worldX: Int): Boolean {
    val i = Math.floorDiv(worldX, CAMP_CELL)
    for (c in i - 1..i + 1) {
        val x = s.campIn(c)
        if (x >= 0 && kotlin.math.abs(worldX - x) < 36) return true
    }
    return false
}

/** Now and then a camp in a clearing by the road: a tent or two, a campfire with smoke, and at night the glow of the fire. */
internal fun PixelScene.drawCampsites(s: SceneState) {
    val offset = floor(s.cam * CAMP_FACTOR)
    val first = Math.floorDiv(offset.toInt(), CAMP_CELL) - 1
    val night = s.night()
    val base = ROAD_TOP - 3
    for (i in first..first + W / CAMP_CELL + 2) {
        val wx = s.campIn(i)
        if (wx < 0) continue
        val cx = (wx - offset).toInt()
        if (cx < -40 || cx > W + 40) continue
        // The ground in the clearing, and the tents either side of the fire.
        val dirt = solid(s, rgb(110, 92, 62), 0.04f)
        fillAlpha(cx - 20, base - 1, cx + 21, base, dirt, 0.4f)
        fillAlpha(cx - 28, base, cx + 29, base + 2, dirt, 0.5f)
        fillAlpha(cx - 22, base + 2, cx + 23, base + 3, dirt, 0.35f)
        val colors = arrayOf(rgb(214, 120, 52), rgb(60, 130, 190), rgb(190, 70, 70), rgb(96, 150, 84))
        for (t in 0..1) {
            val tx = if (t == 0) cx - 22 else cx + 14
            if (t == 1 && Noise.unit(i, 662) < 0.4f) continue
            tent(s, tx, base, colors[(Noise.unit(i, 663 + t) * colors.size).toInt().coerceIn(0, colors.size - 1)], t == 1)
        }
        // A log to sit on, and the fire: a ring of stones, crossed logs and a flickering flame.
        fill(cx - 12, base - 2, cx - 6, base, solid(s, rgb(96, 66, 44), 0.04f))
        fill(cx + 7, base - 2, cx + 13, base, solid(s, rgb(96, 66, 44), 0.04f))
        fill(cx - 4, base, cx + 5, base + 1, solid(s, rgb(110, 110, 114), 0.04f))
        fill(cx - 3, base - 1, cx + 4, base, solid(s, rgb(70, 48, 34), 0.04f))
        val flick = Noise.unit((s.time * 9).toInt(), i, 664)
        val tall = 3 + (flick * 3).toInt()
        val fx = cx - 1 + (if (flick > 0.6f) 1 else 0)
        fill(cx - 2, base - 3, cx + 3, base - 1, rgb(232, 92, 30))
        fill(cx - 1, base - 3 - tall + 2, cx + 2, base - 3, rgb(255, 150, 40))
        fill(fx, base - 2 - tall + 1, fx + 1, base - 2, rgb(255, 232, 130))
        // Smoke drifting up and away.
        for (k in 0 until 7) {
            val age = ((s.time * 0.9 + k * 0.7 + i) % 5.0).toFloat() / 5f
            val sx = cx + (age * 6f).toInt() + (sin(s.time * 0.8 + k) * 2).toInt()
            val sy = base - 6 - (age * 26f).toInt()
            fillAlpha(sx, sy, sx + 2 + (age * 2).toInt(), sy + 2, rgb(190, 190, 196), (1f - age) * 0.35f * (1f - 0.5f * night))
        }
        // The glow of the fire on the ground and the tents once it is dark.
        if (night > 0.1f) {
            for (r in intArrayOf(30, 20, 11)) {
                for (dy in -r / 3..r / 6) {
                    val half = (kotlin.math.sqrt(1f - (dy * dy).toFloat() / ((r / 3f) * (r / 3f))) * r).toInt()
                    if (half > 0) fillAlpha(cx - half, base - 4 + dy, cx + half, base - 3 + dy, rgb(255, 150, 60), night * 0.08f)
                }
            }
        }
    }
}

private fun PixelScene.tent(s: SceneState, x: Int, base: Int, color: Int, flip: Boolean) {
    val body = solid(s, color, 0.04f)
    val dark = Colors.mul(body, 0.7f, 0.7f, 0.74f)
    val h = 9
    for (r in 0 until h) {
        val half = r * 7 / h
        val y = base - h + r + 1
        fill(x + 7 - half, y, x + 8 + half, y + 1, if ((r + (if (flip) 1 else 0)) % 4 == 3) dark else body)
    }
    // The door flap, and the pole at the top.
    fill(x + 6, base - 4, x + 9, base + 1, rgb(26, 22, 28))
    fill(x + 7, base - h - 1, x + 8, base - h + 1, solid(s, rgb(150, 120, 80), 0.04f))
    fill(x, base, x + 15, base + 1, dark)
}
