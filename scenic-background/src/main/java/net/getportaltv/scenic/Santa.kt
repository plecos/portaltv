package net.getportaltv.scenic

import android.graphics.Bitmap
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.floor
import kotlin.math.sin

/** An Easter egg: on Christmas night a sleigh with eight reindeer is sometimes seen crossing the sky. */
private object SantaSprites {
    private val DEER = mapOf('a' to rgb(176, 132, 84), 'h' to rgb(170, 116, 72), 'b' to rgb(150, 100, 62), 'l' to rgb(120, 80, 50), 'n' to rgb(120, 80, 50))
    private val DEER_RED = DEER + ('n' to rgb(255, 60, 50))
    private val DEER_FAR = DEER.mapValues { Colors.mul(it.value, 0.72f, 0.72f, 0.78f) }
    private val ROWS = listOf(
        "....a.a.",
        ".....hhn",
        ".bbbbhh.",
        "bbbbbb..",
        ".l.l.l.."
    )
    private val SLEIGH_ROWS = listOf(
        "...ss...rr....",
        "..ssss.rrrw...",
        "..ssss.wfffw..",
        "gggggggggggggg",
        "grrrrrrrrrrrrg",
        ".rrrrrrrrrrrr.",
        "kkkkkkkkkkkkkk"
    )
    private val SLEIGH = mapOf('s' to rgb(150, 100, 60), 'r' to rgb(214, 52, 52), 'w' to rgb(245, 245, 245), 'f' to rgb(238, 190, 156), 'g' to rgb(240, 200, 80), 'k' to rgb(240, 200, 80))
    private val HARNESS = rgb(240, 200, 80)

    /** The whole team facing right: the sleigh at the back, then four pairs of reindeer, the first of them with a red nose. */
    val team: Sprite by lazy {
        val bm = Bitmap.createBitmap(60, 10, Bitmap.Config.ARGB_8888)
        fun draw(rows: List<String>, pal: Map<Char, Int>, ox: Int, oy: Int) {
            rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> pal[ch]?.let { bm.setPixel(ox + x, oy + y, it) } } }
        }
        for (x in 14 until 56) bm.setPixel(x, 6, HARNESS)
        draw(SLEIGH_ROWS, SLEIGH, 0, 3)
        for (p in 0 until 4) {
            val x = 18 + p * 10
            draw(ROWS, DEER_FAR, x + 2, 2)
            draw(ROWS, if (p == 3) DEER_RED else DEER, x, 5)
        }
        Sprite.of(bm)
    }
}

internal fun PixelScene.drawSanta(s: SceneState) {
    if (!s.season.christmas) return
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    if (night < 0.3f || s.look.deck > 0.7f) return
    val travelV = if (s.travelling) s.speed else 0f
    // One sleigh at a time: it crosses in under 50 seconds and only starts in the first half of its 110-second slot, so the
    // next one cannot begin before it has gone.
    for (ev in LifeSchedule.events(s.time, 110.0, 50.0, 0.9f, 441, s.boost)) {
        if (ev.spawn % 110.0 > 55.0) continue
        val dir = if (ev.unit(1) < 0.5f) 1 else -1
        val v = dir * 9f - travelV * 0.02f
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v > 0) -62f + v * age else W + 2f + v * age
        if (x < -64 || x > W + 4) continue
        val y = 14 + (ev.unit(2) * 40).toInt() + (sin(age * 1.1) * 2).toInt()
        // A trail of golden sparkles behind them.
        for (k in 1..24) {
            val tx = (if (dir > 0) x + 2 - k * 2.5f else x + 58 + k * 2.5f).toInt()
            if (tx < 0 || tx >= W) continue
            if (Noise.unit(k, floor(s.time * 4).toInt() + ev.seed, 880) > 0.5f) continue
            val ty = y + 6 + (Noise.unit(k, ev.seed, 881) * 7).toInt() - 3
            fillAlpha(tx, ty, tx + 1, ty + 1, rgb(255, 226, 120), (1f - k / 26f) * 0.9f * night)
        }
        spritePaint.colorFilter = null
        spritePaint.alpha = (night * 255).toInt()
        sprite(SantaSprites.team, x.toInt(), y + 10, flip = dir < 0)
        spritePaint.alpha = 255
        // The red nose shines.
        val noseX = if (dir > 0) x.toInt() + 55 else x.toInt() + 4
        fillAlpha(noseX - 1, y + 6, noseX + 2, y + 9, rgb(255, 60, 50), 0.35f * night)
    }
}
