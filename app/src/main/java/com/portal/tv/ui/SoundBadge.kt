package com.portal.tv.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.portal.tv.R

/** A small speaker on a dark disc, placed on a video's picture to say the video has sound. Hidden until it's known. */
fun soundBadge(context: Context): ImageView {
    val d = context.resources.displayMetrics.density
    return ImageView(context).apply {
        setImageResource(R.drawable.ic_sound)
        setBackgroundResource(R.drawable.bg_sound_badge)
        val pad = (4 * d).toInt()
        setPadding(pad, pad, pad, pad)
        contentDescription = "Has sound"
        visibility = View.GONE
    }
}
