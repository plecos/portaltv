package com.portal.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.widget.FrameLayout
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.portal.tv.R
import kotlin.math.hypot

/**
 * The intro when Portal starts from scratch, and again when a profile is chosen: on black, the ring
 * from Portal's icon (cyan to violet to pink, around a glowing light) draws itself and then breathes
 * while the home screen is built behind it, out of sight. When the screen is ready (its background is
 * playing and its apps are listed) the light fades, the ring grows outward and the screen opens
 * through it, and the panels settle into place. Any key press skips it.
 *
 * The screen says what it is still waiting for with [hold] and [release]; the opening starts once
 * the ring has drawn itself and nothing is held, or after [MAX_WAIT] ms whatever is held, so a slow
 * video or network never leaves the ring spinning.
 *
 * It is built to be cheap on a TV's modest graphics chip. The opening is the platform's circular
 * reveal, which runs on the render thread. The ring is drawn once into a small hardware layer, and
 * growing or pulsing it is only scaling and fading that layer. The panels use hardware layers too,
 * so their fades and slides don't redraw them.
 */
class IntroAnimation private constructor(private val root: ViewGroup) {

    private class Entrance(val view: View, val delay: Long, val duration: Long, val dx: Float, val dy: Float)

    private val handler = Handler(Looper.getMainLooper())
    private val container: View? = root.findViewById(R.id.container)
    private val ring = RingView(root.context)
    private val light = LightView(root.context)
    private val timeline = ValueAnimator.ofFloat(0f, DRAW.toFloat())
    private val entrances = mutableListOf<Entrance>()
    private val holds = mutableSetOf<String>()
    /**
     * Plain black, laid over the screen while it is built. A screen can't be hidden by making it
     * transparent: a shader background is drawn on a surface of its own, behind the window, that shows
     * through. So the screen stays fully drawn and is simply covered, until the opening starts.
     */
    private val cover = View(root.context).apply { setBackgroundColor(Color.BLACK) }
    private var reveal: Animator? = null
    private var pulse: ValueAnimator? = null
    private var savedBackground: Drawable? = null
    private var drawn = false
    private var revealing = false

    /** True until the intro has finished or been skipped. */
    var active = true
        private set

    private fun start(expectHome: Boolean) {
        savedBackground = root.background
        root.setBackgroundColor(Color.BLACK)
        // The screen is built under the cover, still fully drawn (a video can't start on a screen that
        // isn't). It opens through the ring later.
        root.addView(cover, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (expectHome) holds += HOME
        ring.isClickable = true
        ring.setOnClickListener { skip() }
        // Designed at RING_DP and shown at half that size to begin with, so it is crisp when it grows.
        ring.scaleX = START_SCALE
        ring.scaleY = START_SCALE
        ring.alpha = 0f
        ring.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val side = ring.sizePx
        light.scaleX = START_SCALE
        light.scaleY = START_SCALE
        light.alpha = 0f
        light.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.addView(light, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        root.addView(ring, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        timeline.apply {
            duration = DRAW.toLong()
            interpolator = null
            addUpdateListener {
                val t = it.animatedValue as Float
                ring.drawn = (t / DRAW).coerceIn(0f, 1f)
                ring.alpha = (t / 140f).coerceIn(0f, 1f)
                light.alpha = ((t - 120f) / 330f).coerceIn(0f, 1f)
                ring.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (!active) return
                    drawn = true
                    ring.drawn = 1f
                    ring.invalidate()
                    startPulse()
                    openIfReady()
                }
            })
        }
        // The screen behind is still being built (inflated, filled) in work queued ahead of this, which
        // would otherwise eat the first frames and make the ring jump. Begin once it has had its turn.
        handler.post { handler.post { begin() } }
    }

    private fun begin() {
        if (!active) return
        timeline.start()
        handler.postDelayed(giveUp, MAX_WAIT.toLong())
    }

    /** A slow ring: it breathes while the screen is built, so it is clear that something is happening. */
    private fun startPulse() {
        pulse = ValueAnimator.ofFloat(1f, PULSE_SCALE).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                ring.scaleX = START_SCALE * f
                ring.scaleY = START_SCALE * f
                val calm = 1f - (f - 1f) / (PULSE_SCALE - 1f)
                ring.alpha = 0.75f + 0.25f * calm
                // The light swells as the ring settles back, so the two seem to breathe together.
                light.scaleX = START_SCALE * (0.9f + 0.1f * calm)
                light.scaleY = light.scaleX
                light.alpha = 0.7f + 0.3f * calm
            }
            start()
        }
    }

    /** The opening won't wait any longer for the screen. */
    private val giveUp = Runnable {
        holds.clear()
        if (drawn) openIfReady()
        // If the ring is still drawing, it opens as soon as it has finished.
    }

    /** Says the screen is still getting ready for [what]; the ring opens only when nothing is held. */
    fun hold(what: String) {
        if (active && !revealing) holds += what
    }

    /** [what] is ready. */
    fun release(what: String) {
        holds -= what
        openIfReady()
    }

    private fun openIfReady() {
        if (!active || revealing || !drawn || holds.isNotEmpty()) return
        revealing = true
        pulse?.cancel()
        handler.removeCallbacks(giveUp)
        val target = container ?: return finish(fade = false)
        val cx = target.width / 2
        val cy = target.height / 2
        val full = hypot(cx.toFloat(), cy.toFloat()) + 4f
        val density = root.resources.displayMetrics.density
        val base = BASE_DP * density
        val duration = (REVEAL - DRAW).toLong()
        val anim = ViewAnimationUtils.createCircularReveal(target, cx, cy, base, full)
        anim.duration = duration
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) = finish(fade = false)
        })
        reveal = anim
        anim.start()
        // The cover goes as the opening takes hold, so the screen only ever shows inside the circle.
        root.removeView(cover)
        // The ring grows and fades with the opening. It is a baked layer, so this is only a scale and a fade.
        val grown = full / (RING_DP * density)
        // The light inside goes first, then the ring carries on outward alone.
        light.animate().alpha(0f).setDuration(duration * 2 / 5)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        ring.animate().scaleX(grown).scaleY(grown).alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        // The panels settle in as the opening gets going.
        entrances.forEach { e ->
            e.view.animate()
                .alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay((e.delay - DRAW).coerceAtLeast(0))
                .setDuration(e.duration)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction { e.view.setLayerType(View.LAYER_TYPE_NONE, null) }
                .start()
        }
    }

    /**
     * Has [target] arrive as the screen opens: it starts [delay] ms after the ring would start to open
     * (counting from the start of the intro), fades in over [duration] ms and slides from ([dx], [dy])
     * pixels away. It is made invisible now, under the black.
     */
    fun addEntrance(target: View, delay: Long, duration: Long, dx: Float, dy: Float) {
        if (!active) return
        entrances += Entrance(target, delay, duration, dx, dy)
        target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        target.alpha = 0f
        target.translationX = dx
        target.translationY = dy
    }

    /** Jumps to the end: the screen opens fully, the panels land at once and the ring fades. */
    fun skip() {
        if (!active) return
        // Cancelling would otherwise report the end and run the usual ending.
        timeline.removeAllListeners()
        timeline.cancel()
        finish(fade = true)
    }

    private fun finish(fade: Boolean) {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        holds.clear()
        root.removeView(cover)
        pulse?.cancel()
        ring.animate().cancel()
        light.animate().cancel()
        reveal?.removeAllListeners()
        reveal?.end()
        container?.visibility = View.VISIBLE
        entrances.forEach {
            it.view.animate().cancel()
            it.view.alpha = 1f
            it.view.translationX = 0f
            it.view.translationY = 0f
            it.view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        entrances.clear()
        root.background = savedBackground
        if (root.findFocus() == null) container?.requestFocus()
        if (current === this) current = null
        if (fade) {
            light.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(light) }.start()
            ring.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(ring) }.start()
        } else {
            root.removeView(light)
            root.removeView(ring)
        }
    }

    /**
     * The icon's ring as one picture: a faint full circle, a soft glow and the ring itself, in the
     * icon's cyan, violet and pink. It grows clockwise from the top as [drawn] goes from 0 to 1.
     * Drawn at [RING_DP] radius.
     */
    private class RingView(context: Context) : View(context) {
        var drawn = 0f
        private val density = resources.displayMetrics.density
        private val radius = RING_DP * density
        private val stroke = RING_DP * STROKE_RATIO * density
        /** Room around the ring for its glow. */
        private val pad = 1.6f * stroke + 4f * density
        val sizePx = ((radius + pad) * 2).toInt()
        private val decel = DecelerateInterpolator(1.4f)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            oval.set(c - radius, c - radius, c + radius, c + radius)
            track.strokeWidth = stroke
            track.alpha = 30
            canvas.drawCircle(c, c, radius, track)
            if (arc.shader == null) {
                arc.shader = SweepGradient(c, c, RING_COLORS, RING_STOPS)
            }
            val sweep = 360f * decel.getInterpolation(drawn)
            // The colours start at the top, where the ring starts to draw.
            canvas.save()
            canvas.rotate(-90f, c, c)
            // A soft glow made of two wider, fainter strokes under the ring.
            arc.strokeWidth = stroke * 2.6f
            arc.alpha = 36
            canvas.drawArc(oval, 0f, sweep, false, arc)
            arc.strokeWidth = stroke * 1.7f
            arc.alpha = 80
            canvas.drawArc(oval, 0f, sweep, false, arc)
            arc.strokeWidth = stroke
            arc.alpha = 255
            canvas.drawArc(oval, 0f, sweep, false, arc)
            canvas.restore()
        }
    }

    /** The light inside the ring, as in the icon: bright white in the middle, cooling to blue at the edge. */
    private class LightView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val radius = RING_DP * density - RING_DP * STROKE_RATIO * density / 2
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            if (paint.shader == null) {
                paint.shader = RadialGradient(
                    c, c, radius,
                    intArrayOf(0xCCFFFFFF.toInt(), 0x668A9CFF, 0x1A44D9FF),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(c, c, radius, paint)
        }
    }

    companion object {
        /** The ring has drawn itself by this many ms. */
        const val DRAW = 450
        /** The opening takes [REVEAL] minus [DRAW] ms. */
        const val REVEAL = 1000
        /** The longest the ring waits for the screen to be ready, after it has started. */
        const val MAX_WAIT = 4000
        private const val BASE_DP = 64f
        /** The radius the ring is drawn at; it is shown at [START_SCALE] of this to begin with. */
        private const val RING_DP = 128f
        private const val START_SCALE = BASE_DP / RING_DP
        private const val PULSE_SCALE = 1.1f
        /** The ring's thickness as a share of its radius (the icon's is thicker, which is too heavy this large). */
        private const val STROKE_RATIO = 0.13f
        private val RING_COLORS = intArrayOf(0xFF44D9FF.toInt(), 0xFF8A5CFF.toInt(), 0xFFFF5FA8.toInt(), 0xFF44D9FF.toInt())
        private val RING_STOPS = floatArrayOf(0f, 0.34f, 0.67f, 1f)
        /** The hold for the home screen itself not having been created yet. */
        const val HOME = "home"

        /** The intro now playing, or null. */
        var current: IntroAnimation? = null
            private set

        /**
         * Starts the intro over [root], which must be the activity's content layout. With [expectHome] it
         * waits for the home screen to be created and say it is ready; without, the screen behind needs no wait.
         */
        fun play(root: ViewGroup, expectHome: Boolean): IntroAnimation? {
            if (!ValueAnimator.areAnimatorsEnabled()) return null
            current?.skip()
            return IntroAnimation(root).also { current = it; it.start(expectHome) }
        }

        /** The home screen is up and says what it is still waiting for. Does nothing when no intro is playing. */
        fun hold(what: String) { current?.hold(what) }
        fun release(what: String) { current?.release(what) }
    }
}
