package net.getportaltv.scenic

import android.content.Context
import android.content.SharedPreferences

/** What the person chose in this extension's settings. Read from the saved preferences; the picture reads it again when it changes. */
data class Config(
    /** Follow the real weather (asked of Portal), or use [weather]. */
    val realWeather: Boolean = true,
    val weather: String = "Clear",
    /** Follow the real time of day, or stay at [hour] (0 to 24). */
    val realTime: Boolean = true,
    val hour: Float = 12f,
    /** Let the world scroll by, or stand still. */
    val scroll: Boolean = true,
    val environment: String = Places.JOURNEY,
    val style: String = Settings.STYLES[0],
    /** Days from today for the date used with a chosen time (for the preview only: it is not saved). */
    val dayOffset: Int = 0,
    /** For the preview only: show everything that comes and goes, all the time. Not saved. */
    val boost: Boolean = false,
    /** For the preview only: the animation clock to start at, in seconds (0 for the usual start). */
    val startTime: Double = 0.0,
    val startCam: Double = 0.0,
    /** For the preview only: how many times faster than usual the journey goes. */
    val speed: Float = 1f,
    /** How many frames a second to aim for (one of [Settings.FRAME_RATES]); the picture drops lower if the TV can't keep up. */
    val fps: Int = 30,
    /** Follow the calendar, or always show one season. */
    val season: String = Seasons.CALENDAR,
    /** For the preview only: the day of the year to show (1 to 366), or 0 for the real one. */
    val yearDay: Float = 0f
)

object Settings {
    val ENVIRONMENTS = Places.CHOICES
    val STYLES = listOf("8-bit")
    val SEASONS = Seasons.CHOICES
    /** The frame rates offered in settings. */
    val FRAME_RATES = listOf(15, 30, 60, 90, 120)

    private const val FILE = "scenic"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = prefs(context)
        val d = Config()
        return Config(
            realWeather = p.getBoolean("real_weather", d.realWeather),
            weather = p.getString("weather", d.weather)?.takeIf { Conditions.preset(it) != null } ?: d.weather,
            realTime = p.getBoolean("real_time", d.realTime),
            hour = p.getFloat("hour", d.hour).coerceIn(0f, 24f),
            scroll = p.getBoolean("scroll", d.scroll),
            environment = p.getString("environment", d.environment)?.takeIf { it in ENVIRONMENTS } ?: d.environment,
            style = p.getString("style", d.style)?.takeIf { it in STYLES } ?: d.style,
            season = p.getString("season", d.season)?.takeIf { it in SEASONS } ?: d.season,
            fps = p.getInt("fps", d.fps).takeIf { it in FRAME_RATES } ?: d.fps
        )
    }
}
