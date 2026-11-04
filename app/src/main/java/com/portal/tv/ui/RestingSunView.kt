package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The daytime scene for when a kid's time for the day is used up: a bright sky, a friendly sun that smiles with
 * happy closed eyes while it takes a break, and soft white clouds in a plain bright sky. Drawn with a canvas,
 * so it needs no pictures. It only animates while it is on screen.
 */
class RestingSunView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Far clouds drift slowly across the top and pass in front of the sun; near ones sit beside and below it.
    private val farClouds = listOf(
        SceneCloud(0.10f, 0.16f, 0.15f, drift = 0.0060f),
        SceneCloud(0.60f, 0.08f, 0.12f, drift = 0.0042f),
        SceneCloud(0.90f, 0.27f, 0.14f, drift = 0.0050f),
        SceneCloud(0.34f, 0.38f, 0.10f, drift = 0.0035f)
    )
    private val nearClouds = listOf(
        SceneCloud(0.145f, 0.50f, 0.245f, sway = 0.010f, phase = 0.0f),
        SceneCloud(0.865f, 0.46f, 0.225f, sway = 0.010f, phase = 2.1f),
        SceneCloud(0.02f, 1.02f, 0.28f, sway = 0.008f, phase = 4.0f),
        SceneCloud(0.985f, 1.01f, 0.26f, sway = 0.008f, phase = 1.2f)
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    private var sky: LinearGradient? = null
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sky = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF4DA9F2.toInt(), 0xFF7CC4FA.toInt(), 0xFFBFE3FF.toInt(), 0xFFFFEFC4.toInt()),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    private fun updateRunning() {
        val now = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (now && !running) postInvalidateOnAnimation()
        running = now
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f

        fill.shader = sky
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // The sun is behind every cloud, so a drifting one can pass in front of it.
        drawSun(canvas, w, h, t)
        for (cloud in farClouds) drawSceneCloud(canvas, fill, oval, w, h, t, cloud, 0xFFF1F7FF.toInt(), 0xFFC4D9F2.toInt())
        for (cloud in nearClouds) drawSceneCloud(canvas, fill, oval, w, h, t, cloud, 0xFFFFFFFF.toInt(), 0xFFBBD3F0.toInt())

        if (running) postInvalidateOnAnimation()
    }

    private fun drawSun(canvas: Canvas, w: Float, h: Float, t: Float) {
        val r = 0.138f * h
        val cx = w * 0.5f
        val bob = sin(t * 2f * PI.toFloat() / 6f) * 0.014f * h
        val cy = h * 0.275f + bob
        val breath = 1f + 0.022f * sin(t * 2f * PI.toFloat() / 4f)

        // A warm glow around the sun.
        fill.shader = RadialGradient(cx, cy, r * 2.7f, intArrayOf(0x70FFFFFF, 0x00FFFFFF), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 2.7f, fill)
        fill.shader = null

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(breath, breath)

        // Rays: rounded, alternately long and short, turning very slowly and pulsing a little.
        stroke.color = 0xFFFFC548.toInt()
        stroke.strokeWidth = 0.085f * r
        val spin = t * 7f
        val rays = 14
        for (i in 0 until rays) {
            val angle = (i * 360f / rays + spin) * PI.toFloat() / 180f
            val pulse = 1f + 0.08f * sin(t * 2f * PI.toFloat() / 3f + i)
            val inner = 1.22f * r
            val outer = inner + (if (i % 2 == 0) 0.30f else 0.19f) * r * pulse
            canvas.drawLine(inner * cos(angle), inner * sin(angle), outer * cos(angle), outer * sin(angle), stroke)
        }

        // The body, lit from the upper left.
        fill.shader = RadialGradient(-0.3f * r, -0.35f * r, r * 1.45f, 0xFFFFF4A8.toInt(), 0xFFFFC93C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, r, fill)
        fill.shader = null

        drawFace(canvas, r)
        canvas.restore()

    }

    private fun drawFace(canvas: Canvas, r: Float) {
        // Rosy cheeks.
        fill.color = 0x99FF8FA8.toInt()
        canvas.drawCircle(-0.52f * r, 0.26f * r, 0.13f * r, fill)
        canvas.drawCircle(0.52f * r, 0.26f * r, 0.13f * r, fill)

        // Happy closed eyes: little arcs that curve up, like a smile turned over.
        stroke.color = 0xFF8A4B12.toInt()
        stroke.strokeWidth = 0.055f * r
        for (side in intArrayOf(-1, 1)) {
            oval.set(side * 0.32f * r - 0.16f * r, -0.16f * r, side * 0.32f * r + 0.16f * r, 0.12f * r)
            canvas.drawArc(oval, 200f, 140f, false, stroke)
        }
        // A wide, happy smile.
        stroke.strokeWidth = 0.05f * r
        oval.set(-0.22f * r, 0.08f * r, 0.22f * r, 0.46f * r)
        canvas.drawArc(oval, 25f, 130f, false, stroke)
    }
}
