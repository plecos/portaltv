package net.getportaltv.scenic

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The scene on its own, full screen, with keys to scrub through the day and the weather, for making and checking it.
 * Left and right move the time by 15 minutes, up and down change the weather, OK starts and stops the journey and
 * play/pause turns the time-lapse (an hour every few seconds) on and off. Intent extras set the start:
 * `--ef hour 18.5 --es weather Rain --ez scroll false --ez lapse true`.
 */
class PreviewActivity : Activity() {

    private var config = Config(realWeather = false, realTime = false, hour = 12f)
    private var lapse = false
    private lateinit var view: ScenicView
    private lateinit var readout: TextView
    private val tick = object : Runnable {
        override fun run() {
            if (lapse) config = config.copy(hour = (config.hour + 0.02f) % 24f)
            readout.text = "%s  %d:%02d  %s%s".format(config.weather, config.hour.toInt(), ((config.hour - config.hour.toInt()) * 60).toInt(),
                if (config.scroll) "moving" else "still", if (lapse) "  time-lapse" else "")
            readout.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("weather")?.let { if (Conditions.preset(it) != null) config = config.copy(weather = it) }
        if (intent.hasExtra("hour")) config = config.copy(hour = intent.getFloatExtra("hour", 12f))
        if (intent.hasExtra("scroll")) config = config.copy(scroll = intent.getBooleanExtra("scroll", true))
        if (intent.hasExtra("real_weather")) config = config.copy(realWeather = intent.getBooleanExtra("real_weather", false))
        if (intent.hasExtra("days")) config = config.copy(dayOffset = intent.getIntExtra("days", 0))
        if (intent.hasExtra("at")) config = config.copy(startTime = intent.getFloatExtra("at", 0f).toDouble())
        if (intent.hasExtra("boost")) config = config.copy(boost = intent.getBooleanExtra("boost", false))
        lapse = intent.getBooleanExtra("lapse", false)
        view = ScenicView(this, 30, { config }, PortalWeather(this))
        readout = TextView(this).apply { setTextColor(Color.WHITE); setShadowLayer(4f, 1f, 1f, Color.BLACK); textSize = 18f; setPadding(32, 24, 32, 24) }
        setContentView(FrameLayout(this).apply {
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(readout)
        })
        if (intent.getBooleanExtra("hide_readout", false)) readout.visibility = android.view.View.GONE
        readout.post(tick)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val names = Conditions.PRESETS.map { it.first }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> config = config.copy(hour = (config.hour - 0.25f + 24f) % 24f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> config = config.copy(hour = (config.hour + 0.25f) % 24f)
            KeyEvent.KEYCODE_DPAD_UP -> config = config.copy(weather = names[(names.indexOf(config.weather) - 1 + names.size) % names.size])
            KeyEvent.KEYCODE_DPAD_DOWN -> config = config.copy(weather = names[(names.indexOf(config.weather) + 1) % names.size])
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> config = config.copy(scroll = !config.scroll)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_T -> lapse = !lapse
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
