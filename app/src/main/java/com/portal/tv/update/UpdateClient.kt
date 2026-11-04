package com.portal.tv.update

import android.content.Context
import android.content.pm.PackageManager
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
 * Asks GitHub for Portal's newest release, downloads its APK, and checks it before anything is installed: that it is
 * complete, matches GitHub's checksum when there is one, and is signed with the same key as this app (which Android
 * insists on for an update anyway). Every failure is an [IOException] whose message can be shown to the person.
 */
object UpdateClient {

    private const val LATEST_URL = "https://api.github.com/repos/plecos/portaltv/releases/latest"
    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"

    /** Debug builds can point the check at a test server, through a preference, to try the update without a release. */
    private fun latestUrl(context: Context): String {
        if (!BuildConfig.DEBUG) return LATEST_URL
        return context.getSharedPreferences("update_debug", Context.MODE_PRIVATE).getString("latest_url", null) ?: LATEST_URL
    }

    /** Release builds only follow https, so the file can't be swapped on the way. Debug builds may use a test server. */
    internal fun isTrusted(url: String, debug: Boolean = BuildConfig.DEBUG): Boolean =
        url.startsWith("https://") || (debug && url.startsWith("http://"))

    /** The newest release, or null if there isn't one with a Portal APK. Blocks; call it off the main thread. */
    fun latest(context: Context): ReleaseInfo? {
        val c = URL(latestUrl(context)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            return when (val code = c.responseCode) {
                200 -> Updates.parseLatest(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                404 -> null
                403, 429 -> throw IOException("GitHub is busy just now. Try again in a little while")
                else -> throw IOException("GitHub answered $code")
            }
        } catch (e: UnknownHostException) {
            throw IOException("No connection")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [release]'s APK into the app's cache and checks it. [onProgress] gets the bytes so far and the total (or
     * -1 if not known). Returns the file, ready to install.
     */
    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!isTrusted(release.apkUrl)) throw IOException("The update isn't offered over a secure connection")
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "portal-${release.version}.apk")
            val digest = MessageDigest.getInstance("SHA-256")

            val c = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                if (c.responseCode != 200) throw IOException("The download answered ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
                var done = 0L
                var reported = 0L
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (done - reported >= 512 * 1024) { reported = done; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
            } catch (e: UnknownHostException) {
                file.delete()
                throw IOException("No connection")
            } catch (e: IOException) {
                file.delete()
                throw e
            } finally {
                c.disconnect()
            }

            try {
                check(context, release, file, Updates.hex(digest.digest()))
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            file
        }

    /** Throws if the downloaded [file] isn't the update it should be. */
    private fun check(context: Context, release: ReleaseInfo, file: File, sha256: String) {
        if (release.apkBytes > 0 && file.length() != release.apkBytes) {
            throw IOException("The download was incomplete. Try again")
        }
        if (release.sha256 != null && release.sha256 != sha256) {
            throw IOException("The download didn't match its checksum, so it was thrown away")
        }
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw IOException("The download isn't a valid app")
        if (archive.packageName != context.packageName) throw IOException("The download is a different app")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("The download isn't newer than this version")
        val theirs = archive.signingInfo?.apkContentsSigners?.toSet()
        val ours = installed.signingInfo?.apkContentsSigners?.toSet()
        if (theirs.isNullOrEmpty() || ours.isNullOrEmpty() || theirs != ours) {
            throw IOException("The download isn't signed with Portal's key, so it can't replace this app")
        }
    }
}
