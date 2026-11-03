package com.portal.tv.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.data.Profile
import com.portal.tv.repo.ProfileManager

/**
 * RecyclerView adapter for the "Who's watching?" profile picker screen.
 *
 * Renders each profile as an avatar (the Google account photo when there is
 * one, then the chosen built-in avatar, otherwise the initial on a tinted circle), the name below, and a
 * Google "G" before the name for account-linked profiles. The last cell is an "Add New"
 * tile so users can always reach profile creation from the grid.
 *
 * Remote controls:
 *  - D-pad + OK: select a profile / add a new one
 *  - Hold OK, or Menu (⋮), on a profile: rename / delete options
 */
class ProfileAdapter(
    private val profiles: List<Profile>,
    private val activeProfileId: Int?,
    private val onSelect: (Profile) -> Unit,
    private val onAddNew: () -> Unit,
    private val onLongPress: (Profile) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PROFILE = 0
        private const val TYPE_ADD = 1

        fun colorFor(index: Int): Int = ProfileAvatar.colorFor(index)
    }

    class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarBg: FrameLayout = itemView.findViewById(R.id.profile_avatar_bg)
        val avatarText: TextView = itemView.findViewById(R.id.profile_avatar_text)
        val nameText: TextView = itemView.findViewById(R.id.profile_name)
        val kidsIcon: View = itemView.findViewById(R.id.kids_icon)
        val googleIcon: View = itemView.findViewById(R.id.google_icon)
        val activeRing: View = itemView.findViewById(R.id.active_ring)
    }

    class AddViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int =
        if (position < profiles.size) TYPE_PROFILE else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_PROFILE) {
            ProfileViewHolder(inflater.inflate(R.layout.item_profile, parent, false))
        } else {
            AddViewHolder(inflater.inflate(R.layout.item_profile_add, parent, false))
        }.also { holder ->
            // Make the focused tile obvious from across the room.
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.1f else 1f)
                    .scaleY(if (hasFocus) 1.1f else 1f).setDuration(120).start()
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener { onAddNew() }
            return
        }

        val vh = holder as ProfileViewHolder
        val profile = profiles[position]
        bindAvatar(vh, profile)

        vh.nameText.text = profile.name
        vh.googleIcon.visibility = if (profile.isGoogle) View.VISIBLE else View.GONE
        vh.kidsIcon.visibility = if (profile.isKids) View.VISIBLE else View.GONE
        vh.activeRing.visibility =
            if (profile.id == activeProfileId) View.VISIBLE else View.INVISIBLE

        vh.itemView.setOnClickListener { onSelect(profile) }
        vh.itemView.setOnLongClickListener { onLongPress(profile); true }
        // OK/Enter is handled by the click and long-click listeners above, so a
        // press-and-hold on OK opens the options. Menu opens them directly.
        vh.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                onLongPress(profile)
                true
            } else false
        }
    }

    private fun bindAvatar(vh: ProfileViewHolder, profile: Profile) {
        val context = vh.itemView.context
        val bitmap = ProfileManager.photoPath(context, profile)
            ?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap != null) {
            vh.avatarBg.background = RoundedBitmapDrawableFactory
                .create(context.resources, bitmap).apply { isCircular = true }
            vh.avatarText.text = ""
        } else if (AvatarArt.find(profile.avatarId) != null) {
            vh.avatarBg.background = AvatarArt.drawable(profile.avatarId!!)
            vh.avatarText.text = ""
        } else {
            vh.avatarBg.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorFor(profile.colorIndex))
            }
            vh.avatarText.text = profile.name.take(1).uppercase()
        }
    }

    /** Profile cells plus one trailing "Add New" tile. */
    override fun getItemCount(): Int = profiles.size + 1
}
