package net.getportaltv.scenic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.util.TimeZone

/** Where the weather comes from: Portal's `weather.get`, asked for every minute while the picture is showing. */
class PortalWeather(private val context: Context) : WeatherInput {

    /** What Portal last said (or the defaults before it says anything). Read from the drawing thread. */
    @Volatile override var target = Conditions()
        private set
    @Volatile override var latitude = 40.0
        private set
    @Volatile override var longitude = defaultLongitude()
        private set
    /** Whether Portal has ever answered. */
    @Volatile override var known = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val poll = object : Runnable {
        override fun run() {
            fetch()
            handler?.postDelayed(this, EVERY_MS)
        }
    }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("scenic-weather").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(poll) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun fetch() {
        val reply = try {
            context.contentResolver.call(Uri.parse("content://net.getportaltv.host"), "weather.get", null, Bundle())
        } catch (e: Exception) {
            null
        } ?: return
        if (!reply.getBoolean("ok", false)) return
        target = fromReply(reply)
        if (reply.containsKey("latitude")) {
            latitude = reply.getDouble("latitude")
            longitude = reply.getDouble("longitude")
        }
        known = true
    }

    private companion object {
        const val EVERY_MS = 60_000L

        /** A guess at the longitude from the time zone, for before Portal tells us where the TV is. */
        fun defaultLongitude(): Double = TimeZone.getDefault().rawOffset / 3_600_000.0 * 15.0

        fun fromReply(r: Bundle) = Conditions(
            cloud = r.getDouble("cloud", 0.1).toFloat(),
            rain = r.getDouble("rain", 0.0).toFloat(),
            snow = r.getDouble("snow", 0.0).toFloat(),
            fog = r.getDouble("fog", 0.0).toFloat(),
            thunder = r.getDouble("thunder", 0.0).toFloat(),
            wind = r.getDouble("wind_kmh", 6.0).toFloat(),
            windDir = r.getInt("wind_dir", 270).toFloat(),
            tempC = r.getDouble("temp_c", 15.0).toFloat()
        )
    }
}
