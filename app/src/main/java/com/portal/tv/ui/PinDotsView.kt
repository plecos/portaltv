package com.portal.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** The PIN's digits as dots: an outline for each still to come and a filled one for each entered. Never shows the digits. */
class PinDotsView(context: Context, private val count: Int) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
    }

    var entered = 0
        set(value) { field = value; invalidate() }

    private val radius = 8 * d
    private val gap = 18 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((count * 2 * radius + (count - 1) * gap + 4 * d).toInt(), widthMeasureSpec),
            resolveSize((2 * radius + 4 * d).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val total = count * 2 * radius + (count - 1) * gap
        var x = (width - total) / 2f + radius
        val y = height / 2f
        for (i in 0 until count) {
            if (i < entered) canvas.drawCircle(x, y, radius, fill) else canvas.drawCircle(x, y, radius - d, ring)
            x += 2 * radius + gap
        }
    }
}
