package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_BOTTOM
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The city: a skyline in the distance, blocks of shops and flats along the road, pavement, street lamps and crossings. Every
 * building asks how much city there is at its own place on the road and rises out of the ground as that grows, so the
 * city grows up gradually as the journey comes to it, and shrinks away again after.
 */

internal fun SceneState.night() = (1f - look.day * 1.6f).coerceIn(0f, 1f)

internal val WARM = rgb(255, 214, 120)

/** The colours of festive lights. */
internal val FESTIVE = intArrayOf(rgb(255, 70, 60), rgb(70, 220, 110), rgb(255, 214, 80), rgb(90, 160, 255), rgb(255, 120, 220))

/** One festive bulb at ([x], [y]); [k] picks its colour and when it twinkles. */
internal fun PixelScene.bulb(s: SceneState, x: Int, y: Int, k: Int, night: Float) {
    val fest = s.season.festive
    val c = FESTIVE[Math.floorMod(k, FESTIVE.size)]
    val bright = if ((s.time * 1.6).toInt() % 3 == Math.floorMod(k, 3)) 0.5f else 1f
    val a = fest * (0.5f + 0.5f * night) * bright
    fillAlpha(x, y, x + 1, y + 1, c, a)
    if (night > 0.2f) fillAlpha(x - 1, y - 1, x + 2, y + 2, c, night * 0.16f * fest * bright)
}

/** Lights scattered over a conifer standing with its foot at ([x] + [w] / 2, [bottom]). */
internal fun PixelScene.treeLights(s: SceneState, x: Int, bottom: Int, w: Int, h: Int, seed: Int, night: Float) {
    if (s.season.festive < 0.02f) return
    for (k in 0 until 9) {
        val row = 1 + (Noise.unit(seed, k, 840) * (h - 3)).toInt()
        val reach = (row * (w / 2) / (h - 2)).coerceAtLeast(1)
        val dx = (Noise.unit(seed, k, 841) * (reach * 2 + 1)).toInt() - reach
        bulb(s, x + w / 2 + dx, bottom - h + row, seed + k, night)
    }
}

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
        val body = solid(s, layer.color, layer.air + s.look.haze * 0.3f)
        val roofLine = lerp(body, rgb(255, 255, 255), 0.12f)
        val side = lerp(body, rgb(0, 0, 20), 0.12f)
        val glass = lerp(body, s.look.skyBottom, 0.3f)
        val paleGlass = lerp(glass, rgb(255, 255, 255), 0.14f)
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
            val sc = scale
            val white = rgb(255, 255, 255)
            if (hiRes) {
                // (16-bit) A tower with form: lit left edge, shaded right side, vertical ribs, a stepped crown and a ledge, in see-through strokes.
                val ink = rgb(0, 0, 20)
                fillAlphaPhys(x * sc, top * sc, x * sc + 2, bottom * sc, white, 0.20f)
                fillAlphaPhys((x + w) * sc - 5, top * sc, (x + w) * sc, bottom * sc, ink, 0.18f)
                var rib = x * sc + 8
                while (rib < (x + w) * sc - 6) { fillAlphaPhys(rib, top * sc + 2, rib + 1, bottom * sc, ink, 0.07f); rib += 10 }
                fillAlphaPhys(x * sc, top * sc, (x + w) * sc, top * sc + 2, white, 0.32f)
                if (layer.windows && w > 12) {
                    // A setback crown on the taller ones.
                    val cw = (w * 0.55f).toInt()
                    fillPhys((x + (w - cw) / 2) * sc, (top - 3) * sc, (x + (w + cw) / 2) * sc, top * sc, body)
                    fillAlphaPhys((x + (w - cw) / 2) * sc, (top - 3) * sc, (x + (w - cw) / 2) * sc + 2, top * sc, white, 0.2f)
                    fillAlphaPhys((x + (w - cw) / 2) * sc, (top - 3) * sc, (x + (w + cw) / 2) * sc, (top - 3) * sc + 1, white, 0.3f)
                }
            }
            if (layer.windows) {
                var wy = top + 3
                while (wy < bottom - 3) {
                    var wx = x + 2
                    var col = 0
                    while (wx < x + w - 3) {
                        val on = Noise.unit(x0 + col * 3, wy + i, layer.seed + 9) < 0.55f
                        val wc = if (on) lerp(glass, WARM, night) else glass
                        if (hiRes) {
                            // A narrower, taller pane with a lighter top, like a real window, and a faint glow when lit.
                            // One fill for each (a lighter pane when dark inside, so there is a hint of sky in the glass).
                            fillPhys(wx * sc + 1, wy * sc, wx * sc + 4, wy * sc + 4, if (on) wc else paleGlass)
                        } else fill(wx, wy, wx + 1, wy + 1, wc)
                        wx += 3
                        col++
                    }
                    wy += 3
                }
            }
            // An aerial on the tallest, with a red light that blinks at night.
            if (h > layer.maxH * 0.85f) {
                if (hiRes) {
                    line(x + w / 2f + 0.5f, top - 8f, x + w / 2f + 0.5f, top.toFloat(), side)
                    for (e in 1..3) fillPhys((x + w / 2) * sc - 2, (top - e * 2) * sc, (x + w / 2) * sc + 4, (top - e * 2) * sc + 1, side)
                    if (night > 0.2f && (s.time * 0.8).toInt() % 2 == 0) { fillGlow(x + w / 2f + 0.5f, top - 8.6f, 3f, rgb(255, 70, 60), 0.55f); fillOval(x + w / 2f - 0.1f, top - 9.2f, x + w / 2f + 1.1f, top - 8.0f, rgb(255, 90, 80), 1f) }
                } else {
                fill(x + w / 2, top - 5, x + w / 2 + 1, top, side)
                if (night > 0.2f && (s.time * 0.8).toInt() % 2 == 0) fill(x + w / 2, top - 6, x + w / 2 + 1, top - 5, rgb(255, 70, 60))
                }
            }
        }
    }
}

private val BLOCK_COLORS = intArrayOf(rgb(158, 92, 74), rgb(190, 176, 150), rgb(128, 134, 150), rgb(112, 124, 138), rgb(176, 150, 112), rgb(142, 108, 100))
private val AWNINGS = intArrayOf(rgb(196, 60, 52), rgb(52, 120, 186), rgb(60, 150, 96), rgb(226, 170, 50), rgb(150, 70, 150))

/** Whether column [x] is inside a park, away from the portals at its ends: where the subway shows. */
internal fun SceneState.inPark(x: Int): Boolean {
    var i = 0
    while (i < parks.size) {
        if (x > parks[i] + 6 && x < parks[i + 1] - 6) return true
        i += 2
    }
    return false
}

/** What fills a stretch of frontage: buildings side by side with no gaps, and now and then a park or a school. */
private const val NORMAL = 0
private const val PARK = 1
private const val SCHOOL = 2
private const val TOWER = 3
private const val LOT = 120
private const val NEAR_FACTOR = 0.5

/**
 * The clock tower stands once in every city, in the lot at its middle: for a stretch of the journey that is the city, the lot
 * halfway along it (the city's layer moves at [NEAR_FACTOR] of the journey).
 */
private fun lotKind(lot: Int, mode: String): Int {
    if (mode == Places.JOURNEY || mode == Places.CITY) {
        val stretch = Math.floorDiv(lot * LOT, (Places.STRETCH * NEAR_FACTOR).toInt())
        val middle = Math.floorDiv(((stretch * Places.STRETCH + Places.STRETCH / 2) * NEAR_FACTOR).toInt(), LOT)
        if (lot == middle && (mode == Places.CITY || Places.placeOf(stretch) == Places.CITY)) return TOWER
    }
    val u = Noise.unit(lot, 840)
    return if (u < 0.07f) PARK else if (u < 0.12f) SCHOOL else NORMAL
}

/** Works out where the parks are on screen (once a frame, in [SceneState.parks]): the subway comes up into daylight in them. */
internal fun SceneState.findParks(): IntArray {
    val offset = floor(cam * NEAR_FACTOR).toInt()
    val first = Math.floorDiv(offset, LOT) - 1
    var out = IntArray(0)
    for (lot in first..first + W / LOT + 2) {
        if (lotKind(lot, placeMode) != PARK || cityAt((lot * LOT + LOT / 2).toDouble(), NEAR_FACTOR) < 0.85f) continue
        out += intArrayOf(lot * LOT - offset, lot * LOT - offset + LOT)
    }
    return out
}

/** The frontage along the road, with lit windows at night, rooftop clutter and now and then a billboard, a park or a school. */
internal fun PixelScene.drawCityBlocks(s: SceneState) {
    val night = s.night()
    val offset = floor(s.cam * NEAR_FACTOR).toInt()
    val first = Math.floorDiv(offset, LOT) - 1
    val bottom = FIELD_TOP + 19
    for (lot in first..first + W / LOT + 2) {
        val x0 = lot * LOT - offset
        val amount = s.cityAt((lot * LOT + LOT / 2).toDouble(), NEAR_FACTOR)
        if (amount <= 0.02f) continue
        val growth = smoothstep(0.05f, 0.95f, amount)
        when (lotKind(lot, s.placeMode)) {
            PARK -> drawPark(s, lot, x0, bottom, amount)
            SCHOOL -> drawSchool(s, lot, x0, bottom, growth, night)
            TOWER -> {
                building(s, lot * 7, x0, LOT / 2, bottom, growth, night)
                clockTower(s, lot, x0 + LOT / 2 + 8, bottom, growth, night)
            }
            else -> for (half in 0..1) {
                val x = x0 + half * (LOT / 2)
                // A cell is one building, or two narrow ones.
                if (Noise.unit(lot, half, 830) < 0.35f) {
                    building(s, lot * 7 + half * 2, x, LOT / 4, bottom, growth, night)
                    building(s, lot * 7 + half * 2 + 1, x + LOT / 4, LOT / 4, bottom, growth, night)
                } else building(s, lot * 7 + half * 2, x, LOT / 2, bottom, growth, night)
            }
        }
    }
}

private fun PixelScene.building(s: SceneState, i: Int, x: Int, w: Int, bottom: Int, growth: Float, night: Float) {
    if (x + w < -4 || x > W + 4) return
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val dark = lerp(glassDay, rgb(10, 12, 24), 0.45f)
    val tall = Noise.unit(i, 823)
    val h = ((30f + tall * tall * 64f) * growth).toInt()
    if (h < 4) return
    val top = bottom - h
    val base = BLOCK_COLORS[(Noise.unit(i, 824) * BLOCK_COLORS.size).toInt().coerceIn(0, BLOCK_COLORS.size - 1)]
    val body = solid(s, base, 0.04f + s.look.haze * 0.3f)
    val light = lerp(body, rgb(255, 255, 255), 0.12f)
    val shade = lerp(body, rgb(10, 10, 30), 0.22f)
    fill(x, top, x + w, bottom, body)
    fill(x, top, x + 1, bottom, light)
    fill(x + w - 2, top, x + w, bottom, shade)
    // The parapet.
    fill(x - 1, top - 1, x + w + 1, top + 1, shade)
    // (16-bit) courses of brick on the older buildings, a falling off of the light down the wall, a shadow under the parapet.
    if (hiRes) {
        if (base == BLOCK_COLORS[0] || base == BLOCK_COLORS[5] || base == BLOCK_COLORS[4]) brickTexture(x + 1, top + 1, x + w - 2, bottom - 11, i)
        wallFalloff(x + 1, top + 1, x + w - 2, bottom)
        eaveShadow(x + 1, x + w - 1, top + 1)
    }
    // Snow lies along the roof edge.
    if (s.snowCover > 0.03f) fillAlpha(x - 1, top - 2, x + w + 1, top, solid(s, rgb(238, 242, 250), 0.04f), s.snowCover)
    // Windows in rows, lit at night here and there.
    var wy = top + 5
    val shopTop = bottom - 11
    while (wy < shopTop - 5) {
        var wx = x + 3
        var col = 0
        while (wx + 3 < x + w - 3) {
            val on = Noise.unit(i * 31 + col, wy, 825) < 0.5f
            val coloured = on && s.season.festive > 0.02f && Noise.unit(i * 31 + col, wy, 829) < 0.22f * s.season.festive
            val lamp = if (coloured) FESTIVE[(Noise.unit(i * 31 + col, wy, 842) * FESTIVE.size).toInt().coerceIn(0, FESTIVE.size - 1)] else WARM
            val glass = if (on && night > 0f) lerp(glassDay, lamp, night) else if (night > 0f) lerp(glassDay, dark, night) else glassDay
            fill(wx, wy, wx + 3, wy + 4, glass)
            fill(wx, wy + 4, wx + 3, wy + 5, shade)
            if (hiRes) windowDetail(wx, wy, 3, 4, shade, if (on) night else 0f, lamp, s.look.day)
            wx += 6
            col++
        }
        wy += 8
    }
    // The shop at the bottom: an awning over a glass front with the lights on at night.
    val awning = solid(s, AWNINGS[(Noise.unit(i, 826) * AWNINGS.size).toInt().coerceIn(0, AWNINGS.size - 1)], 0.04f)
    fill(x + 2, shopTop, x + w - 2, shopTop + 3, awning)
    var ax = x + 3
    while (ax < x + w - 3) { fill(ax, shopTop, ax + 2, shopTop + 3, lerp(awning, rgb(255, 255, 255), 0.45f)); ax += 4 }
    if (s.season.festive > 0.02f && Noise.unit(i, 843) < 0.6f) {
        var bx = x + 3
        var k = 0
        while (bx < x + w - 3) { bulb(s, bx, shopTop - 1, i * 13 + k, night); bx += 3; k++ }
    }
    fill(x + 2, shopTop + 3, x + w - 2, bottom, lerp(glassDay, lerp(WARM, body, 0.35f), night * 0.8f))
    storefrontGlints(x + 2, shopTop + 3, x + w - 2, bottom, s.look.day * (1f - night))
    eaveShadow(x + 2, x + w - 2, shopTop + 3)
    var mx = x + 2 + 8
    while (mx < x + w - 4) { fill(mx, shopTop + 3, mx + 1, bottom, lerp(body, rgb(20, 20, 30), 0.4f)); mx += 9 }
    fill(x + w / 2 - 1, shopTop + 4, x + w / 2 + 2, bottom, lerp(body, rgb(40, 30, 30), 0.5f))
    if (hiRes) {
        val sc = scale
        val ink = rgb(8, 10, 24); val white = rgb(255, 255, 255)
        // The shop: a scalloped awning with a shadow under it, goods on a shelf in each window, and a door with a pane, handle and sign.
        var ax2 = x + 2
        var kk = 0
        while (ax2 < x + w - 2) { fillOval(ax2.toFloat(), shopTop + 2.5f, ax2 + 4f, shopTop + 4.4f, if (kk % 2 == 0) awning else lerp(awning, white, 0.45f), 1f); ax2 += 4; kk++ }
        eaveShadow(x + 2, x + w - 2, shopTop + 4)
        var px0 = x + 3
        var pane = 0
        while (px0 + 7 < x + w - 4) {
            val shelf = (shopTop + 8) * sc
            fillAlphaPhys(px0 * sc, shelf, (px0 + 7) * sc, shelf + 1, ink, 0.45f)
            for (g in 0 until 3) {
                val gc = intArrayOf(rgb(220, 90, 80), rgb(80, 150, 220), rgb(240, 200, 80), rgb(120, 200, 140), rgb(230, 230, 230))[(Noise.unit(i * 11 + pane, g, 971) * 5).toInt().coerceIn(0, 4)]
                val gh = 3 + (Noise.unit(i * 11 + pane, g, 972) * 4).toInt()
                fillAlphaPhys(px0 * sc + 2 + g * 4, shelf - gh, px0 * sc + 5 + g * 4, shelf, gc, 0.85f)
            }
            px0 += 9; pane++
        }
        val dxc = (x + w / 2) * sc
        fillAlphaPhys(dxc - 3, (shopTop + 4) * sc, dxc - 2, bottom * sc, white, 0.30f)
        fillAlphaPhys(dxc + 5, (shopTop + 4) * sc, dxc + 6, bottom * sc, ink, 0.35f)
        fillPhys(dxc + 1, (bottom - 4) * sc, dxc + 2, (bottom - 2) * sc, rgb(236, 226, 190))
        fillPhys(dxc - 1, (shopTop + 5) * sc, dxc + 3, (shopTop + 5) * sc + 3, lerp(WARM, white, 0.2f))
        if (w > 28) lettering(x + 6, shopTop - 5, w - 12, 2, i, lerp(body, white, 0.7f))
        // The parapet's coping, and the roof: an air-conditioning unit with its fan and grille, a vent pipe, and on some a tank, an antenna with a dish or a billboard.
        fillAlphaPhys((x - 1) * sc, (top - 1) * sc, (x + w + 1) * sc, (top - 1) * sc + 1, white, 0.38f)
        val roofX = x + 2 + (Noise.unit(i, 827) * (w - 10).coerceAtLeast(1)).toInt()
        val acC = lerp(body, rgb(170, 170, 176), 0.5f)
        fillPhys(roofX * sc, (top - 4) * sc, (roofX + 6) * sc, (top - 1) * sc, acC)
        fillAlphaPhys(roofX * sc, (top - 4) * sc, (roofX + 6) * sc, (top - 4) * sc + 1, white, 0.4f)
        fillOval(roofX + 0.9f, top - 3.6f, roofX + 3.1f, top - 1.6f, ink, 0.55f)
        fillOval(roofX + 1.3f, top - 3.2f, roofX + 2.7f, top - 2f, acC, 0.9f)
        for (gx in 0..2) fillAlphaPhys((roofX + 3) * sc + 2 + gx * 3, (top - 3) * sc, (roofX + 3) * sc + 3 + gx * 3, (top - 1) * sc, ink, 0.4f)
        line(roofX + 6f, top - 2f, roofX + 8f, top - 2f, shade); fillPhys((roofX + 8) * sc, (top - 4) * sc, (roofX + 8) * sc + 2, (top - 2) * sc, shade)
    } else {
    // On the roof: an air-conditioning box, and on some a water tank, an aerial or a billboard.
    val roofX = x + 2 + (Noise.unit(i, 827) * (w - 10).coerceAtLeast(1)).toInt()
    fill(roofX, top - 4, roofX + 6, top - 1, lerp(body, rgb(170, 170, 176), 0.5f))
    }
    val extra = Noise.unit(i, 828)
    when {
        extra < 0.25f && w > 40 -> tank(s, x + w - 12, top - 1, shade)
        extra < 0.5f -> if (hiRes) {
            // An antenna mast with cross elements and a dish, and a light that blinks at night.
            val sc = scale
            line(x + 4.5f, top - 10f, x + 4.5f, top - 1f, shade)
            for (e in intArrayOf(3, 5, 7)) fillPhys((x + 3) * sc + 1, (top - e - 1) * sc, (x + 6) * sc - 1, (top - e - 1) * sc + 1, shade)
            fillOval(x + 5.6f, top - 8.4f, x + 8.4f, top - 6.2f, rgb(210, 214, 222), 1f)
            fillOval(x + 6.1f, top - 8f, x + 7.7f, top - 6.8f, shade, 0.7f)
            line(x + 5f, top - 7.4f, x + 6.4f, top - 7.2f, shade)
            if (night > 0.2f && (s.time * 0.8).toInt() % 2 == 0) { fillGlow(x + 4.5f, top - 10.4f, 4f, rgb(255, 70, 60), 0.6f); fillOval(x + 3.9f, top - 11f, x + 5.1f, top - 9.8f, rgb(255, 90, 80), 1f) }
        } else fill(x + 4, top - 9, x + 5, top - 1, shade)
        extra < 0.62f && w > 40 -> billboard(s, i, x + 4, top - 1, night)
    }
}

/** A park: a lawn with trees at each end and a path and bench between, open in the middle so the subway line behind shows. */
private fun PixelScene.drawPark(s: SceneState, lot: Int, x0: Int, bottom: Int, amount: Float) {
    val grass = lit(s, rgb(86, 150, 70), 0.06f)
    val path = lit(s, rgb(190, 176, 146), 0.04f)
    val fenceC = solid(s, rgb(60, 66, 76), 0.04f)
    // The lawn, over the concrete, from just below the subway line to the pavement.
    fillAlpha(x0, FIELD_TOP + 14, x0 + LOT, ROAD_TOP - 5, grass, amount)
    fillAlpha(x0 + LOT / 2 - 14, FIELD_TOP + 16, x0 + LOT / 2 + 14, ROAD_TOP - 5, path, amount)
    if (amount < 0.7f) return
    // The subway line behind runs in a concrete cutting, with a tunnel mouth at each end of the park.
    val concrete = solid(s, rgb(122, 124, 132), 0.04f)
    val lip = lerp(concrete, rgb(255, 255, 255), 0.18f)
    val mouth = lerp(concrete, rgb(10, 10, 24), 0.12f)
    fill(x0 + 6, FIELD_TOP + 8, x0 + LOT - 6, FIELD_TOP + 14, concrete)
    fill(x0 + 6, FIELD_TOP + 8, x0 + LOT - 6, FIELD_TOP + 9, lip)
    for (end in 0..1) {
        val px = if (end == 0) x0 else x0 + LOT - 6
        fill(px, FIELD_TOP - 2, px + 6, FIELD_TOP + 14, concrete)
        fill(px, FIELD_TOP - 2, px + 6, FIELD_TOP - 1, lip)
        fill(px + 1, FIELD_TOP + 1, px + 5, FIELD_TOP + 8, rgb(14, 14, 22))
        fill(px, FIELD_TOP + 8, px + 6, FIELD_TOP + 9, mouth)
    }
    spritePaint.colorFilter = filter(s, 0.04f + s.look.haze * 0.3f)
    sprite(Sprites.roundTreeYear.at(s.season, lot * 5), x0 + 4, bottom - 1)
    sprite(Sprites.roundTreeYear.at(s.season, lot * 5 + 1), x0 + LOT - 16, bottom - 1)
    sprite(Sprites.pine, x0 + LOT - 28, bottom - 4)
    treeLights(s, x0 + LOT - 28, bottom - 4, Sprites.pine.width, Sprites.pine.height, lot * 3 + 1, s.night())
    sprite(Sprites.bushYear.at(s.season, lot * 5 + 3), x0 + LOT - 44, bottom - 1)
    spritePaint.colorFilter = null
    drawPlayground(s, lot, x0, bottom)
    // A bench by the path, and a low railing along the front.
    val wood = solid(s, rgb(130, 94, 60), 0.04f)
    fill(x0 + LOT / 2 + 18, bottom - 4, x0 + LOT / 2 + 26, bottom - 3, wood)
    fill(x0 + LOT / 2 + 18, bottom - 6, x0 + LOT / 2 + 26, bottom - 5, wood)
    fill(x0 + LOT / 2 + 19, bottom - 3, x0 + LOT / 2 + 20, bottom, fenceC)
    fill(x0 + LOT / 2 + 24, bottom - 3, x0 + LOT / 2 + 25, bottom, fenceC)
    fill(x0 + 1, bottom - 2, x0 + LOT - 1, bottom - 1, fenceC)
}

/** The playground in a park: a swing set (with a child swinging, and the other swing moving a little in the breeze) and a slide a child comes down now and then. */
private fun PixelScene.drawPlayground(s: SceneState, lot: Int, x0: Int, bottom: Int) {
    val t = s.time
    val metal = solid(s, rgb(214, 62, 52), 0.04f)
    val chain = solid(s, rgb(176, 178, 186), 0.04f)
    val seat = solid(s, rgb(246, 200, 60), 0.04f)
    val slideBlue = solid(s, rgb(60, 124, 214), 0.04f)
    val ladder = solid(s, rgb(150, 154, 164), 0.04f)
    // Children are out in the day and dry weather.
    val kids = s.night() < 0.5f && s.cond.rain < 0.15f && s.snowCover < 0.4f
    val shirts = intArrayOf(rgb(230, 90, 80), rgb(70, 150, 230), rgb(250, 210, 80), rgb(110, 200, 130), rgb(220, 120, 200))
    fun kidShirt(k: Int) = solid(s, shirts[(Noise.unit(lot, 950 + k) * shirts.size).toInt().coerceIn(0, shirts.size - 1)], 0.04f)
    fun kidSkin(k: Int) = solid(s, People.SKIN[People.skinIndex(Noise.unit(lot, 954 + k))], 0.04f)
    fun kidHair(k: Int) = solid(s, People.hair(People.hairIndex(Noise.unit(lot, 958 + k))), 0.04f)
    // The swing set: a bar across two A-shaped frames.
    val left = x0 + 20
    val right = x0 + 34
    val top = bottom - 15
    if (hiRes) {
        // The frame on the finer grid: a round top bar with a highlight, and A-shaped legs that splay to the ground.
        val sc = scale
        fillPhys(left * sc, top * sc, (right + 1) * sc, top * sc + 3, metal)
        fillPhys(left * sc, top * sc, (right + 1) * sc, top * sc + 1, Colors.mul(metal, 1.25f, 1.25f, 1.25f))
        for (post in intArrayOf(left, right)) {
            line(post + 0.5f, top + 1f, post - 2.6f, bottom.toFloat(), metal)
            line(post + 0.5f, top + 1f, post + 3.6f, bottom.toFloat(), Colors.scale(metal, 0.8f))
            fillOval(post - 0.8f, top - 0.4f, post + 1.9f, top + 1.8f, Colors.scale(metal, 0.85f), 1f)
        }
    } else {
        fill(left, top, right + 1, top + 2, metal)
        for (post in intArrayOf(left, right)) {
            fill(post, top, post + 1, bottom, metal)
            fill(post - 1, bottom - 2, post, bottom, metal)
            fill(post + 1, bottom - 2, post + 2, bottom, metal)
        }
    }
    for (swing in 0..1) {
        val bx = left + 4 + swing * 6
        // The first swing has a child on it, going high; the other only moves a little.
        val occupied = swing == 0 && kids
        val amplitude = if (occupied) 0.7f else 0.05f + 0.05f * min(1f, s.cond.wind / 30f)
        val angle = sin(t * 1.7 + swing * 2.1).toFloat() * amplitude
        val length = 10f
        val sx = bx + (sin(angle) * length).toInt()
        val sy = top + 2 + (cos(angle) * length).toInt()
        if (hiRes) {
            // Two chains and a rounded seat.
            line(bx - 0.2f, top + 2f, sx - 1.6f, sy.toFloat(), chain)
            line(bx + 1.2f, top + 2f, sx + 1.8f, sy.toFloat(), chain)
            fillOval(sx - 2.6f, sy.toFloat(), sx + 2.8f, sy + 1.4f, seat, 1f)
            fillOval(sx - 2.2f, sy.toFloat(), sx + 2.4f, sy + 0.5f, Colors.mul(seat, 1.2f, 1.2f, 1.2f), 1f)
        } else {
            line(bx + 0.5f, top + 2f, sx + 0.5f, sy.toFloat(), chain)
            fill(sx - 2, sy, sx + 2, sy + 1, seat)
        }
        if (occupied && hiRes) {
            // The child: a round head with hair, a body, arms up to the chains and legs that kick out as they swing forward.
            val kick = if (cos(t * 1.7) > 0) 1f else -1f
            fillOval(sx - 1.3f, sy - 5.6f, sx + 1.7f, sy - 3.2f, kidSkin(0), 1f)
            fillOval(sx - 1.5f, sy - 6.2f, sx + 1.9f, sy - 4.6f, kidHair(0), 1f)
            fillOval(sx - 1.6f, sy - 3.6f, sx + 2.2f, sy + 0.2f, kidShirt(0), 1f)
            line(sx - 1.4f, sy - 2.8f, sx - 1.6f, sy - 6f, kidSkin(0)); line(sx + 1.6f, sy - 2.8f, sx + 1.8f, sy - 6f, kidSkin(0))
            line(sx - 0.4f, sy + 0.2f, sx - 0.4f + kick * 2.4f, sy + 3.4f, solid(s, rgb(60, 70, 110), 0.04f)); line(sx + 0.8f, sy + 0.2f, sx + 0.8f + kick * 2.4f, sy + 3.4f, solid(s, rgb(60, 70, 110), 0.04f))
        } else if (occupied) {
            // The child: head, hair, body and legs that kick out as they swing forward.
            val kick = if (cos(t * 1.7) > 0) 1 else -1
            fill(sx - 1, sy - 5, sx + 1, sy - 3, kidSkin(0))
            fill(sx - 1, sy - 6, sx + 1, sy - 5, kidHair(0))
            fill(sx - 1, sy - 3, sx + 2, sy, kidShirt(0))
            fill(sx - 1 + kick, sy + 1, sx + 1 + kick, sy + 4, solid(s, rgb(60, 70, 110), 0.04f))
        }
    }
    // The slide: a ladder up to a platform, and a ramp down.
    val platform = bottom - 11
    if (hiRes) {
        // A ladder with two rails and rungs, a railed platform, and a ramp that curves down and flattens out to a lip.
        val sc = scale
        fillPhys((x0 + 36) * sc, platform * sc, (x0 + 40) * sc, platform * sc + 3, metal)
        fillPhys((x0 + 36) * sc, platform * sc - 4, (x0 + 36) * sc + 1, platform * sc, metal); fillPhys((x0 + 39) * sc, platform * sc - 4, (x0 + 39) * sc + 1, platform * sc, metal)
        fillPhys((x0 + 36) * sc, platform * sc - 4, (x0 + 40) * sc, platform * sc - 3, metal)
        line(x0 + 36.5f, platform.toFloat(), x0 + 36.5f, bottom.toFloat(), ladder); line(x0 + 38.5f, platform.toFloat(), x0 + 38.5f, bottom.toFloat(), ladder)
        var rungY = platform + 2.5f
        while (rungY < bottom) { fillPhys((x0 + 36) * sc, (rungY * sc).toInt(), (x0 + 39) * sc + 1, (rungY * sc).toInt() + 1, ladder); rungY += 2.5f }
        var prevX = x0 + 40f; var prevY = platform + 1f
        for (st in 1..24) {
            val u = st / 24f
            val nx = x0 + 40f + u * 8.5f
            val ny = platform + 1f + 8f * (u * (2f - u))
            line(prevX, prevY, nx, ny, slideBlue); line(prevX, prevY - 0.6f, nx, ny - 0.6f, Colors.mul(slideBlue, 1.25f, 1.25f, 1.25f))
            line(prevX, prevY + 1.2f, nx, ny + 1.2f, Colors.scale(slideBlue, 0.78f))
            prevX = nx; prevY = ny
        }
        fillAlpha(x0 + 36, bottom, x0 + 50, bottom + 1, rgb(10, 10, 20), 0.2f)
    } else {
    fill(x0 + 36, platform, x0 + 40, platform + 1, metal)
    fill(x0 + 36, platform, x0 + 37, bottom, ladder)
    fill(x0 + 38, platform, x0 + 39, bottom, ladder)
    var rung = platform + 3
    while (rung < bottom) { fill(x0 + 36, rung, x0 + 39, rung + 1, ladder); rung += 3 }
    for (k in 0..7) {
        val ry = platform + (k * 9) / 7
        fill(x0 + 40 + k, ry, x0 + 41 + k, ry + 2, slideBlue)
    }
    }
    if (kids) {
        // A child waits on the platform, then sits and slides down, and then is off again.
        val u = (t + lot * 1.3) % 9.0
        if (u < 3.0) {
            val shift = if (u < 2.0) 0 else 2
            if (hiRes) {
                fillOval(x0 + 36.6f, platform - 5.6f + shift, x0 + 39.4f, platform - 3.2f + shift, kidSkin(1), 1f)
                fillOval(x0 + 36.4f, platform - 6.2f + shift, x0 + 39.6f, platform - 4.6f + shift, kidHair(1), 1f)
                fillOval(x0 + 36.4f, platform - 3.6f + shift, x0 + 40.2f, platform + shift.toFloat(), kidShirt(1), 1f)
            } else {
            fill(x0 + 37, platform - 4, x0 + 39, platform - 2, kidSkin(1))
            fill(x0 + 37, platform - 5, x0 + 39, platform - 4, kidHair(1))
            fill(x0 + 37, platform - 2 + shift, x0 + 40, platform + shift, kidShirt(1))
            }
        } else if (u < 4.6) {
            val k = (((u - 3.0) / 1.6) * 7).toInt().coerceIn(0, 7)
            val ry = platform + (k * 9) / 7
            if (hiRes) {
                val kx = x0 + 40f + k * 1.2f
                fillOval(kx - 0.6f, ry - 5.2f, kx + 2.2f, ry - 2.8f, kidSkin(1), 1f)
                fillOval(kx - 0.8f, ry - 5.8f, kx + 2.4f, ry - 4.2f, kidHair(1), 1f)
                fillOval(kx - 0.8f, ry - 3.2f, kx + 2.8f, ry + 0.2f, kidShirt(1), 1f)
            } else {
            fill(x0 + 40 + k - 1, ry - 4, x0 + 40 + k + 1, ry - 2, kidSkin(1))
            fill(x0 + 40 + k - 1, ry - 5, x0 + 40 + k + 1, ry - 4, kidHair(1))
            fill(x0 + 40 + k - 1, ry - 2, x0 + 40 + k + 2, ry, kidShirt(1))
            }
        }
    }
}

/** A school: a long brick building with a bell tower, a flag and a fenced yard. */
private fun PixelScene.drawSchool(s: SceneState, lot: Int, x0: Int, bottom: Int, growth: Float, night: Float) {
    if (growth < 0.3f) return
    val brick = solid(s, rgb(168, 98, 78), 0.04f + s.look.haze * 0.3f)
    val brickDark = lerp(brick, rgb(10, 10, 30), 0.25f)
    val roof = solid(s, rgb(96, 80, 82), 0.04f)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val lamp = lerp(glassDay, WARM, night * 0.7f)
    val h = (30 * growth).toInt()
    val x = x0 + 4
    val w = 84
    val top = bottom - h
    fill(x, top, x + w, bottom, brick)
    fill(x + w - 2, top, x + w, bottom, brickDark)
    fill(x - 1, top - 2, x + w + 1, top, roof)
    // Two rows of tall windows and a doorway with steps in the middle.
    var wx = x + 5
    while (wx < x + w - 6) {
        if (kotlin.math.abs(wx - (x + w / 2)) > 6) {
            fill(wx, top + 5, wx + 4, top + 12, lamp)
            fill(wx, top + 16, wx + 4, top + 23, lamp)
        }
        wx += 9
    }
    fill(x + w / 2 - 4, bottom - 11, x + w / 2 + 5, bottom, lerp(brick, rgb(30, 24, 24), 0.5f))
    fill(x + w / 2 - 6, bottom - 2, x + w / 2 + 7, bottom, solid(s, rgb(180, 176, 168), 0.04f))
    // The bell tower over the door.
    fill(x + w / 2 - 5, top - 10, x + w / 2 + 6, top - 2, brick)
    fill(x + w / 2 - 6, top - 12, x + w / 2 + 7, top - 10, roof)
    fill(x + w / 2 - 1, top - 8, x + w / 2 + 2, top - 3, lamp)
    // The flag, and a fence round the yard.
    val pole = solid(s, rgb(200, 200, 204), 0.04f)
    fill(x0 + LOT - 8, bottom - 26, x0 + LOT - 7, bottom, pole)
    val wave = (sin(s.time * 3.0).toFloat() * 1.2f).toInt()
    fill(x0 + LOT - 7, bottom - 26, x0 + LOT - 1 + wave, bottom - 21, solid(s, rgb(210, 60, 52), 0.0f))
    val fenceC = solid(s, rgb(60, 66, 76), 0.04f)
    fill(x0 + w + 6, bottom - 5, x0 + LOT - 10, bottom - 4, fenceC)
    var fx = x0 + w + 6
    while (fx < x0 + LOT - 10) { fill(fx, bottom - 6, fx + 1, bottom, fenceC); fx += 4 }
}

private fun PixelScene.tank(s: SceneState, x: Int, top: Int, shade: Int) {
    val wood = solid(s, rgb(120, 86, 58), 0.04f)
    if (hiRes) {
        // A wooden barrel on a braced frame: staves with iron hoops, a conical lid, a ladder.
        val sc = scale
        val ink = rgb(8, 10, 24); val white = rgb(255, 255, 255); val hoop = rgb(60, 64, 72)
        line(x + 1.5f, top - 5f, x + 0.5f, top.toFloat(), shade); line(x + 6.5f, top - 5f, x + 7.5f, top.toFloat(), shade)
        line(x + 1.5f, top - 4f, x + 6.5f, top - 1f, shade, 200); line(x + 6.5f, top - 4f, x + 1.5f, top - 1f, shade, 200)
        fillPhys(x * sc, (top - 12) * sc, (x + 8) * sc, (top - 5) * sc, wood)
        fillAlphaPhys(x * sc, (top - 12) * sc, x * sc + 3, (top - 5) * sc, white, 0.28f)
        fillAlphaPhys((x + 8) * sc - 4, (top - 12) * sc, (x + 8) * sc, (top - 5) * sc, ink, 0.30f)
        var st = x * sc + 3
        while (st < (x + 8) * sc - 2) { fillAlphaPhys(st, (top - 12) * sc, st + 1, (top - 5) * sc, ink, 0.18f); st += 3 }
        for (hy in intArrayOf(top - 11, top - 8, top - 6)) fillPhys(x * sc, hy * sc, (x + 8) * sc, hy * sc + 1, hoop)
        val mid = x * sc + 4 * sc
        for (ry in 0 until 3 * sc) { val half = (4 * sc) - ry * (4 * sc - 2) / (3 * sc); fillPhys(mid - half, (top - 15) * sc + ry, mid, (top - 15) * sc + ry + 1, Colors.mul(wood, 1.15f, 1.15f, 1.15f)); fillPhys(mid, (top - 15) * sc + ry, mid + half, (top - 15) * sc + ry + 1, Colors.scale(wood, 0.8f)) }
        fillPhys(mid - 1, (top - 17) * sc, mid + 1, (top - 15) * sc, shade)
        line(x + 8.5f, top - 11f, x + 9.2f, top.toFloat(), shade, 255)
        return
    }
    fill(x + 1, top - 5, x + 2, top, shade)
    fill(x + 6, top - 5, x + 7, top, shade)
    fill(x, top - 12, x + 8, top - 5, wood)
    fill(x, top - 13, x + 8, top - 12, lerp(wood, rgb(255, 255, 255), 0.2f))
    fill(x + 3, top - 15, x + 5, top - 13, shade)
}

private fun PixelScene.billboard(s: SceneState, i: Int, x: Int, top: Int, night: Float) {
    val colors = intArrayOf(rgb(230, 70, 90), rgb(60, 170, 230), rgb(250, 200, 60), rgb(110, 220, 130))
    val c = colors[(Noise.unit(i, 829) * colors.size).toInt().coerceIn(0, colors.size - 1)]
    val post = solid(s, rgb(70, 70, 78), 0.04f)
    if (hiRes) {
        // A billboard on a truss: steel frame, a panel with a bold shape, lamps along the top and a catwalk.
        val sc = scale
        val ink = rgb(8, 10, 24); val white = rgb(255, 255, 255)
        line(x + 3.5f, top - 5f, x + 3.5f, top.toFloat(), post); line(x + 14.5f, top - 5f, x + 14.5f, top.toFloat(), post)
        line(x + 3.5f, top - 5f, x + 14.5f, top.toFloat(), post, 190); line(x + 14.5f, top - 5f, x + 3.5f, top.toFloat(), post, 190)
        fillPhys(x * sc, (top - 14) * sc, (x + 19) * sc, (top - 5) * sc, solid(s, rgb(220, 220, 224), 0.04f))
        fillPhys(x * sc + 2, (top - 14) * sc + 2, (x + 19) * sc - 2, (top - 5) * sc - 2, lerp(solid(s, c, 0.04f), c, night))
        fillAlphaPhys(x * sc + 2, (top - 14) * sc + 2, (x + 19) * sc - 2, (top - 13) * sc, white, 0.18f)
        fillAlphaPhys(x * sc + 2, (top - 6) * sc - 2, (x + 19) * sc - 2, (top - 6) * sc, ink, 0.22f)
        fillOval(x + 3f, top - 12f, x + 9f, top - 7f, rgb(255, 255, 255), 0.85f)
        fillPhys((x + 10) * sc, (top - 11) * sc, (x + 16) * sc, (top - 10) * sc + 1, white); fillPhys((x + 10) * sc, (top - 9) * sc, (x + 14) * sc, (top - 9) * sc + 1, white)
        fillPhys((x - 1) * sc, (top - 5) * sc, (x + 20) * sc, (top - 5) * sc + 2, post)
        for (lx in 1..17 step 4) { fillPhys((x + lx) * sc, (top - 15) * sc, (x + lx) * sc + 1, (top - 14) * sc, post); fillOval(x + lx - 0.3f, top - 16f, x + lx + 1.3f, top - 14.8f, if (night > 0.2f) rgb(255, 244, 190) else rgb(214, 214, 204), 1f) }
        if (night > 0.2f) fillAlpha(x - 3, top - 17, x + 22, top - 3, c, night * 0.16f)
        return
    }
    fill(x + 3, top - 5, x + 4, top, post)
    fill(x + 14, top - 5, x + 15, top, post)
    fill(x, top - 14, x + 19, top - 5, solid(s, rgb(220, 220, 224), 0.04f))
    fill(x + 1, top - 13, x + 18, top - 6, lerp(solid(s, c, 0.04f), c, night))
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
    val post = solid(s, rgb(58, 62, 72), 0.0f)
    for (i in first..first + W / spacing + 2) {
        val wx = i * spacing + 30
        val amount = s.hardAt(wx.toDouble(), 1.0)
        // Every lamp is full height or not there: as the town thins out they drop out one by one (each at its own point), they
        // do not shrink away.
        if (amount < 0.15f + 0.65f * Noise.unit(i, 1400)) continue
        val h = 22
        val x = wx - offset
        if (hiRes) {
            // A tapered pole on a base plate, a curved arm and a hooded lamp, drawn on the finer grid.
            val sc = scale
            val topP = (ROAD_TOP - h) * sc
            fillPhys(x * sc, topP, x * sc + 2, ROAD_TOP * sc - 3, post)
            fillPhys(x * sc - 1, ROAD_TOP * sc - 3, x * sc + 3, ROAD_TOP * sc, post)
            fillPhys(x * sc - 1, ROAD_TOP * sc - 2, x * sc + 3, ROAD_TOP * sc - 1, lerp(post, rgb(120, 126, 140), 0.5f))
            fillPhys(x * sc, topP, x * sc + 1, ROAD_TOP * sc - 3, lerp(post, rgb(160, 168, 186), 0.3f))
            for (k in 0..8) fillPhys(x * sc + 1 + k, topP - (if (k < 3) 0 else (k - 2) / 4), x * sc + 2 + k, topP + 2 - (if (k < 3) 0 else (k - 2) / 4), post)
            val head = lerp(post, rgb(30, 32, 38), 0.5f)
            fillOval(x + 3.2f, ROAD_TOP - h + 0.6f, x + 7f, ROAD_TOP - h + 2.2f, head, 1f)
            val bulbC = if (night > 0.2f) rgb(255, 238, 176) else solid(s, rgb(214, 214, 204), 0.0f)
            fillOval(x + 3.8f, ROAD_TOP - h + 1.4f, x + 6.4f, ROAD_TOP - h + 2.3f, bulbC, 1f)
        } else {
            fill(x, ROAD_TOP - h, x + 1, ROAD_TOP, post)
            fill(x, ROAD_TOP - h, x + 5, ROAD_TOP - h + 1, post)
            fill(x + 3, ROAD_TOP - h + 1, x + 6, ROAD_TOP - h + 2, if (night > 0.2f) rgb(255, 236, 170) else solid(s, rgb(210, 210, 200), 0.0f))
        }
        // Festive lights hang in a swag from each lamp to the next across the street.
        if (s.season.festive > 0.02f && h > 18 && s.cityAt(wx.toDouble(), 1.0) > 0.5f && s.cityAt((wx + spacing).toDouble(), 1.0) > 0.5f) {
            var px = 5
            while (px < spacing) {
                val f = px / spacing.toFloat()
                bulb(s, x + px, ROAD_TOP - h + 2 + (6f * 4f * f * (1f - f)).toInt(), i * 29 + px / 4, night)
                px += 4
            }
        }
        if (night > 0.05f && h > 18) {
            // A soft glow round the lamp.
            if (hiRes) {
                // Round, in rings that fade outward, and a pool of light on the ground below.
                for (k in 3 downTo 1) fillOval(x + 5f - k * 2.6f, ROAD_TOP - h + 1.8f - k * 2.2f, x + 5f + k * 2.6f, ROAD_TOP - h + 1.8f + k * 2.2f, rgb(255, 226, 150), night * 0.09f)
                fillOval(x - 2f, ROAD_TOP - 2.5f, x + 12f, ROAD_TOP + 0.5f, rgb(255, 226, 150), night * 0.10f)
            } else fillAlpha(x + 2, ROAD_TOP - h, x + 8, ROAD_TOP - h + 5, rgb(255, 226, 150), night * 0.22f)
        }
    }
}

/** Subway entrances on the pavement now and then: a railed stairway down and a sign on a pole. */
internal fun PixelScene.drawSubwayEntrances(s: SceneState) {
    val offset = floor(s.cam).toInt()
    val spacing = 420
    val first = Math.floorDiv(offset, spacing) - 1
    val rail = solid(s, rgb(54, 60, 70), 0.0f)
    val sign = solid(s, rgb(40, 100, 190), 0.0f)
    for (i in first..first + W / spacing + 2) {
        val wx = i * spacing + 200 + (Noise.unit(i, 850) * 100).toInt()
        if (s.cityAt(wx.toDouble(), 1.0) < 0.9f) continue
        val x = wx - offset
        if (hiRes) {
            // The entrance on the finer grid: stairs of lit treads going down between railings with posts, a canopy, a globe lamp on each
            // side, and a round sign on a pole that glows at night.
            val sc = scale
            val ink = rgb(8, 10, 24); val white = rgb(255, 255, 255)
            fillPhys(x * sc, (ROAD_TOP - 5) * sc, (x + 14) * sc, ROAD_TOP * sc, solid(s, rgb(30, 30, 38), 0.0f))
            for (st in 0 until 7) fillAlphaPhys((x + 2) * sc + st, (ROAD_TOP - 4) * sc + st * 2, (x + 12) * sc - st, (ROAD_TOP - 4) * sc + st * 2 + 1, white, 0.18f)
            for (rx in intArrayOf(x, x + 13)) {
                fillPhys(rx * sc, (ROAD_TOP - 6) * sc, rx * sc + 2, ROAD_TOP * sc, rail)
                for (py in 0..2) fillPhys(rx * sc - 1, (ROAD_TOP - 6) * sc + py * 5, rx * sc + 3, (ROAD_TOP - 6) * sc + py * 5 + 1, rail)
                fillOval(rx - 0.3f, ROAD_TOP - 8f, rx + 1.9f, ROAD_TOP - 5.7f, if (s.night() > 0.2f) rgb(255, 240, 190) else solid(s, rgb(220, 220, 214), 0.0f), 1f)
                if (s.night() > 0.2f) fillGlow(rx + 0.8f, ROAD_TOP - 6.8f, 6f, rgb(255, 226, 150), s.night() * 0.45f)
            }
            fillPhys(x * sc, (ROAD_TOP - 6) * sc, (x + 14) * sc, (ROAD_TOP - 6) * sc + 3, rail)
            fillAlphaPhys(x * sc, (ROAD_TOP - 6) * sc, (x + 14) * sc, (ROAD_TOP - 6) * sc + 1, white, 0.3f)
            eaveShadow(x + 1, x + 13, ROAD_TOP - 5)
            // The sign: a roundel with a bar, on a pole.
            line(x + 16.5f, ROAD_TOP - 17f, x + 16.5f, ROAD_TOP - 1f, rail)
            fillOval(x + 12.6f, ROAD_TOP - 20.6f, x + 20.4f, ROAD_TOP - 12.8f, sign, 1f)
            fillOval(x + 13.6f, ROAD_TOP - 19.6f, x + 19.4f, ROAD_TOP - 13.8f, white, 1f)
            fillOval(x + 14.4f, ROAD_TOP - 18.8f, x + 18.6f, ROAD_TOP - 14.6f, sign, 1f)
            fillPhys((x + 12) * sc + 2, (ROAD_TOP - 17) * sc, (x + 21) * sc - 2, (ROAD_TOP - 16) * sc + 1, white)
            if (s.night() > 0.2f) fillGlow(x + 16.5f, ROAD_TOP - 16.7f, 11f, sign, s.night() * 0.45f)
            continue
        }
        // The stairs go down between two railings.
        fill(x, ROAD_TOP - 6, x + 1, ROAD_TOP - 1, rail)
        fill(x + 13, ROAD_TOP - 6, x + 14, ROAD_TOP - 1, rail)
        fill(x, ROAD_TOP - 6, x + 14, ROAD_TOP - 5, rail)
        fill(x + 2, ROAD_TOP - 4, x + 12, ROAD_TOP - 1, solid(s, rgb(30, 30, 38), 0.0f))
        // The sign: a blue square with a white mark.
        fill(x + 15, ROAD_TOP - 17, x + 16, ROAD_TOP - 1, rail)
        fill(x + 12, ROAD_TOP - 19, x + 19, ROAD_TOP - 12, sign)
        fill(x + 14, ROAD_TOP - 17, x + 17, ROAD_TOP - 14, rgb(255, 255, 255))
        if (s.night() > 0.2f) fillAlpha(x + 9, ROAD_TOP - 22, x + 22, ROAD_TOP - 9, sign, s.night() * 0.18f)
    }
}

/** Concrete in place of the fields and grass, a pavement along the road and a crossing now and then. */
internal fun PixelScene.drawCityGround(s: SceneState) {
    if (!s.anyCity) return
    val concrete = lit(s, rgb(116, 118, 126), 0.1f + s.look.haze * 0.3f)
    val pavement = lit(s, rgb(170, 168, 160), 0.06f)
    val joint = lit(s, rgb(136, 134, 128), 0.06f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.cityOnScreen(x)
        if (a <= 0.01f) continue
        fillAlpha(x, FIELD_TOP, x + 1, ROAD_TOP - 5, concrete, a * 0.97f)
        fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP, pavement, a)
        if (Math.floorMod(x + cam, 14) == 0) fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP, joint, a)
        fillAlpha(x, ROAD_TOP - 5, x + 1, ROAD_TOP - 4, lerp(pavement, rgb(255, 255, 255), 0.2f), a)
    }
}

/** The pavement in front of the road, drawn over the grass, and zebra crossings over the road. */
internal fun PixelScene.drawCityRoad(s: SceneState) {
    if (!s.anyCity) return
    val pavement = lit(s, rgb(150, 148, 142), 0.0f)
    val joint = lit(s, rgb(120, 118, 112), 0.0f)
    val kerb = lit(s, rgb(190, 188, 180), 0.0f)
    val white = lit(s, rgb(236, 232, 214), 0.02f, 0.1f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.cityOnScreen(x)
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

private val DIGITS = arrayOf(
    "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
    "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"
)

/** One tall building with the time in glowing digits on a face at the top: the real time, or the hour chosen in the settings. */
private fun PixelScene.clockTower(s: SceneState, lot: Int, x: Int, bottom: Int, growth: Float, night: Float) {
    val w = 44
    if (x + w < -4 || x > W + 4) return
    val h = (96f * growth).toInt()
    if (h < 8) return
    val top = bottom - h
    val body = solid(s, rgb(168, 156, 140), 0.04f + s.look.haze * 0.3f)
    val light = lerp(body, rgb(255, 255, 255), 0.14f)
    val shade = lerp(body, rgb(10, 10, 30), 0.24f)
    fill(x, top, x + w, bottom, body)
    fill(x, top, x + 1, bottom, light)
    fill(x + w - 2, top, x + w, bottom, shade)
    fill(x - 2, top - 2, x + w + 2, top + 1, shade)
    // A little spire.
    fill(x + w / 2 - 3, top - 6, x + w / 2 + 3, top - 2, shade)
    fill(x + w / 2 - 1, top - 12, x + w / 2 + 1, top - 6, shade)
    if (s.snowCover > 0.03f) fillAlpha(x - 2, top - 3, x + w + 2, top, solid(s, rgb(238, 242, 250), 0.04f), s.snowCover)
    // The face: dark glass with the time on it.
    val faceTop = top + 5
    fill(x + 3, faceTop - 1, x + w - 3, faceTop + 15, shade)
    fill(x + 4, faceTop, x + w - 4, faceTop + 14, rgb(14, 16, 24))
    val minutes = (((s.nowMs + java.util.TimeZone.getDefault().getOffset(s.nowMs)) / 60_000L) % 1440L).toInt().let { if (it < 0) it + 1440 else it }
    val hour = (minutes / 60).let { if (it % 12 == 0) 12 else it % 12 }
    val minute = minutes % 60
    val text = IntArray(5)
    var count = 0
    if (hour >= 10) text[count++] = 1
    text[count++] = hour % 10
    text[count++] = 10
    text[count++] = minute / 10
    text[count++] = minute % 10
    val glow = lerp(rgb(255, 186, 64), rgb(255, 240, 190), 0.2f)
    val lit = lerp(rgb(170, 110, 40), glow, 0.55f + 0.45f * night)
    var width = 0
    for (k in 0 until count) width += if (text[k] == 10) 4 else 8
    var dx = x + (w - width + 2) / 2
    val colon = s.time.toInt() % 2 == 0
    for (k in 0 until count) {
        val c = text[k]
        if (c == 10) {
            if (colon) {
                if (hiRes) { fillOval(dx + 0.9f, faceTop + 4.2f, dx + 2.9f, faceTop + 6.2f, lit, 1f); fillOval(dx + 0.9f, faceTop + 8.2f, dx + 2.9f, faceTop + 10.2f, lit, 1f) }
                else { fill(dx + 1, faceTop + 4, dx + 3, faceTop + 6, lit); fill(dx + 1, faceTop + 8, dx + 3, faceTop + 10, lit) }
            }
            dx += 4
        } else {
            val rows = DIGITS[c]
            for (r in 0 until 5) for (q in 0 until 3) {
                if (hiRes) {
                    // A dot-matrix display on the finer grid: each lit cell a round dot with a glow, the unlit ones faint.
                    val ox = dx + q * 2f; val oy = faceTop + 2f + r * 2f
                    if (rows[r * 3 + q] == '1') {
                        fillOval(ox - 0.2f, oy - 0.2f, ox + 2.2f, oy + 2.2f, lit, 0.22f)
                        fillOval(ox + 0.1f, oy + 0.1f, ox + 1.9f, oy + 1.9f, lit, 1f)
                        fillOval(ox + 0.5f, oy + 0.5f, ox + 1.4f, oy + 1.4f, rgb(255, 240, 200), 0.8f)
                    } else fillOval(ox + 0.3f, oy + 0.3f, ox + 1.7f, oy + 1.7f, lit, 0.07f)
                } else if (rows[r * 3 + q] == '1') fill(dx + q * 2, faceTop + 2 + r * 2, dx + q * 2 + 2, faceTop + 4 + r * 2, lit)
            }
            dx += 8
        }
    }
    if (night > 0.2f) fillAlpha(x + 2, faceTop - 3, x + w - 2, faceTop + 17, rgb(255, 190, 90), night * 0.14f)
    // Windows below, a few lit at night, and a door.
    val dark = lerp(lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f), rgb(10, 12, 24), 0.45f * night)
    var wy = faceTop + 22
    while (wy < bottom - 16) {
        var wx = x + 5
        var col = 0
        while (wx + 3 < x + w - 4) {
            val on = Noise.unit(lot * 17 + col, wy, 851) < 0.4f
            fill(wx, wy, wx + 3, wy + 5, if (on && night > 0f) lerp(dark, WARM, night) else dark)
            if (hiRes) windowDetail(wx, wy, 3, 5, shade, if (on) night else 0f, WARM, s.look.day)
            wx += 7
            col++
        }
        wy += 9
    }
    fill(x + w / 2 - 3, bottom - 10, x + w / 2 + 3, bottom, lerp(body, rgb(40, 30, 30), 0.55f))
    fill(x + w / 2 - 4, bottom - 11, x + w / 2 + 4, bottom - 10, shade)
    if (hiRes) {
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        // Cut stone in courses, a falling off of the light, a shadow under the cornice, and corner stones.
        brickTexture(x + 1, top + 1, x + w - 2, bottom, lot * 7)
        wallFalloff(x + 1, top + 1, x + w - 2, bottom)
        eaveShadow(x - 2, x + w + 2, top + 1)
        for (cy in top + 2 until bottom step 6) { fillAlphaPhys(x * sc + 1, cy * sc, x * sc + 4, cy * sc + 5, white, 0.10f); fillAlphaPhys((x + w) * sc - 5, cy * sc, (x + w) * sc - 1, cy * sc + 5, ink, 0.10f) }
        // A pointed spire in tiles with a gold ball, and a ledge under the clock.
        val spTop = (top - 13) * sc
        val spBase = (top - 2) * sc
        val mid = (x + w / 2) * sc
        for (ry in 0 until spBase - spTop) {
            val half = (ry * (4.6f * sc) / (spBase - spTop)).toInt() + 1
            fillPhys(mid - half, spTop + ry, mid, spTop + ry + 1, Colors.mul(shade, 1.15f, 1.15f, 1.15f))
            fillPhys(mid, spTop + ry, mid + half, spTop + ry + 1, Colors.scale(shade, 0.85f))
            if (ry % 5 == 4) fillAlphaPhys(mid - half, spTop + ry, mid + half, spTop + ry + 1, ink, 0.25f)
        }
        fillOval(x + w / 2f - 0.9f, top - 14.3f, x + w / 2f + 0.9f, top - 12.5f, rgb(255, 224, 120), 1f)
        // The clock face: a bezel round the glass with rivets in the corners, and a diagonal glint.
        fillAlphaPhys((x + 3) * sc, (faceTop - 1) * sc, (x + w - 3) * sc, (faceTop - 1) * sc + 1, white, 0.4f)
        fillAlphaPhys((x + 3) * sc, (faceTop + 15) * sc - 1, (x + w - 3) * sc, (faceTop + 15) * sc, ink, 0.4f)
        for (rx in intArrayOf(x + 3, x + w - 4)) for (ry in intArrayOf(faceTop - 1, faceTop + 14)) fillOval(rx + 0.2f, ry + 0.2f, rx + 1.2f, ry + 1.2f, rgb(214, 196, 150), 1f)
        for (k in 0 until 10) fillAlphaPhys((x + 6) * sc + k * 2, (faceTop + 1) * sc + 8 - k, (x + 6) * sc + k * 2 + 2, (faceTop + 1) * sc + 9 - k, white, 0.07f * (1f - night))
        // The doorway: an arch over the door, and its lit frame.
        fillOval(x + w / 2f - 3.2f, bottom - 13.2f, x + w / 2f + 3.2f, bottom - 8f, lerp(body, rgb(40, 30, 30), 0.55f), 1f)
        fillAlphaPhys((x + w / 2 - 3) * sc, (bottom - 10) * sc, (x + w / 2 - 3) * sc + 1, bottom * sc, white, 0.30f)
    }
}
