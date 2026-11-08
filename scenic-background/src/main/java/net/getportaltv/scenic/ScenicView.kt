package net.getportaltv.scenic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Shows the scene: each frame it moves the scene on, draws it into the small picture and scales that up to fill the view with
 * hard edges. It draws at most [maxFps] times a second (asked again each frame, so a change in settings takes effect at once),
 * drops to a lower rate if the TV can't keep up, and stops when it is taken off the screen.
 */
class ScenicView(
    context: Context,
    private val maxFps: () -> Int = { 30 },
    config: () -> Config,
    private val weather: PortalWeather? = null
) : View(context) {

    private val director = Director(config, weather)
    private val scene = PixelScene()
    private val paint = Paint().apply { isFilterBitmap = false; isDither = false; isAntiAlias = false }
    private val target = Rect()
    private var last = 0L
    private var attached = false
    // A slow TV gets a lower frame rate and a plainer picture instead of a stuttering one.
    private var steps = stepsFor(maxFps())
    private var aim = steps[0]
    private var step = 0
    private var ema = 0.0
    private var calm = 0
    private var drawn = 0
    private var frames = 0
    private var spentMs = 0.0
    private var worstMs = 0.0
    private var windowStart = 0L

    /** The average time one frame took to work out and draw over the last few seconds (for the readout and the log). */
    var frameMillis = 0.0
        private set

    /** The frame rate being aimed for now. */
    val fps: Int get() = steps[step]

    /** The state, for the preview's readout. */
    val state: SceneState get() = director.state

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        Profile.on = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        last = 0L
        drawn = 0
        weather?.start()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        weather?.stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        // A different frame rate chosen: start again from it, and let the TV's speed pull it down from there if it has to.
        val want = maxFps()
        if (want != aim) { steps = stepsFor(want); aim = steps[0]; step = 0; ema = 0.0; calm = 0; applyQuality() }
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.25f)
        last = now
        director.step(dt)
        scene.render(director.state)
        if (Profile.on) Profile.frame()
        target.set(0, 0, width, height)
        canvas.drawBitmap(scene.bitmap, null, target, paint)
        val took = (System.nanoTime() - now) / 1e6
        frames++
        spentMs += took
        if (took > worstMs) worstMs = took
        if (windowStart == 0L) windowStart = now
        if (now - windowStart > 5_000_000_000L) {
            frameMillis = spentMs / frames
            if (Profile.on) android.util.Log.i("Scenic", Profile.report())
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.util.Log.i("Scenic", "frame average %.1f ms, worst %.1f ms, %d frames in %.1f s, aiming for %d fps".format(frameMillis, worstMs, frames, (now - windowStart) / 1e9, steps[step]))
            }
            frames = 0; spentMs = 0.0; worstMs = 0.0; windowStart = now
        }
        // The first moments are slow while the code warms up: they are not counted.
        if (++drawn < 60) { if (attached) postInvalidateDelayed(1000L / steps[step]); return }
        ema = if (ema == 0.0) took else ema * 0.95 + took * 0.05
        val budget = 1000.0 / steps[step]
        if (ema > budget * 0.6 && step < steps.size - 1) {
            step++
            ema = 0.0
            calm = 0
            applyQuality()
        } else if (ema < budget * 0.18 && step > 0 && ++calm > steps[step] * 20) {
            step--
            ema = 0.0
            calm = 0
            applyQuality()
        } else if (ema >= budget * 0.18) calm = 0
        if (attached) {
            val spent = (System.nanoTime() - now) / 1_000_000L
            postInvalidateDelayed((1000L / steps[step] - spent).coerceAtLeast(1L))
        }
    }

    /** The picture is plainer at a low rate: no dithering below 30 a second, and half the rain and snow at 15 or less. */
    private fun applyQuality() {
        scene.dither = steps[step] >= 30
        scene.particleScale = if (steps[step] <= 15) 0.5f else 1f
    }

    private companion object {
        /** Every rate the picture steps between, highest first. */
        val RATES = intArrayOf(120, 90, 60, 30, 20, 15, 10)

        /** [target] first, then each lower rate to fall back to if the TV can't keep up. */
        fun stepsFor(target: Int): IntArray = (listOf(target) + RATES.filter { it < target }).toIntArray()
    }
}
