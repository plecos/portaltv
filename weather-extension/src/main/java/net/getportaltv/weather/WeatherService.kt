package net.getportaltv.weather

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.portal.tv.weather.WeatherJson
import com.portal.tv.weather.WeatherPlace
import com.portal.tv.weather.WeatherProtocol
import java.util.concurrent.Executors

/**
 * Portal Weather: the weather source that comes with Portal. Portal binds to this when it wants the forecast, the alerts or a
 * city search (see [WeatherProtocol]); this asks Open-Meteo and the US National Weather Service and answers. It runs only while
 * it is being asked, keeps nothing and has no screen: Portal decides when to ask and what to do with the answer.
 */
class WeatherService : Service() {

    private val workers = Executors.newCachedThreadPool()

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            val what = msg.what
            val data = msg.data
            // The network is not touched on the main thread.
            workers.execute {
                val answer = try { answer(what, data) } catch (e: Exception) { Fetched.Failed("Weather error") }
                val reply = Message.obtain(null, WeatherProtocol.MSG_REPLY).apply {
                    this.data = Bundle().apply {
                        when (answer) {
                            is Fetched.Ok -> putString(WeatherProtocol.KEY_JSON, answer.value)
                            is Fetched.Failed -> putString(WeatherProtocol.KEY_ERROR, answer.reason.take(WeatherProtocol.MAX_ERROR))
                        }
                    }
                }
                runCatching { replyTo.send(reply) }
            }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        workers.shutdown()
        super.onDestroy()
    }

    /** The answer to one question, as the JSON Portal reads, or why there isn't one. Never throws past the caller. */
    private fun answer(what: Int, data: Bundle?): Fetched<String> = when (what) {
        WeatherProtocol.MSG_REPORT -> place(data)?.let { p ->
            when (val r = OpenMeteoSource(base("weather_debug", "forecast_base", OpenMeteoSource.DEFAULT_BASE)).fetch(p)) {
                is Fetched.Ok -> Fetched.Ok(WeatherJson.encode(r.value))
                is Fetched.Failed -> r
            }
        } ?: Fetched.Failed("No place")
        WeatherProtocol.MSG_ALERTS -> place(data)?.let { p ->
            when (val r = NwsAlertsSource(base("alerts_debug", "base", NwsAlertsSource.DEFAULT_BASE)).fetch(p)) {
                is Fetched.Ok -> Fetched.Ok(WeatherJson.encodeAlerts(r.value))
                is Fetched.Failed -> r
            }
        } ?: Fetched.Failed("No place")
        WeatherProtocol.MSG_SEARCH_CITIES -> data?.getString(WeatherProtocol.KEY_QUERY)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }?.let { q ->
            when (val r = Geocoder().search(q)) {
                is Fetched.Ok -> Fetched.Ok(WeatherJson.encodeCities(r.value))
                is Fetched.Failed -> r
            }
        } ?: Fetched.Failed("No search")
        else -> Fetched.Failed("Unknown question")
    }

    /** The place Portal asked about, or null if the question didn't carry a usable one. Only the named keys are read. */
    private fun place(data: Bundle?): WeatherPlace? {
        if (data == null || !data.containsKey(WeatherProtocol.KEY_LAT) || !data.containsKey(WeatherProtocol.KEY_LON)) return null
        val lat = data.getDouble(WeatherProtocol.KEY_LAT, Double.NaN)
        val lon = data.getDouble(WeatherProtocol.KEY_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return WeatherPlace(lat, lon, data.getString(WeatherProtocol.KEY_CITY)?.take(80))
    }

    /** The service's address. Debug builds can point it at a test server, to try odd forecasts and a severe alert. */
    private fun base(file: String, key: String, default: String): String {
        if (!BuildConfig.DEBUG) return default
        return getSharedPreferences(file, Context.MODE_PRIVATE).getString(key, null)?.trimEnd('/') ?: default
    }
}
