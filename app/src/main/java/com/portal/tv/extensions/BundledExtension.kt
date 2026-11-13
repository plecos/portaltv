package com.portal.tv.extensions

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.portal.tv.BuildConfig
import java.io.File
import java.io.IOException

/**
 * The extension that ships inside Portal: the weather (Open-Meteo for the forecast, the National Weather Service for alerts).
 * Its APK is in Portal's assets. Portal installs it, and it updates through the catalog like any other extension.
 *
 * Two rules keep this from being a way round the ones that protect the TV:
 *
 * - **Android always asks.** Portal installs it through Android's installer, which shows its own confirmation, as for any
 *   extension from the catalog. Portal never installs an app silently.
 * - **Pre-allowed once, for this package and Portal's own key only.** Allowing an extension normally needs an adult's PIN.
 *   For this one package, and only when it is signed with the very key Portal is signed with, Portal allows its one
 *   capability (being a weather source) the first time it sees it installed, so weather works out of the box. After that it
 *   is an ordinary extension: an adult can turn it off, and Portal never turns it back on.
 */
object BundledExtension {

    /** The package of the weather extension that ships with Portal. */
    const val WEATHER_PACKAGE = "net.getportaltv.weather"

    private const val ASSET = "bundled/weather-extension.apk"
    private const val PREFS = "portal_bundled"
    private const val TAG = "Portal"

    /** How trying to install it ended. */
    enum class Result {
        /** Android has the file and is (or will be) asking the person to confirm. */
        STARTED,
        /** Already installed, at this version or newer. */
        UP_TO_DATE,
        /** Portal isn't allowed to install apps ("Install unknown apps"); the person has to allow it first. */
        NOT_ALLOWED,
        /** Asked already for this version of Portal and not again until it changes (or [installIfNeeded] is told to force it). */
        ALREADY_ASKED,
        /** An app with this package name is installed that isn't signed by Portal's key; it is left alone. */
        FOREIGN,
        /** There is no usable copy in Portal (not built in, or not signed by Portal's own key). */
        UNAVAILABLE
    }

    /** Whether the weather extension is installed and signed with Portal's own key (the one that ships with it). */
    fun isInstalled(context: Context): Boolean {
        val installed = CatalogInstaller.installedFacts(context, WEATHER_PACKAGE) ?: return false
        return installed.signer != null && installed.signer == ownSigner(context)
    }

    /**
     * Installs, or updates, the weather extension from Portal's assets if it isn't already there at this version or newer.
     * Unless [force], it asks at most once for each version of Portal, so a person who declined isn't asked at every start.
     * Blocks (copies a file), so not on the main thread.
     */
    fun installIfNeeded(context: Context, force: Boolean = false, onlyCheck: Boolean = false): Result {
        val own = ownSigner(context) ?: return Result.UNAVAILABLE
        val installed = CatalogInstaller.installedFacts(context, WEATHER_PACKAGE)
        if (installed != null && installed.signer != own) return Result.FOREIGN
        // Gone (uninstalled): what was allowed for it is forgotten, so a fresh copy is allowed again once, as the first was.
        if (installed == null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("weather_allowed").apply()
        val file = extract(context) ?: return Result.UNAVAILABLE
        try {
            val archive = context.packageManager.getPackageArchiveInfo(
                file.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
            val archiveSigner = archive?.let { CatalogInstaller.signerOf(it) }
            // It must be what it claims: the weather extension, signed with Portal's own key. A copy signed with anything else
            // (an unsigned build, say) is not installed.
            if (archive == null || archive.packageName != WEATHER_PACKAGE || archiveSigner == null || archiveSigner != own) {
                Log.w(TAG, "The bundled weather extension isn't signed with Portal's key; not installing it")
                file.delete()
                return Result.UNAVAILABLE
            }
            if (installed != null && installed.versionCode >= archive.longVersionCode) { file.delete(); return Result.UP_TO_DATE }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!force && prefs.getInt("asked_for", 0) == BuildConfig.VERSION_CODE) { file.delete(); return Result.ALREADY_ASKED }
            // Only asking whether it should be offered: nothing is installed, and what was asked is not recorded.
            if (onlyCheck) { file.delete(); return Result.STARTED }
            if (!CatalogInstaller.canInstall(context)) { file.delete(); return Result.NOT_ALLOWED }
            val edit = prefs.edit().putInt("asked_for", BuildConfig.VERSION_CODE)
            // The person asked for it (from setup or Settings), so an earlier Skip no longer holds.
            if (force) edit.putBoolean("declined", false)
            edit.apply()
            CatalogInstaller.install(context, file, WEATHER_PACKAGE)
            return Result.STARTED
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't install the bundled weather extension: ${e.message}")
            file.delete()
            return Result.UNAVAILABLE
        }
    }

    /** Whether to ask the person about installing it: it isn't there (or is older than the copy in Portal), they haven't said no, and a copy Portal can use is built in. */
    fun needsOffer(context: Context): Boolean =
        !isDeclined(context) && !offeredThisVersion(context) && installIfNeeded(context, force = true, onlyCheck = true) == Result.STARTED

    /** Whether Portal carries a copy it could install (built in and signed with Portal's own key), whatever was asked or answered before. */
    fun canOffer(context: Context): Boolean = installIfNeeded(context, force = true, onlyCheck = true) == Result.STARTED

    /** The question is asked once for each version of Portal, so coming back from Android's installer doesn't ask it again. */
    private fun offeredThisVersion(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("offered_for", 0) == BuildConfig.VERSION_CODE

    fun markOffered(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("offered_for", BuildConfig.VERSION_CODE).apply()
    }

    /** Whether the person chose Skip when asked to install it during setup. Portal then stops offering at each start; the button in Settings still installs it. */
    fun isDeclined(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("declined", false)

    fun setDeclined(context: Context, declined: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("declined", declined).apply()
    }

    /**
     * The first time the weather extension is seen installed and signed with Portal's own key, allows it to be a weather source
     * (nothing else it might ask for later). Does nothing after that, so an adult who turned it off stays obeyed.
     */
    fun allowOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("weather_allowed", false)) return
        val ext = ExtensionRegistry.find(context, WEATHER_PACKAGE) ?: return
        // Only the one signed with Portal's own key: the exact package name alone proves nothing.
        if (!ext.madeByPortal) return
        if (ExtensionRegistry.allow(context, WEATHER_PACKAGE, setOf(Capability.WEATHER_SOURCE, Capability.WEATHER_PAGE))) {
            prefs.edit().putBoolean("weather_allowed", true).apply()
            Log.d(TAG, "The weather extension that ships with Portal was allowed")
        }
    }

    private val checkedThisRun = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * What to do when Portal starts (once for each run of the app): allow the weather extension if it has just arrived. It never
     * installs anything: a person who upgraded is asked on the home screen ([com.portal.tv.ui.WeatherOffer]), and a new one in setup.
     * Not on the main thread.
     */
    fun onStart(context: Context) {
        if (!checkedThisRun.compareAndSet(false, true)) return
        allowOnce(context)
    }

    /** The copy in Portal's assets, written to the cache so Android can read it; null if there isn't one. */
    private fun extract(context: Context): File? = try {
        val dir = File(context.cacheDir, "bundled").apply { mkdirs() }
        val file = File(dir, "weather-extension.apk")
        context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        file
    } catch (e: IOException) {
        null
    }

    /** Who signed Portal itself, in the form an approval is pinned to. */
    private fun ownSigner(context: Context): String? = CatalogInstaller.installedFacts(context, context.packageName)?.signer

    /** Told when the weather extension has just been installed and allowed. Set by the Settings page while it is open. */
    @Volatile var onInstalled: (() -> Unit)? = null

    /** Called when an install ends; allows the extension if it was the weather one that arrived. */
    internal fun installed(context: Context, packageName: String) {
        if (packageName != WEATHER_PACKAGE) return
        File(context.cacheDir, "bundled").listFiles()?.forEach { it.delete() }
        allowOnce(context)
        onInstalled?.invoke()
    }
}
