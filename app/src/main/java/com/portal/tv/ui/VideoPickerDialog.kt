package com.portal.tv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.background.VideoThumbnails
import kotlinx.coroutines.launch

/**
 * A window to choose the background video from, for when there are too many to line up in a row: a grid of
 * thumbnails (with a speaker on those that have sound) where the video now in use is marked and has focus first.
 * OK on one chooses it and closes the window.
 */
object VideoPickerDialog {

    private const val COLUMNS = 4

    fun show(
        context: Context,
        owner: LifecycleOwner,
        videos: List<BackgroundRepository.Companion.VideoChoice>,
        current: String,
        onPick: (BackgroundRepository.Companion.VideoChoice) -> Unit
    ) {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        lateinit var dialog: PortalDialog

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
                val thumb: ImageView = view.findViewById(R.id.thumb)
                val label: TextView = view.findViewById(R.id.label)
                val chosen: View = view.findViewById(R.id.badge)
                val sound: View = view.findViewById(R.id.sound)
                var bound: String? = null
            }

            override fun getItemCount() = videos.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screensaver_video, parent, false)
                view.findViewById<View>(R.id.dim).visibility = View.GONE
                return Holder(view)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                holder as Holder
                val video = videos[position]
                holder.bound = video.asset
                holder.label.text = video.label
                holder.chosen.visibility = if (video.asset == current) View.VISIBLE else View.GONE
                holder.sound.visibility = if (VideoThumbnails.peekAudio(video.asset) == true) View.VISIBLE else View.GONE
                holder.thumb.setImageDrawable(null)
                holder.itemView.contentDescription = video.label + if (video.asset == current) ", in use" else ""
                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    onPick(video)
                }
                owner.lifecycleScope.launch {
                    VideoThumbnails.load(context, video.asset)?.let { if (holder.bound == video.asset) holder.thumb.setImageBitmap(it) }
                    val has = VideoThumbnails.hasAudio(context, video.asset)
                    if (holder.bound == video.asset) holder.sound.visibility = if (has) View.VISIBLE else View.GONE
                }
            }
        }

        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            this.adapter = adapter
            clipToPadding = false
            setPadding(px(4), px(4), px(4), px(4))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        // The dialog measures its view without a height limit, so give the grid a box of its own: tall enough for its
        // rows, up to what the screen allows, after which the grid scrolls.
        val rows = (videos.size + COLUMNS - 1) / COLUMNS
        val height = (rows * px(150) + px(8)).coerceAtMost((context.resources.displayMetrics.heightPixels * 0.6).toInt())
        val box = FrameLayout(context).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }

        dialog = PortalDialog.Builder(context)
            .setTitle("Choose a video")
            .setView(box)
            .setWidthDp(880)
            .setBodyMaxHeightFraction(0.7f)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        // Start on the video in use, so the remote begins where you are.
        val at = videos.indexOfFirst { it.asset == current }.coerceAtLeast(0)
        grid.scrollToPosition(at)
        grid.post { grid.post { (grid.findViewHolderForAdapterPosition(at)?.itemView ?: grid.getChildAt(0))?.requestFocus() } }
    }
}
