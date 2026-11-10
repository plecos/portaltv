package com.portal.tv.background

import android.graphics.Bitmap

/** How bright a picture is, so a frame that is (nearly) all black isn't kept as a video's preview. */
object FrameBrightness {

    /** The mean brightness, 0 (black) to 255 (white), of [pixels] in ARGB. Empty counts as black. */
    fun mean(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r * 299 + g * 587 + b * 114) / 1000
        }
        return sum.toFloat() / pixels.size
    }

    /** The mean brightness of [bitmap], from a small grid of its pixels. */
    fun mean(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return 0f
        val columns = 16
        val rows = 9
        val sample = IntArray(columns * rows) { i ->
            bitmap.getPixel(((i % columns) * 2 + 1) * w / (columns * 2), ((i / columns) * 2 + 1) * h / (rows * 2))
        }
        return mean(sample)
    }

    /** Below this a frame is taken to be black: a dark scene can still be a fine preview, an empty one is not. */
    const val BLANK_BELOW = 14f

    fun isBlank(mean: Float) = mean < BLANK_BELOW
}
