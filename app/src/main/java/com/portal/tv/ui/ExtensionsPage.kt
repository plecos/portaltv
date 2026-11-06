package com.portal.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.portal.tv.R
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.Extension
import com.portal.tv.extensions.ExtensionAccess
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
    private val onCounted: (found: Int, allowed: Int) -> Unit
) {

    private lateinit var list: LinearLayout
    private val d get() = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    fun bind(list: LinearLayout) {
        this.list = list
    }

    /** Looks again at what is installed. After a change, [focusPackage] is the extension whose row gets the remote back. */
    fun refresh(focusPackage: String? = null) {
        scope.launch {
            val all = withContext(Dispatchers.IO) { ExtensionRegistry.scan(context) }
            render(all, focusPackage)
            onCounted(all.size, all.count { it.granted.isNotEmpty() })
        }
    }

    private fun render(all: List<Extension>, focusPackage: String?) {
        list.removeAllViews()
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
                text = "${ext.name}  ·  ${ExtensionLabels.status(ext.state, ext.granted)}"
                textSize = 18f
                typeface = context.resources.getFont(R.font.inter)
                setTextColor(context.resources.getColor(R.color.text_primary, null))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(24), 0, px(24), 0)
                minHeight = px(48)
                setBackgroundResource(R.drawable.bg_settings_tile)
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
        manifest?.description?.takeIf { it.isNotEmpty() }?.let { box.addView(text(it, 16f, soft)) }
        box.addView(text(ext.packageName + "  ·  signed by " + (ext.signer?.let { ExtensionAccess.shortFingerprint(it) } ?: "unknown"), 13f, soft).apply { setPadding(0, px(6), 0, px(10)) })
        val statusColor = when (ext.state) {
            ExtensionState.ALLOWED -> Color.rgb(140, 230, 160)
            ExtensionState.NOT_ALLOWED -> Color.rgb(255, 200, 100)
            else -> Color.rgb(255, 120, 120)
        }
        box.addView(text(ExtensionLabels.status(ext.state, ext.granted), 17f, statusColor))

        when (val parsed = ext.parsed) {
            is ManifestParser.Result.Invalid -> box.addView(text("\n" + parsed.reason, 16f, soft))
            is ManifestParser.Result.NeedsNewerPortal -> box.addView(text("\nIt was written for a newer version of the extension contract (${parsed.contract}) than this Portal speaks (${ManifestParser.CONTRACT_VERSION}).", 16f, soft))
            is ManifestParser.Result.Ok -> {
                if (ext.state == ExtensionState.SIGNER_CHANGED) {
                    box.addView(text("\nThis app was allowed before, but it is now signed by someone else. Only allow it again if you expect that.", 15f, Color.rgb(255, 200, 100)))
                }
                val shown = parsed.manifest.items
                if (parsed.manifest.backgroundService != null) {
                    box.addView(text("\nIt can draw your home screen background, if you choose it under Settings, Background.", 15f, Color.WHITE))
                }
                if (shown.isNotEmpty()) {
                    box.addView(text("\nIt would put on your home screen:", 16f, soft).apply { setPadding(0, 0, 0, px(4)) })
                    shown.forEach { item ->
                        val kind = if (item.isButton) "button" else "text"
                        box.addView(text("•  \"${item.text}\" ($kind, ${item.area.title})", 15f, Color.WHITE).apply { setPadding(px(8), 0, 0, px(2)) })
                    }
                }
                box.addView(text("\nAllow it to:", 16f, soft).apply { setPadding(0, 0, 0, px(6)) })
                parsed.manifest.capabilities.forEach { capability ->
                    val row = TextView(context)
                    fun show() { row.text = (if (capability in chosen) "✔  " else "☐  ") + Capability.describe(capability) }
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
                        setOnClickListener { if (!chosen.add(capability)) chosen.remove(capability); show() }
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

        val builder = PortalDialog.Builder(context)
            .setTitle(ext.name)
            .setView(box)
            .setBodyMaxHeightFraction(0.62f)
            .setNegativeButton(if (manifest == null) "Close" else "Cancel", null)
        if (manifest != null) {
            builder.setPositiveButton("Save") { _, _ -> withPin { save(ext, chosen) } }
            if (Capability.SETTINGS in ext.granted && ext.settingsActivity != null) {
                builder.setNeutralButton("Open its settings") { _, _ -> openSettings(ext) }
            }
        }
        builder.show()
    }

    private fun save(ext: Extension, capabilities: Set<String>) {
        if (!ExtensionRegistry.allow(context, ext.packageName, capabilities)) {
            Toast.makeText(context, "That extension can't be changed right now", Toast.LENGTH_SHORT).show()
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
        if (ParentPin.isSet(context)) PinDialogs.verify(context, "Parent PIN to change extensions") { action() } else action()
    }
}
