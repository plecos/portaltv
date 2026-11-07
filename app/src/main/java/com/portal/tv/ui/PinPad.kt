package com.portal.tv.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.repo.ParentPin
import com.portal.tv.repo.PinEntry

/**
 * A PIN entry made for a remote with someone watching. Up and Down choose a row of numbers; Left,
 * OK or Right then choose the first, second or third number in it. A press shows nothing on the
 * pad itself, only one more dot, and the PIN shows only as dots, so a child can't read the code off the screen
 * or the buttons.
 *
 * The PIN is always four digits and is submitted on the fourth, so there is no Done button to
 * press afterwards. The last row is a Delete icon, 0 and a Cancel icon.
 */
object PinPad {

    private const val LAST_ROW = 3

    fun ask(
        context: Context,
        title: String,
        message: String? = null,
        onDone: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val entry = PinEntry()
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            textSize = sp
            typeface = context.resources.getFont(R.font.inter)
            setTextColor(context.resources.getColor(color, null))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val dots = PinDotsView(context, ParentPin.LENGTH)
        fun showDots() { dots.entered = entry.length }
        showDots()

        lateinit var dialog: PortalDialog
        var finished = false
        fun finish(result: String?) {
            if (finished) return
            finished = true
            dialog.dismiss()
            if (result != null) onDone(result) else onCancel()
        }

        fun press(row: Int, column: Int) {
            // Nothing on the pad moves or lights up on a press, so nobody watching can see which row or key it was.
            // Only the dots change.
            if (row == LAST_ROW && column == 0) {
                entry.delete(); showDots()
            } else if (row == LAST_ROW && column == 2) {
                finish(null)
            } else {
                PinEntry.digitAt(row, column)?.let { entry.add(it) }
                showDots()
                if (entry.length == ParentPin.LENGTH) finish(entry.value)
            }
        }

        val rows = mutableListOf<LinearLayout>()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(8))
            if (message != null) addView(text(14f, R.color.text_secondary).apply {
                this.text = message
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(4))
            })
            addView(dots, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(44)).apply { topMargin = px(4); bottomMargin = px(8) })
            addView(text(13f, R.color.text_secondary).apply {
                this.text = "Up and Down pick a row. Left, OK or Right pick a number.\n" +
                    "On the top row, Up picks 2. On the bottom row, Down picks 0."
                gravity = Gravity.CENTER
                setLineSpacing(px(2).toFloat(), 1f)
                setPadding(0, 0, 0, px(12))
            })
        }

        for (r in 0..LAST_ROW) {
            val labels = if (r < LAST_ROW) PinEntry.ROWS[r].map { it.toString() }
            else listOf("", "0", "") // Delete and Cancel are drawn as icons
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.bg_app_tile)
                setPadding(px(4), px(2), px(4), px(2))
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val column = when {
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> 0
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> 1
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> 2
                        // There is no row above the first or below the last, so those presses pick the middle number (2 and 0).
                        keyCode == KeyEvent.KEYCODE_DPAD_UP && r == 0 -> 1
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && r == LAST_ROW -> 1
                        else -> return@setOnKeyListener false
                    }
                    if (event.repeatCount == 0) press(r, column)
                    true
                }
            }
            labels.forEachIndexed { column, label ->
                val icon = if (r == LAST_ROW) when (column) {
                    0 -> R.drawable.ic_backspace
                    2 -> R.drawable.ic_close
                    else -> null
                } else null
                val key: View = if (icon != null) {
                    android.widget.ImageView(context).apply {
                        setImageResource(icon)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        setPadding(px(10), px(10), px(10), px(10))
                        contentDescription = if (column == 0) "Delete" else "Cancel"
                    }
                } else {
                    text(22f, R.color.text_primary).apply {
                        this.text = label
                        gravity = Gravity.CENTER
                        setBackgroundResource(R.drawable.bg_pin_key)
                    }
                }
                // Each key is a round button in the middle of its third of the row.
                val cell = android.widget.FrameLayout(context).apply {
                    addView(key, android.widget.FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER))
                    setOnClickListener { press(r, column) } // touch and mouse
                }
                row.addView(cell, LinearLayout.LayoutParams(0, px(50), 1f))
            }
            rows += row
            box.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(4) })
        }

        dialog = PortalDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setBodyMaxHeightFraction(0.8f)
            .setOnCancelListener { finish(null) }
            .create()
        dialog.show()
        rows[1].requestFocus()
    }

    /** The shortest PIN [ParentPin] accepts, for messages. */
    val minLength get() = ParentPin.MIN_LENGTH
}
