package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/**
 * Pictures drawn at twice the detail for the 16-bit style. Each is built on a small canvas of characters (like the 8-bit sprites),
 * but on a grid twice as fine, so there is room for windows, lights, wheels and shading. They are used in place of the 8-bit
 * sprite's automatic doubling, and each has to be exactly twice the 8-bit sprite's width and height.
 */
internal class Draw(val w: Int, val h: Int) {
    private val g = Array(h) { CharArray(w) { '.' } }

    fun px(x: Int, y: Int, c: Char) { if (x in 0 until w && y in 0 until h && c != '.') g[y][x] = c }
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Char) { for (y in y0 until y1) for (x in x0 until x1) px(x, y, c) }

    /** Writes [s] along row [y] from column [x]; dots leave what is there. */
    fun put(y: Int, x: Int, s: String) { s.forEachIndexed { i, c -> px(x + i, y, c) } }

    /** A round wheel [n] wide with a hub. */
    fun wheel(x: Int, y: Int, n: Int) {
        val c = (n - 1) / 2f
        for (dy in 0 until n) for (dx in 0 until n) {
            val d = Math.hypot((dx - c).toDouble(), (dy - c).toDouble())
            if (d <= n / 2f - 0.05f) px(x + dx, y + dy, if (d <= n / 5f) 'g' else 'k')
        }
        if (n >= 6) { px(x + n / 2, y + n / 2, 'g'); px(x + n / 2 - 1, y + n / 2 - 1, 'g') }
    }

    fun rows(): List<String> = g.map { String(it) }
}

internal object HiSprites {
    private val GLASS = mapOf(
        'w' to rgb(150, 198, 232), 'G' to rgb(112, 156, 196), 'W' to rgb(232, 246, 255),
        'k' to rgb(26, 26, 32), 'g' to rgb(146, 150, 160), 'h' to rgb(255, 246, 190), 'r' to rgb(224, 44, 40),
        'b' to rgb(64, 68, 78), 'N' to rgb(58, 60, 72), 'Y' to rgb(244, 208, 64)
    )

    private fun bodyColours(body: Int): Map<Char, Int> = mapOf(
        'c' to body, 'L' to Colors.mul(body, 1.22f, 1.22f, 1.22f), 'C' to Colors.scale(body, 0.72f), 'D' to Colors.scale(body, 0.5f)
    )

    /** A car in profile facing right, 28 by 14 (with [raise] extra rows above for a roof sign or light bar). */
    private fun car(oy: Int, h: Int, band: Boolean = false, checker: Boolean = false): Draw {
        val d = Draw(28, h)
        d.put(oy + 0, 8, "cLLLLLLLc")
        d.put(oy + 1, 6, "cGwwwcwwwwwwc")
        d.put(oy + 2, 5, "cwwwwwcwwwwwwwc")
        d.put(oy + 3, 4, "cGGGGGGcGGGGGGGGc")
        d.put(oy + 4, 1, "c" + "L".repeat(24) + "c")
        d.put(oy + 5, 0, "c" + "L".repeat(26) + "c")
        d.rect(0, oy + 6, 28, oy + 9, 'c')
        // Door seams and handles, the lights, and the dark sill with its bumpers.
        d.px(8, oy + 7, 'C'); d.px(18, oy + 7, 'C'); d.px(10, oy + 7, 'L'); d.px(20, oy + 7, 'L')
        d.put(oy + 8, 0, "rr"); d.put(oy + 8, 25, "hhh")
        d.rect(0, oy + 9, 28, oy + 11, 'C')
        d.rect(0, oy + 9, 2, oy + 11, 'b'); d.rect(26, oy + 9, 28, oy + 11, 'b')
        d.px(8, oy + 1, 'W'); d.px(7, oy + 2, 'W'); d.px(6, oy + 3, 'W'); d.px(15, oy + 1, 'W'); d.px(14, oy + 2, 'W'); d.px(13, oy + 3, 'W')
        if (band) d.rect(1, oy + 6, 27, oy + 8, 'C')
        if (checker) for (x in 1 until 27) { d.px(x, oy + 6, if (x % 2 == 0) 'k' else 'c'); d.px(x, oy + 7, if (x % 2 == 0) 'c' else 'k') }
        d.wheel(3, oy + 8, 6); d.wheel(19, oy + 8, 6)
        return d
    }

    fun car(body: Int) = car(0, 14).rows() to (GLASS + bodyColours(body))

    fun taxi(body: Int): Pair<List<String>, Map<Char, Int>> {
        val d = car(2, 16, checker = true)
        d.put(0, 11, "hhhhhh"); d.put(1, 11, "hhhhhh")
        return d.rows() to (GLASS + bodyColours(body))
    }

    fun police(): Pair<List<String>, Map<Char, Int>> {
        val d = car(2, 16, band = true)
        d.rect(11, 0, 17, 2, 'N')
        return d.rows() to (GLASS + bodyColours(rgb(240, 240, 244)) + mapOf('C' to rgb(40, 52, 96)))
    }

    private val EMERGENCY = mapOf('r' to rgb(206, 38, 38), 'R' to rgb(206, 38, 38), 'L' to rgb(176, 176, 184))

    /** The ambulance's colours are fixed (white, with a red stripe). */
    fun ambulance(): Pair<List<String>, Map<Char, Int>> {
        val d = Draw(36, 16)
        d.put(2, 0, "c" + "L".repeat(22) + "c")
        d.rect(0, 3, 36, 11, 'c')
        d.put(3, 26, "cccccc")
        d.put(4, 24, "c" + "G".repeat(7) + "c"); d.put(5, 24, "c" + "w".repeat(8) + "c")
        d.px(26, 4, 'W'); d.px(25, 5, 'W')
        // The red cross on the box, and the stripe along the lower side.
        d.rect(12, 4, 14, 9, 'R'); d.rect(10, 6, 16, 7, 'R')
        d.rect(0, 9, 36, 11, 'R')
        d.rect(0, 11, 36, 12, 'C')
        d.rect(0, 10, 2, 11, 'b'); d.rect(34, 10, 36, 11, 'h')
        d.wheel(2, 10, 6); d.wheel(24, 10, 6)
        return d.rows() to (GLASS + EMERGENCY + mapOf('c' to rgb(244, 244, 246), 'L' to rgb(255, 255, 255), 'C' to rgb(190, 192, 200)))
    }

    fun fireEngine(): Pair<List<String>, Map<Char, Int>> {
        val d = Draw(52, 16)
        // The ladder along the roof: two rails and rungs.
        d.rect(4, 0, 32, 1, 'L')
        for (x in 4 until 32 step 3) d.px(x, 1, 'L')
        d.rect(4, 2, 32, 3, 'L')
        d.rect(2, 3, 32, 5, 'r'); d.rect(36, 3, 50, 5, 'r')
        d.rect(0, 5, 52, 12, 'r')
        d.put(5, 40, "r"); d.rect(40, 5, 51, 8, 'w'); d.rect(40, 5, 41, 8, 'r'); d.rect(50, 5, 52, 8, 'r')
        d.px(42, 5, 'W'); d.px(41, 6, 'W'); d.rect(41, 7, 50, 8, 'G')
        d.rect(1, 8, 51, 10, 'Y')
        d.rect(1, 12, 51, 13, 'C')
        d.rect(0, 11, 2, 12, 'b'); d.rect(50, 11, 52, 12, 'h')
        d.wheel(4, 10, 6); d.wheel(18, 10, 6); d.wheel(40, 10, 6)
        return d.rows() to (GLASS + EMERGENCY + mapOf('r' to rgb(206, 38, 38), 'L' to rgb(190, 190, 198), 'C' to rgb(150, 28, 28)))
    }

    /** A bus in profile facing right, [w] wide, with panes along the side. */
    private fun bus(w: Int, panes: Int, top: Char): Draw {
        val d = Draw(w, 18)
        d.rect(0, 0, w, 14, 'c')
        d.rect(0, 0, w, 1, 'L')
        d.rect(0, 1, w, 2, top)
        for (i in 0 until panes) {
            val x = 3 + i * 9
            d.rect(x, 3, x + 7, 8, 'w'); d.rect(x, 7, x + 7, 8, 'G'); d.px(x + 1, 6, 'W'); d.px(x + 2, 5, 'W'); d.px(x + 3, 4, 'W')
        }
        // The doors and the big windscreen at the front.
        val doorX = 3 + panes * 9
        d.rect(doorX, 3, doorX + 5, 12, 'G'); d.rect(doorX + 2, 3, doorX + 3, 12, 'c')
        d.rect(w - 6, 3, w - 1, 9, 'w'); d.rect(w - 6, 8, w - 1, 9, 'G'); d.px(w - 5, 4, 'W'); d.px(w - 4, 3, 'W')
        d.rect(0, 9, w, 10, 'C'); d.rect(0, 12, w, 14, 'C')
        d.rect(0, 11, 2, 13, 'r'); d.rect(w - 2, 11, w, 13, 'h')
        d.rect(0, 13, 3, 14, 'b'); d.rect(w - 3, 13, w, 14, 'b')
        d.wheel(3, 10, 8); d.wheel(w - 12, 10, 8)
        return d
    }

    fun bus(body: Int) = bus(60, 5, 'c').rows() to (GLASS + bodyColours(body))

    fun schoolBus(): Pair<List<String>, Map<Char, Int>> {
        val d = bus(64, 5, 'K')
        d.px(0, 1, 'R'); d.px(1, 1, 'R'); d.px(62, 1, 'R'); d.px(63, 1, 'R')
        d.rect(0, 9, 64, 10, 'K'); d.rect(0, 11, 64, 12, 'K')
        return d.rows() to (GLASS + bodyColours(rgb(250, 190, 30)) + mapOf('K' to rgb(30, 30, 36), 'R' to rgb(230, 50, 40)))
    }

    fun truck(body: Int): Pair<List<String>, Map<Char, Int>> {
        val d = Draw(46, 16)
        // The load, in a pale frame.
        d.rect(0, 0, 41, 12, 'f')
        d.rect(1, 1, 40, 11, 'c')
        d.rect(1, 1, 40, 2, 'L'); d.rect(1, 8, 40, 11, 'C')
        for (x in 6 until 40 step 8) d.rect(x, 2, x + 1, 8, 'L')
        // The cab: a window and a door.
        d.rect(41, 3, 46, 12, 'f')
        d.rect(42, 4, 45, 8, 'w'); d.rect(42, 7, 45, 8, 'G'); d.px(43, 5, 'W')
        d.rect(42, 8, 46, 11, 'c'); d.rect(45, 9, 46, 10, 'h')
        d.rect(0, 12, 46, 13, 'b')
        d.wheel(2, 9, 6); d.wheel(18, 9, 6); d.wheel(34, 9, 6); d.wheel(41, 9, 5)
        return d.rows() to (GLASS + bodyColours(body) + mapOf('f' to rgb(220, 222, 228)))
    }
}
