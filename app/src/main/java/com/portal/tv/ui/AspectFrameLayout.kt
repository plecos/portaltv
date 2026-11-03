package com.portal.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A frame that is always as tall as a 16:9 picture is for its width. */
class AspectFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY)
        )
    }
}
