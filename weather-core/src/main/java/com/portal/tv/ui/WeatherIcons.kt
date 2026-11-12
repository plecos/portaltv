package com.portal.tv.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Weather icons and wording, shared by the home screen and the weather page. */
object WeatherIcons {

    /** Plain-language description of a WMO weather code. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    /** The sun or moon with the weather in front of it, at [sizeDp], as on the home screen. */
    fun layered(context: Context, sizeDp: Int, code: Int, isDay: Boolean): View {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        return ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(px, px)
            setImageDrawable(WeatherGlyphDrawable(context, code, isDay))
        }
    }
}
