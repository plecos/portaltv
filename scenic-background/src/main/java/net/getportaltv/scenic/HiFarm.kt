package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/**
 * Finer versions of the animals, farm buildings and trains for the 16-bit style. Each starts from its 8-bit picture doubled
 * with the corners rounded, and then has the detail the coarse grid had no room for painted on: eyes, hooves, tails, planks,
 * shingles, wheels. The result is given the same lit and shaded edges as the other fine sprites.
 */
internal class Fine(private val sp: Sprite, blank: Boolean = false) {
    val w = sp.width * 2
    val h = sp.height * 2
    /** The fine pixels: the sprite doubled with rounded corners, or (for [blank]) empty, to be drawn from nothing. */
    val a: IntArray = if (blank) IntArray(w * h) else Sprite.doubled(sp.pixels, sp.width, sp.height)

    /** A solid disc. */
    fun disc(cx: Float, cy: Float, r: Float, c: Int) {
        for (y in (cy - r).toInt() - 1..(cy + r).toInt() + 1) for (x in (cx - r).toInt() - 1..(cx + r).toInt() + 1) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r * r) px(x, y, c)
        }
    }

    /** A line of single pixels. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, c: Int) {
        val n = maxOf(Math.abs(x1 - x0), Math.abs(y1 - y0)).toInt().coerceAtLeast(1)
        for (k in 0..n) px((x0 + (x1 - x0) * k / n).toInt(), (y0 + (y1 - y0) * k / n).toInt(), c)
    }

    fun get(x: Int, y: Int): Int = if (x in 0 until w && y in 0 until h) a[y * w + x] else 0
    fun px(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) a[y * w + x] = c }
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px(x, y, c) }

    /** Paints [c] only where there is already something (so details stay inside the shape). */
    fun over(x: Int, y: Int, c: Int) { if (get(x, y) ushr 24 != 0) px(x, y, c) }
    fun overRect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) over(x, y, c) }

    /** Darkens (or lightens) what is there in a block by [k]. */
    fun tone(x0: Int, y0: Int, x1: Int, y1: Int, k: Float) {
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = get(x, y)
            if (c ushr 24 != 0) px(x, y, (c and 0xFF000000.toInt()) or (Colors.scale(c, k) and 0xFFFFFF))
        }
    }

    /** Scatters [c] over the solid pixels in a block, each with probability [p]. */
    fun speckle(x0: Int, y0: Int, x1: Int, y1: Int, c: Int, p: Float, seed: Int) {
        for (y in y0 until y1) for (x in x0 until x1) if (Noise.unit(x, y, seed) < p) over(x, y, c)
    }

    /** A round wheel [n] across with a tyre and a hub, painted over everything. */
    fun wheel(x: Int, y: Int, n: Int, tyre: Int, hub: Int) {
        val c = (n - 1) / 2f
        for (dy in 0 until n) for (dx in 0 until n) {
            val d = Math.hypot((dx - c).toDouble(), (dy - c).toDouble())
            if (d <= n / 2f - 0.05f) px(x + dx, y + dy, if (d <= n / 4.5f) hub else tyre)
        }
    }

    fun done(): Sprite = sp.detailPixels(a, true)
}

internal object HiFarm {
    private val BLACK = rgb(34, 32, 34)
    private val WHITE = rgb(240, 238, 232)
    private val PINK = rgb(232, 150, 150)
    private val GREY = rgb(170, 164, 150)
    private val CLEAR = 0

    fun cow(sp: Sprite) = Fine(sp).apply {
        // Horns, an eye and the pink muzzle; ears either side; a dark tail and tuft; the udder and hooves; a shaded belly.
        px(22, 0, GREY); px(23, 0, GREY); px(20, 2, BLACK); px(21, 2, BLACK)
        px(23, 3, BLACK); px(25, 5, rgb(120, 70, 70)); px(24, 4, PINK); px(25, 4, PINK); px(24, 5, PINK)
        for (y in 6..9) px(0, y, BLACK)
        px(0, 10, BLACK); px(1, 10, BLACK)
        overRect(2, 10, 22, 12, rgb(206, 204, 198))
        rect(10, 11, 13, 12, PINK)
        for (x in listOf(2, 6, 16, 20)) { rect(x, 12, x + 2, 14, WHITE); rect(x, 13, x + 2, 14, BLACK) }
        // The patches get ragged edges.
        speckle(2, 4, 10, 10, BLACK, 0.08f, 91)
    }.done()

    fun sheep(sp: Sprite) = Fine(sp).apply {
        speckle(0, 0, 14, 9, rgb(208, 206, 198), 0.22f, 92)
        tone(0, 6, 14, 8, 0.86f)
        px(14, 3, GREY); px(12, 1, BLACK); px(13, 1, BLACK)
        for (x in listOf(2, 6, 10, 14)) { rect(x, 8, x + 1, 10, BLACK) }
    }.done()

    fun horse(sp: Sprite) = Fine(sp).apply {
        val mane = rgb(60, 40, 30)
        px(21, 3, BLACK); px(21, 5, BLACK); px(22, 4, rgb(96, 64, 44))
        for (p in listOf(16 to 2, 16 to 3, 15 to 4, 14 to 4, 13 to 5)) px(p.first, p.second, mane)
        for (y in 6..11) px(0, y, mane)
        px(1, 11, mane); px(0, 12, mane)
        tone(2, 8, 20, 10, 0.82f)
        for (x in listOf(2, 6, 14, 18)) rect(x, 13, x + 2, 14, BLACK)
    }.done()

    fun dog(sp: Sprite) = Fine(sp).apply {
        px(10, 1, BLACK); px(11, 2, BLACK); px(8, 1, rgb(100, 66, 40)); px(8, 0, rgb(100, 66, 40))
        for (x in 8..10) px(x, 3, rgb(190, 50, 50))
        tone(0, 3, 12, 4, 0.84f)
    }.done()

    fun rabbit(sp: Sprite) = Fine(sp).apply {
        val inner = rgb(232, 170, 170)
        px(9, 1, inner); px(9, 2, inner); px(13, 1, inner); px(13, 2, inner)
        px(13, 6, WHITE); px(14, 6, BLACK)
        overRect(6, 9, 11, 11, rgb(222, 212, 196))
        rect(0, 6, 2, 10, WHITE); px(0, 6, GREY)
    }.done()

    fun duck(sp: Sprite) = Fine(sp).apply {
        px(6, 1, BLACK)
        tone(2, 4, 8, 5, 0.82f)
        overRect(3, 3, 7, 4, rgb(176, 160, 128))
    }.done()

    fun deerUp(sp: Sprite) = Fine(sp).apply {
        val antler = rgb(196, 172, 130)
        for (p in listOf(11 to 0, 13 to 0, 15 to 0, 12 to 1, 13 to 1, 14 to 1, 12 to 2, 14 to 2)) px(p.first, p.second, antler)
        px(17, 4, BLACK); px(19, 5, BLACK); px(13, 3, rgb(120, 80, 50))
        speckle(4, 9, 17, 14, rgb(240, 232, 220), 0.06f, 93)
        tone(4, 12, 17, 14, 0.86f)
        for (x in listOf(2, 6, 12, 16)) rect(x, 17, x + 2, 18, BLACK)
    }.done()

    fun deerGraze(sp: Sprite) = Fine(sp).apply {
        speckle(4, 5, 17, 11, rgb(240, 232, 220), 0.06f, 94)
        tone(4, 9, 17, 11, 0.86f)
        px(18, 6, BLACK); px(19, 8, BLACK)
        for (x in listOf(2, 6, 12)) rect(x, 13, x + 2, 14, BLACK)
        rect(16, 13, 18, 14, BLACK); rect(18, 13, 20, 14, BLACK)
    }.done()

    fun tractor(sp: Sprite) = Fine(sp).apply {
        // Big rear wheel and small front wheel, with treads and hubs.
        val tyre = rgb(26, 26, 30); val hub = rgb(170, 60, 50)
        rect(0, 10, 12, 18, CLEAR)
        rect(16, 12, 28, 18, CLEAR)
        wheel(0, 8, 11, tyre, hub); wheel(16, 12, 7, tyre, hub); wheel(21, 12, 7, tyre, hub)
        for (x in 0 until 11 step 2) px(x, 18 - 1, rgb(60, 60, 66))
        rect(14, 6, 26, 7, rgb(60, 60, 66))   // the bonnet's top
        px(10, 2, rgb(255, 244, 180))
    }.done()

    fun locomotive(sp: Sprite) = Fine(sp).apply {
        val brass = rgb(226, 184, 52); val tyre = rgb(34, 34, 40); val boiler = rgb(150, 44, 36)
        // The chimney's flared top, boiler bands, a brass dome, and the lamp.
        rect(4, 0, 10, 1, tyre)
        for (x in listOf(14, 20, 26)) for (y in 4..8) over(x, y, boiler)
        rect(16, 1, 20, 3, brass)
        px(30, 6, rgb(255, 240, 170)); px(31, 6, rgb(255, 240, 170)); px(30, 7, rgb(255, 240, 170))
        // Wheels with a brass rod along them.
        rect(0, 10, 32, 12, CLEAR)
        for (x in listOf(3, 11, 19, 27)) wheel(x, 8, 4, tyre, brass)
        rect(5, 9, 29, 10, brass)
    }.done()

    fun boxcar(sp: Sprite) = Fine(sp).apply {
        val plank = rgb(0, 0, 0)
        for (x in listOf(8, 14, 20)) for (y in 4..8) tone(x, y, x + 1, y + 1, 0.82f)
        tone(2, 6, 22, 7, 0.88f)
        px(13, 5, rgb(226, 184, 52)); px(13, 6, rgb(226, 184, 52))
        rect(0, 10, 24, 12, CLEAR)
        for (x in listOf(3, 17)) wheel(x, 8, 4, rgb(34, 34, 40), rgb(150, 154, 162))
    }.done()

    fun tanker(sp: Sprite) = Fine(sp).apply {
        val band = rgb(112, 118, 126)
        for (x in listOf(7, 12, 17)) for (y in 1..9) over(x, y, band)
        overRect(5, 1, 19, 2, rgb(214, 218, 224))
        rect(10, 0, 14, 1, rgb(120, 126, 134))
        rect(0, 10, 24, 12, CLEAR)
        for (x in listOf(3, 17)) wheel(x, 8, 4, rgb(34, 34, 40), rgb(150, 154, 162))
    }.done()

    fun coach(sp: Sprite) = Fine(sp).apply {
        // A frame round each window and a mid bar, a roof highlight and a stripe along the side.
        val frame = rgb(60, 70, 90)
        for (x in listOf(2, 8, 14, 20)) { for (y in 4..7) { over(x - 1, y, frame); over(x + 4, y, frame) }; over(x + 1, 5, frame); over(x + 2, 5, frame) }
        overRect(2, 2, 26, 3, rgb(150, 156, 164))
        overRect(0, 8, 28, 9, rgb(240, 214, 90))
        rect(0, 10, 28, 12, CLEAR)
        for (x in listOf(3, 21)) wheel(x, 8, 4, rgb(34, 34, 40), rgb(150, 154, 162))
    }.done()

    fun barn(sp: Sprite) = Fine(sp).apply {
        val dark = rgb(90, 76, 74)
        // The roof's boards and the wall's planks, a cross on the big door, and a loft window.
        for (y in listOf(2, 4, 6)) for (x in 0 until w) over(x, y, dark)
        for (x in 0 until w step 6) for (y in 8 until 24) over(x, y, rgb(104, 30, 28))
        // A cross brace inside the dark doorway.
        for (i in 0..5) { over(16 + i, 18 + i, rgb(214, 206, 190)); over(23 - i, 18 + i, rgb(214, 206, 190)) }
        rect(18, 8, 22, 10, rgb(60, 52, 70))
    }.done()

    fun house(sp: Sprite) = Fine(sp).apply {
        val shingle = rgb(80, 44, 40); val sideLine = rgb(190, 184, 168)
        for (y in 6 until 12 step 2) for (x in 0 until w) over(x, y, shingle)
        for (y in 14 until 22 step 3) for (x in 0 until w) over(x, y, sideLine)
        // Window bars and the door's knob.
        for (x0 in listOf(4, 20)) { rect(x0 + 1, 14, x0 + 2, 18, rgb(228, 222, 204)); rect(x0, 15, x0 + 4, 16, rgb(228, 222, 204)) }
        px(17, 19, rgb(226, 184, 52))
        rect(12, 0, 16, 1, rgb(176, 120, 100))
    }.done()

    fun cabin(sp: Sprite) = Fine(sp).apply {
        // Round log ends at the corners, a window cross, door planks and the chimney's stones.
        for (y in 12 until 24 step 4) { rect(0, y, 2, y + 2, rgb(190, 140, 90)); rect(38, y, 40, y + 2, rgb(190, 140, 90)) }
        rect(9, 17, 10, 21, rgb(60, 70, 90)); rect(8, 18, 12, 19, rgb(60, 70, 90))
        for (x in listOf(27, 29)) for (y in 16 until 24) over(x, y, rgb(40, 28, 22))
        px(31, 20, rgb(226, 184, 52))
        for (y in 0 until 6) for (x in 30..33) if ((x + y) % 2 == 0) over(x, y, rgb(92, 88, 84))
    }.done()
}
