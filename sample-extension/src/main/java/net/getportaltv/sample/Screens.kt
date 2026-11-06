package net.getportaltv.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

// The three screens the sample extension declares. They are deliberately plain: a Portal extension is just an Android app,
// so these are ordinary activities drawn in code, with rows the TV remote can move between.

private val BACKGROUND = Color.rgb(16, 16, 32)
private val CARD = Color.rgb(34, 36, 64)
private val FOCUSED = Color.rgb(138, 131, 255)

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.label(text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** A rounded card the remote can focus; it lights up while focused. */
private fun Activity.card(child: View, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(28), dp(18), dp(28), dp(18))
    isFocusable = true
    isClickable = onClick != null
    fun look(focused: Boolean) {
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (focused) FOCUSED else CARD)
        }
    }
    look(false)
    setOnFocusChangeListener { _, focused -> look(focused) }
    onClick?.let { c -> setOnClickListener { c() } }
    addView(child)
}

private fun Activity.page(title: String, body: ViewGroup.() -> Unit) {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(dp(96), dp(64), dp(96), dp(48))
        addView(label(title, 40f, bold = true))
        addView(View(this@page), LinearLayout.LayoutParams(1, dp(28)))
    }
    root.body()
    setContentView(root)
}

/** The "Lights" tile: rooms that switch on and off with OK. */
class LightsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Lights") {
            listOf("Living room" to true, "Kitchen" to true, "Porch" to false, "Bedroom" to false).forEach { (room, startsOn) ->
                var on = startsOn
                val state = label(if (on) "On" else "Off", 22f, if (on) Color.rgb(140, 230, 160) else Color.LTGRAY, bold = true)
                val row = LinearLayout(this@LightsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(room, 26f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(state)
                }
                addView(card(row) {
                    on = !on
                    state.text = if (on) "On" else "Off"
                    state.setTextColor(if (on) Color.rgb(140, 230, 160) else Color.LTGRAY)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            }
            requestFocusOnFirst()
        }
    }
}

/** The "Cameras" tile: a grid of camera views (placeholders here). */
class CamerasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Cameras") {
            val grid = GridLayout(this@CamerasActivity).apply { columnCount = 2 }
            listOf("Front door", "Garage", "Backyard", "Driveway").forEach { name ->
                val box = LinearLayout(this@CamerasActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(110)
                    addView(label("● LIVE", 16f, Color.rgb(255, 110, 110), bold = true))
                    addView(label(name, 28f))
                }
                val params = GridLayout.LayoutParams().apply { width = dp(360); height = GridLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, dp(18), dp(18)) }
                grid.addView(card(box), params)
            }
            addView(grid)
            requestFocusOnFirst()
        }
    }
}

/** The settings screen Portal opens from its own Settings: the extension draws it itself. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Sample extension settings") {
            listOf("Home server" to "http://homeassistant.local:8123", "Refresh every" to "30 seconds", "Show cameras in the apps row" to "On").forEach { (name, value) ->
                val col = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(name, 20f, Color.LTGRAY))
                    addView(label(value, 28f))
                }
                addView(card(col), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
            }
            requestFocusOnFirst()
        }
    }
}

private fun ViewGroup.requestFocusOnFirst() {
    post { findFocusable(this)?.requestFocus() }
}

private fun findFocusable(group: ViewGroup): View? {
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        if (child.isFocusable) return child
        if (child is ViewGroup) findFocusable(child)?.let { return it }
    }
    return null
}
