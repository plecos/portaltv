package com.portal.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A field of stars that drifts slowly outward from one point, like a very gentle warp. It is there to show the
 * screen is alive: a star takes about a minute and a half to cross, and each one twinkles a little.
 *
 * Stars are dimmer behind the text column so the words stay easy to read. With animations turned off in the
 * system's accessibility settings the stars are drawn once and stay still.
 */
class StarWarpView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Where the stars flow out from, as a fraction of the view's width and height (the logo's ring). */
    private val originX = 735f / 1920f
    private val originY = 150f / 1080f

    private class Star(
        var angle: Float, var radius: Float, val size: Float, val bright: Float,
        val tint: Int, val twinkleSpeed: Float, val phase: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(7)
    private val stars = ArrayList<Star>()
    private var lastFrame = 0L
    private var time = 0f
    private var maxRadius = 1f

    /** Fraction of the distance to the edge covered per second, in the stars' own units (see [step]). */
    private val flow = 0.043f
    private val nearest = 40f

    private val tints = intArrayOf(0xFFFFFF, 0xFFFFFF, 0xC8D7FF, 0xFFE1EB)

    private fun spawn(spread: Boolean) = Star(
        angle = random.nextFloat() * 2f * PI.toFloat(),
        // At the start the stars fill the whole screen; later ones begin close to the origin.
        radius = if (spread) nearest * Math.exp(random.nextDouble() * ln(1f + 1900.0 / nearest)).toFloat() - nearest
                 else random.nextFloat() * 30f,
        size = 0.6f + random.nextFloat() * 1.5f,
        bright = 0.35f + random.nextFloat() * 0.6f,
        tint = tints[random.nextInt(tints.size)],
        twinkleSpeed = 0.4f + random.nextFloat() * 1.2f,
        phase = random.nextFloat() * 6.28f
    )

    init {
        repeat(350) { stars += spawn(spread = true) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val ox = w * originX
        val oy = h * originY
        maxRadius = max(max(hypot(ox, oy), hypot(w - ox, oy)), max(hypot(ox, h - oy), hypot(w - ox, h - oy)))
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val animated = ValueAnimator.areAnimatorsEnabled()
        if (animated && lastFrame != 0L) step(min((now - lastFrame) / 1e9f, 0.1f))
        lastFrame = now

        val ox = width * originX
        val oy = height * originY
        val scale = width / 1920f
        for (s in stars) {
            val x = ox + cos(s.angle) * s.radius * scale
            val y = oy + sin(s.angle) * s.radius * scale
            if (x < -4 || x > width + 4 || y < -4 || y > height + 4) continue

            // Fade in near the origin, brighten and grow a little as the star travels outward.
            val travel = min(1f, s.radius / maxRadius * 1.6f)
            var alpha = s.bright * min(1f, s.radius / 60f) * (0.55f + 0.45f * travel)
            alpha *= 0.85f + 0.15f * sin(time * s.twinkleSpeed + s.phase)
            // Calmer behind the text column.
            if (kotlin.math.abs(x - width / 2f) < 460f * scale && y > 290f * scale) alpha *= 0.5f

            paint.color = s.tint
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawCircle(x, y, s.size * scale * (0.7f + 0.8f * travel), paint)
        }
        if (animated) postInvalidateOnAnimation()
    }

    /** Move every star outward. Speed grows with distance, which gives the sense of depth. */
    private fun step(dt: Float) {
        time += dt
        for (i in stars.indices) {
            val s = stars[i]
            s.radius += flow * (s.radius + nearest) * dt
            if (s.radius * 1f > maxRadius / max(0.0001f, width / 1920f)) {
                stars[i] = spawn(spread = false)
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lastFrame = 0L   // don't count the time spent hidden as one big step
        if (isVisible) postInvalidateOnAnimation()
    }
}
