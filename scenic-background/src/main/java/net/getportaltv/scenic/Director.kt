package net.getportaltv.scenic

import kotlin.math.exp
import kotlin.math.sin

/** Everything the picture is drawn from at one moment. Reused, so nothing is created each frame. */
class SceneState {
    /** Seconds since the picture started; drives every animation. */
    var time = 0.0
    /** How far the journey has gone, in pixels of the nearest layer. */
    var cam = 0.0
    /** How far the clouds have been blown, in pixels. */
    var drift = 0f
    val cond = Conditions()
    val look = Look()
    var nowMs = 0L
    var sunAlt = -30.0
    var sunAz = 0.0
    var moon: Astronomy.MoonView? = null
    var snowCover = 0f
    /** Lightning: how bright the sky is flashing, 0 to 1. */
    var flash = 0f
    /** Which way the wind blows on screen: -1 left, +1 right (0 when still). */
    var windDir = 1f
    var travelling = true
    /** How fast the journey goes now, in pixels a second of the nearest layer. */
    var speed = 30f
    /** Which place the journey is in: see [Places]. */
    var placeMode = Places.JOURNEY
    /** Where the covered bridges are on screen (left edges), worked out once a frame. */
    var bridges = IntArray(0)
    /** Where the year has got to. */
    val season = SeasonState()
    /** Where the parks are on screen, as pairs of left and right edge, worked out once a frame. */
    var parks = IntArray(0)
    /** For the preview: everything that comes and goes is there, so it can be looked at. */
    var boost = false
    var lat = 40.0
    var lon = 0.0
}

/** Where the weather and place come from; the real one asks Portal, tests give their own. */
interface WeatherInput {
    val target: Conditions
    val latitude: Double
    val longitude: Double
    val known: Boolean
}

/** Moves the scene on by one frame: the time, the journey, the weather easing toward what it should be, the sun and moon. */
class Director(private val config: () -> Config, private val weather: WeatherInput?, private val clock: () -> Long = System::currentTimeMillis) {

    val state = SceneState()
    private var started = false

    init { state.time = 60.0 }

    fun step(dtSeconds: Float) {
        val c = config()
        val s = state
        if (!started && c.startTime > 0.0) s.time = c.startTime
        if (!started && c.startCam > 0.0) s.cam = c.startCam
        s.time += dtSeconds

        // The journey.
        s.travelling = c.scroll
        s.boost = c.boost
        s.placeMode = c.environment
        s.speed = SPEED * c.speed
        if (c.scroll) s.cam += s.speed * dtSeconds

        // The weather eases toward its target: slowly for real changes, faster when someone picks one.
        val target = if (c.realWeather) (weather?.takeIf { it.known }?.target ?: CLEAR) else Conditions.preset(c.weather) ?: CLEAR
        // The first frame starts out in the weather it should have, instead of easing in from clear.
        if (!started) {
            s.cond.set(target)
            // Chosen snow has already settled; real snow builds up from nothing.
            if (!c.realWeather && target.snow > 0.3f) s.snowCover = 0.85f
            started = true
        } else s.cond.approach(target, dtSeconds, if (c.realWeather) 45f else 6f)

        // Wind pushes the clouds; from the west (270 degrees) it blows to the right.
        val push = -sin(Math.toRadians(s.cond.windDir.toDouble())).toFloat()
        s.windDir = if (kotlin.math.abs(push) < 0.2f) 1f else if (push > 0) 1f else -1f
        s.drift += (2f + s.cond.wind * 0.55f) * s.windDir * dtSeconds

        // Snow settles while it falls in the cold and melts away slowly.
        val cold = s.cond.tempC < 2f
        // (In the preview's fast time-lapse the snow comes and goes as fast as the day does.)
        val clock = dtSeconds * c.speed.coerceAtLeast(1f)
        if (s.cond.snow > 0.15f && cold) s.snowCover = (s.snowCover + s.cond.snow * clock / 150f).coerceAtMost(1f)
        else s.snowCover = (s.snowCover - clock / (if (cold) 900f else 240f)).coerceAtLeast(0f)

        // Sky: the real moment, or today at the hour chosen.
        s.lat = weather?.takeIf { it.known }?.latitude ?: weather?.latitude ?: 40.0
        s.lon = weather?.takeIf { it.known }?.longitude ?: weather?.longitude ?: 0.0
        s.nowMs = if (c.realTime) clock() else todayAt(c.hour)
        val sun = Astronomy.sun(s.nowMs, s.lat, s.lon)
        s.sunAlt = sun.altDeg
        s.sunAz = sun.azDeg
        s.moon = Astronomy.moon(s.nowMs, s.lat, s.lon)

        s.flash = lightning(s.time, s.cond.thunder)
        s.look.update(s.sunAlt.toFloat(), s.cond, s.snowCover, s.flash)
        val calendar = c.season == Seasons.CALENDAR
        val day = if (c.yearDay > 0f) c.yearDay else if (calendar) dayOfYear(s.nowMs) else Seasons.dayFor(c.season)
        Seasons.update(s.season, day, s.lat, calendar)
    }

    private var dayCachedFor = Long.MIN_VALUE
    private var dayCached = 196f

    /** The day of the year (1 to 366) at [ms]; worked out once an hour, not every frame. */
    private fun dayOfYear(ms: Long): Float {
        val hour = ms / 3_600_000L
        if (hour != dayCachedFor) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = ms
            dayCached = cal.get(java.util.Calendar.DAY_OF_YEAR).toFloat()
            dayCachedFor = hour
        }
        return dayCached
    }

    /** Today's date at [hour] on the TV's clock, as a moment. */
    private fun todayAt(hour: Float): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = clock()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, config().dayOffset)
        return cal.timeInMillis + (hour * 3_600_000f).toLong()
    }

    companion object {
        /** How fast the nearest layer moves, pixels a second. */
        const val SPEED = 30f
        private val CLEAR = Conditions(cloud = 0.05f)
        private const val SLOT = 7.0

        /** One flash happens now and then in each seven-second slot, more often the stronger the storm. */
        fun lightning(time: Double, thunder: Float): Float {
            if (thunder < 0.25f) return 0f
            val slot = Math.floor(time / SLOT).toInt()
            var best = 0f
            for (s in slot - 1..slot) {
                if (Noise.unit(s, 91) > thunder * 0.8f) continue
                val at = s * SLOT + Noise.unit(s, 92) * (SLOT - 1.0)
                val e = (time - at).toFloat()
                val v = when {
                    e < 0f -> 0f
                    e < 0.10f -> 1f
                    e < 0.18f -> 0.15f
                    e < 0.32f -> 0.85f
                    e < 0.7f -> (0.7f - e) / 0.38f * 0.4f
                    else -> 0f
                }
                if (v > best) best = v
            }
            return best
        }
    }
}
