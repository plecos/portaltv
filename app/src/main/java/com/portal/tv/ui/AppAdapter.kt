package com.portal.tv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.data.AppInfo
import com.portal.tv.settings.AppSettings

/**
 * Draws apps in the chosen layout: icon with title, icon only, or a list row
 * with the title beside the icon. Used for both the Frequently Used and All
 * Apps sections.
 */
class AppAdapter(
    private val apps: List<AppInfo>,
    private val layout: String,
    private val onAppClick: (AppInfo, ImageView) -> Unit,
    private val onAppLongClick: ((AppInfo) -> Unit)? = null
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val label: TextView? = itemView.findViewById(R.id.app_label)
        /** What an extension put on its tile: the layouts that have room for each have these views. */
        val badge: TextView? = itemView.findViewById(R.id.app_badge)
        val subtitle: TextView? = itemView.findViewById(R.id.app_subtitle)
    }

    companion object {
        /** Width in dp of one item, so rows and grids can fit whole columns. */
        fun itemWidthDp(layout: String): Int = when (layout) {
            AppSettings.LAYOUT_ICONS -> 112
            AppSettings.LAYOUT_LIST -> 420
            else -> 128
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val res = when (layout) {
            AppSettings.LAYOUT_ICONS -> R.layout.item_app_icon
            AppSettings.LAYOUT_LIST -> R.layout.item_app_list
            else -> R.layout.item_app
        }
        val holder = AppViewHolder(LayoutInflater.from(parent.context).inflate(res, parent, false))
        // The selected item lifts slightly under the glass highlight.
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.03f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        }
        return holder
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.label?.text = app.label
        holder.badge?.let { it.text = app.badge.orEmpty(); it.visibility = if (app.badge != null) View.VISIBLE else View.GONE }
        holder.subtitle?.let { it.text = app.subtitle.orEmpty(); it.visibility = if (app.subtitle != null) View.VISIBLE else View.GONE }
        holder.itemView.contentDescription = listOfNotNull(app.label, app.badge, app.subtitle).joinToString(", ")

        // Select opens the app; holding select (or the remote's menu key) opens the app menu.
        holder.itemView.setOnClickListener { onAppClick(app, holder.icon) }
        if (onAppLongClick != null) {
            holder.itemView.isLongClickable = true
            holder.itemView.setOnLongClickListener { onAppLongClick.invoke(app); true }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onAppLongClick.invoke(app)
                    true
                } else false
            }
        } else {
            holder.itemView.isLongClickable = false
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.setOnKeyListener(null)
        }
    }

    override fun getItemCount(): Int = apps.size
}
