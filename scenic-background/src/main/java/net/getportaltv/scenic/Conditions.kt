package net.getportaltv.scenic

import kotlin.math.exp

/**
 * The weather as the scene uses it. Every part moves toward a target a little at a time, so a change of weather (real or
 * chosen) fades in over a while instead of jumping.
 */
class Conditions(
    var cloud: Float = 0.1f,
    var rain: Float = 0f,
    var snow: Float = 0f,
    var fog: Float = 0f,
    var thunder: Float = 0f,
    /** Wind speed, km/h. */
    var wind: Float = 6f,
    /** Where the wind comes from, degrees; only whether it blows left or right matters here. */
    var windDir: Float = 270f,
    var tempC: Float = 15f
) {
    fun set(o: Conditions) {
        cloud = o.cloud; rain = o.rain; snow = o.snow; fog = o.fog; thunder = o.thunder; wind = o.wind; windDir = o.windDir; tempC = o.tempC
    }

    /** Moves each part toward [t]; after [tauSeconds] it has covered about two thirds of the way. */
    fun approach(t: Conditions, dtSeconds: Float, tauSeconds: Float) {
        val k = 1f - exp(-dtSeconds / tauSeconds.coerceAtLeast(0.01f))
        cloud += (t.cloud - cloud) * k
        rain += (t.rain - rain) * k
        snow += (t.snow - snow) * k
        fog += (t.fog - fog) * k
        thunder += (t.thunder - thunder) * k
        wind += (t.wind - wind) * k
        tempC += (t.tempC - tempC) * k
        // The direction is turned the short way round.
        var d = ((t.windDir - windDir + 540f) % 360f) - 180f
        if (d.isNaN()) d = 0f
        windDir = (windDir + d * k + 360f) % 360f
    }

    companion object {
        /** The weather an adult can pick instead of the real one. */
        val PRESETS: List<Pair<String, Conditions>> = listOf(
            "Clear" to Conditions(cloud = 0.05f, wind = 6f),
            "Partly cloudy" to Conditions(cloud = 0.5f, wind = 12f),
            "Cloudy" to Conditions(cloud = 0.95f, wind = 14f),
            "Fog" to Conditions(cloud = 0.6f, fog = 0.85f, wind = 2f),
            "Rain" to Conditions(cloud = 0.95f, rain = 0.55f, wind = 16f),
            "Heavy rain" to Conditions(cloud = 1f, rain = 0.95f, wind = 28f),
            "Thunderstorm" to Conditions(cloud = 1f, rain = 0.85f, thunder = 1f, wind = 34f),
            "Snow" to Conditions(cloud = 0.95f, snow = 0.6f, wind = 8f, tempC = -3f),
            "Blizzard" to Conditions(cloud = 1f, snow = 0.95f, fog = 0.3f, wind = 36f, tempC = -8f)
        )

        fun preset(name: String): Conditions? = PRESETS.firstOrNull { it.first == name }?.second
    }
}
