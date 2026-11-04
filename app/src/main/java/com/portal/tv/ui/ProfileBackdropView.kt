package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * The still backdrop behind "Who's watching?": a deep night gradient, soft pools of Portal's cyan, violet and pink,
 * a faint arc of the logo's ring rising from the bottom, and a vignette. Nothing moves, so it is drawn once and costs
 * nothing while the person picks. The stars are a view of their own over it ([ProfileStarsView]).
 */
class ProfileBackdropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // The night, lighter toward the middle.
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0B1030.toInt(), 0xFF17113F.toInt(), 0xFF0A0A1C.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // Soft pools of the logo's colors.
        pool(canvas, w * 0.5f, h * 0.02f, w * 0.75f, 0x666C4BFF)
        pool(canvas, w * 0.06f, h * 0.98f, w * 0.5f, 0x402FC9FF)
        pool(canvas, w * 0.96f, h * 0.86f, w * 0.46f, 0x33FF5FA8)

        drawRing(canvas, w, h)

        // A vignette keeps the edges dark so the profiles stand out.
        paint.shader = RadialGradient(
            w / 2f, h / 2f, Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.62f,
            intArrayOf(0x00000000, 0x00000000, 0x77000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun pool(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(cx, cy, radius, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    /** A big arc of the ring from Portal's icon, mostly below the screen, so it rises like a horizon. */
    private fun drawRing(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 1.28f
        val radius = h * 0.78f
        val stroke = h * 0.045f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(0xFF44D9FF.toInt(), 0xFF8A5CFF.toInt(), 0xFFFF5FA8.toInt(), 0xFF44D9FF.toInt()),
            floatArrayOf(0f, 0.34f, 0.67f, 1f)
        )
        paint.style = Paint.Style.STROKE
        paint.shader = sweep
        // A glow made of many wider, fainter strokes under the ring, so it fades out smoothly instead of in bands.
        val layers = 9
        for (i in 0 until layers) {
            val f = i / (layers - 1f)
            paint.strokeWidth = stroke * (4.2f - 3.2f * f)
            paint.alpha = (5 + 22 * f * f).toInt()
            canvas.drawArc(oval, 0f, 360f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = 255
    }
}
