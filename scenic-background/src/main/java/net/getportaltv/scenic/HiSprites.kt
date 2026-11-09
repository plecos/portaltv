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

    /** A bicycle wheel: a tyre ring with a hub. */
    fun ring(x: Int, y: Int, n: Int) {
        val c = (n - 1) / 2f
        for (dy in 0 until n) for (dx in 0 until n) {
            val d = Math.hypot((dx - c).toDouble(), (dy - c).toDouble())
            if (d <= n / 2f - 0.05f && d >= n / 2f - 1.15f) px(x + dx, y + dy, 'k')
        }
        px(x + n / 2, y + n / 2, 'g')
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

    // ---------- people ----------

    private fun skinTones(skin: Int) = mapOf('s' to skin, 'S' to Colors.scale(skin, 0.8f))
    private fun hairTones(hair: Int) = mapOf('h' to hair, 'H' to Colors.scale(hair, 0.65f))
    private fun shirtTones(shirt: Int) = mapOf('b' to shirt, 'L' to Colors.mul(shirt, 1.2f, 1.2f, 1.2f), 'D' to Colors.scale(shirt, 0.72f))

    /** A walker, 10 wide and 16 tall (6 more under an umbrella), facing right. */
    fun person(shirt: Int, pants: Int, frame: Int, umbrella: Int?, skin: Int, hair: Int, bag: Int?): Pair<List<String>, Map<Char, Int>> {
        val top = if (umbrella != null) 6 else 0
        val d = Draw(10, 16 + top)
        if (umbrella != null) {
            d.put(0, 3, "uuuu"); d.put(1, 1, "uuuuuuuu"); d.put(2, 0, "UuuuuUuuuu"); d.put(3, 0, "uUuuUuuUuu"); d.put(4, 0, "u.uu.uu.u.")
            d.px(5, 4, 'k'); d.px(5, 5, 'k')
        }
        val y = top
        d.put(y + 0, 3, "hhhh"); d.put(y + 1, 2, "hhhhhh"); d.put(y + 2, 2, "hsssss")
        d.put(y + 3, 2, "SssssK"); d.put(y + 4, 3, "ssss")
        d.put(y + 5, 4, "ss")
        // The shirt, lit on the left and shaded on the right, with the arms either side.
        d.put(y + 6, 2, "LbbbbD"); d.put(y + 7, 1, "bLbbbDb"); d.put(y + 8, 1, "bLbbbDb"); d.put(y + 9, 1, "sLbbbDs"); d.put(y + 10, 2, "LbbbbD")
        d.put(y + 7, 1, "b"); d.put(y + 7, 8, "b")
        if (frame == 1) { d.px(1, y + 9, '.'); d.px(1, y + 10, 's'); d.px(8, y + 9, '.'); d.px(8, y + 8, 's') }
        // The trousers and legs, and the shoes.
        d.put(y + 11, 2, "pppppq"); d.put(y + 12, 2, "ppppqq")
        if (frame == 0) {
            d.put(y + 13, 2, "pp"); d.put(y + 13, 6, "qq"); d.put(y + 14, 2, "pp"); d.put(y + 14, 6, "qq")
            d.put(y + 15, 1, "kkk"); d.put(y + 15, 6, "kkk")
        } else {
            d.put(y + 13, 3, "ppqq"); d.put(y + 14, 4, "pq"); d.put(y + 15, 3, "kkkk")
        }
        if (bag != null) { d.rect(0, y + 10, 2, y + 13, 'g'); d.px(1, y + 9, 's') }
        val pal = HashMap<Char, Int>()
        pal.putAll(skinTones(skin)); pal.putAll(hairTones(hair)); pal.putAll(shirtTones(shirt))
        pal['p'] = pants; pal['q'] = Colors.scale(pants, 0.7f); pal['k'] = rgb(30, 30, 36)
        pal['K'] = rgb(40, 30, 28)
        pal['u'] = umbrella ?: 0; pal['U'] = Colors.scale(umbrella ?: 0, 0.75f); pal['g'] = bag ?: 0
        return d.rows() to pal
    }

    /** Somebody on a bicycle facing right, 16 by 12. */
    fun cyclist(shirt: Int, frame: Int, skin: Int, hair: Int): Pair<List<String>, Map<Char, Int>> {
        val d = Draw(16, 12)
        d.put(0, 8, "hhhh"); d.put(1, 7, "hhhhh"); d.put(2, 8, "hssss"); d.put(3, 9, "sss")
        d.put(3, 4, "LbD"); d.put(4, 3, "LbbbD"); d.put(5, 3, "LbbbbD"); d.put(6, 4, "bbbD")
        d.put(4, 11, "s"); d.put(5, 10, "ss")
        // The bicycle: two wheels and a frame, and the leg pedalling.
        d.ring(0, 6, 6); d.ring(10, 6, 6)
        d.put(9, 3, "y"); d.put(8, 4, "yy"); d.put(7, 6, "yyyy"); d.put(8, 9, "yy"); d.put(9, 12, "y")
        d.put(6, 12, "kk")
        if (frame == 0) { d.put(7, 5, "p"); d.put(8, 6, "p"); d.put(9, 7, "pp"); d.put(10, 7, "k") }
        else { d.put(7, 6, "pp"); d.put(8, 8, "p"); d.put(9, 9, "k") }
        val pal = hashMapOf('s' to skin, 'h' to hair, 'p' to rgb(52, 60, 96), 'k' to rgb(36, 36, 42), 'g' to rgb(150, 154, 162), 'y' to rgb(70, 74, 86))
        pal.putAll(shirtTones(shirt))
        return d.rows() to pal
    }

    // ---------- trees ----------

    /** The three leaf tones the seasons recolour (they must match [SeasonalTree]'s). */
    private val LEAVES = mapOf(
        'd' to rgb(30, 92, 48), 'g' to rgb(46, 128, 62), 'l' to rgb(86, 168, 74), 'b' to rgb(104, 70, 40), 'B' to rgb(74, 48, 30)
    )

    /** A fir of [tiers] layers of drooping boughs, at twice the detail: lit on the left, shaded under each bough. */
    fun fir(tiers: Int): Pair<List<String>, Map<Char, Int>> {
        val half = tiers + 1
        val w = (half * 2 + 1) * 2
        val crownRows = tiers * 6
        val d = Draw(w, crownRows + 4)
        val cx = half * 2   // the middle of the tree falls between this column and the next
        for (r in 0 until crownRows) {
            val k = r / 6
            val f = (r % 6) / 5f
            val reach = 2f * k + 1f + f * 6f
            for (x in 0 until w) {
                val dx = x - cx + 0.5f
                var edge = reach
                // Drooping, ragged boughs along the foot of each layer.
                if (r % 6 >= 4) edge += if (((x + k) shr 1) % 2 == 0) 0.8f else -0.6f
                if (kotlin.math.abs(dx) > edge) continue
                val left = dx < 0
                val deep = r % 6 >= 4
                val c = when {
                    kotlin.math.abs(dx) > edge - 1.1f && !left -> 'd'
                    deep && !left -> 'd'
                    deep -> 'g'
                    left && Noise.unit(x shr 1, r, 31 + tiers) < 0.45f -> 'l'
                    left -> 'g'
                    Noise.unit(x shr 1, r, 32 + tiers) < 0.3f -> 'd'
                    else -> 'g'
                }
                d.px(x, r, c)
            }
        }
        for (t in 0 until 4) { d.px(cx, crownRows + t, 'B'); d.px(cx - 1, crownRows + t, 'b') }
        return d.rows() to LEAVES
    }

    /** A broad round tree [radius] across at half the detail's scale ([trunk] low rows of trunk), lit from the upper left, clumped and ragged at the edge. */
    fun broadleaf(radius: Int, seed: Int, trunk: Int): Pair<List<String>, Map<Char, Int>> {
        val w = (radius * 2 + 1) * 2
        val crownH = (radius * 2 - 1) * 2
        val d = Draw(w, crownH + trunk * 2)
        val cx = (w - 1) / 2f
        val cy = (crownH - 1) / 2f
        val rr = w / 2f
        for (y in 0 until crownH) for (x in 0 until w) {
            val dx = x - cx
            val dy = (y - cy) * 1.02f
            val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val ang = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
            // A crown made of lobes, so its outline is lumpy rather than a circle.
            val lump = 0.90f + 0.07f * kotlin.math.sin(ang * 5f + seed) + 0.04f * kotlin.math.sin(ang * 9f + seed * 2) + 0.05f * Noise.unit(x shr 1, y shr 1, seed)
            if (dist > rr * lump) continue
            val lightness = (-dx * 0.6f - dy * 0.8f) / rr
            val clump = Noise.unit(x shr 1, y shr 1, seed + 1) - 0.5f
            val v = lightness + 0.55f * clump
            val edge = dist > rr * lump * 0.82f
            d.px(x, y, when {
                edge && lightness < 0f -> 'd'
                v > 0.42f -> 'l'
                v < -0.28f -> 'd'
                else -> 'g'
            })
        }
        // The trunk, with its roots spreading a little at the foot.
        val mid = w / 2
        for (t in 0 until trunk * 2) {
            val flare = if (t >= trunk * 2 - 2) 1 else 0
            d.rect(mid - 1 - flare, crownH + t, mid, crownH + t + 1, 'b')
            d.rect(mid, crownH + t, mid + 1 + flare, crownH + t + 1, 'B')
        }
        return d.rows() to LEAVES
    }
}
