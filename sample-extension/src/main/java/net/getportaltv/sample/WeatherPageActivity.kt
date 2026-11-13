package net.getportaltv.sample

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * A weather page, as the example for docs/EXTENSIONS.md. Portal opens this when the person chose it under Settings, Weather,
 * Weather page and presses the time and weather panel. It asks Portal for the weather ("weather.report") and the alerts
 * ("weather.alerts") and draws them however it likes. It does no weather work of its own: where the weather comes from is a
 * separate choice (a weather source), so this page works with whatever source Portal is using.
 */
class WeatherPageActivity : Activity() {

    private val uri = Uri.parse("content://net.getportaltv.host")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(96, 72, 96, 72)
            gravity = Gravity.START
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 16, 28)); addView(column) })
        fun line(text: String, size: Float = 24f, color: Int = Color.WHITE) =
            column.addView(TextView(this).apply { this.text = text; textSize = size; setTextColor(color); setPadding(0, 8, 0, 8) })

        val report = call("weather.report")
        if (report == null || !report.getBoolean("ok")) {
            line("No weather yet (${report?.getString("error") ?: "Portal didn't answer"}). Is this extension allowed to be the weather page?", 24f, Color.LTGRAY)
            return
        }
        val metric = report.getBoolean("metric", true)
        val r = JSONObject(report.getString("json") ?: "{}")
        fun temp(c: Double) = if (metric) "${Math.round(c)}°C" else "${Math.round(c * 9 / 5 + 32)}°F"

        line("${r.optString("city", "Weather")}  (sample page)", 40f)
        line("${temp(r.optDouble("temp_c"))}, feels like ${temp(r.optDouble("feels_like_c"))}", 32f)
        line("Humidity ${r.optInt("humidity")}%   Wind ${Math.round(r.optDouble("wind_kmh"))} km/h", 24f, Color.LTGRAY)
        if (report.getBoolean("stale")) line("This weather is old.", 20f, Color.rgb(255, 200, 80))

        val days = r.optJSONArray("days") ?: JSONArray()
        for (i in 0 until minOf(days.length(), 5)) {
            val d = days.getJSONObject(i)
            line("${d.optString("date")}   ${temp(d.optDouble("high_c"))} / ${temp(d.optDouble("low_c"))}   rain ${d.optInt("rain")}%", 22f)
        }

        val alerts = JSONArray(call("weather.alerts")?.getString("json") ?: "[]")
        line(if (alerts.length() == 0) "No alerts." else "Alerts:", 24f, Color.LTGRAY)
        for (i in 0 until alerts.length()) {
            val a = alerts.getJSONObject(i)
            line("${a.optString("event")} (${a.optString("severity").lowercase()}): ${a.optString("headline")}", 22f, Color.rgb(255, 200, 80))
        }
    }

    /** One call to Portal; the reply's `ok` says whether it worked, `error` why not. Null if Portal can't be reached. */
    private fun call(method: String): Bundle? = try {
        contentResolver.call(uri, method, null, Bundle())
    } catch (e: Exception) {
        null
    }
}
