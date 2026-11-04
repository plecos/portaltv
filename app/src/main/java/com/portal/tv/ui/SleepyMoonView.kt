package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The bedtime scene: a starry night sky, a sleepy moon in a nightcap that breathes, bobs and snores a few Zs, and
 * soft puffy clouds that sway, with the moon behind all of them. Drawn with a canvas, so it needs no pictures. It only animates while it is on screen.
 */
class SleepyMoonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private class Star(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float, val sparkle: Boolean)

    private val stars = Random(7).let { random ->
        List(80) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat() * 0.78f,
                size = 0.8f + random.nextFloat() * 1.8f,
                phase = random.nextFloat(),
                speed = 0.12f + random.nextFloat() * 0.35f,
                sparkle = it % 9 == 0
            )
        }
    }

    // Far clouds drift slowly across the top and pass in front of the moon, in a deeper lilac; near ones sit beside and below it.
    // All are solid, so no star shows through.
    private val farClouds = listOf(
        SceneCloud(0.08f, 0.17f, 0.15f, drift = 0.0060f),
        SceneCloud(0.58f, 0.08f, 0.12f, drift = 0.0042f),
        SceneCloud(0.88f, 0.26f, 0.14f, drift = 0.0050f),
        SceneCloud(0.34f, 0.37f, 0.10f, drift = 0.0035f)
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val oval = RectF()

    private var sky: LinearGradient? = null
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sky = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF0A0F2C.toInt(), 0xFF151A45.toInt(), 0xFF2B2A66.toInt(), 0xFF47367F.toInt()),
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

        drawStars(canvas, w, h, t)
        // The moon is behind every cloud, so a drifting one can pass in front of it.
        drawMoon(canvas, w, h, t)
        for (cloud in farClouds) drawSceneCloud(canvas, fill, oval, w, h, t, cloud, 0xFFB7A8E6.toInt(), 0xFF8B7DC8.toInt())
        for (cloud in nearClouds) drawSceneCloud(canvas, fill, oval, w, h, t, cloud, 0xFFF4F6FF.toInt(), 0xFFB9BEF2.toInt())

        if (running) postInvalidateOnAnimation()
    }

    private fun drawStars(canvas: Canvas, w: Float, h: Float, t: Float) {
        for (star in stars) {
            val twinkle = 0.5f + 0.5f * sin(2f * PI.toFloat() * (t * star.speed + star.phase))
            val alpha = (80 + 175 * twinkle).toInt()
            val size = star.size * density
            val cx = star.x * w
            val cy = star.y * h
            fill.color = Color.argb(alpha, 255, 250, 225)
            if (star.sparkle) {
                // A four-pointed sparkle, a little bigger than the dots.
                val long = size * 3.2f * (0.7f + 0.3f * twinkle)
                val short = size * 0.55f
                path.reset()
                path.moveTo(cx, cy - long)
                path.quadTo(cx + short, cy - short, cx + long, cy)
                path.quadTo(cx + short, cy + short, cx, cy + long)
                path.quadTo(cx - short, cy + short, cx - long, cy)
                path.quadTo(cx - short, cy - short, cx, cy - long)
                canvas.drawPath(path, fill)
            } else {
                canvas.drawCircle(cx, cy, size, fill)
            }
        }
    }

    private fun drawMoon(canvas: Canvas, w: Float, h: Float, t: Float) {
        val r = 0.172f * h
        val cx = w * 0.5f
        val bob = sin(t * 2f * PI.toFloat() / 6f) * 0.014f * h
        val cy = h * 0.305f + bob
        val breath = 1f + 0.022f * sin(t * 2f * PI.toFloat() / 4f)
        val tilt = 2.2f * sin(t * 2f * PI.toFloat() / 9f)

        // A soft glow around the moon.
        fill.shader = RadialGradient(cx, cy, r * 2.5f, intArrayOf(0x4DB4BEFF, 0x00B4BEFF), floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 2.5f, fill)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(tilt)
        canvas.scale(breath, breath)

        // Body, lit from the upper left, with a few soft craters.
        fill.shader = RadialGradient(-0.3f * r, -0.35f * r, r * 1.45f, 0xFFFFF9D9.toInt(), 0xFFF6D77C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, r, fill)
        fill.shader = null
        fill.color = 0x2ED9A93C
        canvas.drawCircle(0.52f * r, -0.28f * r, 0.17f * r, fill)
        canvas.drawCircle(0.30f * r, 0.60f * r, 0.12f * r, fill)
        canvas.drawCircle(-0.62f * r, 0.46f * r, 0.10f * r, fill)

        drawFace(canvas, r)
        drawCap(canvas, r, t)
        canvas.restore()

        drawZs(canvas, cx, cy, r, t)
    }

    private fun drawFace(canvas: Canvas, r: Float) {
        // Rosy cheeks.
        fill.color = 0x99FF8FA8.toInt()
        canvas.drawCircle(-0.50f * r, 0.24f * r, 0.12f * r, fill)
        canvas.drawCircle(0.50f * r, 0.24f * r, 0.12f * r, fill)

        // Closed, sleeping eyes: little downward-curving arcs.
        stroke.color = 0xFF6B4A2B.toInt()
        stroke.strokeWidth = 0.05f * r
        for (side in intArrayOf(-1, 1)) {
            oval.set(side * 0.30f * r - 0.15f * r, -0.10f * r, side * 0.30f * r + 0.15f * r, 0.12f * r)
            canvas.drawArc(oval, 20f, 140f, false, stroke)
        }
        // A tiny content smile.
        stroke.strokeWidth = 0.04f * r
        oval.set(-0.10f * r, 0.18f * r, 0.10f * r, 0.34f * r)
        canvas.drawArc(oval, 25f, 130f, false, stroke)
    }

    /** A floppy blue nightcap leaning over one side, with a straight fluffy trim along its bottom edge and a swinging pom-pom. */
    private fun drawCap(canvas: Canvas, r: Float, t: Float) {
        val sway = sin(t * 2f * PI.toFloat() / 3f)
        val tipX = -1.26f * r + 0.05f * r * sway
        val tipY = -1.50f * r + 0.02f * r * sway

        path.reset()
        path.moveTo(-0.80f * r, -0.56f * r)
        path.quadTo(-0.96f * r, -1.22f * r, tipX, tipY)
        path.quadTo(-0.32f * r, -1.52f * r, 0.50f * r, -0.86f * r)
        path.close()
        fill.color = 0xFF5B6CFF.toInt()
        canvas.drawPath(path, fill)

        // A few stars on the cap.
        fill.color = 0xCCFFF6C9.toInt()
        canvas.drawCircle(-0.66f * r, -0.98f * r, 0.035f * r, fill)
        canvas.drawCircle(-0.30f * r, -1.04f * r, 0.028f * r, fill)
        canvas.drawCircle(-0.82f * r, -1.30f * r, 0.024f * r, fill)

        // The trim runs straight along the cap's bottom edge: a fluffy band of little round puffs.
        val x1 = -0.80f * r
        val y1 = -0.56f * r
        val x2 = 0.50f * r
        val y2 = -0.86f * r
        stroke.color = 0xFFF4F6FF.toInt()
        stroke.strokeWidth = 0.15f * r
        canvas.drawLine(x1 - 0.04f * (x2 - x1), y1 - 0.04f * (y2 - y1), x2 + 0.04f * (x2 - x1), y2 + 0.04f * (y2 - y1), stroke)
        fill.color = 0xFFF4F6FF.toInt()
        val puffs = 13
        for (i in 0..puffs) {
            val f = -0.03f + 1.06f * i / puffs
            canvas.drawCircle(x1 + f * (x2 - x1), y1 + f * (y2 - y1), 0.095f * r, fill)
        }

        canvas.drawCircle(tipX, tipY, 0.15f * r, fill)
    }

    /** Zs that rise from the moon, grow, and fade, one after another. */
    private fun drawZs(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        for (i in 0 until 3) {
            val phase = ((t / 6f) + i / 3f) % 1f
            val alpha = sin(PI.toFloat() * phase)
            text.color = Color.argb((alpha * 235).toInt(), 222, 229, 255)
            text.textSize = r * (0.24f + 0.30f * phase)
            val x = cx + r * (0.92f + 0.45f * phase) + sin(phase * 5f + i) * 0.04f * r
            val y = cy - r * (0.35f + 0.95f * phase)
            canvas.save()
            canvas.rotate(-12f, x, y)
            canvas.drawText("Z", x, y, text)
            canvas.restore()
        }
    }
}
