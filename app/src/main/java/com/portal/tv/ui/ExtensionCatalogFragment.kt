package com.portal.tv.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.portal.tv.BuildConfig
import com.portal.tv.R
import com.portal.tv.extensions.Catalog
import com.portal.tv.extensions.CatalogClient
import com.portal.tv.extensions.CatalogEntry
import com.portal.tv.extensions.CatalogIcons
import com.portal.tv.extensions.CatalogInstaller
import com.portal.tv.extensions.CatalogLabels
import com.portal.tv.extensions.CatalogSearch
import com.portal.tv.extensions.ExtensionAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse extensions, full screen: a grid of everything in the catalog, with a search box and a tag filter. The list opens at
 * once from the copy kept on the TV and is fetched again when it is more than a day old, or on Refresh. Selecting one opens
 * its card (details, Install or Update). Built to hold hundreds: the grid recycles its cards, and icons are loaded as they
 * come on screen.
 */
class ExtensionCatalogFragment : Fragment() {

    companion object {
        /** Told, through the fragment manager, which extension to open for review after an install. */
        const val REVIEW_KEY = "extension_review"
        const val REVIEW_PACKAGE = "package"
    }

    private var catalog: Catalog? = null
    private var query = ""
    private var tag: String? = null
    private var note = ""
    private var focused = false
    private var installedLoaded = false
    private var shown = emptyList<CatalogEntry>()
    private var installed = emptyMap<String, com.portal.tv.extensions.InstalledFacts>()
    private var installedJob: kotlinx.coroutines.Job? = null
    private val icons = object : LruCache<String, Bitmap>(96) {}

    private lateinit var adapter: Adapter
    private lateinit var status: TextView
    private lateinit var tagChip: TextView
    private lateinit var results: RecyclerView
    private lateinit var pages: CatalogPages

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_extension_catalog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pages = CatalogPages(requireContext(), viewLifecycleOwner.lifecycleScope) { pkg ->
            parentFragmentManager.setFragmentResult(REVIEW_KEY, Bundle().apply { putString(REVIEW_PACKAGE, pkg) })
            parentFragmentManager.popBackStack()
        }
        status = view.findViewById(R.id.ec_status)
        tagChip = view.findViewById(R.id.ec_tag)
        results = view.findViewById(R.id.ec_results)
        val search = view.findViewById<EditText>(R.id.ec_query)

        adapter = Adapter()
        results.layoutManager = GridLayoutManager(requireContext(), 3)
        results.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refill() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                requireContext().getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
                results.requestFocus()
                true
            } else false
        }
        tagChip.setOnClickListener { chooseTag() }
        view.findViewById<View>(R.id.ec_refresh).setOnClickListener {
            status.text = "Checking for a newer list…"
            load(force = true)
        }
        load(force = false)
    }

    override fun onResume() {
        super.onResume()
        // Something may have been installed or removed since the cards were drawn.
        if (catalog != null) loadInstalled()
    }

    override fun onDestroyView() {
        // The result of an install goes to whoever is showing; nobody is, once this screen is gone.
        CatalogInstaller.onOutcome = null
        installedJob?.cancel()
        super.onDestroyView()
    }

    /** What is installed, for the cards' Installed / Update labels. Read off the main thread, and not on every keystroke. */
    private fun loadInstalled() {
        val c = catalog ?: return
        val appContext = requireContext().applicationContext
        installedJob?.cancel()
        installedJob = viewLifecycleOwner.lifecycleScope.launch {
            val now = withContext(kotlinx.coroutines.Dispatchers.IO) { CatalogInstaller.installedFacts(appContext, c.extensions.map { it.packageName }) }
            if (now != installed) { installed = now; adapter.notifyDataSetChanged() }
        }
    }

    // ---------- Loading and filtering ----------

    private fun load(force: Boolean) {
        val cached = CatalogClient.cached(requireContext())
        if (cached != null) {
            catalog = cached.catalog
            note = " · list from ${cached.catalog.updated.ifEmpty { "an earlier visit" }}"
            refill()
        } else {
            status.text = "Loading the list…"
        }
        if (!force && cached != null && !CatalogLabels.isStale(cached.fetchedAtMs, System.currentTimeMillis())) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fresh = CatalogClient.fetch(requireContext())
                catalog = fresh.catalog
                note = " · up to date"
                refill()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = e.message ?: "It couldn't be loaded"
                if (cached != null) { note = " · couldn't update ($why), showing the last copy"; refill() }
                else status.text = "Couldn't load the list: $why"
            }
        }
    }

    private fun refill() {
        val c = catalog ?: return
        tagChip.text = "Tag: " + (tag ?: "all")
        val offered = CatalogLabels.shown(c)
        shown = CatalogSearch.filter(offered, query, tag)
        status.text = (if (query.isNotBlank() || tag != null) "${shown.size} of ${CatalogLabels.count(offered.size)}" else CatalogLabels.count(offered.size)) + note +
            (if (CatalogLabels.expired(c, CatalogClient.trustedNow(requireContext()))) " · installing is paused, this catalog hasn't been updated for a while" else "") +
            if (shown.isEmpty() && offered.isNotEmpty()) " · nothing matches, try other words or Tag: all"
            else if (offered.isEmpty()) "\nNothing is listed yet. An extension you install another way still shows up under Settings, Extensions." else ""
        adapter.notifyDataSetChanged()
        if (!installedLoaded) { installedLoaded = true; loadInstalled() }
        if (!focused && shown.isNotEmpty()) {
            focused = true
            results.post { results.requestFocus() }
        }
    }

    private fun chooseTag() {
        val c = catalog ?: return
        val tags = CatalogSearch.tags(CatalogLabels.shown(c))
        val labels = (listOf("All tags") + tags).toTypedArray()
        PortalDialog.Builder(requireContext())
            .setTitle("Show only")
            .setSingleChoiceItems(labels, if (tag == null) 0 else tags.indexOf(tag) + 1) { d, which ->
                tag = if (which == 0) null else tags[which - 1]
                refill()
                d.dismiss()
            }
            .show()
    }

    // ---------- The grid ----------

    private inner class Holder(val card: LinearLayout, val icon: ImageView, val name: TextView, val line: TextView, val tagLine: TextView, val state: TextView) :
        RecyclerView.ViewHolder(card) {
        var iconUrl: String? = null
        var iconJob: kotlinx.coroutines.Job? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        // A card that scrolls away stops loading its icon.
        override fun onViewRecycled(holder: Holder) {
            holder.iconJob?.cancel()
            holder.iconJob = null
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val d = resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()
            val icon = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(px(56), px(56)).apply { marginEnd = px(16) }
            }
            fun label(sp: Float, color: Int) = TextView(parent.context).apply {
                textSize = sp
                setTextColor(color)
                typeface = resources.getFont(R.font.inter)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val name = label(19f, Color.WHITE)
            val line = label(14f, Color.rgb(176, 176, 192))
            val tagLine = label(13f, Color.rgb(140, 140, 160))
            val state = label(14f, Color.rgb(140, 230, 160))
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(name); addView(line); addView(tagLine); addView(state)
            }
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), px(14), px(18), px(14))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(px(6), px(6), px(6), px(6)) }
                addView(icon); addView(texts)
            }
            return Holder(card, icon, name, line, tagLine, state)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val entry = shown[position]
            val c = catalog ?: return
            val here = installed[entry.packageName]
            val state = CatalogLabels.state(c, entry, here?.versionCode, here?.signer)
            val stateText = CatalogLabels.stateText(state)
            h.name.text = entry.name
            h.line.text = CatalogLabels.byLine(entry)
            h.tagLine.text = entry.tags.joinToString("  ") { "#$it" }
            h.tagLine.visibility = if (entry.tags.isEmpty()) View.GONE else View.VISIBLE
            val priceLine = if (stateText.isEmpty()) CatalogLabels.priceLine(entry) else ""
            h.state.text = stateText.ifEmpty { priceLine }
            h.state.setTextColor(if (priceLine.isNotEmpty()) Color.rgb(255, 214, 120) else Color.rgb(140, 230, 160))
            h.state.visibility = if (stateText.isEmpty() && priceLine.isEmpty()) View.GONE else View.VISIBLE
            h.card.contentDescription = "${entry.name} by ${entry.author}. ${stateText.ifEmpty { priceLine }} Select for details."
            h.card.setOnClickListener { pages.showDetail(c, entry) }

            // The icon: the puzzle piece until the catalog's own arrives.
            val url = CatalogSearch.iconUrl(CatalogClient.url(requireContext()), entry.iconPath)
            h.iconJob?.cancel()
            h.iconUrl = url
            val have = url?.let { icons.get(it) }
            if (have != null) {
                h.icon.setImageBitmap(have); h.icon.alpha = 1f
            } else {
                h.icon.setImageResource(R.drawable.ic_cat_extensions); h.icon.alpha = 0.6f
                if (url != null) h.iconJob = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = CatalogIcons.load(requireContext(), url, BuildConfig.DEBUG) ?: return@launch
                    icons.put(url, bitmap)
                    if (h.iconUrl == url) { h.icon.setImageBitmap(bitmap); h.icon.alpha = 1f }
                }
            }
        }
    }
}
