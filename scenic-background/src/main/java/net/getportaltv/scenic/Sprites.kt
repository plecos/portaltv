package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.rgb

/**
 * Small pictures drawn as text, one character per pixel, so the scenery is easy to read and change. A dot is empty. They are
 * turned into bitmaps once; colour and light are applied when they are drawn.
 */
class Sprite private constructor(val width: Int, val height: Int, val bitmap: Bitmap) {

    constructor(rows: List<String>, palette: Map<Char, Int>) : this(
        rows.maxOf { it.length }, rows.size,
        Bitmap.createBitmap(rows.maxOf { it.length }, rows.size, Bitmap.Config.ARGB_8888).also { b ->
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch -> palette[ch]?.let { b.setPixel(x, y, it) } }
            }
        }
    )

    companion object {
        /** A sprite made from a picture that is already drawn. */
        fun of(bitmap: Bitmap) = Sprite(bitmap.width, bitmap.height, bitmap)
    }
}

object Sprites {
    private val GREEN = mapOf('d' to rgb(30, 92, 48), 'g' to rgb(46, 128, 62), 'l' to rgb(86, 168, 74), 'b' to rgb(104, 70, 40), 'B' to rgb(74, 48, 30))

    val pine = Sprite(
        listOf(
            "....d....",
            "...dgd...",
            "...dgd...",
            "..dgggd..",
            "..dglgd..",
            ".dgggggd.",
            ".dgglggd.",
            "dgggggggd",
            "dggglgggd",
            "....b....",
            "....b...."
        ), GREEN
    )

    val pineSmall = Sprite(
        listOf(
            "..d..",
            ".dgd.",
            ".dgd.",
            "dgggd",
            "dgggd",
            "..b.."
        ), GREEN
    )

    val roundTree = Sprite(
        listOf(
            "...dgggd...",
            "..dgglggd..",
            ".dgglgggld.",
            ".dggggglgd.",
            "dgglgggggld",
            "dgggggglggd",
            ".dgglggggd.",
            "..dgggggd..",
            "....bB.....",
            "....bB.....",
            "....bB....."
        ), GREEN
    )

    val roundSmall = Sprite(
        listOf(
            ".dggd.",
            "dgglgd",
            "dggggd",
            ".dggd.",
            "..bB.."
        ), GREEN
    )

    /** A tall fir: [tiers] layers of branches, each wider than the one above, lit on the left, with a short trunk. */
    fun fir(tiers: Int): Sprite {
        val half = tiers + 1
        val width = half * 2 + 1
        val rows = ArrayList<String>()
        for (k in 0 until tiers) for (r in 0..2) {
            val hw = k + r
            rows += CharArray(width) { x ->
                val d = x - half
                when {
                    kotlin.math.abs(d) > hw -> '.'
                    kotlin.math.abs(d) == hw && hw > 0 -> 'd'
                    d < 0 -> if (r == 1) 'l' else 'g'
                    d == 0 -> 'g'
                    else -> if (r == 2) 'd' else 'g'
                }
            }.concatToString()
        }
        for (t in 0 until 2) rows += CharArray(width) { x -> if (x == half || x == half - 1 && t == 1) (if (x == half) 'B' else 'b') else '.' }.concatToString()
        return Sprite(rows, GREEN)
    }

    /** A broad tree with a round crown of the given [radius], lit from the upper left, on a trunk. */
    fun broadleaf(radius: Int, seed: Int): Sprite {
        val width = radius * 2 + 1
        val height = radius * 2 - 1
        val rows = ArrayList<String>()
        for (y in 0 until height) {
            rows += CharArray(width) { x ->
                val dx = (x - radius).toFloat()
                val dy = (y - (height - 1) / 2f) * 1.05f
                val r2 = dx * dx + dy * dy
                val limit = radius * radius.toFloat() * (0.94f + 0.12f * Noise.unit(x, y, seed))
                when {
                    r2 > limit -> '.'
                    r2 > limit * 0.72f -> if (dx + dy < 0) 'g' else 'd'
                    dx * -0.6f + dy * -0.8f > radius * 0.35f -> 'l'
                    Noise.unit(x, y, seed + 1) < 0.2f -> 'd'
                    else -> 'g'
                }
            }.concatToString()
        }
        val trunk = (radius / 2).coerceAtLeast(2)
        for (t in 0 until trunk) rows += CharArray(width) { x -> if (x == radius) 'B' else if (x == radius - 1) 'b' else '.' }.concatToString()
        return Sprite(rows, GREEN)
    }

    /** Trees for the forest: tall firs and broad trees of a few sizes. */
    val firs by lazy { listOf(fir(4), fir(5), fir(7)) }
    val broads by lazy { listOf(broadleaf(5, 1), broadleaf(6, 2), broadleaf(8, 3)) }

    val bush = Sprite(
        listOf(
            ".dggd..",
            "dglggd.",
            "dggggld"
        ), GREEN
    )

    private val BARN = mapOf('r' to rgb(158, 48, 40), 'R' to rgb(118, 34, 32), 'w' to rgb(232, 226, 210), 'k' to rgb(70, 52, 44), 'o' to rgb(96, 84, 80), 'y' to rgb(60, 52, 70))

    val barn = Sprite(
        listOf(
            "......oooooooo......",
            "....oooooooooooo....",
            "..oooooooooooooooo..",
            "oooooooooooooooooooo",
            "rrrrrrrrrrrrrrrrrrrr",
            "rrrrrrrwwwwrrrrrrrrr",
            "rrrrrrwrrrrwrrrrrrrr",
            "rRRRRRRwrrrrwRRRRRRr",
            "rRRRRRRwwwwwwRRRRRRr",
            "rRRRRRRwkkkkwRRRRRRr",
            "rRRRRRRwkkkkwRRRRRRr",
            "rRRRRRRwkkkkwRRRRRRr"
        ), BARN
    )

    private val HOUSE = mapOf('s' to rgb(228, 222, 204), 'S' to rgb(190, 184, 168), 'r' to rgb(120, 68, 56), 'R' to rgb(90, 50, 44), 'k' to rgb(84, 62, 48), 'c' to rgb(150, 96, 80), 'y' to rgb(74, 86, 110))

    val house = Sprite(
        listOf(
            "......cc........",
            "......cc.rr.....",
            "...rrrrrrrrrr...",
            "..rrRrrrrrrRrr..",
            ".rrrrrrrrrrrrrr.",
            "rrRrrrrrrrrrrRrr",
            "ssssssssssssssss",
            "ssyyssssssyyssss",
            "ssyyssskkssyyss",
            "ssSSssskkssSSss",
            "ssssssskkssssss"
        ), HOUSE
    )

    /** Where the lit windows of the house and barn are (x, y, width, height), counted from each picture's top left. */
    private val CABIN = mapOf('R' to rgb(86, 56, 44), 'r' to rgb(118, 78, 56), 'L' to rgb(154, 108, 66), 'l' to rgb(112, 76, 48), 'k' to rgb(64, 42, 30), 'y' to rgb(74, 86, 110), 'c' to rgb(124, 120, 116))

    /** A log cabin with a stone chimney. */
    val cabin = Sprite(
        listOf(
            "...............cc...",
            "......RRRRRRRR.cc...",
            ".....RrRRrRRrRRRR...",
            "...RRrRRrRRrRRrRRR..",
            ".RRrRRrRRrRRrRRrRRR.",
            "RRRRRRRRRRRRRRRRRRRR",
            ".LLLLLLLLLLLLLLLLLL.",
            ".llllllllllllllllll.",
            ".LLLyyLLLLLLLkkLLLL.",
            ".llyyllllllllkkllll.",
            ".LLLLLLLLLLLLkkLLLL.",
            ".llllllllllllkkllll."
        ), CABIN
    )
    val cabinWindows = listOf(intArrayOf(4, 8, 2, 2))

    val houseWindows = listOf(intArrayOf(2, 7, 2, 2), intArrayOf(10, 7, 2, 2))
    val barnWindows = emptyList<IntArray>()

    private val ANIMAL = mapOf('w' to rgb(238, 236, 230), 'k' to rgb(40, 36, 36), 'p' to rgb(232, 168, 156), 'g' to rgb(170, 164, 150), 'h' to rgb(120, 82, 56), 'd' to rgb(86, 58, 40))

    val cow = Sprite(
        listOf(
            "..........kk.",
            "..wwwwwww.kwk",
            ".wwkkwwwwwwwp",
            "wwkkkwwwkkww.",
            "wwwwwwwwkkww.",
            "wwwwwwwwwwww.",
            ".w.w....w.w.."
        ), ANIMAL
    )

    val sheep = Sprite(
        listOf(
            ".wwwww..",
            "wwwwwwwk",
            "wwwwwwkk",
            "wwwwwww.",
            ".k.k.k.k"
        ), ANIMAL
    )

    val horse = Sprite(
        listOf(
            ".........hh.",
            "........hhhh",
            ".hhhhhhhhhd.",
            "hhhhhhhhhd..",
            "hhhhhhhhh...",
            ".h.h...h.h..",
            ".d.d...d.d.."
        ), ANIMAL
    )

    private val BIRD = mapOf('k' to rgb(38, 40, 52))
    val birdUp = Sprite(listOf("k...k", ".k.k.", "..k.."), BIRD)
    val birdDown = Sprite(listOf("..k..", ".kkk.", "k...k"), BIRD)

    private val CAR = mapOf('c' to rgb(220, 60, 60), 'C' to rgb(160, 40, 44), 'w' to rgb(170, 214, 240), 'k' to rgb(28, 28, 34), 'h' to rgb(255, 244, 180))

    /** A car with a placeholder body colour 'c' that is replaced when it is baked. */
    fun car(body: Int): Sprite = Sprite(
        listOf(
            "....wwwwww....",
            "...wcccccc.w..",
            "cccccccccccccc",
            "cCCCCCCCCCCCCc",
            "cccccccccccccc",
            ".kkk......kkk.",
            ".kkk......kkk."
        ), CAR + mapOf('c' to body, 'C' to Colors.scale(body, 0.72f))
    )

    fun truck(body: Int): Sprite = Sprite(
        listOf(
            "wwwwwwwwwwwwwwwwwwwww..",
            "wcccccccccccccccccccw.w",
            "wcccccccccccccccccccwww",
            "wCCCCCCCCCCCCCCCCCCCwcc",
            "wcccccccccccccccccccwcc",
            "wcccccccccccccccccccwcc",
            ".kkk.....kkk.....kkk.kk",
            ".kkk.....kkk.....kkk.kk"
        ), CAR + mapOf('c' to body, 'C' to Colors.scale(body, 0.78f), 'w' to rgb(214, 216, 222))
    )

    /** Cloud blobs: lumpy white on top, a darker underside. [seed] picks the lumps. */
    fun cloud(width: Int, height: Int, seed: Int): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val lumps = 4 + width / 14
        val cx = FloatArray(lumps)
        val cy = FloatArray(lumps)
        val cr = FloatArray(lumps)
        for (i in 0 until lumps) {
            val u = (i + 0.5f) / lumps
            cx[i] = 3f + u * (width - 6)
            // Lumps are tallest in the middle.
            val mid = 1f - kotlin.math.abs(u - 0.5f) * 1.6f
            cr[i] = (height * (0.28f + 0.30f * mid)) * (0.8f + 0.5f * Noise.unit(seed, i))
            cy[i] = height - 2f - cr[i] * 0.55f - Noise.unit(seed, i, 1) * height * 0.12f
        }
        for (y in 0 until height) for (x in 0 until width) {
            var inside = false
            var top = false
            for (i in 0 until lumps) {
                val dx = x - cx[i]
                val dy = y - cy[i]
                if (dx * dx + dy * dy <= cr[i] * cr[i]) {
                    inside = true
                    // The lit side is the upper left of each lump.
                    if (dx * 0.4f + dy * 0.9f < cr[i] * 0.15f) top = true
                }
            }
            if (!inside) continue
            // The underside curves up toward the ends instead of ending in a flat line.
            val fromBottom = (height - 1 - y).toFloat() / height
            val edge = kotlin.math.abs(x - width / 2f) / (width / 2f)
            if (fromBottom < 0.28f && edge > 0.45f + fromBottom * 1.6f) continue
            b.setPixel(x, y, if (top) rgb(255, 255, 255) else rgb(196, 204, 218))
        }
        return b
    }
}
