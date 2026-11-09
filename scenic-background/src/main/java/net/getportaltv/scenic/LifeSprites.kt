package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.rgb

/** The small pictures of the people, animals, train and the rest that move through the countryside, drawn as text. */
object LifeSprites {

    /** A person on foot, 5 wide: [frame] 0 or 1 is the step, [umbrella] holds one up for the rain. */
    fun person(shirt: Int, pants: Int, frame: Int, umbrella: Int?, skin: Int, hair: Int, bag: Int? = null): Sprite {
        val body = listOf(
            "..h..",
            ".hhh.",
            ".sss.",
            ".bbb.",
            "sbbbs",
            ".ppp.",
            if (frame == 0) ".p.p." else "..p..",
            if (frame == 0) ".p.p." else "..p.."
        )
        // A shopping bag swings from one hand.
        val carried = if (bag == null) body else body.mapIndexed { y, row -> if (y == 5 || y == 6) "g" + row.substring(1) else row }
        val rows = if (umbrella != null) listOf(".uuu.", "uuuuu", "..k..") + carried else carried
        return Sprite(rows, mapOf('g' to (bag ?: 0), 'h' to hair, 's' to skin, 'b' to shirt, 'p' to pants, 'u' to (umbrella ?: 0), 'k' to rgb(50, 50, 56)))
            .let { s -> HiSprites.person(shirt, pants, frame, umbrella, skin, hair, bag).let { (r, p) -> s.detail(r, p) } }
    }

    /** Somebody on a bicycle, facing right. */
    fun cyclist(shirt: Int, frame: Int, skin: Int, hair: Int): Sprite = Sprite(
        listOf(
            "...hh...",
            "...bb.s.",
            "..bbb...",
            if (frame == 0) ".k.bb.k." else ".k.bp.k.",
            "kkk..kkk",
            ".k....k."
        ), mapOf('h' to hair, 's' to skin, 'b' to shirt, 'p' to rgb(52, 60, 96), 'k' to rgb(40, 40, 46))
    ).let { s -> HiSprites.cyclist(shirt, frame, skin, hair).let { (r, p) -> s.detail(r, p) } }

    val dog = Sprite(listOf("d...dd", "dddddd", ".d..d."), mapOf('d' to rgb(140, 96, 60)))
    val dogStep = Sprite(listOf("d...dd", "dddddd", "d....d"), mapOf('d' to rgb(140, 96, 60)))
    /** A rabbit in profile facing right: long ears, a round body, a white scut and a pink nose. */
    val rabbit = Sprite(
        listOf(
            "....r.r.",
            "....r.r.",
            "...rrrrr",
            "w.rrrrkn",
            "wrrrrrrr",
            ".rrrrrr.",
            "..r..rr."
        ), mapOf('r' to rgb(176, 150, 120), 'w' to rgb(240, 236, 228), 'k' to rgb(40, 34, 34), 'n' to rgb(232, 150, 150))
    )
    val duck = Sprite(listOf("..gg.", "wwwwy", ".www."), mapOf('g' to rgb(40, 110, 70), 'w' to rgb(214, 196, 160), 'y' to rgb(240, 190, 60)))

    private val DEER = mapOf('h' to rgb(150, 100, 62), 'd' to rgb(112, 74, 46), 'w' to rgb(240, 232, 220), 'a' to rgb(196, 172, 130))

    val deerUp = Sprite(
        listOf(
            "......a.a.",
            ".......hh.",
            "......hhhh",
            ".......hh.",
            "...hhhhhh.",
            "whhhhhhhh.",
            "hhhhhhhhh.",
            ".h.h..h.h.",
            ".d.d..d.d."
        ), DEER
    )

    val deerGraze = Sprite(
        listOf(
            "..........",
            "...hhhhhh.",
            "whhhhhhhh.",
            "hhhhhhhhhh",
            "hhhhhhhhhh",
            ".h.h..hhhh",
            ".d.d..d.dd"
        ), DEER
    )

    private val TRAIN = mapOf(
        'k' to rgb(34, 34, 40), 'o' to rgb(96, 84, 76), 'd' to rgb(72, 56, 46), 'w' to rgb(190, 222, 240), 'h' to rgb(255, 240, 170),
        'y' to rgb(226, 184, 52), 'r' to rgb(188, 56, 44)
    )

    /** The engine, facing right. */
    val locomotive = Sprite(
        listOf(
            "...kk...........",
            "..rrrrrrrrrrrrr.",
            "..rwwrrrrrrrrrrr",
            "..rwwrrrrrrrrrrh",
            "..rrrrrrrrrrrrrr",
            ".ykkykkykkykkykk"
        ), TRAIN
    )

    fun boxcar(body: Int): Sprite = Sprite(
        listOf(
            ".oooooooooo.",
            "cccccccccccc",
            "cccddccddccc",
            "cccddccddccc",
            "cccccccccccc",
            ".kk......kk."
        ), TRAIN + mapOf('c' to body)
    )

    val tanker = Sprite(
        listOf(
            "..gggggggg..",
            ".gggggggggg.",
            "gggggggggggg",
            ".gggggggggg.",
            "..gggggggg..",
            ".kk......kk."
        ), mapOf('g' to rgb(150, 156, 164), 'k' to rgb(34, 34, 40))
    )

    fun coach(body: Int): Sprite = Sprite(
        listOf(
            ".oooooooooooo.",
            "cccccccccccccc",
            "cwwcwwcwwcwwcc",
            "cwwcwwcwwcwwcc",
            "cccccccccccccc",
            ".kk........kk."
        ), TRAIN + mapOf('c' to body)
    )

    /** Where a coach's windows are (x, y, width, height), lit at night. */
    val coachWindows = listOf(intArrayOf(1, 2, 2, 2), intArrayOf(4, 2, 2, 2), intArrayOf(7, 2, 2, 2), intArrayOf(10, 2, 2, 2))

    val tractor = Sprite(
        listOf(
            "..b...........",
            "..b..cccc.....",
            ".ggg.cwwc.....",
            "gggggcccrrrrr.",
            "gggggrrrrrrrrr",
            ".kkkk.rrrrrrrr",
            "kkkkkk..kkk.kk",
            "kkkkkk..kkk.kk",
            ".kkkk...kkk..k"
        ), mapOf('b' to rgb(60, 60, 66), 'g' to rgb(46, 128, 62), 'c' to rgb(206, 60, 48), 'r' to rgb(206, 60, 48), 'w' to rgb(170, 214, 240), 'k' to rgb(28, 28, 34))
    )

    val bat1 = Sprite(listOf("k...k", ".kkk.", "..k.."), mapOf('k' to rgb(30, 28, 40)))
    val bat2 = Sprite(listOf("..k..", ".kkk.", "k...k"), mapOf('k' to rgb(30, 28, 40)))

    /** A hot air balloon in stripes of two colours, drawn once. */
    fun balloon(a: Int, b: Int): Sprite {
        val w = 15
        val h = 21
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cx = 7f
        val cy = 7f
        for (y in 0..14) for (x in 0 until w) {
            // An envelope: round on top, narrowing toward the basket.
            val radius = if (y <= 7) 7f else 7f - (y - 7) * 0.5f
            val dx = x - cx
            val dy = if (y <= 7) y - cy else 0f
            if (dx * dx + dy * dy > radius * radius + 0.5f) continue
            val stripe = ((x + 7) / 3) % 2
            bmp.setPixel(x, y, if (stripe == 0) a else b)
        }
        val rope = rgb(90, 70, 50)
        for (y in 15..17) { bmp.setPixel(5 + (y - 15), y, rope); bmp.setPixel(9 - (y - 15), y, rope) }
        for (x in 6..8) for (y in 18..20) bmp.setPixel(x, y, rgb(140, 100, 60))
        return SpriteFromBitmap(bmp).sprite
    }

    /** A plane seen from the side, tiny, heading right. */
    val plane = Sprite(listOf("..ww....", "wwwwwwww", "..ww...."), mapOf('w' to rgb(240, 240, 246)))
}

/** Wraps a ready-made bitmap as a [Sprite]. */
private class SpriteFromBitmap(bmp: Bitmap) {
    val sprite: Sprite = Sprite.of(bmp)
}
