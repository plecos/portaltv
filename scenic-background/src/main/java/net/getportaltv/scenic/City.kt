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

/**
 * The city: a skyline in the distance, blocks of shops and flats along the road, pavement, street lamps and crossings. Every
 * building asks how much city there is at its own place on the road and rises out of the ground as that grows, so the
 * city grows up gradually as the journey comes to it, and shrinks away again after.
 */

private fun SceneState.night() = (1f - look.day * 1.6f).coerceIn(0f, 1f)

private val WARM = rgb(255, 214, 120)

/** One layer of skyline: tall plain towers far away, with lit windows at night. */
private class Skyline(val factor: Double, val cell: Int, val minH: Float, val maxH: Float, val color: Int, val air: Float, val seed: Int, val windows: Boolean)

private val skylines = listOf(
    Skyline(0.06, 22, 14f, 60f, rgb(132, 144, 170), 0.55f, 811, false),
    Skyline(0.14, 26, 18f, 78f, rgb(104, 116, 146), 0.34f, 812, true)
)

/** The towers far away, behind the blocks and in front of the hills. */
internal fun PixelScene.drawSkyline(s: SceneState) {
    val night = s.night()
    for (layer in skylines) {
        val offset = floor(s.cam * layer.factor).toInt()
        val first = Math.floorDiv(offset, layer.cell) - 2
        val body = lit(s, layer.color, layer.air + s.look.haze * 0.3f)
        val roofLine = lerp(body, rgb(255, 255, 255), 0.12f)
        val side = lerp(body, rgb(0, 0, 20), 0.12f)
        val glass = lerp(body, s.look.skyBottom, 0.3f)
        for (i in first..first + W / layer.cell + 4) {
            val x0 = i * layer.cell + (Noise.unit(i, layer.seed, 1) * 8).toInt() - 4
            val w = layer.cell - 4 + (Noise.unit(i, layer.seed, 2) * layer.cell * 0.5f).toInt()
            val amount = s.cityAt((x0 + w / 2).toDouble(), layer.factor)
            if (amount <= 0.02f) continue
            val tall = Noise.unit(i, layer.seed, 3)
            var h = layer.minH + tall * tall * (layer.maxH - layer.minH)
            if (Noise.unit(i, layer.seed, 4) > 0.9f) h *= 1.35f
            h *= smoothstep(0.05f, 0.95f, amount)
            val bottom = FIELD_TOP + 3
            val top = bottom - h.toInt()
            if (h < 2f) continue
            val x = x0 - offset
            if (x + w < 0 || x > W) continue
            fill(x, top, x + w, bottom, body)
            fill(x, top, x + w, top + 1, roofLine)
            fill(x + w - 2, top + 1, x + w, bottom, side)
            if (layer.windows) {
                var wy = top + 3
                while (wy < bottom - 3) {
                    var wx = x + 2
                    var col = 0
                    while (wx < x + w - 3) {
                        val on = Noise.unit(x0 + col * 3, wy + i, layer.seed + 9) < 0.55f
                        fill(wx, wy, wx + 1, wy + 1, if (on) lerp(glass, WARM, night) else glass)
                        wx += 3
                        col++
                    }
                    wy += 3
                }
            }
            // An aerial on the tallest, with a red light that blinks at night.
            if (h > layer.maxH * 0.85f) {
                fill(x + w / 2, top - 5, x + w / 2 + 1, top, side)
                if (night > 0.2f && (s.time * 0.8).toInt() % 2 == 0) fill(x + w / 2, top - 6, x + w / 2 + 1, top - 5, rgb(255, 70, 60))
            }
        }
    }
}

private val BLOCK_COLORS = intArrayOf(rgb(158, 92, 74), rgb(190, 176, 150), rgb(128, 134, 150), rgb(112, 124, 138), rgb(176, 150, 112), rgb(142, 108, 100))
private val AWNINGS = intArrayOf(rgb(196, 60, 52), rgb(52, 120, 186), rgb(60, 150, 96), rgb(226, 170, 50), rgb(150, 70, 150))

/** The blocks of shops and flats along the road, with lit windows at night, rooftop clutter and now and then a billboard. */
internal fun PixelScene.drawCityBlocks(s: SceneState) {
    val night = s.night()
    val factor = 0.5
    val offset = floor(s.cam * factor).toInt()
    val cell = 66
    val first = Math.floorDiv(offset, cell) - 2
    val bottom = FIELD_TOP + 19
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val dark = lerp(glassDay, rgb(10, 12, 24), 0.45f)
    for (i in first..first + W / cell + 4) {
        val x0 = i * cell + (Noise.unit(i, 821) * 14).toInt()
        val w = 30 + (Noise.unit(i, 822) * 24).toInt()
        val amount = s.cityAt((x0 + w / 2).toDouble(), factor)
        if (amount <= 0.02f) continue
        val x = x0 - offset
        if (x + w < -4 || x > W + 4) continue
        val tall = Noise.unit(i, 823)
        val h = ((30f + tall * tall * 64f) * smoothstep(0.05f, 0.95f, amount)).toInt()
        if (h < 4) continue
        val top = bottom - h
        val base = BLOCK_COLORS[(Noise.unit(i, 824) * BLOCK_COLORS.size).toInt().coerceIn(0, BLOCK_COLORS.size - 1)]
        val body = lit(s, base, 0.04f + s.look.haze * 0.3f)
        val light = lerp(body, rgb(255, 255, 255), 0.12f)
        val shade = lerp(body, rgb(10, 10, 30), 0.22f)
        fill(x, top, x + w, bottom, body)
        fill(x, top, x + 1, bottom, light)
        fill(x + w - 3, top, x + w, bottom, shade)
        // The parapet.
        fill(x - 1, top - 1, x + w + 1, top + 1, shade)
        // Windows in rows, lit at night here and there.
        var wy = top + 5
        val shopTop = bottom - 11
        while (wy < shopTop - 5) {
            var wx = x + 4
            var col = 0
            while (wx + 3 < x + w - 4) {
                val on = Noise.unit(i * 31 + col, wy, 825) < 0.5f
                val glass = if (on && night > 0f) lerp(glassDay, WARM, night) else if (night > 0f) lerp(glassDay, dark, night) else glassDay
                fill(wx, wy, wx + 3, wy + 4, glass)
                fill(wx, wy + 4, wx + 3, wy + 5, shade)
                wx += 6
                col++
            }
            wy += 8
        }
        // The shop at the bottom: an awning over a glass front with the lights on at night.
        val awning = lit(s, AWNINGS[(Noise.unit(i, 826) * AWNINGS.size).toInt().coerceIn(0, AWNINGS.size - 1)], 0.04f)
        fill(x + 2, shopTop, x + w - 2, shopTop + 3, awning)
        var ax = x + 3
        while (ax < x + w - 3) { fill(ax, shopTop, ax + 2, shopTop + 3, lerp(awning, rgb(255, 255, 255), 0.45f)); ax += 4 }
        fill(x + 2, shopTop + 3, x + w - 2, bottom, lerp(glassDay, lerp(WARM, body, 0.35f), night * 0.8f))
        var mx = x + 2 + 8
        while (mx < x + w - 4) { fill(mx, shopTop + 3, mx + 1, bottom, lerp(body, rgb(20, 20, 30), 0.4f)); mx += 9 }
        fill(x + w / 2 - 1, shopTop + 4, x + w / 2 + 2, bottom, lerp(body, rgb(40, 30, 30), 0.5f))
        // On the roof: an air-conditioning box, and on some a water tank, an aerial or a billboard.
        val roofX = x + 3 + (Noise.unit(i, 827) * (w - 12)).toInt()
        fill(roofX, top - 4, roofX + 6, top - 1, lerp(body, rgb(170, 170, 176), 0.5f))
        val extra = Noise.unit(i, 828)
        when {
            extra < 0.25f -> tank(s, x + w - 12, top - 1, shade)
            extra < 0.5f -> fill(x + 6, top - 9, x + 7, top - 1, shade)
            extra < 0.62f -> billboard(s, i, x + 4, top - 1, night)
        }
    }
}

private fun PixelScene.tank(s: SceneState, x: Int, top: Int, shade: Int) {
    val wood = lit(s, rgb(120, 86, 58), 0.04f)
    fill(x + 1, top - 5, x + 2, top, shade)
    fill(x + 6, top - 5, x + 7, top, shade)
    fill(x, top - 12, x + 8, top - 5, wood)
    fill(x, top - 13, x + 8, top - 12, lerp(wood, rgb(255, 255, 255), 0.2f))
    fill(x + 3, top - 15, x + 5, top - 13, shade)
}

private fun PixelScene.billboard(s: SceneState, i: Int, x: Int, top: Int, night: Float) {
    val colors = intArrayOf(rgb(230, 70, 90), rgb(60, 170, 230), rgb(250, 200, 60), rgb(110, 220, 130))
    val c = colors[(Noise.unit(i, 829) * colors.size).toInt().coerceIn(0, colors.size - 1)]
    val post = lit(s, rgb(70, 70, 78), 0.04f)
    fill(x + 3, top - 5, x + 4, top, post)
    fill(x + 14, top - 5, x + 15, top, post)
    fill(x, top - 14, x + 19, top - 5, lit(s, rgb(220, 220, 224), 0.04f))
    fill(x + 1, top - 13, x + 18, top - 6, lerp(lit(s, c, 0.04f), c, night))
    fill(x + 4, top - 11, x + 15, top - 10, rgb(255, 255, 255))
    fill(x + 4, top - 9, x + 11, top - 8, rgb(255, 255, 255))
    if (night > 0.2f) fillAlpha(x - 3, top - 17, x + 22, top - 3, c, night * 0.16f)
}

/** Street lamps along the far pavement, with a glow on the ground at night. */
internal fun PixelScene.drawStreetLamps(s: SceneState) {
    val night = s.night()
    val offset = floor(s.cam).toInt()
    val spacing = 104
    val first = Math.floorDiv(offset, spacing) - 1
    val post = lit(s, rgb(58, 62, 72), 0.0f)
    for (i in first..first + W / spacing + 2) {
        val wx = i * spacing + 30
        val amount = s.cityAt(wx.toDouble(), 1.0)
        if (amount <= 0.05f) continue
        val h = (22 * smoothstep(0.1f, 0.9f, amount)).toInt()
        if (h < 3) continue
        val x = wx - offset
        fill(x, ROAD_TOP - h, x + 1, ROAD_TOP, post)
        fill(x, ROAD_TOP - h, x + 5, ROAD_TOP - h + 1, post)
        fill(x + 3, ROAD_TOP - h + 1, x + 6, ROAD_TOP - h + 2, if (night > 0.2f) rgb(255, 236, 170) else lit(s, rgb(210, 210, 200), 0.0f))
        if (night > 0.05f && h > 18) {
            // A soft glow round the lamp.
            fillAlpha(x + 2, ROAD_TOP - h, x + 8, ROAD_TOP - h + 5, rgb(255, 226, 150), night * 0.22f)
        }
    }
}

/** Concrete in place of the fields and grass, a pavement along the road and a crossing now and then. */
internal fun PixelScene.drawCityGround(s: SceneState) {
    val concrete = lit(s, rgb(116, 118, 126), 0.1f + s.look.haze * 0.3f)
    val pavement = lit(s, rgb(170, 168, 160), 0.06f)
    val joint = lit(s, rgb(136, 134, 128), 0.06f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.cityAt((x + cam).toDouble(), 1.0)
        if (a <= 0.01f) continue
        fillAlpha(x, FIELD_TOP, x + 1, ROAD_TOP - 5, concrete, a * 0.97f)
        fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP, pavement, a)
        if (Math.floorMod(x + cam, 14) == 0) fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP, joint, a)
        fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP - 4, lerp(pavement, rgb(255, 255, 255), 0.2f), a)
    }
}

/** The pavement in front of the road, drawn over the grass, and zebra crossings over the road. */
internal fun PixelScene.drawCityRoad(s: SceneState) {
    val pavement = lit(s, rgb(150, 148, 142), 0.0f)
    val joint = lit(s, rgb(120, 118, 112), 0.0f)
    val kerb = lit(s, rgb(190, 188, 180), 0.0f)
    val white = lit(s, rgb(236, 232, 214), 0.02f, 0.1f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.cityAt((x + cam).toDouble(), 1.0)
        if (a <= 0.01f) continue
        fillAlpha(x, ROAD_BOTTOM + 2, x + 1, H, pavement, a)
        fillAlpha(x, ROAD_BOTTOM + 2, x + 1, ROAD_BOTTOM + 3, kerb, a)
        if (Math.floorMod(x + cam, 12) == 0) fillAlpha(x, ROAD_BOTTOM + 3, x + 1, H, joint, a)
    }
    // Zebra crossings, every so often.
    val spacing = 560
    val first = Math.floorDiv(cam, spacing) - 1
    for (i in first..first + W / spacing + 2) {
        val wx = i * spacing + 120 + (Noise.unit(i, 831) * 200).toInt()
        if (s.cityAt(wx.toDouble(), 1.0) < 0.8f) continue
        val x = wx - cam
        var sx = x
        while (sx < x + 22) { fill(sx, ROAD_TOP + 3, sx + 3, ROAD_BOTTOM - 2, white); sx += 5 }
    }
}
