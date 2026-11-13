package com.portal.tv.extensions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.portal.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Downloads a catalog extension, checks it, and hands it to Android to install. Portal never installs another app by
 * itself: Android always shows its own confirmation. What may be installed is decided by [CatalogInstallChecks]; this
 * class only gathers the facts from the file and does the work. Every failure is an [IOException] whose message can be
 * shown to the person.
 */
object CatalogInstaller {

    const val ACTION_RESULT = "com.portal.tv.extensions.CATALOG_INSTALL_RESULT"
    private const val TAG = "Portal"
    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"

    sealed class Outcome {
        data class Installed(val packageName: String) : Outcome()
        object Cancelled : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** Told how an install ended. Set by the page that started it, while it is open. */
    @Volatile var onOutcome: ((Outcome) -> Unit)? = null

    /** Whether Portal is allowed to install apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** What is installed now for [packageName], or null if nothing is. */
    fun installedFacts(context: Context, packageName: String): InstalledFacts? = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        InstalledFacts(info.longVersionCode, signerOf(info), signersOf(info))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * What is installed now for each of [packageNames] that is installed at all. Cheap even on a TV with hundreds of apps:
     * the names of everything installed are read without signatures, and signatures only for the ones asked about.
     * Blocks, so not on the main thread.
     */
    fun installedFacts(context: Context, packageNames: Collection<String>): Map<String, InstalledFacts> {
        val wanted = packageNames.toHashSet()
        val pm = context.packageManager
        val present = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).map { it.packageName }.filter { it in wanted }
        return present.mapNotNull { pkg -> installedFacts(context, pkg)?.let { pkg to it } }.toMap()
    }

    /**
     * Downloads [version] into the app's cache and checks it against the catalog. [onProgress] gets the bytes so far and
     * the expected total. Returns the file, ready to install; a file that fails any check is deleted.
     */
    suspend fun download(
        context: Context, catalog: Catalog, entry: CatalogEntry, version: CatalogVersion, onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = version.apkUrl
        if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) {
            throw IOException("The download isn't offered over a secure connection")
        }
        val dir = File(context.cacheDir, "catalog_apks").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${entry.packageName}-${version.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L

        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != 200) throw IOException("The download answered ${c.responseCode}")
            var reported = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        // The catalog says how big it is, so a file that keeps going is not what was listed.
                        if (done > version.sizeBytes) throw IOException("The download is bigger than what was listed, so it was stopped.")
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        if (done - reported >= 256 * 1024) { reported = done; onProgress(done, version.sizeBytes) }
                    }
                }
            }
            onProgress(done, version.sizeBytes)
        } catch (e: UnknownHostException) {
            file.delete()
            throw IOException("No connection")
        } catch (e: java.net.ConnectException) {
            file.delete()
            throw IOException("Couldn't reach the download")
        } catch (e: java.net.SocketTimeoutException) {
            file.delete()
            throw IOException("The download took too long")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled by the person: clean up and let the cancellation through as it is, not as an error.
            file.delete()
            throw e
        } catch (e: Exception) {
            file.delete()
            throw if (e is IOException) e else IOException(e.message ?: "The download failed")
        } finally {
            c.disconnect()
        }

        val why = CatalogInstallChecks.file(version, done, ExtensionAccess.hex(digest.digest()))
            ?: CatalogInstallChecks.archive(catalog, entry, version, readArchive(context, file), installedFacts(context, entry.packageName))
        if (why != null) {
            file.delete()
            throw IOException(why)
        }
        file
    }

    /** What the APK says about itself, read by Android from the file. */
    private fun readArchive(context: Context, file: File): ArchiveFacts {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            ?: throw IOException("The download isn't a valid app")
        val app = info.applicationInfo ?: throw IOException("The download isn't a valid app")
        app.sourceDir = file.path
        app.publicSourceDir = file.path

        var capabilities: Set<String>? = null
        var unknown = emptySet<String>()
        val id = app.metaData?.getInt(ExtensionRegistry.META_KEY, 0) ?: 0
        if (id != 0) {
            val parsed = runCatching {
                val res = pm.getResourcesForApplication(app)
                val xml = res.getXml(id)
                try {
                    XmlAdapter.read(xml, res)?.let { ManifestParser.parse(it, info.packageName) }
                } finally {
                    xml.close()
                }
            }.getOrNull()
            if (parsed is ManifestParser.Result.Ok) {
                capabilities = parsed.manifest.capabilities
                unknown = parsed.manifest.unknownCapabilities
            }
        }
        return ArchiveFacts(info.packageName, info.longVersionCode, signerOf(info), signersOf(info), capabilities, unknown)
    }

    /** Every certificate the app has been signed with, now or earlier (its rotation history), one hash each. */
    internal fun signersOf(info: android.content.pm.PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return certs.orEmpty().map { ExtensionAccess.sha256Hex(it.toByteArray()) }.toSet()
    }

    internal fun signerOf(info: android.content.pm.PackageInfo): String? =
        info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
            ?.let { signers -> ExtensionAccess.combineSigners(signers.map { ExtensionAccess.sha256Hex(it.toByteArray()) }) }

    /** Gives [apk] to Android's installer. Android asks the person to confirm; the answer comes to [onOutcome]. */
    fun install(context: Context, apk: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            // Always asked: Portal can never install another app without the person confirming.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("extension.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, CatalogInstallReceiver::class.java).setAction(ACTION_RESULT)
                // Mutable, so Android can add the status and the confirmation screen to it.
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw IOException(e.message ?: "Android wouldn't take the file")
        }
    }

    internal fun report(context: Context, outcome: Outcome) {
        Log.d(TAG, "extension install: $outcome")
        if (outcome is Outcome.Installed) BundledExtension.installed(context, outcome.packageName)
        // Whatever happened, the downloaded file is no longer needed (Android has its own copy once installed).
        File(context.cacheDir, "catalog_apks").listFiles()?.forEach { it.delete() }
        onOutcome?.invoke(outcome)
    }
}

/** Android's answer to an extension install: show its confirmation screen, or report how it ended. */
class CatalogInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
                CatalogInstaller.report(context, CatalogInstaller.Outcome.Installed(pkg))
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> CatalogInstaller.report(context, CatalogInstaller.Outcome.Cancelled)
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.d("Portal", "extension install failed: status $status, $why")
                CatalogInstaller.report(context, CatalogInstaller.Outcome.Failed("Android couldn't install it${why?.let { ": $it" } ?: ""}"))
            }
        }
    }
}
