package net.getportaltv.weather.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.drawable.Drawable
import android.graphics.Shader
import android.view.View
import com.portal.tv.background.WeatherBackground.Kind
import com.portal.tv.repo.MoonPhase
import com.portal.tv.ui.MoonPhotoDrawable
import kotlin.math.sin
import kotlin.random.Random

/**
 * A drawn, animated sky for the weather page, used when there is no video for the weather:
 * a gradient with a sun glow or stars, drifting clouds, falling rain or snow, fog banks and
 * lightning, depending on the [Kind] of weather. It animates only while attached to a window.
 */
class WeatherEffectView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private class Drop(var x: Float, var y: Float, val speed: Float, val length: Float, val size: Float)
    private class Cloud(var x: Float, val y: Float, val scale: Float, val speed: Float, val alpha: Int)
    private class Star(val x: Float, val y: Float, val phase: Float, val size: Float)

    private var kind = Kind.CLEAR
    private var isDay = true
    private val random = Random(7)
    private val drops = mutableListOf<Drop>()
    private val clouds = mutableListOf<Cloud>()
    private val stars = mutableListOf<Star>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastNanos = 0L
    private var seconds = 0f
    private var nextLightning = 4f
    private var flash = 0f
    private var flickered = false
    private var sizedFor = 0
    private var moon: Drawable? = null
    private var moonLit = 1.0

    fun show(kind: Kind, isDay: Boolean) {
        this.kind = kind
        this.isDay = isDay
        val phase = com.portal.tv.repo.MoonPhase.stateAt(System.currentTimeMillis())
        moon = MoonPhotoDrawable.now(context)
        moonLit = phase.illumination
        sizedFor = 0
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        sizedFor = width
        drops.clear()
        clouds.clear()
        stars.clear()
        val d = resources.displayMetrics.density
        when (kind) {
            Kind.RAIN, Kind.STORM -> {
                val count = if (kind == Kind.STORM) 220 else 150
                repeat(count) {
                    drops += Drop(random.nextFloat() * (w + 200 * d), random.nextFloat() * h,
                        (900f + random.nextFloat() * 700f) * d, (14f + random.nextFloat() * 22f) * d,
                        (1f + random.nextFloat()) * d)
                }
            }
            Kind.SNOW -> repeat(130) {
                drops += Drop(random.nextFloat() * w, random.nextFloat() * h,
                    (50f + random.nextFloat() * 90f) * d, 0f, (2f + random.nextFloat() * 4f) * d)
            }
            else -> {}
        }
        val cloudCount = when (kind) {
            Kind.PARTLY_CLOUDY -> 4
            Kind.OVERCAST, Kind.RAIN, Kind.SNOW -> 7
            Kind.STORM -> 8
            Kind.FOG -> 0
            Kind.CLEAR -> 1
        }
        repeat(cloudCount) {
            clouds += Cloud(
                x = random.nextFloat() * w,
                // Partly cloudy skies keep their clouds high, where the sun or moon is.
                y = random.nextFloat() * h * (if (kind == Kind.PARTLY_CLOUDY) 0.3f else 0.45f),
                scale = (0.8f + random.nextFloat() * 1.0f) * d,
                speed = (6f + random.nextFloat() * 14f) * d,
                alpha = when {
                    kind == Kind.PARTLY_CLOUDY && !isDay -> 95
                    kind == Kind.CLEAR || kind == Kind.PARTLY_CLOUDY -> 55
                    else -> 80
                }
            )
        }
        if (!isDay && (kind == Kind.CLEAR || kind == Kind.PARTLY_CLOUDY)) {
            repeat(90) {
                stars += Star(random.nextFloat() * w, random.nextFloat() * h * 0.8f,
                    random.nextFloat() * 6.28f, (0.8f + random.nextFloat() * 1.8f) * d)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
        postInvalidateOnAnimation()
    }

    private fun sky(): Pair<Int, Int> = when (kind) {
        Kind.CLEAR -> if (isDay) 0xFF2F6FB5.toInt() to 0xFF8CC3EC.toInt() else 0xFF050B1E.toInt() to 0xFF152245.toInt()
        Kind.PARTLY_CLOUDY -> if (isDay) 0xFF3C77B4.toInt() to 0xFF9CC4E4.toInt() else 0xFF0A1226.toInt() to 0xFF1D2B4B.toInt()
        Kind.OVERCAST -> if (isDay) 0xFF5A6572.toInt() to 0xFF8E98A3.toInt() else 0xFF1B2028.toInt() to 0xFF343B46.toInt()
        Kind.FOG -> if (isDay) 0xFF7C868F.toInt() to 0xFFAEB6BC.toInt() else 0xFF262C33.toInt() to 0xFF444C55.toInt()
        Kind.RAIN -> if (isDay) 0xFF3A4654.toInt() to 0xFF667584.toInt() else 0xFF10151C.toInt() to 0xFF262F3B.toInt()
        Kind.STORM -> 0xFF1A1F29.toInt() to 0xFF3A4150.toInt()
        Kind.SNOW -> if (isDay) 0xFF8793A3.toInt() to 0xFFC4CCD6.toInt() else 0xFF2A3342.toInt() to 0xFF4D586A.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (sizedFor != width) rebuild()
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        seconds += dt
        val w = width.toFloat()
        val h = height.toFloat()

        val (top, bottom) = sky()
        paint.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Stars first, so the moon covers them rather than letting them show through its shadow.
        drawStars(canvas)
        drawSunOrMoon(canvas, w, h)
        drawClouds(canvas, w, dt)
        if (kind == Kind.FOG) drawFog(canvas, w, h)
        when (kind) {
            Kind.RAIN, Kind.STORM -> drawRain(canvas, w, h, dt)
            Kind.SNOW -> drawSnow(canvas, w, h, dt)
            else -> {}
        }
        if (kind == Kind.STORM) drawLightning(canvas, w, h, dt)

        postInvalidateOnAnimation()
    }

    private fun drawSunOrMoon(canvas: Canvas, w: Float, h: Float) {
        val d = resources.displayMetrics.density
        if (kind != Kind.CLEAR && kind != Kind.PARTLY_CLOUDY) {
            // Behind thick cloud there is no disc to see, only a soft bright patch where it is.
            if (kind == Kind.FOG) return
            if (kind == Kind.STORM && !isDay) drawStormMoon(canvas, w, h)
            val glow = if (isDay) 0xFFFFF1C8.toInt() else 0xFFDDE6FF.toInt()
            val alpha = if (isDay) 90 else (80 * (0.25 + 0.75 * moonLit)).toInt()
            val radius = 340f * d
            paint.shader = RadialGradient(
                w * 0.82f, h * 0.13f, radius,
                Color.argb(alpha, Color.red(glow), Color.green(glow), Color.blue(glow)), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.82f, h * 0.13f, radius, paint)
            paint.shader = null
            return
        }
        val cx = w * 0.82f
        val cy = h * 0.13f
        val pulse = 1f + 0.04f * sin(seconds * 0.6f)
        val color = if (isDay) 0xFFFFE9A8.toInt() else 0xFFDDE6FF.toInt()
        val radius = (if (isDay) 300f else 170f) * d * pulse
        paint.shader = RadialGradient(
            cx, cy, radius, intArrayOf(Color.argb(if (isDay) 230 else (150 * (0.25 + 0.75 * moonLit)).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        if (isDay) {
            // White hot in the middle, warmer toward the edge, with a bright bloom around it.
            paint.shader = RadialGradient(
                cx, cy, 120f * d,
                intArrayOf(0x55FFFFFF, 0x22FFF1C8, Color.TRANSPARENT), floatArrayOf(0.3f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, 120f * d, paint)
            paint.shader = RadialGradient(
                cx - 12f * d, cy - 12f * d, 58f * d,
                intArrayOf(0xFFFFFFF4.toInt(), 0xFFFFEA9A.toInt(), 0xFFFFC445.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, 46f * d, paint)
            paint.shader = null
        } else {
            // The moon at its real phase.
            val r = (52f * d).toInt()
            moon?.apply {
                setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
                draw(canvas)
            }
        }
    }

    /** The moon seen through gaps in the storm clouds: dimmed, with the clouds drawn over it afterwards. */
    private fun drawStormMoon(canvas: Canvas, w: Float, h: Float) {
        val d = resources.displayMetrics.density
        val cx = w * 0.82f
        val cy = h * 0.13f
        val r = (52f * d).toInt()
        moon?.apply {
            setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
            val layer = canvas.saveLayerAlpha(cx - r, cy - r, cx + r, cy + r, 130)
            draw(canvas)
            canvas.restoreToCount(layer)
        }
    }

    private fun drawStars(canvas: Canvas) {
        if (stars.isEmpty()) return
        for (s in stars) {
            val twinkle = 0.5f + 0.5f * sin(seconds * 1.4f + s.phase)
            paint.color = Color.argb((60 + 170 * twinkle).toInt(), 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.size, paint)
        }
    }

    private fun drawClouds(canvas: Canvas, w: Float, dt: Float) {
        val dark = kind == Kind.STORM || kind == Kind.RAIN || (!isDay)
        val tint = when {
            kind == Kind.PARTLY_CLOUDY && !isDay -> 0xFFC6D0E2.toInt()   // moonlit
            dark -> 0xFFB8C0CC.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        for (c in clouds) {
            c.x += c.speed * dt
            val span = 520f * c.scale
            if (c.x - span > w) c.x = -span
            // Five overlapping soft puffs make one cloud.
            val puffs = listOf(0f to 0f, 120f to -30f, 240f to 0f, 60f to 30f, 180f to 34f)
            for ((i, p) in puffs.withIndex()) {
                val r = (110f + (i % 3) * 30f) * c.scale
                val px = c.x + p.first * c.scale
                val py = c.y + p.second * c.scale
                paint.shader = RadialGradient(
                    px, py, r,
                    Color.argb(c.alpha, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(px, py, r, paint)
            }
        }
        paint.shader = null
    }

    private fun drawFog(canvas: Canvas, w: Float, h: Float) {
        val tint = if (isDay) 0xFFFFFFFF.toInt() else 0xFFAAB4C0.toInt()
        for (i in 0 until 5) {
            val y = h * (0.15f + 0.18f * i)
            val drift = sin(seconds * (0.12f + 0.03f * i) + i) * w * 0.12f
            paint.shader = LinearGradient(
                drift - w * 0.2f, y, drift + w * 1.2f, y,
                intArrayOf(Color.TRANSPARENT, Color.argb(95, Color.red(tint), Color.green(tint), Color.blue(tint)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, y - h * 0.09f, w, y + h * 0.09f, paint)
        }
        paint.shader = null
    }

    private fun drawRain(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val slant = 0.18f
        paint.strokeCap = Paint.Cap.ROUND
        for (d in drops) {
            d.y += d.speed * dt
            d.x -= d.speed * slant * dt
            if (d.y > h) { d.y = -d.length; d.x = random.nextFloat() * (w + 200f) }
            if (d.x < -20f) d.x += w + 40f
            paint.strokeWidth = d.size
            paint.color = Color.argb(150, 200, 220, 240)
            canvas.drawLine(d.x, d.y, d.x + d.length * slant, d.y - d.length, paint)
        }
    }

    private fun drawSnow(canvas: Canvas, w: Float, h: Float, dt: Float) {
        for (d in drops) {
            d.y += d.speed * dt
            d.x += sin(seconds * 0.8f + d.size * 7f) * 22f * dt
            if (d.y > h + d.size) { d.y = -d.size; d.x = random.nextFloat() * w }
            paint.color = Color.argb(215, 255, 255, 255)
            canvas.drawCircle(d.x, d.y, d.size, paint)
        }
    }

    private fun drawLightning(canvas: Canvas, w: Float, h: Float, dt: Float) {
        nextLightning -= dt
        if (nextLightning <= 0f) {
            flash = 1f
            flickered = false
            nextLightning = 4f + random.nextFloat() * 8f
        }
        if (flash > 0f) {
            paint.color = Color.argb((flash * 110).toInt(), 235, 240, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
            // A quick double flicker.
            flash -= dt * 4f
            if (!flickered && flash < 0.5f) { flickered = true; flash = 0.9f }
        }
    }
}
