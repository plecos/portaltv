package net.getportaltv.weather.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import net.getportaltv.weather.R

/**
 * Today's range as a bar from the low to the high, with a dot where the temperature is now and the low and high
 * written at the ends. It only draws what it is given, in whatever units the labels are in.
 */
class TempRangeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = resources.getFont(R.font.inter)
        textSize = 20f * resources.displayMetrics.scaledDensity
        color = 0xFFFFFFFF.toInt()
    }
    private val track = RectF()

    private var lowC = 0.0
    private var highC = 0.0
    private var nowC = 0.0
    private var lowLabel = ""
    private var highLabel = ""

    fun set(lowC: Double, highC: Double, nowC: Double, lowLabel: String, highLabel: String) {
        this.lowC = lowC; this.highC = highC; this.nowC = nowC
        this.lowLabel = lowLabel; this.highLabel = highLabel
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize((56f * d).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val labelWidth = maxOf(text.measureText(lowLabel), text.measureText(highLabel))
        val gap = 16f * d
        val left = labelWidth + gap
        val right = w - labelWidth - gap
        if (right <= left) return
        val cy = height / 2f
        val barH = 10f * d

        // Low and high at the ends, centred on the bar.
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        text.color = 0xFFB0B0C0.toInt()
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(lowLabel, 0f, baseline, text)
        text.color = 0xFFFFFFFF.toInt()
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(highLabel, w, baseline, text)

        // The bar: cool at the low end, warm at the high end.
        track.set(left, cy - barH / 2, right, cy + barH / 2)
        paint.shader = LinearGradient(left, 0f, right, 0f, 0xFF6EA8FF.toInt(), 0xFFFFB45E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(track, barH / 2, barH / 2, paint)
        paint.shader = null

        // Where it is now.
        val span = highC - lowC
        val fraction = if (span <= 0.0) 0.5 else ((nowC - lowC) / span).coerceIn(0.0, 1.0)
        val x = left + (right - left) * fraction.toFloat()
        paint.color = 0x55000000
        canvas.drawCircle(x, cy + 1f * d, 13f * d, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, cy, 12f * d, paint)
    }
}
