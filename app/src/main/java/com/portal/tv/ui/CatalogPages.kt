package com.portal.tv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.Catalog
import com.portal.tv.extensions.CatalogClient
import com.portal.tv.extensions.CatalogEntry
import com.portal.tv.extensions.CatalogLabels
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

    fun browse() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(4), px(24), px(8))
        }
        val status = text("", 14f, soft).apply { setPadding(0, 0, 0, px(10)) }
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

        fun fill(catalog: Catalog) {
            rows.removeAllViews()
            val shown = CatalogLabels.shown(catalog)
            if (shown.isEmpty()) rows.addView(text("Nothing is listed yet.", 16f, soft))
            val installed = installedVersions()
            shown.forEach { entry ->
                val state = CatalogLabels.state(catalog, entry, installed[entry.packageName]?.first, installed[entry.packageName]?.second)
                val extra = CatalogLabels.stateText(state)
                rows.addView(TextView(context).apply {
                    this.text = entry.name + "  ·  " + entry.author + if (extra.isNotEmpty()) "  ·  $extra" else ""
                    textSize = 18f
                    typeface = context.resources.getFont(R.font.inter)
                    setTextColor(Color.WHITE)
                    setPadding(px(20), px(10), px(20), px(10))
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isFocusable = true
                    isClickable = true
                    contentDescription = "${entry.name} by ${entry.author}. $extra Select for details."
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = px(8) }
                    setOnClickListener { detail(catalog, entry) }
                })
            }
        }

        fun load(force: Boolean) {
            val cached = CatalogClient.cached(context)
            if (cached != null) {
                fill(cached.catalog)
                status.text = "${CatalogLabels.count(CatalogLabels.shown(cached.catalog).size)} · list from ${cached.catalog.updated.ifEmpty { "an earlier visit" }}"
            } else {
                status.text = "Loading the list…"
            }
            if (!force && cached != null && !CatalogLabels.isStale(cached.fetchedAtMs, System.currentTimeMillis())) return
            scope.launch {
                try {
                    val fresh = CatalogClient.fetch(context)
                    if (!dialog.isShowing) return@launch
                    fill(fresh.catalog)
                    status.text = "${CatalogLabels.count(CatalogLabels.shown(fresh.catalog).size)} · up to date"
                } catch (e: Exception) {
                    if (!dialog.isShowing) return@launch
                    val why = e.message ?: "It couldn't be loaded"
                    status.text = if (cached != null) "Couldn't update the list ($why). Showing the last copy." else "Couldn't load the list: $why"
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
        box.addView(text(entry.description, 16f, soft))
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
