package com.portal.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.portal.tv.R
import com.portal.tv.repo.MoonPhase
import com.portal.tv.repo.WeatherRepository
import kotlin.math.abs
import kotlin.math.cos

/**
 * A photograph of the full moon with the night side laid over it as a mostly opaque shadow, so the
 * craters and seas show through only faintly on the dark part, like earthshine. [elongation] is
 * the moon's angle ahead of the sun (0 new, 180 full); the lit edge is on the right while waxing.
 * The edge between light and dark is softened, as the real one is.
 *
 * The picture is a full-moon render from NASA's Scientific Visualization Studio, "Moon Phase and
 * Libration, 2024" (https://svs.gsfc.nasa.gov/5187), credited in THIRD_PARTY_NOTICES.md.
 */
class MoonPhotoDrawable(
    context: Context,
    elongation: Double,
    private val tilt: MoonPhase.Tilt? = null
) : Drawable() {

    private val photo: Bitmap
    /** The part of the picture that is the moon; the rest is black sky. */
    private val disc: Rect

    init {
        // Many moons can be on screen at once (the hourly row), so the picture is decoded only once.
        val shared = synchronized(Companion) {
            cached ?: BitmapFactory.decodeResource(context.applicationContext.resources, R.drawable.moon_full)
                .let { it to findDisc(it) }.also { cached = it }
        }
        photo = shared.first
        disc = shared.second
    }
    private val e360 = ((elongation % 360.0) + 360.0) % 360.0

    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(shadePassAlpha(), 3, 5, 12) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val circle = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

        canvas.save()
        canvas.clipPath(circle)
        // Turn the picture (north up) the way the moon is turned in the sky where the viewer is.
        canvas.rotate((tilt?.rotation ?: 0.0).toFloat(), cx, cy)
        canvas.drawBitmap(photo, disc, RectF(cx - r, cy - r, cx + r, cy + r), photoPaint)

        val waxing = e360 < 180.0
        val e = if (waxing) e360 else 360.0 - e360
        val bulge = r * abs(cos(Math.toRadians(e))).toFloat()
        val soft = r * 0.035f

        canvas.save()
        if (!waxing) canvas.scale(-1f, 1f, cx, cy)
        if (tilt != null) {
            // The shadow is drawn with the lit edge due west (waxing) or east (waning); turn it to the
            // real direction of the sun, which can lean by up to a few tens of degrees.
            val flat = if (waxing) 270.0 else 90.0
            val lean = ((tilt.brightLimb - flat + 540.0) % 360.0) - 180.0
            canvas.rotate((if (waxing) -lean else lean).toFloat(), cx, cy)
        }
        // Several passes with the edge moved a little each time make a soft terminator.
        for (i in -SOFT_STEPS..SOFT_STEPS) {
            val shadow = Path(circle)
            shadow.op(litPath(cx, cy, r, (bulge + i * soft).coerceIn(0f, r), e < 90.0), Path.Op.DIFFERENCE)
            canvas.drawPath(shadow, shade)
        }
        canvas.restore()
        canvas.restore()
    }

    /** The lit part of a disc on the right: the right half, joined along the terminator ellipse. */
    private fun litPath(cx: Float, cy: Float, r: Float, bulge: Float, crescent: Boolean): Path {
        val path = Path()
        path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f)
        path.arcTo(RectF(cx - bulge, cy - r, cx + bulge, cy + r), 90f, if (crescent) -180f else 180f)
        path.close()
        return path
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The moon as it is right now, turned to match the sky at the weather location when that is known. */
        fun now(context: Context): MoonPhotoDrawable {
            val now = System.currentTimeMillis()
            val where = WeatherRepository.knownLocation(context)
            val tilt = where?.let { MoonPhase.tiltAt(now, it.first, it.second) }
            return MoonPhotoDrawable(context, MoonPhase.stateAt(now).elongation, tilt)
        }

        private var cached: Pair<Bitmap, Rect>? = null

        private const val SOFT_STEPS = 3
        /** Where all the passes overlap the shadow should reach about 93%. */
        private const val SHADE_TOTAL = 0.93

        private fun shadePassAlpha(): Int {
            val passes = 2 * SOFT_STEPS + 1
            val each = 1.0 - Math.pow(1.0 - SHADE_TOTAL, 1.0 / passes)
            return (each * 255).toInt()
        }

        /** The box around the moon in the picture: everything brighter than the black sky. */
        private fun findDisc(bitmap: Bitmap): Rect {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            for (y in 0 until h) for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 90) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) return Rect(0, 0, w, h)
            // A square around the disc's centre, as wide as the disc is across.
            val size = maxOf(maxX - minX, maxY - minY) + 1
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2
            return Rect(cx - size / 2, cy - size / 2, cx - size / 2 + size, cy - size / 2 + size)
        }
    }
}
