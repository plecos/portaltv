package net.getportaltv.scenic

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings page Portal opens from Settings, Extensions: what the picture follows (the real weather and time, or ones you
 * choose), whether the journey scrolls, and the setting and drawing style. A live preview at the top shows each change as it
 * is made. Everything is saved at once.
 */
class SettingsActivity : Activity() {

    private lateinit var preview: ScenicView
    private var config = Config()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Settings.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(18, 20, 34))
            setPadding(dp(56), dp(40), dp(56), dp(40))
        }
        // The rows on the left, the live preview on the right.
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text("Scenic Travel", 34f, Color.WHITE, bold = true))
        list.addView(text("Choose it under Settings, Background. Changes show at once.", 16f, Color.rgb(176, 176, 192)).apply { setPadding(0, dp(4), 0, dp(18)) })
        val rows = mutableListOf<Row>()
        fun row(label: String, value: () -> String, change: (Int) -> Unit): Row {
            val r = Row(label, value, change)
            rows += r
            list.addView(r.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            return r
        }
        fun save() { Settings.prefs(this).edit()
            .putBoolean("real_weather", config.realWeather).putString("weather", config.weather)
            .putBoolean("real_time", config.realTime).putFloat("hour", config.hour)
            .putBoolean("scroll", config.scroll).putString("environment", config.environment).putString("style", config.style)
            .putString("season", config.season).apply() }
        fun refresh() = rows.forEach { it.update() }

        row("Weather", { if (config.realWeather) "Follow the real weather" else "Always: ${config.weather}" }) { d ->
            // Real, then each weather in turn.
            val names = Conditions.PRESETS.map { it.first }
            val index = if (config.realWeather) -1 else names.indexOf(config.weather)
            val next = (index + 1 + d + names.size + 1) % (names.size + 1) - 1
            config = if (next < 0) config.copy(realWeather = true) else config.copy(realWeather = false, weather = names[next])
            save(); refresh()
        }
        row("Time of day", { if (config.realTime) "Follow the real time" else "Always ${clockText(config.hour)}" }) { d ->
            config = if (config.realTime) config.copy(realTime = false, hour = 12f)
            else if (d > 0 && config.hour >= 23.5f) config.copy(realTime = true)
            else config.copy(hour = (config.hour + 0.5f * d).coerceIn(0f, 24f))
            if (!config.realTime && config.hour < 0f) config = config.copy(realTime = true)
            save(); refresh()
        }
        row("Scrolling", { if (config.scroll) "The journey moves on" else "Standing still" }) { _ ->
            config = config.copy(scroll = !config.scroll); save(); refresh()
        }
        row("Place", { if (config.environment == Places.JOURNEY) "Journey: the place changes as you go" else config.environment }) { d ->
            val i = Settings.ENVIRONMENTS.indexOf(config.environment)
            config = config.copy(environment = Settings.ENVIRONMENTS[(i + d + Settings.ENVIRONMENTS.size) % Settings.ENVIRONMENTS.size]); save(); refresh()
        }
        row("Season", { if (config.season == Seasons.CALENDAR) "Follow the calendar" else "Always ${config.season.lowercase()}" }) { d ->
            val i = Settings.SEASONS.indexOf(config.season)
            config = config.copy(season = Settings.SEASONS[(i + d + Settings.SEASONS.size) % Settings.SEASONS.size]); save(); refresh()
        }
        row("Drawing style", { config.style }) { d ->
            val i = Settings.STYLES.indexOf(config.style)
            config = config.copy(style = Settings.STYLES[(i + d + Settings.STYLES.size) % Settings.STYLES.size]); save(); refresh()
        }
        list.addView(text("Left and right change a setting. Time of day moves by half an hour, and past midnight goes back to the real time.", 14f, Color.rgb(150, 150, 170)).apply { setPadding(0, dp(8), 0, 0) })

        preview = ScenicView(this, 20, { config }, PortalWeather(this))
        val frame = FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = false }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(frame, LinearLayout.LayoutParams(dp(520), dp(292)).apply { leftMargin = dp(32); gravity = Gravity.TOP })
        setContentView(root)
        refresh()
        rows.firstOrNull()?.view?.requestFocus()
    }

    private fun clockText(hour: Float): String {
        val h = hour.toInt() % 24
        val m = ((hour - hour.toInt()) * 60).toInt()
        return "%d:%02d".format(h, m)
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** A focusable row with a name and its value; left and right change it, so does OK. */
    private inner class Row(private val label: String, private val value: () -> String, private val change: (Int) -> Unit) {
        private val valueView = text("", 24f, Color.WHITE)
        val view = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(22), dp(14), dp(22), dp(14))
            addView(text(label, 16f, Color.rgb(176, 176, 192)))
            addView(valueView)
            background = box(false)
            setOnFocusChangeListener { v, focused -> v.background = box(focused) }
            setOnClickListener { change(1) }
            setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { change(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { change(1); true }
                    else -> false
                }
            }
        }
        fun update() { valueView.text = value() }
        private fun box(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (focused) Color.rgb(70, 74, 150) else Color.rgb(36, 38, 64))
            if (focused) setStroke(dp(3), Color.rgb(160, 164, 255))
        }
    }
}
