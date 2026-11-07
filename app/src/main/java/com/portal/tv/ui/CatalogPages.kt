package com.portal.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import com.portal.tv.extensions.CatalogInstallChecks
import com.portal.tv.extensions.CatalogInstaller
import com.portal.tv.extensions.CatalogLabels
import com.portal.tv.extensions.CatalogVersion
import com.portal.tv.repo.ParentPin
import com.portal.tv.extensions.CatalogSearch
import com.portal.tv.extensions.CatalogRules
import com.portal.tv.extensions.CatalogState
import com.portal.tv.extensions.ExtensionAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse extensions: the catalog's list and one extension's details, and installing one.
 * The list is the copy kept from last time and opens at once; it is fetched again when it is more than a day old, or when
 * asked. Kept out of the Fragment class on purpose (see HolidaysPage).
 */
class CatalogPages(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called with the package of an extension that has just been installed, when the person chooses Review. */
    private val onReview: (String) -> Unit
) {

    private val d get() = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val soft = Color.rgb(176, 176, 192)

    private fun text(value: String, sp: Float, color: Int = Color.WHITE) = TextView(context).apply {
        this.text = value
        textSize = sp
        setTextColor(color)
        typeface = context.resources.getFont(R.font.inter)
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

    // ---------- One extension ----------

    fun showDetail(catalog: Catalog, entry: CatalogEntry) {
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

        val installed = CatalogInstaller.installedFacts(context, entry.packageName)
        val state = CatalogLabels.state(catalog, entry, installed?.versionCode, installed?.signer)
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
        box.addView(text("Source: ${entry.sourceUrl}", 13f, soft).apply { setPadding(0, px(8), 0, 0); isFocusable = true })

        val builder = PortalDialog.Builder(context)
            .setTitle(entry.name)
            .setView(box)
            .setBodyMaxHeightFraction(0.58f)
            .setNegativeButton("Close", null)
        when (state) {
            CatalogState.NOT_INSTALLED -> builder.setPositiveButton("Install") { _, _ -> startInstall(catalog, entry) }
            CatalogState.UPDATE_AVAILABLE -> builder.setPositiveButton("Update") { _, _ -> startInstall(catalog, entry) }
            else -> {}
        }
        builder.show()
    }

    // ---------- Installing ----------

    private fun notice(title: String, message: String) {
        PortalDialog.Builder(context).setTitle(title).setMessage(message).setNegativeButton("Close", null).show()
    }

    /** Checks there is something to install, then the PIN, then goes on. */
    private fun startInstall(catalog: Catalog, entry: CatalogEntry) {
        val (version, refusal) = CatalogInstallChecks.start(catalog, entry, CatalogInstaller.installedFacts(context, entry.packageName), CatalogClient.trustedNow(context))
        if (version == null) { notice("Can't install ${entry.name}", refusal.orEmpty()); return }
        if (!CatalogInstaller.canInstall(context)) {
            PortalDialog.Builder(context)
                .setTitle("Allow Portal to install apps")
                .setMessage("Android needs you to allow Portal to install apps before it can install an extension. Open the setting, turn it on for Portal, then come back.")
                .setPositiveButton("Open the setting") { _, _ ->
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (ParentPin.isSet(context)) PinDialogs.verify(context, "Parent PIN to install an extension") { download(catalog, entry, version) }
        // With no PIN, anyone using an adult profile could install. Like a kid's settings, a PIN is set first.
        else PinDialogs.create(
            context,
            message = "A parent PIN is needed to install extensions. ${ParentPin.MIN_LENGTH} digits.",
            onSet = { download(catalog, entry, version) }
        )
    }

    private fun download(catalog: Catalog, entry: CatalogEntry, version: CatalogVersion) {
        val line = text("Starting…", 16f).apply { setPadding(px(24), px(8), px(24), px(8)) }
        var job: Job? = null
        val progress = PortalDialog.Builder(context)
            .setTitle("Installing ${entry.name}")
            .setView(line)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        job = scope.launch {
            try {
                val file = CatalogInstaller.download(context, catalog, entry, version) { done, total ->
                    line.post { line.text = "Downloading…  ${if (total > 0) (done * 100 / total).toInt() else 0}%" }
                }
                CatalogInstaller.onOutcome = { outcome ->
                    CatalogInstaller.onOutcome = null
                    finished(entry, outcome)
                }
                CatalogInstaller.install(context, file, entry.packageName)
                // Android takes it from here: it asks the person to confirm, in its own screen.
                progress.dismiss()
            } catch (e: CancellationException) {
                progress.dismiss()
            } catch (e: Exception) {
                progress.dismiss()
                notice("Couldn't install ${entry.name}", e.message ?: "Something went wrong.")
            }
        }
    }

    private fun finished(entry: CatalogEntry, outcome: CatalogInstaller.Outcome) {
        when (outcome) {
            is CatalogInstaller.Outcome.Installed -> PortalDialog.Builder(context)
                .setTitle("${entry.name} is installed")
                .setMessage("Nothing is allowed yet. Review what it asks for, and turn on only what you want.")
                .setPositiveButton("Review") { _, _ -> onReview(outcome.packageName.ifEmpty { entry.packageName }) }
                .setNegativeButton("Later", null)
                .show()
            CatalogInstaller.Outcome.Cancelled -> Toast.makeText(context, "The install was cancelled", Toast.LENGTH_SHORT).show()
            is CatalogInstaller.Outcome.Failed -> notice("Couldn't install ${entry.name}", outcome.message)
        }
    }
}
