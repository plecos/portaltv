package com.portal.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R

/**
 * A window to choose an animated shader from: every style is shown moving, in the color chosen, so you see what you
 * get. The color and color cycling are chosen here too, and every picture follows them at once. Nothing is kept until
 * Done; Back or Cancel leaves things as they were. The picture in focus moves smoothly and the others slowly, which
 * keeps the graphics chip from working on eleven pictures at full speed.
 */
object ShaderPickerDialog {

    /** What the window starts with, and what Done hands back. */
    data class Choice(val style: String, val color: String, val cycle: Boolean)

    fun show(context: Context, title: String, start: Choice, onDone: (Choice) -> Unit) {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val soft = 0xFFB0B0C0.toInt()
        var chosen = start
        val dialog = Dialog(context, R.style.Theme_Portal_Dialog)
        val previews = ArrayList<Pair<ShaderStyles.Style, ShaderBackgroundView>>()

        fun text(s: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(context).apply {
            this.text = s
            textSize = sp
            setTextColor(color)
            typeface = context.resources.getFont(R.font.inter)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        fun rounded(r: Int) = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, px(r).toFloat()) }
        }

        // ---------- Every style, moving ----------
        val checks = HashMap<String, View>()
        val grid = GridLayout(context).apply { columnCount = 3 }
        ShaderStyles.all.forEach { style ->
            val preview = ShaderBackgroundView(
                context, ShaderPalettes.find(chosen.color), style, cycle = chosen.cycle,
                bufferWidth = 320, bufferHeight = 180, startAt = 25f
            ).apply { frameMs = SLOW_MS }
            previews += style to preview
            val card = FrameLayout(context).apply {
                setPadding(px(3), px(3), px(3), px(3))
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_video_card)
                contentDescription = style.label
                setOnFocusChangeListener { _, focused -> preview.frameMs = if (focused) FAST_MS else SLOW_MS }
            }
            // The picture, its name and the check sit in one rounded box, so nothing pokes out of the corners.
            val inner = FrameLayout(context).apply {
                clipToOutline = true
                outlineProvider = rounded(12)
            }
            inner.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            inner.addView(text(style.label, 14f, bold = true).apply {
                setPadding(px(10), px(14), px(10), px(7))
                setBackgroundResource(R.drawable.bg_video_scrim)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            val mark = ImageView(context).apply {
                setImageResource(R.drawable.ic_dialog_check)
                setBackgroundResource(R.drawable.bg_video_badge)
                setPadding(px(5), px(5), px(5), px(5))
                visibility = if (style.id == chosen.style) View.VISIBLE else View.GONE
            }
            checks[style.id] = mark
            inner.addView(mark, FrameLayout.LayoutParams(px(26), px(26), Gravity.TOP or Gravity.END).apply { setMargins(0, px(7), px(7), 0) })
            card.addView(inner, FrameLayout.LayoutParams(px(168), px(94)))
            card.setOnClickListener {
                chosen = chosen.copy(style = style.id)
                checks.forEach { (id, v) -> v.visibility = if (id == style.id) View.VISIBLE else View.GONE }
            }
            grid.addView(card, GridLayout.LayoutParams().apply { setMargins(0, 0, px(8), px(8)) })
        }

        // ---------- Color and cycling ----------
        val colorName = text(ShaderPalettes.find(chosen.color).label, 14f, soft).apply { setPadding(px(4), px(6), 0, 0) }
        val rings = HashMap<String, View>()
        val swatches = GridLayout(context).apply { columnCount = 5 }
        fun paintRings() {
            rings.forEach { (id, v) ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ShaderPalettes.find(id).swatch)
                    setStroke(px(if (id == chosen.color) 4 else 0), Color.WHITE)
                }
            }
        }
        ShaderPalettes.all.forEach { palette ->
            val dot = View(context)
            rings[palette.id] = dot
            val tile = FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                isClickable = true
                contentDescription = palette.label
                setPadding(px(6), px(6), px(6), px(6))
                addView(dot, FrameLayout.LayoutParams(px(32), px(32)))
                setOnFocusChangeListener { _, focused -> if (focused) colorName.text = palette.label }
                setOnClickListener {
                    chosen = chosen.copy(color = palette.id)
                    colorName.text = palette.label
                    paintRings()
                    previews.forEach { (_, view) -> view.setPalette(palette) }
                }
            }
            swatches.addView(tile, GridLayout.LayoutParams().apply { setMargins(0, 0, px(4), px(4)) })
        }
        paintRings()

        val cycleChips = ArrayList<Pair<Boolean, TextView>>()
        fun paintCycle() {
            cycleChips.forEach { (value, chip) ->
                val on = value == chosen.cycle
                chip.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
                chip.setTextColor(if (on) Color.WHITE else soft)
            }
        }
        val cycleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, value) in listOf("Off" to false, "On" to true)) {
            val chip = text(label, 18f).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    chosen = chosen.copy(cycle = value)
                    paintCycle()
                    previews.forEach { (_, view) -> view.setCycle(value) }
                }
            }
            cycleChips += value to chip
            cycleRow.addView(chip, LinearLayout.LayoutParams(px(84), px(46)).apply { marginEnd = px(8) })
        }
        paintCycle()

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 18f).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(if (primary) R.drawable.bg_dialog_button_primary else R.drawable.bg_dialog_button_secondary)
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button("Cancel", false) { dialog.dismiss() }, LinearLayout.LayoutParams(px(120), px(48)).apply { marginEnd = px(10) })
            addView(button("Done", true) { dialog.dismiss(); onDone(chosen) }, LinearLayout.LayoutParams(px(120), px(48)))
        }

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Color", 16f, soft).apply { setPadding(px(4), 0, 0, px(6)) })
            addView(swatches)
            addView(colorName)
            addView(text("Color cycling", 16f, soft).apply { setPadding(px(4), px(18), 0, px(6)) })
            addView(cycleRow)
            addView(text("Slowly turns the colors round the wheel, starting from the one chosen.", 13f, 0xFF8888A0.toInt())
                .apply { setPadding(px(4), px(8), 0, 0) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(buttons)
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = px(14) })
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_about_panel)
            setPadding(px(24), px(18), px(24), px(14))
            addView(text(title, 24f, bold = true).apply { setPadding(px(4), 0, 0, px(12)) })
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dialog.setContentView(FrameLayout(context).apply {
            setPadding(px(14), px(12), px(14), px(12))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener {
            previews.forEach { (_, view) -> view.onResume() }
            // Start on the style in use, so the remote begins where you are.
            val at = ShaderStyles.all.indexOfFirst { it.id == chosen.style }.coerceAtLeast(0)
            grid.getChildAt(at)?.requestFocus()
        }
        dialog.setOnDismissListener { previews.forEach { (_, view) -> view.onPause() } }
        dialog.show()
    }

    private const val FAST_MS = 16L
    private const val SLOW_MS = 120L
}
