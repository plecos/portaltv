package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/** Finer versions of what is in the sky for the 16-bit style: clouds, birds, bats, the plane, balloons and the sleigh (see [Fine]). */
internal object HiSky {
    private val BIRD = rgb(38, 40, 52)
    private val WHITE = rgb(246, 246, 244)
    private val GREY = rgb(206, 208, 216)

    private fun Fine.thick(x0: Float, y0: Float, x1: Float, y1: Float, c: Int) { line(x0, y0, x1, y1, c); line(x0, y0 + 1, x1, y1 + 1, c) }

    /** A cloud of lumps, drawn at twice the size from the same lumps with finer puffs: lit white on top, soft blue-grey beneath. */
    fun cloud(sp: Sprite, seed: Int) = Fine(sp, blank = true).apply {
        val width = sp.width; val height = sp.height
        val lumps = 4 + width / 14
        val cx = FloatArray(lumps); val cy = FloatArray(lumps); val cr = FloatArray(lumps)
        for (i in 0 until lumps) {
            val u = (i + 0.5f) / lumps
            cx[i] = 3f + u * (width - 6)
            val mid = 1f - kotlin.math.abs(u - 0.5f) * 1.6f
            cr[i] = (height * (0.28f + 0.30f * mid)) * (0.8f + 0.5f * Noise.unit(seed, i))
            cy[i] = height - 2f - cr[i] * 0.55f - Noise.unit(seed, i, 1) * height * 0.12f
        }
        val white = rgb(255, 255, 255); val light = rgb(240, 243, 250); val mid = rgb(216, 224, 238); val shade = rgb(188, 198, 218)
        for (py in 0 until h) for (px in 0 until w) {
            val x = (px + 0.5f) / 2f; val y = (py + 0.5f) / 2f
            var inside = false
            var lightest = -9f
            for (i in 0 until lumps) {
                // Each lump is a little rough at its edge, so the outline is puffy instead of smooth.
                val rough = 1f + 0.10f * (Noise.unit(px shr 1, py shr 1, seed + 7 + i) - 0.5f)
                val dx = x - cx[i]; val dy = y - cy[i]
                val r = cr[i] * rough
                if (dx * dx + dy * dy <= r * r) {
                    inside = true
                    val l = (-dx * 0.12f - dy * 1.0f) / cr[i]
                    if (l > lightest) lightest = l
                }
            }
            if (!inside) continue
            val fromBottom = (height - 1 - y) / height
            val edge = kotlin.math.abs(x - width / 2f) / (width / 2f)
            if (fromBottom < 0.28f && edge > 0.45f + fromBottom * 1.6f) continue
            val v = lightest + 0.22f * (Noise.unit(px shr 1, py shr 1, seed + 91) - 0.5f)
            px(px, py, when {
                v > 0.50f -> white
                v > 0.12f -> light
                fromBottom < 0.30f -> shade
                else -> mid
            })
        }
    }.done()

    /** A bird in flight seen from the side: wings raised ([up]) or lowered, thick strokes with a body. */
    fun bird(sp: Sprite, up: Boolean) = Fine(sp, blank = true).apply {
        if (up) { thick(0f, 0f, 3f, 3f, BIRD); thick(9f, 0f, 6f, 3f, BIRD); rect(4, 3, 6, 5, BIRD) }
        else { thick(0f, 5f, 3f, 2f, BIRD); thick(9f, 5f, 6f, 2f, BIRD); rect(4, 1, 6, 3, BIRD); px(4, 3, BIRD); px(5, 3, BIRD) }
    }.done()

    fun gull(sp: Sprite, up: Boolean) = Fine(sp, blank = true).apply {
        if (up) { thick(0f, 0f, 3f, 3f, WHITE); thick(9f, 0f, 6f, 3f, WHITE); rect(4, 3, 6, 5, GREY); px(0, 0, GREY); px(9, 0, GREY) }
        else { thick(0f, 5f, 3f, 2f, WHITE); thick(9f, 5f, 6f, 2f, WHITE); rect(4, 1, 6, 3, GREY); px(0, 5, GREY); px(9, 5, GREY) }
    }.done()

    /** A bat: wings with a scalloped edge, ears and a small body; [up] raises the wings. */
    fun bat(sp: Sprite, up: Boolean) = Fine(sp, blank = true).apply {
        val c = rgb(30, 28, 40)
        rect(4, 2, 6, 5, c); px(4, 1, c); px(5, 1, c)
        if (up) {
            for (x in 0..3) { val top = (x * 0.6f).toInt(); for (y in top..3 + (x % 2)) px(x, y, c) }
            for (x in 6..9) { val xx = 9 - x; val top = (xx * 0.6f).toInt(); for (y in top..3 + (xx % 2)) px(x, y, c) }
        } else {
            for (x in 0..3) { val bottom = 5 - (x * 0.5f).toInt(); for (y in 2 + (x % 2)..bottom) px(x, y, c) }
            for (x in 6..9) { val xx = 9 - x; val bottom = 5 - (xx * 0.5f).toInt(); for (y in 2 + (xx % 2)..bottom) px(x, y, c) }
        }
    }.done()

    /** A plane from above, 16 by 6: a long white fuselage with a pointed nose, swept wings and tail planes. */
    fun plane(sp: Sprite) = Fine(sp, blank = true).apply {
        val shade = rgb(190, 196, 210)
        rect(1, 2, 14, 4, WHITE); px(14, 2, WHITE); px(15, 3, WHITE); rect(1, 3, 14, 4, shade)
        for (i in 0..3) { rect(6 + i, 1 - i / 2, 8 + i, 2, WHITE); rect(6 + i, 4, 8 + i, 5 + i / 2, shade) }
        rect(1, 1, 3, 2, WHITE); rect(1, 4, 3, 5, shade)
        px(12, 2, rgb(70, 100, 140)); px(13, 2, rgb(70, 100, 140))
    }.done()

    /** A hot-air balloon, 30 by 42: gores of two colours lit from the left, a dark skirt, ropes and a woven basket. */
    fun balloon(sp: Sprite, a: Int, b: Int) = Fine(sp, blank = true).apply {
        val cxm = 14.5f; val cym = 14f
        for (y in 0..29) for (x in 0 until 30) {
            val r = if (y <= 14) cxm * Math.sqrt((1.0 - ((cym - y) / cxm) * ((cym - y) / cxm)).coerceAtLeast(0.0)).toFloat() else cxm - (y - 14) * 0.5f
            val u = (x + 0.5f - 15f) / r
            if (u < -1f || u > 1f) continue
            val gore = (((u + 1f) * 3f)).toInt().coerceIn(0, 5)
            var c = if (gore % 2 == 0) a else b
            val seam = Math.abs((((u + 1f) * 3f)) - Math.round((u + 1f) * 3f)) < 0.07f
            if (seam) c = Colors.scale(c, 0.78f)
            c = if (u < -0.35f) Colors.mul(c, 1.15f, 1.15f, 1.15f) else if (u > 0.45f) Colors.scale(c, 0.8f) else c
            px(x, y, c)
        }
        rect(11, 29, 19, 31, rgb(60, 50, 50))
        val rope = rgb(96, 76, 54)
        for (k in 0..5) { px(11 + k / 3, 31 + k, rope); px(18 - k / 3, 31 + k, rope) }
        px(14, 31, rgb(250, 200, 80)); px(15, 31, rgb(250, 200, 80)); px(14, 32, rgb(240, 150, 60))
        for (y in 36..41) for (x in 12..17) px(x, y, if ((x + y) % 2 == 0) rgb(150, 108, 66) else rgb(112, 78, 46))
        rect(12, 36, 18, 37, rgb(176, 130, 80))
    }.done()

    /** The sleigh and eight reindeer, 120 by 20: antlered reindeer in pairs on a golden harness, the lead one with a red nose, and Santa in the sleigh. */
    fun team(sp: Sprite) = Fine(sp, blank = true).apply {
        val deer = rgb(176, 132, 84); val far = Colors.mul(deer, 0.72f, 0.72f, 0.78f); val dark = rgb(120, 80, 50); val gold = rgb(240, 200, 80)
        val antler = rgb(214, 190, 140)
        fun reindeer(x: Int, y: Int, body: Int, red: Boolean) {
            rect(x + 2, y + 3, x + 12, y + 7, body)           // the body
            rect(x + 11, y + 1, x + 14, y + 4, body)          // neck and head
            rect(x + 14, y + 2, x + 16, y + 4, if (red) rgb(255, 60, 50) else dark)   // muzzle
            px(x + 13, y + 2, rgb(30, 24, 20))
            px(x + 11, y, antler); px(x + 13, y, antler); px(x + 10, y - 1, antler); px(x + 14, y - 1, antler)
            for (lx in listOf(2, 4, 9, 11)) rect(x + lx, y + 7, x + lx + 1, y + 10, dark)
            rect(x, y + 3, x + 2, y + 5, body)                // the tail
            rect(x + 2, y + 6, x + 12, y + 7, Colors.scale(body, 0.85f))
        }
        for (x in 14 until 112) px(x, 12, gold)
        for (p in 0 until 4) {
            val x = (18 + p * 10) * 2
            reindeer(x + 4, 4, far, false)
            reindeer(x, 10, deer, p == 3)
        }
        // The sleigh: curled runners, a red body with a gold rim, a sack, and Santa.
        val red = rgb(214, 52, 52); val white = rgb(245, 245, 245)
        rect(0, 18, 28, 20, gold); px(0, 16, gold); px(0, 17, gold); px(1, 15, gold)
        rect(0, 12, 28, 18, red); rect(0, 12, 28, 13, gold); rect(0, 17, 28, 18, Colors.scale(red, 0.75f))
        rect(4, 2, 10, 12, rgb(150, 100, 60))                // the sack
        rect(13, 6, 22, 12, red); rect(13, 6, 22, 7, white)  // his coat
        rect(14, 1, 21, 6, rgb(238, 190, 156)); rect(14, 4, 21, 7, white); rect(13, 0, 21, 2, red); rect(13, 0, 15, 3, white); px(22, 3, red)
        px(18, 3, rgb(30, 24, 20))
    }.done()
}
