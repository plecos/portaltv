package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/** Finer versions of the beach's pictures for the 16-bit style (see [Fine]). */
internal object HiBeach {
    private val CLEAR = 0
    private val DARK = rgb(40, 38, 44)
    private val WHITE = rgb(246, 246, 244)

    /** A beach umbrella, 26 by 22: a round canopy of eight panels with a scalloped edge, lit from the upper left, on a pole. */
    fun umbrella(sp: Sprite, c1: Int, c2: Int, pole: Int) = Fine(sp, blank = true).apply {
        rect(12, 6, 14, 22, pole)
        rect(13, 6, 14, 22, Colors.scale(pole, 0.8f))
        for (y in 0..8) for (x in 0 until 26) {
            val ex = (x + 0.5f - 13f) / 13f
            val ey = (y + 0.5f - 8.5f) / 8.5f
            if (ex * ex + ey * ey > 1f) continue
            val panel = (x / 3.25f).toInt()
            var c = if (panel % 2 == 0) c1 else c2
            if (x > 17) c = Colors.scale(c, 0.86f)
            if (x < 9 && y < 4) c = Colors.mul(c, 1.14f, 1.14f, 1.14f)
            px(x, y, c)
        }
        // The scalloped rim: a little half-round under each panel.
        for (k in 0 until 8) {
            val c = if (k % 2 == 0) c1 else c2
            disc((k + 0.5f) * 3.25f, 8.6f, 1.7f, if (k > 4) Colors.scale(c, 0.8f) else Colors.scale(c, 0.92f))
        }
        px(12, 0, pole); px(13, 0, pole)
    }.done()

    /** A palm: a curved ringed trunk, and seven fronds each with leaflets drooping either side of its spine; 38 by 50. */
    fun palm(sp: Sprite, lean: Int) = Fine(sp, blank = true).apply {
        val trunk = rgb(132, 96, 62); val ring = rgb(96, 68, 44); val light = rgb(158, 120, 80)
        val top = 18
        val dir = if (lean == 0) 1 else -1
        for (y in 49 downTo top) {
            val bend = dir * ((49 - y) * 4 / 31)
            val x = 18 + bend
            val wide = if (y > 40) 4 else 3
            for (k in 0 until wide) px(x + k - 1, y, if (y % 4 == 0) ring else if (k == 0) light else trunk)
        }
        val cx = 18f + dir * 4 + 0.5f
        val leaf = rgb(46, 138, 60); val leafLight = rgb(104, 192, 86); val leafDark = rgb(30, 100, 46)
        val dirs = arrayOf(floatArrayOf(-1f, 0.6f), floatArrayOf(-1f, 0.05f), floatArrayOf(-0.7f, -0.6f), floatArrayOf(0f, -1f), floatArrayOf(0.7f, -0.6f), floatArrayOf(1f, 0.05f), floatArrayOf(1f, 0.6f))
        for ((k, d) in dirs.withIndex()) {
            val len = if (k == 3) 14f else 17f
            var t = 0f
            while (t < len) {
                val x = cx + d[0] * t
                val y = top + d[1] * t + t * t * 0.05f
                px(x.toInt(), y.toInt(), if (k % 2 == 0) leaf else leafLight)
                // Leaflets droop down either side of the spine.
                if (t > 2f && ((t * 2).toInt() % 2 == 0)) {
                    val s = (3.2f * (1f - t / (len + 2f))).coerceAtLeast(1f)
                    px((x - 0.6f).toInt(), (y + s).toInt(), leafDark); px((x + 0.6f).toInt(), (y + s).toInt(), leaf)
                    px((x - 0.4f).toInt(), (y + s * 0.5f).toInt(), leaf); px((x + 0.4f).toInt(), (y + s * 0.5f).toInt(), leafLight)
                }
                t += 0.5f
            }
        }
        val nut = rgb(110, 76, 44)
        disc(cx - 1.5f, top + 2.2f, 1.2f, nut); disc(cx + 1.5f, top + 2.2f, 1.2f, nut); disc(cx, top + 3.2f, 1.2f, Colors.scale(nut, 0.8f))
    }.done()

    fun tower(sp: Sprite) = Fine(sp).apply {
        val dark = rgb(112, 80, 54); val slat = rgb(216, 216, 214)
        for (y in 14 until 18 step 3) overRect(4, y, 24, y + 1, slat)
        // The legs braced with crosses, and rungs on the ladder side.
        line(3f, 21f, 8f, 33f, dark); line(8f, 21f, 3f, 33f, dark); line(19f, 21f, 24f, 33f, dark); line(24f, 21f, 19f, 33f, dark)
        for (y in 24 until 33 step 3) rect(10, y, 18, y + 1, dark)
        rect(12, 8, 13, 12, rgb(36, 50, 70)); px(8, 12, rgb(180, 210, 230)); px(9, 11, rgb(180, 210, 230))
        overRect(2, 2, 26, 3, rgb(240, 120, 110))
    }.done()

    fun castle(sp: Sprite) = Fine(sp).apply {
        px(2, 2, CLEAR); px(3, 2, CLEAR); px(14, 2, CLEAR); px(15, 2, CLEAR); px(9, 0, CLEAR); px(10, 0, CLEAR); px(7, 1, CLEAR)
        for (y in 8..11) for (x in 7..10) if (y > 8 || (x == 8 || x == 9)) over(x, y, rgb(150, 124, 84))
        speckle(0, 0, 18, 12, rgb(232, 210, 160), 0.14f, 61)
        speckle(0, 0, 18, 12, rgb(170, 146, 100), 0.10f, 62)
    }.done()

    fun net(sp: Sprite) = Fine(sp, blank = true).apply {
        val post = rgb(236, 236, 232); val shade = rgb(196, 196, 198); val mesh = rgb(210, 210, 216)
        rect(0, 0, 2, 24, post); rect(1, 0, 2, 24, shade); rect(34, 0, 36, 24, post); rect(35, 0, 36, 24, shade)
        for (y in 4..11) for (x in 2..33) if ((x + y) % 3 == 0 || (x - y + 99) % 3 == 0) px(x, y, mesh)
        rect(2, 2, 34, 4, post); rect(2, 3, 34, 4, shade)
        rect(2, 12, 34, 13, shade)
    }.done()

    fun sailboat(sp: Sprite) = Fine(sp).apply {
        val seam = 0.88f
        for (y in 6 until 16 step 3) tone(10, y, 20, y + 1, seam)
        for (y in 6 until 16 step 3) tone(0, y, 8, y + 1, 0.9f)
        px(10, 0, rgb(210, 60, 52)); px(11, 0, rgb(210, 60, 52)); px(10, 1, rgb(210, 60, 52))
        px(0, 19, CLEAR); px(1, 19, CLEAR); px(0, 18, CLEAR); px(18, 19, CLEAR); px(19, 19, CLEAR); px(19, 18, CLEAR)
        for (x in listOf(6, 10, 14)) px(x, 17, rgb(60, 80, 110))
    }.done()

    fun ship(sp: Sprite) = Fine(sp).apply {
        val rib = 0.82f
        for (x in 22 until 44 step 2) tone(x, 4, x + 1, 12, rib)
        px(10, 6, WHITE); px(12, 6, WHITE); px(14, 6, WHITE); px(10, 7, WHITE); px(12, 7, WHITE); px(14, 7, WHITE)
        overRect(10, 1, 14, 2, WHITE)
        for (x in 4 until 44 step 4) px(x, 14, rgb(206, 206, 196))
        px(47, 17, CLEAR); px(46, 17, CLEAR); px(47, 16, CLEAR)
    }.done()

    fun lighthouse(sp: Sprite) = Fine(sp).apply {
        tone(9, 14, 18, 44, 0.9f)
        for (x in 4..13 step 2) px(x, 12, CLEAR)
        rect(9, 6, 10, 12, rgb(70, 70, 80))
        rect(8, 38, 10, 44, rgb(60, 44, 40)); px(8, 38, CLEAR)
        for (y in listOf(24, 34)) rect(8, y, 10, y + 2, rgb(50, 56, 70))
        speckle(0, 44, 18, 48, rgb(132, 128, 134), 0.2f, 63)
    }.done()

    fun cruise(sp: Sprite) = Fine(sp).apply {
        for (x in listOf(48, 60)) rect(x, 4, x + 8, 5, WHITE)
        for (y in listOf(11, 17, 23)) overRect(10, y, 82, y + 1, rgb(190, 196, 208))
        for (x in 12 until 80 step 4) px(x, 28, rgb(60, 70, 100))
        speckle(2, 24, 90, 32, rgb(250, 250, 250), 0.05f, 64)
    }.done()

    fun pelican(sp: Sprite, headY: Int) = Fine(sp).apply {
        val pouch = rgb(220, 160, 120)
        px(20, headY + 1, DARK)
        rect(22, headY + 4, 25, headY + 5, pouch)
        px(25, headY, rgb(150, 110, 70))
        px(22, headY - 1, rgb(60, 52, 44))
    }.done()
}
