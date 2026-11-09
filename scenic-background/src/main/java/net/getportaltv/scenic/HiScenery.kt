package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/**
 * Extra detail for the buildings and other scenery drawn in code, for the 16-bit style only (each does nothing in 8-bit). The
 * building is drawn as usual on the scene's own grid; these then add what the finer grid has room for, painted over it in see-through
 * strokes so the light, the haze and the time of day already in the colours still show: mortar and siding lines, window frames and
 * glints, a shadow under the eaves, a glow round lit windows.
 */

private val HI_DARK = rgb(8, 10, 24)
private val HI_LIGHT = rgb(255, 255, 255)

internal val PixelScene.hiRes: Boolean get() = scale > 1

/** Courses of brick: a fine mortar line every few rows with the joints staggered, over the wall (a rectangle on the scene's grid). */
internal fun PixelScene.brickTexture(x0: Int, y0: Int, x1: Int, y1: Int, seed: Int) {
    if (!hiRes) return
    val sc = scale
    val px0 = x0 * sc; val px1 = x1 * sc
    var row = 0
    var py = y0 * sc
    val end = y1 * sc
    while (py < end) {
        fillAlphaPhys(px0, py, px1, py + 1, HI_DARK, 0.15f)
        var jx = px0 + (if (row % 2 == 0) 0 else 4) + (Noise.hash(seed, row, 7) and 3)
        while (jx < px1) { fillAlphaPhys(jx, py + 1, jx + 1, minOf(py + 3, end), HI_DARK, 0.10f); jx += 8 }
        py += 3
        row++
    }
}

/** Boards laid along a wall: a dark line and a light line under it every few rows. */
internal fun PixelScene.sidingTexture(x0: Int, y0: Int, x1: Int, y1: Int) {
    if (!hiRes) return
    val sc = scale
    var py = y0 * sc
    val end = y1 * sc
    while (py < end) {
        fillAlphaPhys(x0 * sc, py, x1 * sc, py + 1, HI_DARK, 0.14f)
        fillAlphaPhys(x0 * sc, py + 1, x1 * sc, py + 2, HI_LIGHT, 0.05f)
        py += 3
    }
}

/** A roof's tiles in one row of the roof (a strip on the scene's grid): staggered joints and a lit top edge. */
internal fun PixelScene.shingleRow(x0: Int, x1: Int, y: Int, seed: Int) {
    if (!hiRes) return
    val sc = scale
    val py = y * sc
    fillAlphaPhys(x0 * sc, py, x1 * sc, py + 1, HI_LIGHT, 0.10f)
    fillAlphaPhys(x0 * sc, py + sc - 1, x1 * sc, py + sc, HI_DARK, 0.16f)
    var jx = x0 * sc + (Noise.hash(seed, y, 11) and 3)
    while (jx < x1 * sc) { fillAlphaPhys(jx, py, jx + 1, py + sc, HI_DARK, 0.12f); jx += 6 }
}

/** The wall gets a little darker toward the foot (the light falls off), in a few soft steps. */
internal fun PixelScene.wallFalloff(x0: Int, y0: Int, x1: Int, y1: Int) {
    if (!hiRes) return
    val sc = scale
    val h = (y1 - y0) * sc
    if (h < 8) return
    val steps = 4
    for (k in 0 until steps) {
        val a = h * k / steps
        val b = h * (k + 1) / steps
        fillAlphaPhys(x0 * sc, y0 * sc + a, x1 * sc, y0 * sc + b, HI_DARK, 0.015f + 0.025f * k)
    }
}

/** The shadow a cornice or an eave throws on the wall below it. */
internal fun PixelScene.eaveShadow(x0: Int, x1: Int, y: Int) {
    if (!hiRes) return
    val sc = scale
    fillAlphaPhys(x0 * sc, y * sc, x1 * sc, y * sc + 1, HI_DARK, 0.30f)
    fillAlphaPhys(x0 * sc, y * sc + 1, x1 * sc, y * sc + 2, HI_DARK, 0.20f)
    fillAlphaPhys(x0 * sc, y * sc + 2, x1 * sc, y * sc + 3, HI_DARK, 0.10f)
}

/**
 * Detail for a window already filled in ([x], [y], [w] by [h] on the scene's grid): a frame, a cross bar, a sill and, by day, a
 * glint of sky; lit windows get a faint glow round them. [lit] is how bright it is inside (0 for dark), [glow] the colour.
 */
internal fun PixelScene.windowDetail(x: Int, y: Int, w: Int, h: Int, frame: Int, lit: Float, glow: Int, day: Float) {
    if (!hiRes) return
    val sc = scale
    val px0 = x * sc; val py0 = y * sc; val px1 = (x + w) * sc; val py1 = (y + h) * sc
    // The frame, and the bars across (one down the middle when it is wide, one across when it is tall).
    fillAlphaPhys(px0, py0, px1, py0 + 1, frame, 0.6f); fillAlphaPhys(px0, py1 - 1, px1, py1, frame, 0.6f)
    fillAlphaPhys(px0, py0, px0 + 1, py1, frame, 0.6f); fillAlphaPhys(px1 - 1, py0, px1, py1, frame, 0.6f)
    if (w >= 3) fillAlphaPhys((px0 + px1) / 2, py0, (px0 + px1) / 2 + 1, py1, frame, 0.45f)
    if (h >= 4) fillAlphaPhys(px0, (py0 + py1) / 2, px1, (py0 + py1) / 2 + 1, frame, 0.45f)
    // A pale sill under it.
    fillAlphaPhys(px0 - 1, py1, px1 + 1, py1 + 1, HI_LIGHT, 0.22f)
    // By day the glass catches the sky in a short slanting glint at the top left.
    if (lit < 0.05f && day > 0.3f) {
        fillAlphaPhys(px0 + 1, py0 + 1, px0 + 2, py0 + 3, HI_LIGHT, 0.35f * day)
        fillAlphaPhys(px0 + 2, py0 + 1, px0 + 3, py0 + 2, HI_LIGHT, 0.25f * day)
    }
    if (lit > 0.05f) {
        fillAlphaPhys(px0 - 1, py0 - 1, px1 + 1, py0, glow, 0.16f * lit)
        fillAlphaPhys(px0 - 1, py1, px1 + 1, py1 + 1, glow, 0.16f * lit)
        fillAlphaPhys(px0 - 1, py0, px0, py1, glow, 0.16f * lit)
        fillAlphaPhys(px1, py0, px1 + 1, py1, glow, 0.16f * lit)
    }
}

/** Slanting reflections across a big pane of shop glass (a rectangle on the scene's grid), faint by day. */
internal fun PixelScene.storefrontGlints(x0: Int, y0: Int, x1: Int, y1: Int, day: Float) {
    if (!hiRes || day < 0.25f) return
    val sc = scale
    var gx = x0 * sc + 3
    while (gx < x1 * sc - 4) {
        for (r in 0 until (y1 - y0) * sc) {
            val x = gx + (r / 2)
            if (x < x1 * sc - 1) fillAlphaPhys(x, y0 * sc + r, x + 2, y0 * sc + r + 1, HI_LIGHT, 0.10f * day)
        }
        gx += 14
    }
}
