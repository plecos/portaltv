package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.H
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.floor
import kotlin.math.sin

/** Things that only the forest has: the big trees along the road and mist on the ground. */

private val bigFirs by lazy { listOf(Sprites.fir(8), Sprites.fir(10), Sprites.fir(12)) }
private val bigBroads by lazy { listOf(Sprites.broadleaf(10, 4), Sprites.broadleaf(12, 5), Sprites.broadleaf(14, 6)).map { SeasonalTree(it) } }

/** The tall trees standing right beside the road, in front of the rest of the wood. */
internal fun PixelScene.drawRoadsideTrees(s: SceneState) {
    val factor = 0.9
    val offset = floor(s.cam * factor)
    val cell = 30
    val first = floor(offset / cell).toInt() - 2
    spritePaint.colorFilter = filter(s, 0.02f + s.look.haze * 0.25f)
    for (i in first..first + W / cell + 5) {
        val worldX = i * cell + (Noise.unit(i, 621) * 20).toInt()
        val f = s.forestAt(worldX.toDouble(), factor)
        if (f <= 0f || Noise.unit(i, 622) > f * 0.85f) continue
        val x = (worldX - offset).toInt()
        if (s.riverCovers(x, ROAD_TOP - 2, 12)) continue
        val tree = if (Noise.unit(i, 623) < 0.6f) bigFirs[(Noise.unit(i, 624) * bigFirs.size).toInt().coerceIn(0, bigFirs.size - 1)]
        else bigBroads[(Noise.unit(i, 624) * bigBroads.size).toInt().coerceIn(0, bigBroads.size - 1)].at(s.season, i * 11)
        val sway = (sin(s.time * 1.3 + i) * minOf(1f, s.cond.wind / 30f)).toFloat().let { Math.round(it) }
        sprite(tree, x - tree.width / 2 + sway, ROAD_TOP - 1 - (Noise.unit(i, 625) * 3).toInt())
    }
    spritePaint.colorFilter = null
}

/** Mist lying along the ground in the woods, thicker at dawn and dusk and when it is damp. */
internal fun PixelScene.drawForestMist(s: SceneState) {
    val forest = s.forestAt(s.cam + W / 2.0, 1.0)
    if (forest < 0.05f) return
    val low = 1f - smoothstep(6f, 24f, s.sunAlt.toFloat())
    val damp = maxOf(s.cond.rain, s.cond.fog)
    val amount = forest * (0.18f + 0.5f * low + 0.4f * damp).coerceAtMost(0.8f)
    val color = lerp(s.look.fogColor, rgb(220, 230, 235), 0.4f * (1f - low))
    for (band in 0 until 4) {
        val y = FIELD_TOP + band * 4 - 2
        val drift = (s.time * (1.2 + band * 0.4) + band * 40) % W
        for (seg in 0 until 3) {
            val x0 = ((seg * (W / 3f) + drift) % (W + 60)).toInt() - 60
            fillAlpha(x0, y, x0 + 90, y + 3, color, amount * (0.22f - band * 0.03f))
        }
    }
}
