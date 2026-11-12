package net.getportaltv.sample

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * A weather source, as the example for docs/EXTENSIONS.md. Portal binds to this when an adult chose it in Settings, Weather,
 * sends one question (the weather, or the alerts, for a place) and waits for one reply. This one answers with made-up weather
 * and one plainly made-up alert; a real source would look the place up on its own service and answer with what it found.
 *
 * The question carries "lat" and "lon" (rounded to about 10 km) and sometimes "city". The reply carries "json" (the answer, in
 * the format described in docs/EXTENSIONS.md) or "error" (a few words saying why not). Portal checks every number and word
 * before it uses any of it, and decides for itself where the TV is and what is allowed to cover an app.
 */
class WeatherService : Service() {

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            val answer = when (msg.what) {
                MSG_REPORT -> report(msg.data?.getString("city"))
                MSG_ALERTS -> alerts()
                else -> return
            }
            val reply = Message.obtain(null, MSG_REPLY).apply { data = Bundle().apply { putString("json", answer) } }
            runCatching { replyTo.send(reply) }
        }
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun report(city: String?): String {
        val today = LocalDate.now()
        return JSONObject()
            .put("city", city ?: "Sample City")
            .put("temp_c", 18.0).put("feels_like_c", 17.0).put("humidity", 55)
            .put("wind_kmh", 12.0).put("wind_dir", 270).put("code", 2).put("is_day", true)
            .put("lat", 0.0).put("lon", 0.0).put("utc_offset_s", 0)
            .put("hours", JSONArray((0 until 12).map { i ->
                JSONObject().put("h", (9 + i) % 24).put("temp_c", 18.0 + i % 4).put("code", 2).put("is_day", true).put("rain", 10)
            }))
            .put("days", JSONArray((0 until 7).map { i ->
                JSONObject().put("date", today.plusDays(i.toLong()).toString()).put("code", 2)
                    .put("high_c", 21.0).put("low_c", 12.0).put("rain", 10)
                    .put("sunrise", "${today.plusDays(i.toLong())}T06:30").put("sunset", "${today.plusDays(i.toLong())}T19:45")
            }))
            .toString()
    }

    private fun alerts(): String {
        val now = OffsetDateTime.now()
        return JSONArray().put(
            JSONObject().put("id", "sample-1").put("event", "Sample Advisory").put("severity", "MINOR")
                .put("headline", "This is a made-up alert from the sample extension")
                .put("description", "Nothing is wrong. The sample extension sends this so you can see where an alert shows.")
                .put("instruction", "Carry on.").put("area", "Nowhere")
                .put("onset", now.toString()).put("ends", now.plusHours(2).toString())
                .put("interrupts", false)
        ).toString()
    }

    private companion object {
        const val MSG_REPORT = 1
        const val MSG_ALERTS = 2
        const val MSG_REPLY = 3
    }
}
