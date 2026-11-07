package com.portal.tv.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.extensions.ExtensionItems
import com.portal.tv.extensions.ShownItem

/**
 * Draws the items extensions put on the home screen. Portal makes every view itself from the words and icon the
 * extension gave, in Portal's own look; an extension never supplies a view.
 */
object ExtensionItemViews {

    /** Fills [container] with [items] (and hides it when there are none). [onOpen] runs when a button is pressed, [onInfo] on a long press. */
    fun fill(
        container: LinearLayout,
        items: List<ShownItem>,
        onOpen: (ShownItem) -> Unit,
        onInfo: (ShownItem) -> Unit
    ) {
        container.removeAllViews()
        container.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEachIndexed { index, shown ->
            val view = if (shown.item.isButton) button(container.context, shown, onOpen) else line(container.context, shown)
            view.setOnLongClickListener { onInfo(shown); true }
            if (index > 0) (view.layoutParams as ViewGroup.MarginLayoutParams).marginStart = (8 * container.resources.displayMetrics.density).toInt()
            container.addView(view)
        }
    }

    private fun px(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun button(context: Context, shown: ShownItem, onOpen: (ShownItem) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(context, 48)
            setPadding(px(context, 14), 0, px(context, 16), 0)
            setBackgroundResource(R.drawable.bg_app_tile)
            isFocusable = true
            isClickable = true
            contentDescription = "${shown.item.text}, from ${shown.extensionName}"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(context, 48))
            setOnClickListener { onOpen(shown) }
        }
        ExtensionItems.iconOf(context, shown)?.let { icon ->
            row.addView(ImageView(context).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(px(context, 26), px(context, 26)).apply { marginEnd = px(context, 8) }
            })
        }
        row.addView(label(context, shown.item.text, 16f, R.color.text_primary))
        row.addView(mark(context))
        return row
    }

    /**
     * A line of text. Pressing it does nothing, but the remote can stop on it so a long press can say whose it is (an
     * extension's text looks like part of Portal, so there must be a way to find out).
     */
    private fun line(context: Context, shown: ShownItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(context, 8), 0, px(context, 8), 0)
            contentDescription = "${shown.item.text}, from ${shown.extensionName}"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(context, 48))
            setBackgroundResource(R.drawable.bg_app_tile)
            isFocusable = true
            isClickable = true
        }
        row.addView(label(context, shown.item.text, 16f, R.color.text_primary))
        row.addView(mark(context))
        return row
    }

    /**
     * A small puzzle piece on every item an extension adds, so it never looks exactly like one of Portal's own buttons
     * (Portal's own never carry it). The long press still says whose it is.
     */
    private fun mark(context: Context) = ImageView(context).apply {
        setImageResource(R.drawable.ic_cat_extensions)
        alpha = 0.7f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(px(context, 14), px(context, 14)).apply { marginStart = px(context, 8) }
    }

    private fun label(context: Context, text: String, sp: Float, color: Int) = TextView(context).apply {
        this.text = text
        textSize = sp
        typeface = context.resources.getFont(R.font.inter)
        setTextColor(context.resources.getColor(color, null))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = px(context, 260)
    }
}
