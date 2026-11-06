package com.portal.tv.extensions

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/** An installed app that declares itself an extension, and where it stands. */
data class Extension(
    val packageName: String,
    /** What the app is called on the TV, for when its own file can't be read. */
    val appLabel: String,
    val parsed: ManifestParser.Result,
    /** The tiles whose screens exist and can be opened; ones that can't are left out and explained in [problems]. */
    val tiles: List<ExtensionTile>,
    val settingsActivity: String?,
    /** The home screen items that can be shown: text, and buttons whose screens exist and can be opened. */
    val items: List<ExtensionItem>,
    /** The service that draws its home screen background, if it exists and Portal may bind to it. */
    val backgroundService: String?,
    val problems: List<String>,
    /** Fingerprint of who signed it now. */
    val signer: String?,
    val state: ExtensionState,
    /** What it may do right now. */
    val granted: Set<String>,
    /** Why the catalog withdrew it, when [state] is [ExtensionState.REVOKED]. */
    val revokedReason: String? = null
) {
    val manifest: ExtensionManifest? get() = (parsed as? ManifestParser.Result.Ok)?.manifest
    val name: String get() = manifest?.label ?: appLabel
}

/**
 * Finds the extensions installed on the TV and answers what each may do. Portal never loads an extension's code: it
 * reads the file an extension names in its manifest, and checks the app's signer against the one an adult approved.
 */
object ExtensionRegistry {

    /** The application metadata key an extension uses to point at its `portal-extension` XML resource. */
    const val META_KEY = "net.getportaltv.extension"

    private const val TAG = "Portal"

    /** Every installed extension, in name order. */
    fun scan(context: Context): List<Extension> {
        val pm = context.packageManager
        forgetUninstalled(context, pm)
        val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        return apps.filter { it.packageName != context.packageName && it.enabled && it.metaData?.containsKey(META_KEY) == true }
            .map { describe(context, pm, it) }
            .sortedBy { it.name.lowercase() }
    }

    /** Whether any extension is installed, without reading any of them. */
    fun anyInstalled(context: Context): Boolean =
        context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .any { it.packageName != context.packageName && it.enabled && it.metaData?.containsKey(META_KEY) == true }

    /**
     * What was allowed for an app that has been uninstalled is forgotten, so putting it back later starts from nothing
     * (a disabled app is still installed, so it keeps what it was allowed).
     */
    private fun forgetUninstalled(context: Context, pm: PackageManager) {
        for (pkg in ExtensionStore.all(context).keys) {
            val installed = try { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)); true } catch (e: PackageManager.NameNotFoundException) { false }
            if (!installed) forget(context, pkg)
        }
        // Reminders and item changes of an extension that is gone (even one never allowed) go with it.
        for (pkg in com.portal.tv.reminders.ReminderStore.all(context).mapNotNull { it.source }.toSet() + ExtensionItemStore.packages(context) + ExtensionTileStore.packages(context)) {
            val installed = try { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)); true } catch (e: PackageManager.NameNotFoundException) { false }
            if (!installed) forget(context, pkg)
        }
    }

    /** Everything Portal kept for [packageName]: what was allowed, its reminders and its item changes. */
    fun forget(context: Context, packageName: String) {
        ExtensionStore.remove(context, packageName)
        ExtensionItemStore.remove(context, packageName)
        ExtensionTileStore.remove(context, packageName)
        com.portal.tv.reminders.ReminderStore.clearForSource(context, packageName)
    }

    /** One installed package, or null if it isn't an extension. */
    fun find(context: Context, packageName: String): Extension? {
        val pm = context.packageManager
        val app = try {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        if (packageName == context.packageName || !app.enabled || app.metaData?.containsKey(META_KEY) != true) return null
        return describe(context, pm, app)
    }

    /** What [packageName] may do right now: nothing if it isn't an extension, isn't allowed, or its signer has changed. */
    fun granted(context: Context, packageName: String): Set<String> = find(context, packageName)?.granted ?: emptySet()

    /**
     * An adult allows [capabilities] for [packageName] (only ones it asks for count). This pins the signer it has now. It
     * does nothing for an extension that can't be used, and returns whether anything was saved.
     */
    fun allow(context: Context, packageName: String, capabilities: Set<String>): Boolean {
        val ext = find(context, packageName) ?: return false
        // A withdrawn extension can't be allowed, whatever is asked.
        if (ext.state == ExtensionState.REVOKED) return false
        val manifest = ext.manifest ?: return false
        val signer = ext.signer ?: return false
        val wanted = Capability.known.filter { it in capabilities && it in manifest.capabilities }.toCollection(linkedSetOf())
        if (wanted.isEmpty()) {
            ExtensionStore.remove(context, packageName)
        } else {
            ExtensionStore.put(context, packageName, Allowance(signer, wanted))
        }
        // Turning something off takes back what the extension made with it.
        if (Capability.REMINDERS !in wanted) com.portal.tv.reminders.ReminderStore.clearForSource(context, packageName)
        ItemArea.values().filter { it.capability !in wanted }.forEach { ExtensionItemStore.clearArea(context, packageName, it) }
        if (Capability.TILES !in wanted) ExtensionTileStore.remove(context, packageName)
        return true
    }

    /** Takes back everything allowed for [packageName]. */
    fun disallow(context: Context, packageName: String) {
        ExtensionStore.remove(context, packageName)
        ExtensionItemStore.remove(context, packageName)
        ExtensionTileStore.remove(context, packageName)
        com.portal.tv.reminders.ReminderStore.clearForSource(context, packageName)
    }

    // ---------- Reading one extension ----------

    private fun describe(context: Context, pm: PackageManager, app: ApplicationInfo): Extension {
        val pkg = app.packageName
        val appLabel = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
        val parsed = readManifest(pm, app)
        val problems = mutableListOf<String>()
        var tiles = emptyList<ExtensionTile>()
        var settings: String? = null
        var items = emptyList<ExtensionItem>()
        var background: String? = null
        if (parsed is ManifestParser.Result.Ok) {
            tiles = parsed.manifest.tiles.filter { tile ->
                canOpen(pm, pkg, tile.activity).also { if (!it) problems += "The tile \"${tile.label}\" opens a screen that isn't there or isn't open to Portal." }
            }
            val usable = parsed.manifest.items.filter { item ->
                val screen = item.activity
                (screen == null || canOpen(pm, pkg, screen)).also { if (!it) problems += "The button \"${item.text}\" opens a screen that isn't there or isn't open to Portal." }
            }
            // What it has changed while running (new words, taken away, lines it added) is applied on top.
            items = ItemOverrides.apply(usable, ExtensionItemStore.get(context, pkg))
            background = parsed.manifest.backgroundService?.takeIf { canBind(pm, pkg, it).also { ok -> if (!ok) problems += "Its background service isn't there or isn't open to Portal." } }
            settings = parsed.manifest.settingsActivity?.takeIf { canOpen(pm, pkg, it).also { ok -> if (!ok) problems += "Its settings screen isn't there or isn't open to Portal." } }
        }
        val signer = signerOf(pm, pkg)
        val allowance = ExtensionStore.get(context, pkg)
        val matches = allowance != null && signer != null && signerMatches(pm, pkg, allowance.signer, signer)
        val declared = (parsed as? ManifestParser.Result.Ok)?.manifest?.capabilities.orEmpty()
        val granted = if (parsed is ManifestParser.Result.Ok) ExtensionAccess.granted(declared, allowance, matches) else emptySet()
        // The catalog's withdrawn list, from the copy kept on the TV (no network here). Withdrawn means off, and what was
        // allowed is forgotten, so it has to be allowed again by a person if it ever comes back.
        val installedVersion = runCatching { pm.getPackageInfo(pkg, 0).longVersionCode }.getOrNull()
        val revocation = CatalogClient.cachedCatalog(context)?.let { CatalogRules.revocation(it, pkg, installedVersion, signer) }
        if (revocation != null && allowance != null) disallow(context, pkg)
        val (state, effective) = ExtensionAccess.withRevocation(ExtensionAccess.state(parsed, allowance, matches), granted, revocation)
        return Extension(pkg, appLabel, parsed, tiles, settings, items, background, problems, signer, state, effective, revocation?.reason)
    }

    private fun readManifest(pm: PackageManager, app: ApplicationInfo): ManifestParser.Result {
        val id = app.metaData?.getInt(META_KEY, 0) ?: 0
        if (id == 0) return ManifestParser.Result.Invalid("Its extension file couldn't be found.")
        return try {
            val res = pm.getResourcesForApplication(app)
            val parser = res.getXml(id)
            try {
                val root = XmlAdapter.read(parser, res) ?: return ManifestParser.Result.Invalid("Its extension file is empty.")
                ManifestParser.parse(root, app.packageName)
            } finally {
                parser.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the extension file of ${app.packageName}: ${e.message}")
            ManifestParser.Result.Invalid("Its extension file couldn't be read.")
        }
    }

    /** A screen of the extension that Portal is allowed to open: it exists, is turned on, and is exported. */
    private fun canOpen(pm: PackageManager, packageName: String, className: String): Boolean = try {
        val info = pm.getActivityInfo(ComponentName(packageName, className), 0)
        info.exported && info.enabled && info.applicationInfo?.enabled != false
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** A service of the extension that Portal may bind to: it exists, is turned on, and is exported. */
    private fun canBind(pm: PackageManager, packageName: String, className: String): Boolean = try {
        val info = pm.getServiceInfo(ComponentName(packageName, className), 0)
        info.exported && info.enabled && info.applicationInfo?.enabled != false
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    // ---------- Signers ----------

    /** One fingerprint for everyone who signed the app now, or null if it can't be read. */
    private fun signerOf(pm: PackageManager, packageName: String): String? = try {
        val info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
            ?.let { signers -> ExtensionAccess.combineSigners(signers.map { ExtensionAccess.sha256Hex(it.toByteArray()) }) }
    } catch (e: Exception) {
        null
    }

    /**
     * Whether the app is still signed by who was approved: the same signer, or (for an app that changed its signing key
     * the way Android allows, with a proof signed by the old key) one whose history includes the approved signer.
     */
    private fun signerMatches(pm: PackageManager, packageName: String, pinned: String, current: String): Boolean {
        if (pinned == current) return true
        val bytes = ExtensionAccess.fromHex(pinned) ?: return false
        return try {
            pm.hasSigningCertificate(packageName, bytes, PackageManager.CERT_INPUT_SHA256)
        } catch (e: Exception) {
            false
        }
    }
}
