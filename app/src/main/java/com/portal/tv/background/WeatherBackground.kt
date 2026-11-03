package com.portal.tv.background

import android.content.Context
import java.time.LocalDate

/**
 * What the weather page shows behind its panels. Weather codes become a handful of kinds, and a
 * video from assets/backgrounds/weather/ is used when there is one for the kind; otherwise the page
 * draws an animated scene itself (see WeatherEffectView).
 *
 * A video belongs to a kind by its file name: clear*.mp4 (or sunny*), partly*, cloud* or
 * overcast*, fog*, rain*, snow*, storm* (or thunder*). Add "night" to the name for a night
 * version, for example rain_night.mp4. Case, dashes and underscores are ignored. A missing kind
 * borrows a near one: partly cloudy and fog use overcast, storms use rain.
 */
object WeatherBackground {

    enum class Kind(val aliases: List<String>) {
        CLEAR(listOf("clear", "sunny", "sun")),
        PARTLY_CLOUDY(listOf("partly")),
        OVERCAST(listOf("cloud", "overcast")),
        FOG(listOf("fog", "mist")),
        RAIN(listOf("rain", "drizzle", "shower")),
        SNOW(listOf("snow")),
        STORM(listOf("storm", "thunder"))
    }

    const val FOLDER = "backgrounds/weather"

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

    private val fallbacks = mapOf(
        Kind.PARTLY_CLOUDY to Kind.OVERCAST,
        Kind.FOG to Kind.OVERCAST,
        Kind.STORM to Kind.RAIN
    )

    /**
     * The video to play for [kind], as a path relative to assets/backgrounds (for example
     * "weather/rain1.mp4"), or null when there is none. With several matching files one is picked
     * per day. Night files are preferred after dark, and day files (the ones not marked night) in
     * the daytime.
     */
    fun videoFor(context: Context, kind: Kind, isDay: Boolean, today: LocalDate = LocalDate.now()): String? {
        val files = (context.assets.list(FOLDER) ?: emptyArray()).toList()
        return pickFile(files, kind, isDay, today)?.let { "weather/$it" }
    }

    internal fun pickFile(files: List<String>, kind: Kind, isDay: Boolean, today: LocalDate): String? {
        var k: Kind? = kind
        while (k != null) {
            val matching = files
                .filter { it.endsWith(".mp4", ignoreCase = true) }
                .filter { name -> normalized(name).let { n -> k!!.aliases.any { n.startsWith(it) } } }
                .sorted()
            if (matching.isNotEmpty()) {
                val wanted = matching.filter { normalized(it).contains("night") == !isDay }
                val pool = wanted.ifEmpty { matching }
                return pool[Math.floorMod(today.toEpochDay().toInt(), pool.size)]
            }
            k = fallbacks[k]
        }
        return null
    }

    private fun normalized(name: String) =
        name.substringBeforeLast('.').lowercase().filter { it in 'a'..'z' }
}
