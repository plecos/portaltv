package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.portal.tv.background.WeatherBackground
import com.portal.tv.background.WeatherBackground.Kind

/**
 * The weather icon: a glowing sun, or the moon at its phase, with soft shaded clouds, rain, snow,
 * lightning or fog in front of it. Everything is drawn from gradients, with no flat artwork, so
 * it stays smooth from the 32 dp home icon up to the weather page's big one.
 *
 * Clear weather shows just the sun or moon. Otherwise the sun or moon moves up and to the left and
 * the cloud sits down and to the right, so a bit of the sun or moon always peeks out.
 */
class WeatherGlyphDrawable(
    private val context: Context,
    code: Int,
    private val isDay: Boolean
) : Drawable() {

    private val kind = WeatherBackground.kindFor(code)
    private val heavy = code in intArrayOf(65, 67, 75, 82, 86, 96, 99)
    private val moon: Drawable? = if (isDay) null else MoonPhotoDrawable.now(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        canvas.save()
        canvas.translate(b.exactCenterX() - s / 2, b.exactCenterY() - s / 2)

        // Where the sun or moon sits, how big, and how much of it shows through the weather.
        val (cx, cy, size, alpha) = when (kind) {
            Kind.CLEAR -> Look(0.5f, 0.5f, 0.86f, 1f)
            Kind.PARTLY_CLOUDY -> Look(0.38f, 0.38f, 0.62f, 1f)
            Kind.FOG -> Look(0.5f, 0.42f, 0.7f, 0.85f)
            Kind.OVERCAST -> Look(0.36f, 0.36f, 0.56f, 0.65f)
            else -> Look(0.34f, 0.34f, 0.5f, 0.55f)   // rain, snow, storm
        }
        drawCelestial(canvas, s, cx * s, cy * s, size * s, alpha)

        when (kind) {
            Kind.CLEAR -> {}
            Kind.PARTLY_CLOUDY -> cloud(canvas, s, 0.60f, 0.60f, 0.72f, dark = 0f)
            Kind.OVERCAST -> {
                cloud(canvas, s, 0.34f, 0.50f, 0.52f, dark = 0.45f)
                cloud(canvas, s, 0.62f, 0.60f, 0.70f, dark = 0.55f)
            }
            Kind.FOG -> fog(canvas, s)
            Kind.RAIN -> {
                rain(canvas, s)
                cloud(canvas, s, 0.60f, 0.52f, 0.74f, dark = 0.65f)
            }
            Kind.SNOW -> {
                snow(canvas, s)
                cloud(canvas, s, 0.60f, 0.52f, 0.74f, dark = 0.4f)
            }
            Kind.STORM -> {
                cloud(canvas, s, 0.60f, 0.50f, 0.76f, dark = 0.85f)
                bolt(canvas, s)
            }
        }
        canvas.restore()
    }

    private data class Look(val cx: Float, val cy: Float, val size: Float, val alpha: Float)

    // ---------- Sun and moon ----------

    private fun drawCelestial(canvas: Canvas, s: Float, cx: Float, cy: Float, size: Float, alpha: Float) {
        val r = size / 2f
        // The glow must fade out before the icon's edge, or its square outline would show.
        val reach = minOf(cx, cy, s - cx, s - cy)
        val layer = canvas.saveLayerAlpha(0f, 0f, s, s, (alpha * 255).toInt())
        if (isDay) {
            // A wide soft glow, then the disc: white hot in the middle, warmer toward the edge.
            val glow = minOf(r * 2.1f, reach)
            paint.shader = RadialGradient(
                cx, cy, glow,
                intArrayOf(0x99FFE9A0.toInt(), 0x33FFC95A, 0x00FFB93A),
                floatArrayOf(0.30f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, glow, paint)
            paint.shader = RadialGradient(
                cx - r * 0.18f, cy - r * 0.18f, r * 1.05f,
                intArrayOf(0xFFFFFFF0.toInt(), 0xFFFFE680.toInt(), 0xFFFFB92E.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r * 0.72f, paint)
            paint.shader = null
        } else {
            // A faint halo, then the moon photo.
            val halo = minOf(r * 1.7f, reach)
            paint.shader = RadialGradient(
                cx, cy, halo,
                intArrayOf(0x55DDE6FF, 0x22DDE6FF, 0x00DDE6FF),
                floatArrayOf(0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, halo, paint)
            paint.shader = null
            moon?.apply {
                setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
                draw(canvas)
            }
        }
        canvas.restoreToCount(layer)
    }

    // ---------- Clouds ----------

    /**
     * A soft cloud centred at ([cx], [cy]) and [width] across (all as fractions of the icon). [dark]
     * from 0 to 1 greys it for rain and storms. Puffs are drawn white, then a gradient is laid
     * over only the cloud's own pixels to shade the underside.
     */
    private fun cloud(canvas: Canvas, s: Float, cx: Float, cy: Float, width: Float, dark: Float) {
        val u = width * s            // the cloud's width in pixels
        val x = cx * s
        val y = cy * s
        val layer = canvas.saveLayer(0f, 0f, s, s, null)

        // (x offset, y offset, radius), as fractions of the cloud's width.
        val puffs = listOf(
            Triple(-0.30f, 0.06f, 0.20f), Triple(-0.13f, -0.08f, 0.25f), Triple(0.09f, -0.14f, 0.29f),
            Triple(0.30f, 0.0f, 0.22f), Triple(0.0f, 0.08f, 0.26f), Triple(0.17f, 0.10f, 0.20f),
            Triple(-0.19f, 0.12f, 0.17f)
        )
        for ((dx, dy, r) in puffs) {
            val px = x + dx * u
            val py = y + dy * u
            val pr = r * u
            paint.shader = RadialGradient(
                px, py, pr,
                intArrayOf(Color.WHITE, Color.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, pr, paint)
        }
        // A flat-ish base so the bottom edge reads as the underside of a cloud.
        paint.shader = LinearGradient(
            0f, y + 0.04f * u, 0f, y + 0.24f * u, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(x - 0.42f * u, y + 0.02f * u, x + 0.42f * u, y + 0.24f * u), 0.11f * u, 0.11f * u, paint)

        // Shade: light at the top, darker toward the bottom, and greyer for rain and storms.
        val topC = blend(0xFFF6F9FD.toInt(), 0xFFB9C3D0.toInt(), dark)
        val midC = blend(0xFFDCE3EC.toInt(), 0xFF8794A4.toInt(), dark)
        val botC = blend(0xFFAAB6C5.toInt(), 0xFF566273.toInt(), dark)
        paint.shader = LinearGradient(
            0f, y - 0.4f * u, 0f, y + 0.26f * u, intArrayOf(topC, midC, botC), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        canvas.drawRect(0f, 0f, s, s, paint)
        if (!isDay) {
            paint.shader = null
            paint.color = Color.argb(70, 10, 20, 50)
            canvas.drawRect(0f, 0f, s, s, paint)
        }
        paint.xfermode = null
        paint.shader = null
        canvas.restoreToCount(layer)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    // ---------- Precipitation, lightning and fog ----------

    private fun rain(canvas: Canvas, s: Float) {
        val count = if (heavy) 6 else 4
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = s * 0.028f
        paint.color = Color.argb(215, 190, 215, 245)
        for (i in 0 until count) {
            val x = s * (0.36f + i * (0.46f / (count - 1)))
            val y = s * (0.72f + (i % 2) * 0.07f)
            canvas.drawLine(x, y, x - s * 0.045f, y + s * 0.16f, paint)
        }
    }

    private fun snow(canvas: Canvas, s: Float) {
        val spots = listOf(0.38f to 0.80f, 0.54f to 0.90f, 0.68f to 0.78f, 0.82f to 0.90f, 0.47f to 0.70f, 0.75f to 0.69f)
        for ((i, p) in spots.withIndex()) {
            if (!heavy && i >= 4) continue
            val x = p.first * s
            val y = p.second * s
            paint.shader = RadialGradient(
                x, y, s * 0.05f, intArrayOf(Color.WHITE, 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, s * 0.05f, paint)
        }
        paint.shader = null
    }

    private fun bolt(canvas: Canvas, s: Float) {
        val path = Path().apply {
            moveTo(0.66f * s, 0.54f * s)
            lineTo(0.48f * s, 0.82f * s)
            lineTo(0.60f * s, 0.82f * s)
            lineTo(0.52f * s, 1.00f * s)
            lineTo(0.78f * s, 0.70f * s)
            lineTo(0.64f * s, 0.70f * s)
            lineTo(0.74f * s, 0.54f * s)
            close()
        }
        // A glow made of a few wider, fainter passes, then the bright bolt.
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        for ((w, a) in listOf(0.07f to 40, 0.045f to 70, 0.025f to 110)) {
            paint.strokeWidth = w * s
            paint.color = Color.argb(a, 255, 225, 90)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0.54f * s, 0f, s, 0xFFFFFBE0.toInt(), 0xFFFFC62E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun fog(canvas: Canvas, s: Float) {
        for (i in 0 until 3) {
            val y = s * (0.58f + i * 0.14f)
            val inset = s * (0.06f + (i % 2) * 0.10f)
            paint.shader = LinearGradient(
                inset, y, s - inset, y,
                intArrayOf(0x00F2F5F9, 0xCCF2F5F9.toInt(), 0x00F2F5F9), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(RectF(inset, y - s * 0.035f, s - inset, y + s * 0.035f), s * 0.04f, s * 0.04f, paint)
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Required by Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
