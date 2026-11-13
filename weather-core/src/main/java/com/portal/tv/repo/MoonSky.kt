package com.portal.tv.repo

/**
 * Where on Earth the moon is drawn for, so it can be turned to match the sky there: (latitude, longitude), or null when unknown.
 * Whoever shows weather sets this when it learns the place (Portal's home screen, the weather page).
 */
object MoonSky {
    @Volatile var where: Pair<Double, Double>? = null
}
