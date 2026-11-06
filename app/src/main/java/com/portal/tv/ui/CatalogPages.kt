package com.portal.tv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.BuildConfig
import com.portal.tv.R
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.Catalog
import com.portal.tv.extensions.CatalogClient
import com.portal.tv.extensions.CatalogEntry
import com.portal.tv.extensions.CatalogIcons
import com.portal.tv.extensions.CatalogLabels
import com.portal.tv.extensions.CatalogSearch
import com.portal.tv.extensions.CatalogRules
import com.portal.tv.extensions.CatalogState
import com.portal.tv.extensions.ExtensionAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse extensions: the catalog's list and one extension's details. Nothing here installs anything; it is for looking.
 * The list is the copy kept from last time and opens at once; it is fetched again when it is more than a day old, or when
 * asked. Kept out of the Fragment class on purpose (see HolidaysPage).
 */
class CatalogPages(private val context: Context, private val scope: CoroutineScope) {

    private val d get() = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val soft = Color.rgb(176, 176, 192)

    private fun text(value: String, sp: Float, color: Int = Color.WHITE) = TextView(context).apply {
        this.text = value
        textSize = sp
        setTextColor(color)
        typeface = context.resources.getFont(R.font.inter)
    }

    private fun qr(link: String, caption: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(12), 0, 0)
        isFocusable = true
        addView(ImageView(context).apply {
            setImageBitmap(QrCode.bitmap(link, px(150)))
            layoutParams = LinearLayout.LayoutParams(px(100), px(100)).apply { marginEnd = px(16) }
        })
        addView(text(caption + "\n" + link, 14f, soft).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
    }

    // ---------- The list ----------

    /** A row's picture: the catalog's icon once it arrives, the extension puzzle piece until then or if there isn't one. */
    private fun iconView(entry: CatalogEntry, sizeDp: Int) = ImageView(context).apply {
        setImageResource(R.drawable.ic_cat_extensions)
        alpha = 0.6f
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { marginEnd = px(14) }
        val url = CatalogSearch.iconUrl(CatalogClient.url(context), entry.iconPath) ?: return@apply
        scope.launch {
            CatalogIcons.load(context, url, BuildConfig.DEBUG)?.let { setImageBitmap(it); alpha = 1f }
        }
    }

    fun browse() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(4), px(24), px(8))
        }
        var query = ""
        var tag: String? = null
        var all: Catalog? = null

        val search = EditText(context).apply {
            hint = "Search by name, description or tag"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(soft)
            textSize = 17f
            typeface = context.resources.getFont(R.font.inter)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(px(16), px(12), px(16), px(12))
            setBackgroundResource(R.drawable.bg_settings_tile)
        }
        val tagChip = TextView(context).apply {
            textSize = 16f
            typeface = context.resources.getFont(R.font.inter)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(px(18), px(10), px(18), px(10))
            setBackgroundResource(R.drawable.bg_settings_tile)
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = px(10) }
        }
        box.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tagChip)
        })
        val status = text("", 14f, soft).apply { setPadding(0, px(10), 0, px(8)) }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(status)
        box.addView(rows)
        // One focusable block at the end, so the remote can scroll down to it when the list is long.
        box.addView(qr(
            CatalogClient.WEB_PAGE,
            "Each listed extension was looked at by a person. That is a review, not a guarantee, and they are made by other people. " +
                "This list sends nothing about your TV; the site that hosts it sees your address, as any website does.\n\nBrowse on your phone:"
        ).apply { setPadding(0, px(14), 0, 0) })

        val dialog = PortalDialog.Builder(context)
            .setTitle("Browse extensions")
            .setView(box)
            .setBodyMaxHeightFraction(0.58f)
            .setNegativeButton("Close", null)
            .setNeutralButton("Refresh", null)
            .show()

        var note = ""
        fun refill() {
            val catalog = all ?: return
            tagChip.text = "Tag: " + (tag ?: "all")
            rows.removeAllViews()
            val offered = CatalogLabels.shown(catalog)
            val found = CatalogSearch.filter(offered, query, tag)
            status.text = (if (query.isNotBlank() || tag != null) "${found.size} of ${CatalogLabels.count(offered.size)}" else CatalogLabels.count(offered.size)) + note
            if (found.isEmpty()) rows.addView(text(if (offered.isEmpty()) "Nothing is listed yet." else "Nothing matches. Try other words, or choose Tag: all.", 16f, soft))
            val installed = installedVersions()
            found.forEach { entry ->
                val state = CatalogLabels.state(catalog, entry, installed[entry.packageName]?.first, installed[entry.packageName]?.second)
                val extra = CatalogLabels.stateText(state)
                rows.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16), px(8), px(16), px(8))
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isFocusable = true
                    isClickable = true
                    contentDescription = "${entry.name} by ${entry.author}. $extra Select for details."
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = px(8) }
                    addView(iconView(entry, 44))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(entry.name + if (extra.isNotEmpty()) "  ·  $extra" else "", 18f))
                        addView(text(entry.author + if (entry.tags.isNotEmpty()) "  ·  " + entry.tags.joinToString(", ") else "", 13f, soft))
                    })
                    setOnClickListener { detail(catalog, entry) }
                })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refill() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        tagChip.setOnClickListener {
            val catalog = all ?: return@setOnClickListener
            val tags = CatalogSearch.tags(CatalogLabels.shown(catalog))
            val labels = (listOf("All tags") + tags).toTypedArray()
            PortalDialog.Builder(context)
                .setTitle("Show only")
                .setSingleChoiceItems(labels, if (tag == null) 0 else tags.indexOf(tag) + 1) { d, which ->
                    tag = if (which == 0) null else tags[which - 1]
                    refill()
                    d.dismiss()
                }
                .show()
        }

        fun load(force: Boolean) {
            val cached = CatalogClient.cached(context)
            if (cached != null) {
                all = cached.catalog
                note = " · list from ${cached.catalog.updated.ifEmpty { "an earlier visit" }}"
                refill()
            } else {
                status.text = "Loading the list…"
            }
            if (!force && cached != null && !CatalogLabels.isStale(cached.fetchedAtMs, System.currentTimeMillis())) return
            scope.launch {
                try {
                    val fresh = CatalogClient.fetch(context)
                    if (!dialog.isShowing) return@launch
                    all = fresh.catalog
                    note = " · up to date"
                    refill()
                } catch (e: Exception) {
                    if (!dialog.isShowing) return@launch
                    val why = e.message ?: "It couldn't be loaded"
                    if (cached != null) { note = " · couldn't update ($why), showing the last copy"; refill() }
                    else status.text = "Couldn't load the list: $why"
                }
            }
        }
        // Refresh stays open on click: the Neutral button would close the dialog, so it is wired by hand.
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            status.text = "Checking for a newer list…"
            load(force = true)
        }
        load(force = false)
    }

    /** Package to (version code, signer) for what is installed, only for what could be in the catalog. */
    private fun installedVersions(): Map<String, Pair<Long, String?>> {
        val pm = context.packageManager
        return pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).associate { p ->
            val signers = p.signingInfo?.apkContentsSigners?.map { ExtensionAccess.sha256Hex(it.toByteArray()) }
            p.packageName to (p.longVersionCode to signers?.let { ExtensionAccess.combineSigners(it) })
        }
    }

    // ---------- One extension ----------

    private fun detail(catalog: Catalog, entry: CatalogEntry) {
        val version = CatalogRules.best(catalog, entry) ?: return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(4), px(24), px(8))
        }
        box.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(entry, 64))
            addView(text(entry.description, 16f, soft).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        })
        if (entry.tags.isNotEmpty()) box.addView(text(entry.tags.joinToString("   ") { "#$it" }, 14f, soft).apply { setPadding(0, px(6), 0, 0) })
        box.addView(text("by ${entry.author}" + if (entry.license.isNotEmpty()) "  ·  ${entry.license}" else "", 14f, soft).apply { setPadding(0, px(6), 0, px(6)) })

        val installed = installedVersions()[entry.packageName]
        val state = CatalogLabels.state(catalog, entry, installed?.first, installed?.second)
        val stateLine = CatalogLabels.stateText(state)
        if (stateLine.isNotEmpty()) {
            val color = if (state == CatalogState.SIGNED_BY_OTHER) Color.rgb(255, 200, 100) else Color.rgb(140, 230, 160)
            box.addView(text(stateLine, 17f, color))
        }
        if (state == CatalogState.SIGNED_BY_OTHER) {
            box.addView(text("The app on this TV isn't signed by the person the catalog lists, so it isn't the listed extension.", 14f, Color.rgb(255, 200, 100)))
        }

        box.addView(text("\nIt asks to be allowed to:", 16f, soft))
        if (entry.capabilities.isEmpty()) box.addView(text("•  nothing (you would still choose what it may do)", 15f))
        entry.capabilities.forEach { box.addView(text("•  " + Capability.describe(it), 15f).apply { setPadding(px(8), 0, 0, px(2)) }) }
        box.addView(text("Nothing is allowed until you allow it, after it is installed.", 13f, soft).apply { setPadding(0, px(4), 0, 0) })

        box.addView(text(
            "\n${entry.packageName}\nSigned by ${ExtensionAccess.shortFingerprint(entry.signer)}  ·  version ${version.versionName}  ·  ${CatalogLabels.sizeText(version.sizeBytes)}",
            13f, soft
        ))
        box.addView(qr(entry.sourceUrl, "Read the source on your phone:"))

        PortalDialog.Builder(context)
            .setTitle(entry.name)
            .setView(box)
            .setBodyMaxHeightFraction(0.58f)
            .setNegativeButton("Close", null)
            .show()
    }
}
