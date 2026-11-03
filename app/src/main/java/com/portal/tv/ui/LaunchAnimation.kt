package com.portal.tv.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.portal.tv.R

/**
 * The flourish when an app is opened: its icon lifts off the grid and flies to the middle of the
 * screen, growing, while the home screen dims and the app's name fades in beneath it. The app is
 * started part way through, so the two overlap rather than the launch waiting for the whole show.
 * It stays up until [dismiss] (when Portal is back on screen) so nothing flashes between the
 * animation and the app's own window.
 */
class LaunchAnimation(private val root: ViewGroup) {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var hidden: View? = null
    private var launchStep: Runnable? = null
    private var fallback: Runnable? = null

    /** True from the start of the animation until it has been dismissed. */
    val active get() = overlay != null

    /**
     * Plays the animation for [icon] titled [label]. It flies from [source], the icon's view in the
     * grid, or grows from the middle of the screen when there is none. [onLaunch] runs after
     * [LAUNCH_AT] ms.
     */
    fun start(icon: Drawable, label: String, source: ImageView?, onLaunch: () -> Unit) {
        cancel()
        val context = root.context
        val density = context.resources.displayMetrics.density
        val rw = root.width.toFloat()
        val rh = root.height.toFloat()
        if (rw == 0f || rh == 0f) { onLaunch(); return }

        // Where the icon starts: on the grid, or small in the middle.
        val size: Float
        val startX: Float
        val startY: Float
        if (source != null && source.width > 0) {
            val loc = IntArray(2)
            val rootLoc = IntArray(2)
            source.getLocationInWindow(loc)
            root.getLocationInWindow(rootLoc)
            val itemScale = (source.parent as? View)?.scaleX ?: 1f
            size = source.width * itemScale
            startX = loc[0] - rootLoc[0] + source.width / 2f
            startY = loc[1] - rootLoc[1] + source.height / 2f
            hidden = source
            source.visibility = View.INVISIBLE
        } else {
            size = 96 * density
            startX = rw / 2
            startY = rh / 2
        }
        val endSize = 200 * density
        val endScale = endSize / size
        val endX = rw / 2
        val endY = rh / 2 - 28 * density

        val scrim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val picture = ImageView(context).apply {
            setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(size.toInt(), size.toInt())
            pivotX = size / 2
            pivotY = size / 2
            translationX = startX - size / 2
            translationY = startY - size / 2
        }
        val title = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = ResourcesCompat.getFont(context, R.font.inter)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(8f * density, 0f, 2f * density, 0xCC000000.toInt())
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP
            ).apply { topMargin = (endY + endSize / 2 + 20 * density).toInt() }
        }
        val layer = FrameLayout(context).apply {
            // Taps during the animation must not reach the grid underneath.
            isClickable = true
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(picture)
            addView(title)
        }
        root.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = layer

        if (ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    scrim.alpha = 0.82f * f
                    picture.translationX = startX + (endX - startX) * f - size / 2
                    picture.translationY = startY + (endY - startY) * f - size / 2
                    val s = 1f + (endScale - 1f) * f
                    picture.scaleX = s
                    picture.scaleY = s
                    // The name arrives over the second half, rising slightly into place.
                    val t = ((f - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    title.alpha = t
                    title.translationY = 12 * density * (1f - t)
                }
                start()
            }
        } else {
            scrim.alpha = 0.82f
        }
        val step = Runnable {
            onLaunch()
            // If the app never takes over the screen, give the home screen back after a while.
            val back = Runnable { dismiss() }
            fallback = back
            handler.postDelayed(back, FALLBACK)
        }
        launchStep = step
        if (ValueAnimator.areAnimatorsEnabled()) handler.postDelayed(step, LAUNCH_AT) else step.run()
    }

    /** Removes the overlay, fading it out, once Portal is back on screen. */
    fun dismiss() {
        clearPending()
        val layer = overlay ?: return
        overlay = null
        val shown = hidden
        hidden = null
        layer.animate().alpha(0f).setDuration(150).withEndAction {
            root.removeView(layer)
            shown?.visibility = View.VISIBLE
        }.start()
    }

    /** Removes the overlay at once and forgets any pending launch. */
    fun cancel() {
        clearPending()
        overlay?.let { root.removeView(it) }
        overlay = null
        hidden?.visibility = View.VISIBLE
        hidden = null
    }

    /** The app has taken over the screen, so the fallback that would reveal the home screen is no longer wanted. */
    fun appStarted() {
        fallback?.let { handler.removeCallbacks(it) }
        fallback = null
    }

    private fun clearPending() {
        launchStep?.let { handler.removeCallbacks(it) }
        fallback?.let { handler.removeCallbacks(it) }
        launchStep = null
        fallback = null
    }

    companion object {
        const val DURATION = 350L
        const val LAUNCH_AT = 250L
        const val FALLBACK = 4000L
    }
}
