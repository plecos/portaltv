package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The stars over [ProfileBackdropView]: they twinkle, and slowly rise, the nearer (bigger) ones a little faster than the
 * far ones, and wrap round at the top. It only animates while it is on screen, and each frame is a few hundred circles,
 * so it is cheap. Set [animated] to false for a still scatter of the same stars.
 */
class ProfileStarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Whether the stars move. Off, they are drawn once. */
    var animated = true
        set(value) {
            field = value
            updateRunning()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var running = false

    private class Star(
        val x: Float, val y: Float, val size: Float, val alpha: Int, val sparkle: Boolean,
        val phase: Float, val twinkle: Float, val rise: Float
    )

    private val stars = Random(23).let { random ->
        List(150) {
            val size = 0.7f + random.nextFloat() * 1.3f
            Star(
                x = random.nextFloat(),
                y = random.nextFloat() * random.nextFloat().coerceAtLeast(0.35f) * 0.95f,
                size = size,
                alpha = 70 + random.nextInt(170),
                sparkle = it % 25 == 0,
                phase = random.nextFloat(),
                twinkle = 0.10f + random.nextFloat() * 0.30f,
                // Bigger stars are nearer, so they move a little faster: a fraction of the height each second.
                rise = 0.0025f + size * 0.0030f
            )
        }
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

    /** A dialog (the PIN pad, say) takes the focus: the stars hold still so the screen can answer the remote quickly. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateRunning()
    }

    private fun updateRunning() {
        val now = animated && isAttachedToWindow && isShown && windowVisibility == VISIBLE && hasWindowFocus()
        if (now && !running) postInvalidateOnAnimation()
        running = now
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val t = if (animated) (SystemClock.uptimeMillis() % 3_600_000L) / 1000f else 0f

        for (star in stars) {
            val twinkle = if (animated) 0.6f + 0.4f * sin(2f * PI.toFloat() * (t * star.twinkle + star.phase)) else 1f
            paint.color = Color.argb((star.alpha * twinkle).toInt().coerceIn(0, 255), 255, 250, 235)
            val cx = star.x * w
            // Rises and wraps: fractions of the height, kept to the top 95% where the stars live.
            val fy = ((star.y - t * star.rise) % 0.95f + 0.95f) % 0.95f
            val cy = fy * h
            val size = star.size * density
            if (star.sparkle) {
                val long = size * 3.4f * (0.75f + 0.25f * twinkle)
                val short = size * 0.55f
                path.reset()
                path.moveTo(cx, cy - long)
                path.quadTo(cx + short, cy - short, cx + long, cy)
                path.quadTo(cx + short, cy + short, cx, cy + long)
                path.quadTo(cx - short, cy + short, cx - long, cy)
                path.quadTo(cx - short, cy - short, cx, cy - long)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawCircle(cx, cy, size, paint)
            }
        }
        if (running) postInvalidateOnAnimation()
    }
}
