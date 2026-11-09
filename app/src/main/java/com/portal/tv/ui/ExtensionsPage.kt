package com.portal.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.portal.tv.R
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.Catalog
import com.portal.tv.extensions.CatalogClient
import com.portal.tv.extensions.ExtensionUpdates
import com.portal.tv.extensions.CatalogLabels
import com.portal.tv.extensions.Extension
import com.portal.tv.extensions.ExtensionAccess
import com.portal.tv.extensions.ExtensionIcons
import com.portal.tv.extensions.ExtensionLabels
import com.portal.tv.extensions.ExtensionRegistry
import com.portal.tv.extensions.ExtensionState
import com.portal.tv.extensions.ManifestParser
import com.portal.tv.repo.ParentPin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Extensions page in Settings: the extensions installed on the TV, and where an adult allows each one, one
 * capability at a time. Kept out of the Fragment class on purpose (see HolidaysPage).
 *
 * Reading an extension is free, but changing what it may do asks for the parent PIN when one is set.
 */
class ExtensionsPage(
    private val context: Context,
    private val scope: CoroutineScope,
    private val startActivity: (Intent) -> Unit,
    /** Told how many extensions were found and how many have something allowed, for the category's summary. */
    private val onCounted: (found: Int, allowed: Int, updates: Int) -> Unit,
    /** Opens the full-screen catalog. */
    private val onBrowse: () -> Unit
) {

    private lateinit var list: LinearLayout
    /** The catalog kept from the last time it was fetched, if any; read with the scan, so drawing a page never waits on it. */
    private var catalog: Catalog? = null
    /** Package to the newer version name the catalog offers. */
    private var updates: Map<String, String> = emptyMap()
    private val d get() = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    fun bind(list: LinearLayout) {
        this.list = list
    }

    /** Looks again at what is installed. After a change, [focusPackage] is the extension whose row gets the remote back. */
    fun refresh(focusPackage: String? = null) {
        scope.launch {
            val all = withContext(Dispatchers.IO) { ExtensionRegistry.scan(context) }
            catalog = withContext(Dispatchers.IO) { CatalogClient.cached(context)?.catalog }
            updates = catalog?.takeIf { !CatalogLabels.expired(it, System.currentTimeMillis()) }?.let { ExtensionUpdates.offered(it, all) } ?: emptyMap()
            render(all, focusPackage)
            onCounted(all.size, all.count { it.granted.isNotEmpty() }, updates.size)
        }
    }

    /** Opens the page for a freshly installed extension, so an adult can look at what it asks for. */
    fun review(packageName: String) {
        scope.launch {
            val ext = withContext(Dispatchers.IO) { ExtensionRegistry.find(context, packageName) }
            // The catalog is read first, so the page can say whether this one is listed (refresh() does it later).
            catalog = withContext(Dispatchers.IO) { CatalogClient.cachedCatalog(context) }
            refresh(packageName)
            if (ext != null) showDetail(ext)
            else Toast.makeText(context, "It isn't an extension Portal can use", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(all: List<Extension>, focusPackage: String?) {
        list.removeAllViews()
        renderRows(all, focusPackage)
        renderKidsBackground(all)
        list.addView(TextView(context).apply {
            text = "Browse extensions"
            textSize = 18f
            typeface = context.resources.getFont(R.font.inter)
            setTextColor(context.resources.getColor(R.color.text_primary, null))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(24), 0, px(24), 0)
            minHeight = px(48)
            setBackgroundResource(R.drawable.bg_settings_tile)
            isFocusable = true
            contentDescription = "Browse extensions in the catalog. Select to open."
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(16) }
            setOnClickListener { onBrowse() }
        })
    }

    /**
     * One row per kid profile with Off and On for whether they may choose an extension's animated background. It is shown when
     * there are kid profiles and an extension an adult allowed to draw the background. Changing it needs the parent PIN, and a
     * kid's Settings never shows this page.
     */
    private fun renderKidsBackground(all: List<Extension>) {
        if (all.none { Capability.BACKGROUND in it.granted && it.backgroundService != null }) return
        val kids = com.portal.tv.repo.ProfileManager.getAll(context).filter { it.isKids }
        if (kids.isEmpty()) return
        list.addView(TextView(context).apply {
            text = "Kids and animated backgrounds"
            textSize = 16f
            setTextColor(context.resources.getColor(R.color.text_secondary, null))
            setPadding(0, px(18), 0, px(2))
        })
        list.addView(TextView(context).apply {
            text = "A kid can choose an extension's animated background only if you turn it on for them here."
            textSize = 14f
            setTextColor(context.resources.getColor(R.color.text_secondary, null))
            setPadding(0, 0, 0, px(8))
        })
        val tiles = mutableListOf<Triple<com.portal.tv.data.Profile, Boolean, TextView>>()
        fun restyle() = tiles.forEach { (kid, on, tile) ->
            val chosen = com.portal.tv.settings.BackgroundSettings.kidCanPickExtension(context, kid.id) == on
            tile.setBackgroundResource(if (chosen) R.drawable.bg_app_tile_selected else R.drawable.bg_settings_tile)
            tile.setTextColor(context.resources.getColor(if (chosen) R.color.text_primary else R.color.text_secondary, null))
        }
        kids.forEach { kid ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
            }
            row.addView(TextView(context).apply {
                text = kid.name
                textSize = 18f
                typeface = context.resources.getFont(R.font.inter)
                setTextColor(context.resources.getColor(R.color.text_primary, null))
                layoutParams = LinearLayout.LayoutParams(px(210), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            listOf("Off" to false, "On" to true).forEach { (label, on) ->
                val tile = TextView(context).apply {
                    text = label
                    textSize = 17f
                    typeface = context.resources.getFont(R.font.inter)
                    gravity = Gravity.CENTER
                    setPadding(px(20), 0, px(20), 0)
                    minHeight = px(44)
                    isFocusable = true
                    contentDescription = "${kid.name}: animated background $label. Select to change."
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8) }
                    setOnClickListener {
                        if (com.portal.tv.settings.BackgroundSettings.kidCanPickExtension(context, kid.id) == on) return@setOnClickListener
                        withPin {
                            com.portal.tv.settings.BackgroundSettings.setKidCanPickExtension(context, kid.id, on)
                            restyle()
                        }
                    }
                }
                tiles += Triple(kid, on, tile)
                row.addView(tile)
            }
            list.addView(row)
        }
        restyle()
    }

    private fun renderRows(all: List<Extension>, focusPackage: String?) {
        if (all.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No extensions installed"
                textSize = 16f
                setTextColor(context.resources.getColor(R.color.text_secondary, null))
                setPadding(0, 0, 0, px(12))
            })
            return
        }
        all.forEach { ext ->
            val row = TextView(context).apply {
                text = ext.name + (ext.versionName?.let { "  ·  $it" } ?: "") + (if (updates.containsKey(ext.packageName)) "  ·  Update available" else "") + if (ext.state == ExtensionState.REVOKED) "  ·  Withdrawn" else ""
                textSize = 18f
                typeface = context.resources.getFont(R.font.inter)
                setTextColor(context.resources.getColor(R.color.text_primary, null))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(24), 0, px(24), 0)
                minHeight = px(48)
                setBackgroundResource(R.drawable.bg_settings_tile)
                // Its own picture, so it is known at a glance.
                ExtensionIcons.of(context, ext.packageName, ext.manifest?.iconRes ?: 0)?.let {
                    it.setBounds(0, 0, px(32), px(32))
                    setCompoundDrawables(it, null, null, null)
                    compoundDrawablePadding = px(14)
                }
                isFocusable = true
                contentDescription = "${ext.name}, ${ExtensionLabels.status(ext.state, ext.granted)}. Select for details."
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = px(8) }
                setOnClickListener { showDetail(ext) }
            }
            list.addView(row)
            if (ext.packageName == focusPackage) row.post { row.requestFocus() }
        }
    }

    // ---------- One extension ----------

    private fun showDetail(ext: Extension) {
        val manifest = ext.manifest
        val chosen = ext.granted.toMutableSet()
        val soft = Color.rgb(176, 176, 192)
        fun text(value: String, sp: Float, color: Int = Color.WHITE) = TextView(context).apply {
            this.text = value
            textSize = sp
            setTextColor(color)
            typeface = context.resources.getFont(R.font.inter)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(4), px(24), px(8))
        }
        ext.versionName?.let { box.addView(text("Version $it" + (updates[ext.packageName]?.let { n -> "  ·  $n is available" } ?: ""), 15f, soft)) }
        var shown: PortalDialog? = null
        updates[ext.packageName]?.let { newer ->
            box.addView(TextView(context).apply {
                text = "Update to $newer"
                textSize = 18f
                typeface = context.resources.getFont(R.font.inter)
                setTextColor(Color.WHITE)
                setPadding(px(20), px(10), px(20), px(10))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12); bottomMargin = px(8) }
                contentDescription = "Update ${ext.name} to $newer. Select to open the catalog."
                setOnClickListener { shown?.dismiss(); onBrowse() }
            })
        }
        // One line says whether to trust it. Verified: one of ours, or the one the catalog lists (same signer). Otherwise a warning.
        // Only judged once the catalog has been loaded.
        val listing = catalog?.let { CatalogLabels.listing(it, ext.packageName, ext.signer) }
        val verified = ext.madeByPortal || listing == com.portal.tv.extensions.Listing.LISTED
        val unlisted = !ext.madeByPortal && listing != null && listing != com.portal.tv.extensions.Listing.LISTED
        if (ext.state != ExtensionState.REVOKED) {
            if (verified) {
                box.addView(text("✔  Verified" + if (ext.madeByPortal) "  ·  Made by Portal" else "", 15f, Color.rgb(140, 230, 160)))
            } else if (unlisted) {
                box.addView(text("⚠  Not verified", 15f, Color.rgb(255, 200, 100)))
                box.addView(text(
                    (if (listing == com.portal.tv.extensions.Listing.LISTED_BUT_SIGNED_BY_OTHER) "It is in the catalog, but signed by someone else, so it isn't the one listed. " else "It isn't in the catalog. ") +
                        "Nobody has looked at it. Anything it can do, it can do on your TV and in front of your family. " +
                        "Only allow it if you got it from someone you trust.",
                    14f, Color.rgb(255, 200, 100)
                ).apply { setPadding(0, px(4), 0, 0) })
            } else {
                box.addView(text("Not checked yet. Open Browse extensions once to check it.", 14f, soft))
            }
        }
        box.addView(View(context), LinearLayout.LayoutParams(1, px(8)))
        manifest?.description?.takeIf { it.isNotEmpty() }?.let { box.addView(text(it, 16f, soft)) }
        box.addView(View(context), LinearLayout.LayoutParams(1, px(8)))
        val statusColor = when (ext.state) {
            ExtensionState.ALLOWED -> Color.rgb(140, 230, 160)
            ExtensionState.NOT_ALLOWED -> Color.rgb(255, 200, 100)
            else -> Color.rgb(255, 120, 120)  // withdrawn, signed by someone else, can't be used
        }
        box.addView(text(ExtensionLabels.status(ext.state, ext.granted), 17f, statusColor))
        if (ext.state == ExtensionState.NOT_ALLOWED && ext.parsed is ManifestParser.Result.Ok) {
            box.addView(text("Nothing is turned on yet. Choose what it can do below, then Save.", 14f, soft))
        }
        if (ext.state == ExtensionState.REVOKED) {
            box.addView(text(
                "\n" + (ext.revokedReason?.let { "Reason given: $it" } ?: "No reason was given.") +
                    "\n\nIt has been switched off and what you allowed has been forgotten. Uninstall it below.",
                16f, Color.rgb(255, 120, 120)
            ))
        }

        when (val parsed = ext.parsed) {
            is ManifestParser.Result.Invalid -> box.addView(text("\n" + parsed.reason, 16f, soft))
            is ManifestParser.Result.NeedsNewerPortal -> box.addView(text("\nIt needs a newer version of Portal than this one. Update Portal, then look again.", 16f, soft))
            is ManifestParser.Result.Ok -> if (ext.state == ExtensionState.REVOKED) {
                // Nothing to allow: it is off. Only Uninstall is left.
            } else {
                if (ext.state == ExtensionState.SIGNER_CHANGED) {
                    box.addView(text("\nThis app was allowed before, but it is now signed by someone else. Only allow it again if you expect that.", 15f, Color.rgb(255, 200, 100)))
                }
                val shown = parsed.manifest.items
                if (shown.isNotEmpty()) {
                    box.addView(text("\nIt would put on your home screen:", 16f, soft).apply { setPadding(0, 0, 0, px(4)) })
                    shown.forEach { item ->
                        val kind = if (item.isButton) "button" else "text"
                        box.addView(text("•  \"${item.text}\" ($kind, ${item.area.title})", 15f, Color.WHITE).apply { setPadding(px(8), 0, 0, px(2)) })
                    }
                }
                box.addView(text("\nAllow it to:", 16f, soft).apply { setPadding(0, 0, 0, px(6)) })
                val all = parsed.manifest.capabilities
                val redraws = mutableListOf<() -> Unit>()
                if (all.size > 1) {
                    // One press turns everything on, or, when everything is on, everything off.
                    val toggle = TextView(context)
                    fun label() { toggle.text = if (chosen.containsAll(all)) "Deselect all" else "Select all" }
                    toggle.apply {
                        textSize = 17f
                        typeface = context.resources.getFont(R.font.inter)
                        setTextColor(Color.WHITE)
                        setPadding(px(20), px(8), px(20), px(8))
                        setBackgroundResource(R.drawable.bg_settings_tile)
                        isFocusable = true
                        isClickable = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { bottomMargin = px(8) }
                        setOnClickListener {
                            if (chosen.containsAll(all)) chosen.clear() else chosen.addAll(all)
                            redraws.forEach { it() }
                            label()
                        }
                    }
                    label()
                    redraws += { label() }
                    box.addView(toggle)
                }
                parsed.manifest.capabilities.forEach { capability ->
                    val row = TextView(context)
                    fun show() { row.text = (if (capability in chosen) "✔  " else "☐  ") + Capability.describe(capability) }
                    redraws += { show() }
                    row.apply {
                        textSize = 18f
                        typeface = context.resources.getFont(R.font.inter)
                        setTextColor(Color.WHITE)
                        setPadding(px(20), px(10), px(20), px(10))
                        setBackgroundResource(R.drawable.bg_settings_tile)
                        isFocusable = true
                        isClickable = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { bottomMargin = px(8) }
                        setOnClickListener { if (!chosen.add(capability)) chosen.remove(capability); redraws.forEach { it() } }
                    }
                    show()
                    box.addView(row)
                }
                if (parsed.manifest.unknownCapabilities.isNotEmpty()) {
                    box.addView(text("It also asks for things this Portal doesn't know, which are never allowed: " + parsed.manifest.unknownCapabilities.joinToString(", "), 14f, soft))
                }
            }
        }
        ext.problems.forEach { box.addView(text("\n⚠ $it", 14f, Color.rgb(255, 200, 100))) }

        // Taking it off the TV: Android asks to confirm, and Portal forgets what was allowed once it is gone.
        val system = runCatching {
            context.packageManager.getApplicationInfo(ext.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(true)
        if (!system) {
            box.addView(TextView(context).apply {
                text = "Uninstall this extension"
                textSize = 18f
                typeface = context.resources.getFont(R.font.inter)
                setTextColor(Color.rgb(255, 140, 140))
                context.getDrawable(R.drawable.ic_dialog_trash)?.mutate()?.let {
                    it.setTint(Color.rgb(255, 140, 140))
                    it.setBounds(0, 0, px(26), px(26))
                    setCompoundDrawables(it, null, null, null)
                    compoundDrawablePadding = px(14)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), px(10), px(20), px(10))
                setBackgroundResource(R.drawable.bg_settings_tile)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(14) }
                setOnClickListener {
                    shown?.dismiss()
                    withPin {
                        startActivity(
                            Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", ext.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            })
        }

        val builder = PortalDialog.Builder(context)
            .setTitle(ext.name)
            .setView(box)
            .setBodyMaxHeightFraction(0.62f)
            .setNegativeButton(if (manifest == null || ext.state == ExtensionState.REVOKED) "Close" else "Cancel", null)
        if (manifest != null && ext.state != ExtensionState.REVOKED) {
            builder.setPositiveButton("Save") { _, _ ->
                val adding = chosen.any { it !in ext.granted }
                if (unlisted && adding) {
                    // Something new is being turned on for an extension nobody has looked at: ask once more, plainly.
                    PortalDialog.Builder(context)
                        .setTitle("Allow ${ext.name}?")
                        .setMessage("It isn't in the catalog, so nobody has looked at it. You are about to allow ${chosen.size} thing" +
                            "${if (chosen.size == 1) "" else "s"}. Only go on if you trust where it came from.")
                        .setPositiveButton("Allow") { _, _ -> withPin { save(ext, chosen) } }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    withPin { save(ext, chosen) }
                }
            }
            if (Capability.SETTINGS in ext.granted && ext.settingsActivity != null) {
                builder.setNeutralButton("Settings") { _, _ -> openSettings(ext) }
            }
        }
        shown = builder.show()
    }

    private fun save(ext: Extension, capabilities: Set<String>) {
        // What is saved is checked against what was on this page: if the app was replaced meanwhile, nothing is saved.
        if (!ExtensionRegistry.allow(context, ext.packageName, capabilities, ext.signer, ext.manifest?.capabilities, checkShown = true)) {
            Toast.makeText(context, "That extension changed, or can't be changed right now. Open it again to see it as it is now", Toast.LENGTH_LONG).show()
        }
        refresh(ext.packageName)
    }

    private fun openSettings(ext: Extension) {
        val activity = ext.settingsActivity ?: return
        // Checked again now, in case it changed since this page was drawn.
        val now = ExtensionRegistry.find(context, ext.packageName)
        if (now == null || Capability.SETTINGS !in now.granted || now.settingsActivity != activity) {
            Toast.makeText(context, "Its settings aren't available any more", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(ext.packageName, activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, "Couldn't open its settings", Toast.LENGTH_SHORT).show() }
    }

    /** Runs [action] now, or after the parent PIN is entered if one is set. */
    private fun withPin(action: () -> Unit) {
        if (ParentPin.isSet(context)) PinDialogs.verify(context, "Parent PIN to change extensions") { action() }
        // With no PIN, anyone using an adult profile could allow things. Like a kid's settings, a PIN is set first.
        else PinDialogs.create(
            context,
            message = "A parent PIN is needed to change what extensions can do. ${ParentPin.MIN_LENGTH} digits.",
            onSet = action
        )
    }
}
