package net.getportaltv.scenic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Shows the scene: each frame it moves the scene on, draws it into the small picture and scales that up to fill the view with
 * hard edges. It draws at most [maxFps] times a second and stops when it is taken off the screen.
 */
class ScenicView(
    context: Context,
    maxFps: Int = 30,
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
    private val steps = intArrayOf(30, 20, 15, 10)
    private var step = steps.indexOfFirst { it <= maxFps }.coerceAtLeast(0)
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
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.25f)
        last = now
        director.step(dt)
        scene.render(director.state)
        target.set(0, 0, width, height)
        canvas.drawBitmap(scene.bitmap, null, target, paint)
        val took = (System.nanoTime() - now) / 1e6
        frames++
        spentMs += took
        if (took > worstMs) worstMs = took
        if (windowStart == 0L) windowStart = now
        if (now - windowStart > 5_000_000_000L) {
            frameMillis = spentMs / frames
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
            scene.dither = step == 0
            scene.particleScale = if (step >= 2) 0.5f else 1f
        } else if (ema < budget * 0.18 && step > 0 && ++calm > steps[step] * 20) {
            step--
            ema = 0.0
            calm = 0
            scene.dither = step == 0
            scene.particleScale = if (step >= 2) 0.5f else 1f
        } else if (ema >= budget * 0.18) calm = 0
        if (attached) {
            val spent = (System.nanoTime() - now) / 1_000_000L
            postInvalidateDelayed((1000L / steps[step] - spent).coerceAtLeast(1L))
        }
    }
}
