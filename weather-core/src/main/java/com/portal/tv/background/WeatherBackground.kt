package com.portal.tv.background

/**
 * What the weather page and the weather icons show for a kind of weather. Weather codes become a handful of kinds, and each
 * kind has a scene drawn for it (see the page's WeatherEffectView) and a matching icon.
 */
object WeatherBackground {

    enum class Kind {
        CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, RAIN, SNOW, STORM
    }

    /** Which kind of scene fits a WMO weather code. */
    fun kindFor(code: Int): Kind = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY_CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.OVERCAST
    }
}
