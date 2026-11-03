package com.portal.tv.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.portal.tv.data.Profile
import com.portal.tv.repo.ProfileManager

/** The circular picture for a profile, wherever it is shown. */
object ProfileAvatar {

    private val PALETTE = intArrayOf(
        0xFFE50914.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(),
        0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt()
    )

    fun colorFor(index: Int): Int = PALETTE[Math.floorMod(index, PALETTE.size)]

    /**
     * The Google account photo if there is one, then the chosen built-in avatar,
     * otherwise the initial on a colored circle.
     */
    fun drawable(context: Context, profile: Profile): Drawable {
        ProfileManager.photoPath(context, profile)
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.let { bitmap ->
                return RoundedBitmapDrawableFactory.create(context.resources, bitmap)
                    .apply { isCircular = true }
            }
        profile.avatarId?.takeIf { AvatarArt.find(it) != null }
            ?.let { return AvatarArt.drawable(it) }
        return InitialDrawable(profile.name.take(1).uppercase(), colorFor(profile.colorIndex))
    }

    /** A colored circle with a centered letter. */
    private class InitialDrawable(private val letter: String, private val color: Int) : Drawable() {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@InitialDrawable.color }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) / 2f
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle)
            text.textSize = r * 1.1f
            val baseline = b.exactCenterY() - (text.descent() + text.ascent()) / 2f
            canvas.drawText(letter, b.exactCenterX(), baseline, text)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
