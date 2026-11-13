package com.portal.tv.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.R
import com.portal.tv.background.VideoThumbnails
import com.portal.tv.settings.BackgroundSettings
import com.portal.tv.video.DownloadedVideos
import com.portal.tv.video.VideoCatalog
import com.portal.tv.video.VideoCatalog.Provider
import com.portal.tv.video.VideoCatalogClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Find a background video on Pexels or Pixabay and download it. Searching needs a free key from the
 * service, which the user pastes in once. Downloaded videos then show in the video list, with their
 * creator credited, and can be deleted here.
 */
class VideoBrowserFragment : Fragment() {

    private class Tab(val label: String, val provider: Provider?) {
        companion object {
            /** One tab for each service that is switched on, then Downloaded. */
            val all = VideoCatalog.ENABLED_PROVIDERS.map { Tab(it.label, it) } + Tab("Downloaded", null)
        }
    }

    private sealed class Entry {
        class Result(val hit: VideoCatalog.Hit) : Entry()
        class Saved(val item: DownloadedVideos.Item) : Entry()
        object More : Entry()
    }

    private var tab = Tab.all.first()
    private var query = ""
    private var page = 1
    private val entries = mutableListOf<Entry>()
    private var searching: Job? = null
    private var downloading: Job? = null
    private val images = object : LruCache<String, Bitmap>(48) {}

    private lateinit var tabViews: List<Pair<Tab, TextView>>
    private lateinit var adapter: Adapter
    private lateinit var message: TextView
    private lateinit var keyStatus: TextView
    private lateinit var keyButton: TextView
    private lateinit var searchRow: View
    private lateinit var queryField: EditText
    private lateinit var credit: TextView
    private lateinit var progress: View

    private val cancelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { downloading?.cancel() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_video_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cancelOnBack)
        val density = resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        message = view.findViewById(R.id.vb_message)
        keyStatus = view.findViewById(R.id.vb_key_status)
        keyButton = view.findViewById(R.id.vb_key_button)
        searchRow = view.findViewById(R.id.vb_search_row)
        queryField = view.findViewById(R.id.vb_query)
        credit = view.findViewById(R.id.vb_credit)
        progress = view.findViewById(R.id.vb_progress)

        tabViews = Tab.all.map { t ->
            val chip = TextView(requireContext()).apply {
                text = t.label
                textSize = 18f
                typeface = resources.getFont(R.font.inter)
                gravity = Gravity.CENTER
                setPadding(px(24), 0, px(24), 0)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48)).apply { marginEnd = px(8) }
                setOnClickListener { select(t) }
            }
            view.findViewById<LinearLayout>(R.id.vb_tabs).addView(chip)
            t to chip
        }

        adapter = Adapter()
        val results = view.findViewById<RecyclerView>(R.id.vb_results)
        results.layoutManager = GridLayoutManager(requireContext(), 4)
        results.adapter = adapter

        val search = { runSearch(reset = true) }
        view.findViewById<View>(R.id.vb_search).setOnClickListener { search() }
        queryField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                (requireContext().getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(queryField.windowToken, 0)
                search()
                true
            } else false
        }
        keyButton.setOnClickListener { askForKey() }
        view.findViewById<View>(R.id.vb_cancel).setOnClickListener { downloading?.cancel() }

        select(Tab.all.first())
    }

    // ---------- Tabs and searching ----------

    private fun select(next: Tab) {
        tab = next
        searching?.cancel()
        tabViews.forEach { (t, chip) ->
            val on = t == next
            chip.setBackgroundResource(if (on) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
            chip.setTextColor(resources.getColor(if (on) R.color.text_primary else R.color.text_secondary, null))
        }
        val provider = next.provider
        searchRow.visibility = if (provider != null) View.VISIBLE else View.GONE
        // Services that need no key have no key to add.
        keyButton.visibility = if (provider?.needsKey == true) View.VISIBLE else View.GONE
        keyStatus.visibility = keyButton.visibility
        credit.text = when (provider) {
            null -> ""
            VideoCatalog.Provider.NASA ->
                "Videos from NASA's Image and Video Library, generally in the public domain. NASA doesn't endorse Portal."
            else -> "Videos from ${provider.label}. Each is free to use; the creator is credited with every download."
        }
        queryField.hint = if (provider == VideoCatalog.Provider.NASA) "Search NASA, such as aurora, earth or moon (empty shows Earth from space)"
            else "Search for videos, such as aurora, fireplace or ocean"
        entries.clear()
        adapter.notifyDataSetChanged()
        if (provider == null) showDownloaded() else {
            refreshKey()
            if (VideoCatalogClient.hasKey(requireContext(), provider)) runSearch(reset = true)
            else showMessage("Add your free ${provider.label} key to search. Get one at ${provider.getKeyUrl}.")
        }
    }

    private fun refreshKey() {
        val provider = tab.provider ?: return
        val has = VideoCatalogClient.hasKey(requireContext(), provider)
        keyStatus.text = if (has) "${provider.label} key added" else "No ${provider.label} key yet"
        keyButton.text = if (has) "Change key" else "Add key"
    }

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun runSearch(reset: Boolean) {
        val provider = tab.provider ?: return
        if (!VideoCatalogClient.hasKey(requireContext(), provider)) {
            showMessage("Add your free ${provider.label} key to search. Get one at ${provider.getKeyUrl}.")
            return
        }
        if (reset) {
            query = queryField.text.toString().trim()
            page = 1
            entries.clear()
            adapter.notifyDataSetChanged()
        } else {
            page++
        }
        searching?.cancel()
        showMessage("Searching…")
        val forTab = tab
        searching = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val hits = VideoCatalogClient.search(requireContext(), provider, query, page)
                if (tab != forTab) return@launch
                entries.removeAll { it is Entry.More }
                val start = entries.size
                entries += hits.map { Entry.Result(it) }
                if (hits.size >= VideoCatalogClient.PER_PAGE / 2) entries += Entry.More
                adapter.notifyDataSetChanged()
                showMessage(if (entries.isEmpty()) "No videos found. Try another word." else null)
                if (reset && hits.isNotEmpty() && start == 0) view?.findViewById<RecyclerView>(R.id.vb_results)?.scrollToPosition(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tab == forTab) showMessage("Couldn't search: ${e.message ?: "something went wrong"}")
            }
        }
    }

    private fun showDownloaded() {
        val saved = DownloadedVideos.all(requireContext())
        entries.clear()
        entries += saved.map { Entry.Saved(it) }
        adapter.notifyDataSetChanged()
        showMessage(if (saved.isEmpty()) "Nothing downloaded yet. Find a video on Pexels or Pixabay and select it." else null)
        credit.text = if (saved.isEmpty()) "" else "Select a video to use it as the background or delete it."
    }

    private fun askForKey() {
        val provider = tab.provider ?: return
        val context = requireContext()
        val field = EditText(context).apply {
            hint = "${provider.label} key"
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val box = LinearLayout(context).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        PortalDialog.Builder(context)
            .setTitle("${provider.label} key")
            .setMessage("A free key from ${provider.getKeyUrl}. It stays on this TV, stored encrypted.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                VideoCatalogClient.setKey(context, provider, field.text.toString())
                select(tab)
            }
            .setNeutralButton("Remove key") { _, _ ->
                VideoCatalogClient.setKey(context, provider, null)
                select(tab)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Choosing a video ----------

    private fun onChoose(found: VideoCatalog.Hit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val hit = try {
                VideoCatalogClient.resolve(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Couldn't get that video", Toast.LENGTH_LONG).show()
                return@launch
            }
            val bytes = if (hit.bytes > 0) hit.bytes else DownloadedVideos.sizeOf(hit.fileUrl)
            if (bytes > DownloadedVideos.MAX_BYTES) {
                Toast.makeText(requireContext(), "That one is too big (${VideoCatalog.size(bytes)})", Toast.LENGTH_LONG).show()
                return@launch
            }
            askUse(
                title = hit.title,
                about = "By ${hit.creator} on ${hit.provider.label}\n" +
                    listOf(hit.details, VideoCatalog.size(bytes)).filter { it.isNotEmpty() }.joinToString(" · "),
                current = DownloadedVideos.USE_BACKGROUND,
                positive = "Download"
            ) { use -> download(hit, use) }
        }
    }

    /**
     * Asks what a video is for: the regular background, one of the holidays, or birthdays. A holiday video
     * plays on the day before and the day of that holiday, a birthday video on someone's birthday.
     */
    private fun askUse(title: String, about: String, current: String, positive: String, onPicked: (String) -> Unit) {
        val context = requireContext()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val white = 0xFFFFFFFF.toInt()
        val soft = 0xFFB0B0C0.toInt()
        val choices = DownloadedVideos.uses(context)
        var picked = choices.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val tiles = ArrayList<LinearLayout>()
        val checks = ArrayList<ImageView>()
        lateinit var dialog: PortalDialog

        val blurb = TextView(context).apply {
            textSize = 15f
            setTextColor(soft)
            setPadding(0, dp(14), 0, 0)
        }

        fun refresh() {
            tiles.forEachIndexed { i, tile ->
                tile.isActivated = i == picked
                checks[i].visibility = if (i == picked) View.VISIBLE else View.INVISIBLE
            }
            val (use, label) = choices[picked]
            blurb.text = when {
                use == DownloadedVideos.USE_BACKGROUND -> "Plays on the home screen on ordinary days."
                use == DownloadedVideos.USE_BIRTHDAY -> "Plays on someone's birthday."
                else -> "Plays on the days around $label."
            }
        }

        fun emoji(use: String) = when {
            use == DownloadedVideos.USE_BACKGROUND -> "\uD83C\uDF04"
            use == DownloadedVideos.USE_BIRTHDAY -> "\uD83C\uDF82"
            use.endsWith("halloween") -> "\uD83C\uDF83"
            use.endsWith("thanksgiving") -> "\uD83E\uDD83"
            use.endsWith("christmas") -> "\uD83C\uDF84"
            use.endsWith("newyear") -> "\uD83C\uDF86"
            else -> "\uD83C\uDF89"
        }

        fun tile(i: Int): LinearLayout {
            val (use, label) = choices[i]
            val check = ImageView(context).apply {
                setImageResource(R.drawable.ic_dialog_check)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            checks += check
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = context.getDrawable(R.drawable.bg_dialog_tile)
                setPadding(dp(16), dp(10), dp(12), dp(10))
                minimumHeight = dp(58)
                isFocusable = true
                isClickable = true
                addView(TextView(context).apply { text = emoji(use); textSize = 22f; setPadding(0, 0, dp(12), 0) })
                addView(TextView(context).apply { text = label; textSize = 18f; setTextColor(white) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
                setOnClickListener {
                    picked = i
                    refresh()
                    // Choosing is the last decision, so the remote's next press is Download.
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).requestFocus()
                }
            }.also { tiles += it }
        }

        fun label(text: String, top: Int) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(soft)
            setPadding(dp(4), dp(top), 0, dp(8))
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = about; textSize = 16f; setTextColor(soft); setLineSpacing(dp(2).toFloat(), 1f) })
            addView(label("What is it for?", 18))
            addView(tile(0), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label("Special days", 14))
            // The special days sit two to a row.
            var i = 1
            while (i < choices.size) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(tile(i), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                if (i + 1 < choices.size) {
                    row.addView(tile(i + 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
                } else {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(4) })
                }
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                i += 2
            }
            addView(blurb)
        }

        dialog = PortalDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setWidthDp(660)
            .setPositiveButton(positive) { _, _ -> onPicked(choices[picked].first) }
            .setNegativeButton("Cancel", null)
            .create()
        refresh()
        dialog.setOnShowListener { tiles[picked].requestFocus() }
        dialog.show()
    }

    private fun download(hit: VideoCatalog.Hit, use: String) {
        val bar = requireView().findViewById<ProgressBar>(R.id.vb_progress_bar)
        val text = requireView().findViewById<TextView>(R.id.vb_progress_text)
        requireView().findViewById<TextView>(R.id.vb_progress_title).text = hit.title
        bar.progress = 0
        text.text = "Starting…"
        progress.visibility = View.VISIBLE
        cancelOnBack.isEnabled = true
        requireView().findViewById<View>(R.id.vb_cancel).requestFocus()
        downloading = viewLifecycleOwner.lifecycleScope.launch {
            try {
                DownloadedVideos.download(requireContext(), hit, use) { done, total ->
                    bar.post {
                        bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                        text.text = "${VideoCatalog.size(done)}" + if (total > 0) " of ${VideoCatalog.size(total)}" else ""
                    }
                }
                progress.visibility = View.GONE
                cancelOnBack.isEnabled = false
                val dialog = PortalDialog.Builder(requireContext())
                    .setTitle("Downloaded")
                    .setMessage("${hit.title}\nBy ${hit.creator} on ${hit.provider.label}\nFor: ${DownloadedVideos.useLabel(requireContext(), use)}")
                if (use == DownloadedVideos.USE_BACKGROUND) {
                    dialog.setPositiveButton("Use as background") { _, _ -> useAsBackground(hit.fileName) }
                        .setNegativeButton("Done", null)
                } else {
                    dialog.setPositiveButton("Done", null)
                }
                dialog.show()
            } catch (e: CancellationException) {
                progress.visibility = View.GONE
                cancelOnBack.isEnabled = false
                Toast.makeText(requireContext(), "Download cancelled", Toast.LENGTH_SHORT).show()
                throw e
            } catch (e: Exception) {
                // Anything that went wrong, including after the file arrived, so a failure always leaves a trace.
                Log.w("Portal", "Download failed for ${hit.fileName} (${hit.provider.label})", e)
                progress.visibility = View.GONE
                cancelOnBack.isEnabled = false
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                Toast.makeText(requireContext(), "Download failed: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun useAsBackground(file: String) {
        val context = requireContext()
        BackgroundSettings.setMode(context, "video")
        BackgroundSettings.setVideo(context, file)
        Toast.makeText(context, "It will play on the home screen", Toast.LENGTH_SHORT).show()
    }

    private fun onChoose(item: DownloadedVideos.Item) {
        val context = requireContext()
        val actions = buildList {
            if (item.use == DownloadedVideos.USE_BACKGROUND) add("Use as background" to { useAsBackground(item.file) })
            add("Change what it's for" to {
                askUse(item.title, "By ${item.creator} on ${item.provider}", item.use, "Save") { use ->
                    DownloadedVideos.setUse(context, item.file, use)
                    showDownloaded()
                }
            })
            add("Delete" to {
                DownloadedVideos.remove(context, item.file)
                showDownloaded()
            })
            add("Close" to {})
        }
        PortalDialog.Builder(context)
            .setTitle("${item.title}\nBy ${item.creator} on ${item.provider} · ${DownloadedVideos.useLabel(requireContext(), item.use)}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // ---------- The grid ----------

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val root: LinearLayout, val thumb: ImageView, val title: TextView, val line: TextView, val sound: ImageView) :
            RecyclerView.ViewHolder(root)

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val d = context.resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1A1A2E.toInt())
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, px(10).toFloat())
                    }
                }
            }
            val sound = soundBadge(context)
            val title = TextView(context).apply {
                textSize = 14f
                setTextColor(resources.getColor(R.color.text_primary, null))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, px(6), 0, 0)
            }
            val line = TextView(context).apply {
                textSize = 12f
                setTextColor(resources.getColor(R.color.text_secondary, null))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), px(8), px(8), px(8))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(px(4), px(4), px(4), px(4)) }
                addView(android.widget.FrameLayout(context).apply {
                    addView(thumb, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(sound, android.widget.FrameLayout.LayoutParams(px(22), px(22), android.view.Gravity.TOP or android.view.Gravity.START)
                        .apply { setMargins(px(6), px(6), 0, 0) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96)))
                addView(title)
                addView(line)
            }
            return Holder(root, thumb, title, line, sound)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val e = entries[position]) {
                is Entry.Result -> {
                    h.title.text = e.hit.title
                    h.line.text = listOf(e.hit.creator, e.hit.details).filter { it.isNotEmpty() }.joinToString(" · ")
                    h.thumb.setImageBitmap(null)
                    h.sound.visibility = View.GONE
                    loadImage(e.hit.thumbUrl, h.thumb)
                    h.root.setOnClickListener { onChoose(e.hit) }
                }
                is Entry.Saved -> {
                    h.title.text = e.item.title
                    h.line.text = "${e.item.creator} · ${DownloadedVideos.useLabel(requireContext(), e.item.use)}"
                    h.thumb.setImageBitmap(null)
                    h.sound.visibility = if (VideoThumbnails.peekAudio(e.item.file) == true) View.VISIBLE else View.GONE
                    viewLifecycleOwner.lifecycleScope.launch {
                        VideoThumbnails.load(requireContext(), e.item.file)?.let { h.thumb.setImageBitmap(it) }
                        val has = VideoThumbnails.hasAudio(requireContext(), e.item.file)
                        if (entries.getOrNull(h.bindingAdapterPosition) === e) h.sound.visibility = if (has) View.VISIBLE else View.GONE
                    }
                    h.root.setOnClickListener { onChoose(e.item) }
                }
                Entry.More -> {
                    h.title.text = "More results"
                    h.line.text = "Next page"
                    h.thumb.setImageBitmap(null)
                    h.sound.visibility = View.GONE
                    h.root.setOnClickListener { runSearch(reset = false) }
                }
            }
        }

        private fun loadImage(url: String, into: ImageView) {
            if (url.isEmpty()) return
            images.get(url)?.let { into.setImageBitmap(it); return }
            into.tag = url
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = downloadPicture(url) ?: return@launch
                images.put(url, bitmap)
                if (into.tag == url) into.setImageBitmap(bitmap)
            }
        }
    }
}

/** A picture from a web address (a video's preview), or null if it couldn't be fetched. */
private suspend fun downloadPicture(address: String): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val c = java.net.URL(address).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Portal TV (https://github.com/plecos/portaltv)")
            if (c.responseCode != 200) return@runCatching null
            c.inputStream.use { input -> input.readBytes().let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
