package com.portal.tv.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.portal.tv.R

/**
 * Every dialog in Portal. It looks like the About card (a solid rounded panel on a dimmed screen), lists are
 * big rows with a clear focus ring, and the buttons are pills. It is used like a standard alert dialog: the
 * [Builder] has the same methods, so turning one into the other is a matter of changing its name.
 *
 * A dialog with only a message gets centered text and equal buttons; one with a list or a view of its own is
 * left-aligned. [Builder.setDestructive] makes the main button red, adds a bin icon, and starts the remote's
 * focus on Cancel so a stray press can't delete anything.
 */
class PortalDialog private constructor(context: Context, private val spec: Spec) :
    Dialog(context, R.style.Theme_Portal_Dialog) {

    /** What a [Builder] has collected. */
    private class Spec {
        var title: CharSequence? = null
        var message: CharSequence? = null
        var view: View? = null
        var items: Array<out CharSequence>? = null
        var checked = -1
        var singleChoice = false
        var itemListener: DialogInterface.OnClickListener? = null
        val buttons = LinkedHashMap<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>()
        var cancelable = true
        var onCancel: DialogInterface.OnCancelListener? = null
        var onDismiss: DialogInterface.OnDismissListener? = null
        var destructive = false
        var widthDp = 560
    }

    private val buttonViews = HashMap<Int, TextView>()
    private val rows = ArrayList<LinearLayout>()
    private var defaultFocus: View? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        setCancelable(spec.cancelable)
        spec.onCancel?.let { setOnCancelListener(it) }
        spec.onDismiss?.let { setOnDismissListener(it) }
        setContentView(buildPanel())
        setOnShowListener { defaultFocus?.requestFocus() }
    }

    /** The button for [which] (one of [DialogInterface.BUTTON_POSITIVE], [DialogInterface.BUTTON_NEGATIVE], [DialogInterface.BUTTON_NEUTRAL]). */
    fun getButton(which: Int): TextView = buttonViews[which] ?: error("This dialog has no such button")

    override fun onStart() {
        super.onStart()
        // The window's own width is narrower than the panel wants, so say what is wanted.
        val screen = context.resources.displayMetrics.widthPixels
        window?.setLayout(minOf(dp(spec.widthDp), screen - dp(96)), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    // ---------- Building the panel ----------

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = context.resources.getFont(R.font.inter)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildPanel(): View {
        val messageOnly = spec.view == null && spec.items == null
        val textGravity = if (messageOnly) Gravity.CENTER_HORIZONTAL else Gravity.START
        val white = 0xFFFFFFFF.toInt()
        val soft = 0xFFB0B0C0.toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_about_panel)
            setPadding(dp(28), dp(26), dp(28), dp(22))
        }

        if (spec.destructive) {
            panel.addView(FrameLayout(context).apply {
                background = context.getDrawable(R.drawable.bg_dialog_icon)
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_dialog_trash)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
                )
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = if (messageOnly) Gravity.CENTER_HORIZONTAL else Gravity.START
                bottomMargin = dp(12)
            })
        }

        spec.title?.let {
            panel.addView(text(24f, white, bold = true).apply { this.text = it; gravity = textGravity }, matchWrap())
        }
        spec.message?.let {
            panel.addView(text(17f, soft).apply {
                this.text = it
                gravity = textGravity
                setLineSpacing(dp(3).toFloat(), 1f)
            }, matchWrap().apply { topMargin = dp(8) })
        }

        val body: View? = spec.items?.let { buildList(it) } ?: spec.view?.also { (it.parent as? ViewGroup)?.removeView(it) }
        if (body != null) {
            val limit = (context.resources.displayMetrics.heightPixels * 0.5).toInt()
            panel.addView(MaxHeightScrollView(context, limit).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(body)
            }, matchWrap().apply { topMargin = dp(18) })
        }

        if (spec.buttons.isNotEmpty()) panel.addView(buildButtons(messageOnly), matchWrap().apply { topMargin = dp(24) })

        // Where the remote starts: the chosen row or the first, Cancel for a deletion, the main button otherwise.
        defaultFocus = when {
            rows.isNotEmpty() -> rows.firstOrNull { it.isActivated } ?: rows.first()
            spec.destructive -> buttonViews[DialogInterface.BUTTON_NEGATIVE] ?: buttonViews[DialogInterface.BUTTON_POSITIVE]
            messageOnly -> buttonViews[DialogInterface.BUTTON_POSITIVE] ?: buttonViews[DialogInterface.BUTTON_NEGATIVE]
            else -> null
        }
        return panel
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun buildList(items: Array<out CharSequence>): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val accessories = ArrayList<ImageView>()
        items.forEachIndexed { index, label ->
            val chosen = spec.singleChoice && index == spec.checked
            val accessory = ImageView(context).apply {
                setImageResource(if (spec.singleChoice) R.drawable.ic_dialog_check else R.drawable.ic_dialog_chevron)
                alpha = if (spec.singleChoice) 1f else 0.6f
                visibility = if (spec.singleChoice && !chosen) View.INVISIBLE else View.VISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            accessories += accessory
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = context.getDrawable(R.drawable.bg_dialog_tile)
                setPadding(dp(18), dp(12), dp(14), dp(12))
                minimumHeight = dp(56)
                isFocusable = true
                isClickable = true
                isActivated = chosen
                addView(text(18f, 0xFFFFFFFF.toInt()).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(accessory, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
                setOnClickListener {
                    if (spec.singleChoice) {
                        rows.forEachIndexed { i, other ->
                            other.isActivated = i == index
                            accessories[i].visibility = if (i == index) View.VISIBLE else View.INVISIBLE
                        }
                    }
                    spec.itemListener?.onClick(this@PortalDialog, index)
                    // A plain list closes when a row is chosen; a single-choice list leaves that to its listener.
                    if (!spec.singleChoice) dismiss()
                }
            }
            rows += row
            list.addView(row, matchWrap().apply { if (index < items.size - 1) bottomMargin = dp(8) })
        }
        return list
    }

    private fun buildButtons(messageOnly: Boolean): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (messageOnly) Gravity.CENTER else Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun add(which: Int, background: Int, leftSide: Boolean = false) {
            val (label, listener) = spec.buttons[which] ?: return
            val button = text(18f, 0xFFFFFFFF.toInt()).apply {
                text = label
                setTextColor(context.getColorStateList(R.color.dialog_button_text))
                gravity = Gravity.CENTER
                this.background = context.getDrawable(background)
                minHeight = dp(52)
                minWidth = dp(120)
                setPadding(dp(26), dp(10), dp(26), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    listener?.onClick(this@PortalDialog, which)
                    dismiss()
                }
            }
            buttonViews[which] = button
            val params = if (messageOnly) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (bar.childCount > 0 && !leftSide) params.marginStart = dp(10)
            bar.addView(button, params)
            if (leftSide) bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // pushes the rest to the right
        }
        add(DialogInterface.BUTTON_NEUTRAL, R.drawable.bg_dialog_button_secondary, leftSide = !messageOnly)
        add(DialogInterface.BUTTON_NEGATIVE, R.drawable.bg_dialog_button_secondary)
        add(DialogInterface.BUTTON_POSITIVE, if (spec.destructive) R.drawable.bg_dialog_button_danger else R.drawable.bg_dialog_button_primary)
        return bar
    }

    /** A scroll view that never grows past [maxHeight], so a long list scrolls instead of filling the screen. */
    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    // ---------- The builder ----------

    class Builder(val context: Context) {
        private val spec = Spec()

        fun setTitle(title: CharSequence?) = apply { spec.title = title }
        fun setMessage(message: CharSequence?) = apply { spec.message = message }
        fun setView(view: View) = apply { spec.view = view }

        fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?) = apply {
            spec.items = items
            spec.singleChoice = false
            spec.itemListener = listener
        }

        fun setSingleChoiceItems(items: Array<out CharSequence>, checked: Int, listener: DialogInterface.OnClickListener?) = apply {
            spec.items = items
            spec.singleChoice = true
            spec.checked = checked
            spec.itemListener = listener
        }

        fun setPositiveButton(label: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { spec.buttons[DialogInterface.BUTTON_POSITIVE] = label to listener }

        fun setNegativeButton(label: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { spec.buttons[DialogInterface.BUTTON_NEGATIVE] = label to listener }

        fun setNeutralButton(label: CharSequence, listener: DialogInterface.OnClickListener?) =
            apply { spec.buttons[DialogInterface.BUTTON_NEUTRAL] = label to listener }

        fun setCancelable(cancelable: Boolean) = apply { spec.cancelable = cancelable }
        fun setOnCancelListener(listener: DialogInterface.OnCancelListener) = apply { spec.onCancel = listener }
        fun setOnDismissListener(listener: DialogInterface.OnDismissListener) = apply { spec.onDismiss = listener }

        /** For deleting or removing: a red main button and a bin icon, with focus starting on Cancel. */
        fun setDestructive() = apply { spec.destructive = true }

        /** How wide the panel is, for content that needs more than the usual room. */
        fun setWidthDp(width: Int) = apply { spec.widthDp = width }

        fun create(): PortalDialog = PortalDialog(context, spec)
        fun show(): PortalDialog = create().also { it.show() }
    }
}
