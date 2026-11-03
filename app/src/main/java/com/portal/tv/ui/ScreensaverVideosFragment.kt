package com.portal.tv.ui

import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.background.VideoThumbnails
import com.portal.tv.screensaver.ScreensaverSettings
import com.portal.tv.settings.VideoSound
import com.portal.tv.video.DownloadedVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chooses which videos the screen saver plays. A grid of thumbnails, each marked when it is included; OK turns one
 * on or off. The video that has focus plays in a larger preview at the side, with its title and credit, using the
 * screen saver's own sound setting so what is heard is what the saver will play.
 */
class ScreensaverVideosFragment : Fragment() {

    private class Entry(val asset: String, val label: String, val credit: String)

    private lateinit var entries: List<Entry>
    private var included: Set<String> = emptySet()
    private lateinit var adapter: Adapter

    private lateinit var texture: TextureView
    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var credit: TextView
    private lateinit var state: TextView
    private lateinit var count: TextView

    private var focused: Entry? = null
    private var previewJob: Job? = null
    private var player: MediaPlayer? = null
    private var pending: File? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_screensaver_videos, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        texture = view.findViewById(R.id.pv_texture)
        poster = view.findViewById(R.id.pv_poster)
        title = view.findViewById(R.id.pv_title)
        credit = view.findViewById(R.id.pv_credit)
        state = view.findViewById(R.id.pv_state)
        count = view.findViewById(R.id.sv_count)

        val downloads = DownloadedVideos.all(context).associateBy { it.file }
        entries = BackgroundRepository.availableVideos(context).map { choice ->
            val item = downloads[choice.asset]
            Entry(
                choice.asset, choice.label,
                if (item != null) "By ${item.creator} on ${item.provider}" else "Comes with Portal"
            )
        }
        included = ScreensaverSettings.enabledVideos(context).toSet()

        adapter = Adapter()
        view.findViewById<RecyclerView>(R.id.sv_grid).apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = this@ScreensaverVideosFragment.adapter
            post { getChildAt(0)?.requestFocus() }
        }
        view.findViewById<TextView>(R.id.sv_all).setOnClickListener {
            entries.forEach { ScreensaverSettings.setVideoEnabled(context, it.asset, true) }
            refresh()
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                pending?.let { pending = null; play(it) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        refreshCount()
        showState(null)
    }

    override fun onResume() {
        super.onResume()
        focused?.let { schedulePreview(it) }
    }

    override fun onPause() {
        super.onPause()
        previewJob?.cancel()
        releasePlayer()
    }

    override fun onDestroyView() {
        previewJob?.cancel()
        releasePlayer()
        super.onDestroyView()
    }

    // ---------- Including and removing ----------

    private fun toggle(entry: Entry) {
        val context = requireContext()
        if (entry.asset in included && included.size <= 1) {
            Toast.makeText(context, "Keep at least one video", Toast.LENGTH_SHORT).show()
            return
        }
        ScreensaverSettings.setVideoEnabled(context, entry.asset, entry.asset !in included)
        refresh()
    }

    private fun refresh() {
        included = ScreensaverSettings.enabledVideos(requireContext()).toSet()
        adapter.notifyDataSetChanged()
        refreshCount()
        showState(focused)
    }

    private fun refreshCount() {
        count.text = "${included.size} of ${entries.size} included in the screen saver"
    }

    private fun showState(entry: Entry?) {
        title.text = entry?.label.orEmpty()
        credit.text = entry?.credit.orEmpty()
        val on = entry != null && entry.asset in included
        state.visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
        state.text = if (on) "Included in the screen saver" else "Not included"
        state.background = GradientDrawable().apply {
            cornerRadius = 100f * resources.displayMetrics.density
            setColor(if (on) 0xFF2E8B57.toInt() else 0x33FFFFFF)
        }
    }

    // ---------- The preview ----------

    private fun onFocused(entry: Entry) {
        if (focused === entry) return
        focused = entry
        showState(entry)
        // The picture shows at once; the video starts once focus has settled, so moving quickly doesn't thrash the decoder.
        poster.alpha = 1f
        poster.setImageDrawable(null)
        viewLifecycleOwner.lifecycleScope.launch {
            VideoThumbnails.load(requireContext(), entry.asset)?.let { if (focused === entry) poster.setImageBitmap(it) }
        }
        schedulePreview(entry)
    }

    private fun schedulePreview(entry: Entry) {
        previewJob?.cancel()
        releasePlayer()
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            val context = requireContext()
            val file = withContext(Dispatchers.IO) {
                val repo = BackgroundRepository(context, entry.asset)
                if (repo.extractBundledVideo()) repo.getVideoFile() else null
            } ?: return@launch
            if (focused !== entry) return@launch
            if (texture.isAvailable) play(file) else pending = file
        }
    }

    private fun play(file: File) {
        releasePlayer()
        val volume = VideoSound.volume(ScreensaverSettings.sound(requireContext()))
        try {
            val mp = MediaPlayer()
            mp.setSurface(Surface(texture.surfaceTexture))
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setVolume(volume, volume)
            mp.isLooping = true
            mp.setDataSource(file.path)
            mp.setOnPreparedListener {
                // A video that can't seek back to its start can't loop, so play it again from scratch at its end.
                if (it.duration <= 0) {
                    it.isLooping = false
                    it.setOnCompletionListener { _ -> if (player === it) play(file) }
                }
                it.start()
                poster.animate().alpha(0f).setDuration(250).start()
                captureStill()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w("Portal", "Preview video error $what/$extra")
                releasePlayer()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("Portal", "Preview failed: ${e.message}")
            releasePlayer()
        }
    }

    /**
     * If the video has no picture yet (the usual way of reading one failed for it), take one from the preview once it
     * is playing, so its card and the pane show something from then on.
     */
    private fun captureStill() {
        val entry = focused ?: return
        if (VideoThumbnails.peek(entry.asset) != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (focused !== entry || player == null || !texture.isAvailable) return@launch
            val frame = texture.getBitmap(320, 180) ?: return@launch
            Log.d("Portal", "Thumbnail for ${entry.asset}: taken from the preview")
            VideoThumbnails.store(requireContext(), entry.asset, frame)
            adapter.notifyItemChanged(entries.indexOf(entry))
        }
    }

    private fun releasePlayer() {
        pending = null
        player?.let { runCatching { it.release() } }
        player = null
        if (::poster.isInitialized) poster.alpha = 1f
    }

    // ---------- The grid ----------

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.thumb)
            val dim: View = view.findViewById(R.id.dim)
            val label: TextView = view.findViewById(R.id.label)
            val badge: View = view.findViewById(R.id.badge)
            var bound: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_screensaver_video, parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            val on = entry.asset in included
            holder.bound = entry.asset
            holder.label.text = entry.label
            holder.dim.visibility = if (on) View.GONE else View.VISIBLE
            holder.badge.visibility = if (on) View.VISIBLE else View.GONE
            holder.thumb.setImageDrawable(null)
            viewLifecycleOwner.lifecycleScope.launch {
                VideoThumbnails.load(holder.itemView.context, entry.asset)?.let {
                    if (holder.bound == entry.asset) holder.thumb.setImageBitmap(it)
                }
            }
            holder.itemView.setOnClickListener { toggle(entry) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(entry) }
            holder.itemView.contentDescription = entry.label + if (on) ", included" else ", not included"
        }
    }
}
