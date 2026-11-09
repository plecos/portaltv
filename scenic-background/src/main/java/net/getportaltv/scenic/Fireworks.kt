package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** The colours a burst can be: a main one and a paler centre for each. */
private val BURSTS = listOf(
    rgb(255, 80, 70) to rgb(255, 200, 180),
    rgb(255, 205, 80) to rgb(255, 245, 190),
    rgb(120, 220, 255) to rgb(220, 250, 255),
    rgb(130, 255, 140) to rgb(220, 255, 220),
    rgb(255, 110, 220) to rgb(255, 210, 245),
    rgb(255, 150, 60) to rgb(255, 225, 170),
    rgb(250, 250, 255) to rgb(255, 255, 255)
)

/**
 * Fireworks: at the amusement park on summer nights, and New Year: through the nights of 31 December and 1 January fireworks go up in every scene, few at first and thicker in the
 * hour before and after midnight, when the finale is a sky full of them.
 */
internal fun PixelScene.drawFireworks(s: SceneState) {
    // Summer nights at the amusement park have a show too, quieter than New Year's, and not in rain, snow or fog.
    val season = s.season
    val summer = if (season.day <= 0f) 1f else smoothstep(140f, 165f, season.day) * (1f - smoothstep(250f, 275f, season.day))
    val dry = (1f - maxOf(s.cond.rain * 1.5f, s.cond.snow * 1.5f, s.cond.fog, s.cond.thunder)).coerceIn(0f, 1f)
    val park = 0.45f * summer * dry * s.parkAt(s.cam + W / 2.0, 1.0)
    val strength = maxOf(season.newYear, park)
    if (strength <= 0.01f) return
    val night = (1f - s.look.day * 1.6f).coerceIn(0f, 1f)
    if (night < 0.3f || s.look.deck > 0.85f) return
    // Three streams of bursts; the second and third only join in as midnight nears.
    for (stream in 0 until 3) {
        val join = when (stream) { 0 -> 0.35f + 0.5f * strength; 1 -> strength * strength * 0.9f; else -> strength * strength * strength * 0.9f }
        for (ev in LifeSchedule.events(s.time, 3.0, 4.4, join.coerceIn(0f, 0.95f), 460 + stream, s.boost)) {
            val age = (s.time - ev.spawn).toFloat()
            val rise = 0.9f
            val cx = 40 + (ev.unit(1) * (W - 80)).toInt()
            val cy = 22 + (ev.unit(2) * 48).toInt()
            val (main, centre) = BURSTS[(ev.unit(3) * BURSTS.size).toInt().coerceAtMost(BURSTS.size - 1)]
            if (age < rise) {
                // The rocket climbing, with a short spark tail.
                val f = age / rise
                val y = 124 - ((124 - cy) * (1f - (1f - f) * (1f - f))).toInt()
                if (scale > 1) {
                    // A bright head with a glow, and a trail of sparks falling away behind it.
                    fillGlow(cx + 0.5f, y + 0.5f, 5f, rgb(255, 230, 170), 0.55f * night)
                    fillOval(cx - 0.2f, y - 0.2f, cx + 1.2f, y + 1.2f, rgb(255, 250, 220), 0.95f * night)
                    for (k in 1..10) fillOval(cx + 0.1f + (Noise.unit(k, ev.seed, 463) - 0.5f) * 0.8f, y + k * 1.2f, cx + 0.8f + (Noise.unit(k, ev.seed, 463) - 0.5f) * 0.8f, y + k * 1.2f + 0.7f, rgb(255, 190, 110), (0.8f - k * 0.075f).coerceAtLeast(0f) * night)
                } else {
                fillAlpha(cx, y, cx + 1, y + 1, rgb(255, 240, 200), 0.95f * night)
                for (k in 1..4) fillAlpha(cx, y + k * 2, cx + 1, y + k * 2 + 1, rgb(255, 190, 110), (0.7f - k * 0.15f) * night)
                }
                continue
            }
            val a = age - rise
            val willow = ev.unit(4) < 0.3f
            val life = if (willow) 3.4f else 2.5f
            if (a > life) continue
            val radius = (if (willow) 24f else 20f + ev.unit(5) * 14f)
            val gravity = if (willow) 15f else 7f
            val fade = (1f - a / life).coerceIn(0f, 1f)
            val sparks = if (willow) 44 else 56
            // The flash of the burst lights the sky around it for a moment.
            if (a < 0.3f) {
                if (scale > 1) fillGlow(cx + 0.5f, cy + 0.5f, 16f, centre, 0.7f * (1f - a / 0.3f) * night)
                else fillAlpha(cx - 2, cy - 2, cx + 3, cy + 3, centre, 0.8f * (1f - a / 0.3f) * night)
            }
            val r = radius * (1f - exp(-a * 2.8f))
            for (j in 0 until sparks) {
                val ang = (j + ev.unit(6 + j % 5) * 0.8f) * (2 * Math.PI / sparks).toFloat()
                val shell = if (willow || j % 2 == 0) 0.82f + 0.18f * Noise.unit(j, ev.seed, 461) else 0.4f + 0.3f * Noise.unit(j, ev.seed, 462)
                val reach = r * shell
                val px = cx + (cos(ang) * reach).toInt()
                val py = cy + (sin(ang) * reach * 0.92f + 0.5f * gravity * a * a).toInt()
                if (px < 0 || px >= W || py < 0 || py > 130) continue
                if (Noise.unit(j, floor(s.time * 14).toInt(), ev.seed) < (a / life) * 0.55f) continue
                val col = if (j % 4 == 0) centre else main
                if (scale > 1) {
                    // Round sparks with a faint halo and a tail of fainter and fainter points behind them along their path.
                    val fx = cx + cos(ang) * reach; val fy = cy + sin(ang) * reach * 0.92f + 0.5f * gravity * a * a
                    fillOval(fx - 1.1f, fy - 1.1f, fx + 1.1f, fy + 1.1f, col, fade * 0.18f * night)
                    fillOval(fx - 0.55f, fy - 0.55f, fx + 0.55f, fy + 0.55f, col, (fade * 0.95f) * night)
                    if (a < life * 0.7f) for (k in 1..4) {
                        val a2 = (a - k * 0.045f).coerceAtLeast(0f)
                        val r2 = radius * (1f - exp(-a2 * 2.8f)) * shell
                        val tx = cx + cos(ang) * r2; val ty = cy + sin(ang) * r2 * 0.92f + 0.5f * gravity * a2 * a2
                        fillOval(tx - 0.4f, ty - 0.4f, tx + 0.4f, ty + 0.4f, main, fade * (0.5f - k * 0.1f) * night)
                    }
                } else {
                fillAlpha(px, py, px + 2, py + 2, col, (fade * 0.95f) * night)
                if (a < life * 0.6f) {
                    val a2 = a - 0.09f
                    val r2 = radius * (1f - exp(-a2.coerceAtLeast(0f) * 2.8f))
                    val tx = cx + (cos(ang) * r2 * shell).toInt()
                    val ty = cy + (sin(ang) * r2 * 0.92f * shell + 0.5f * gravity * a2 * a2).toInt()
                    if (tx in 0 until W && ty >= 0) fillAlpha(tx, ty, tx + 1, ty + 1, main, fade * 0.45f * night)
                }
                }
            }
        }
    }
}
