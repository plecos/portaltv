package com.portal.tv.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cloud in a kid-lock scene: where it sits (fractions of the screen), how wide it is, and how it moves. One with a
 * [drift] floats slowly right and wraps around; the others sway on the spot.
 */
internal class SceneCloud(
    val x: Float,
    val y: Float,
    val width: Float,
    val sway: Float = 0f,
    val phase: Float = 0f,
    val drift: Float = 0f
)

/** Draws [cloud] at time [t] seconds on a [w] by [h] screen: a [shadeColor] copy underneath for depth, then [color]. */
internal fun drawSceneCloud(
    canvas: Canvas, fill: Paint, oval: RectF, w: Float, h: Float, t: Float,
    cloud: SceneCloud, color: Int, shadeColor: Int
) {
    val width = cloud.width * w
    val cx = if (cloud.drift != 0f) {
        // Drifts right and wraps around, fully off screen before it comes back.
        val span = w + width * 1.4f
        ((cloud.x * w + t * cloud.drift * w + width * 0.7f) % span) - width * 0.7f
    } else {
        cloud.x * w + sin(t * 0.35f + cloud.phase) * cloud.sway * w
    }
    val cy = cloud.y * h + (if (cloud.drift == 0f) cos(t * 0.3f + cloud.phase) * 0.004f * h else 0f)
    drawPuffyCloud(canvas, fill, oval, cx, cy + width * 0.035f, width, shadeColor)
    drawPuffyCloud(canvas, fill, oval, cx, cy, width, color)
}

/** One puffy cloud: round bumps on a soft flat base. Solid, so nothing behind it shows through. */
internal fun drawPuffyCloud(canvas: Canvas, fill: Paint, oval: RectF, cx: Float, cy: Float, width: Float, color: Int) {
    fill.color = color
    // Bumps as (x, y, radius) fractions of the cloud width, around its centre line.
    val bumps = floatArrayOf(
        -0.33f, -0.01f, 0.20f,
        -0.12f, -0.15f, 0.27f,
        0.14f, -0.09f, 0.24f,
        0.35f, -0.01f, 0.19f,
        0.00f, -0.02f, 0.26f
    )
    var i = 0
    while (i < bumps.size) {
        canvas.drawCircle(cx + bumps[i] * width, cy + bumps[i + 1] * width, bumps[i + 2] * width, fill)
        i += 3
    }
    oval.set(cx - 0.50f * width, cy - 0.10f * width, cx + 0.50f * width, cy + 0.14f * width)
    canvas.drawRoundRect(oval, 0.12f * width, 0.12f * width, fill)
}
