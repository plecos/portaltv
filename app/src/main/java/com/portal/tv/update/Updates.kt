package com.portal.tv.update

import android.content.Context
import org.json.JSONObject

/** A release on GitHub that Portal could update to: its version, what's new, and the APK to download. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkBytes: Long,
    /** The APK's SHA-256 as lowercase hex, when GitHub gives one. */
    val sha256: String?
)

/** Reading GitHub's "latest release", and comparing versions. No network or Android here, so it can be tested. */
object Updates {

    /** The release's version, from its tag ("v1.0.36" is "1.0.36"). */
    fun versionOfTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** "1.0.36" as [1, 0, 36], or null if it isn't made of dotted numbers. */
    fun parseVersion(version: String): List<Int>? {
        val parts = version.trim().split('.')
        if (parts.isEmpty()) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.all { n -> n >= 0 } }
    }

    /** Whether [latest] is a newer version than [current]. Compared number by number, so 1.0.10 is newer than 1.0.9. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release described by GitHub's JSON for a release, or null if it has no Portal APK (the file named
     * portal-<version>.apk) or no usable version.
     */
    fun parseLatest(json: String): ReleaseInfo? {
        val release = JSONObject(json)
        val version = versionOfTag(release.optString("tag_name"))
        if (parseVersion(version) == null) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.startsWith("portal-") || !name.endsWith(".apk")) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            return ReleaseInfo(
                version = version,
                notes = release.optString("body").trim(),
                apkUrl = url,
                apkBytes = asset.optLong("size", -1L),
                sha256 = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 }
            )
        }
        return null
    }

    /** The checksum as lowercase hex. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Release notes made shorter for a dialog: the lines that say what changed, without GitHub's headings and links. */
    fun tidyNotes(notes: String): String =
        notes.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("**Full Changelog**") }
            .joinToString("\n") { line ->
                line.removePrefix("* ").removePrefix("- ")
                    // "Title by @someone in https://github.com/…/pull/12" becomes "Title".
                    .replace(Regex("\\s+by @\\S+ in \\S+$"), "")
            }
            .ifEmpty { "No release notes." }

    // ---------- What the last check found, kept so Settings can say so without asking again ----------

    private const val PREFS = "portal_updates"

    fun saveCheck(context: Context, release: ReleaseInfo?, now: Long = System.currentTimeMillis()) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", now)
        if (release == null) {
            edit.remove("version").remove("notes").remove("url").remove("bytes").remove("sha256")
        } else {
            edit.putString("version", release.version).putString("notes", release.notes)
                .putString("url", release.apkUrl).putLong("bytes", release.apkBytes)
                .putString("sha256", release.sha256)
        }
        edit.apply()
    }

    /** The release the last check found, or null. */
    fun lastRelease(context: Context): ReleaseInfo? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = p.getString("version", null) ?: return null
        val url = p.getString("url", null) ?: return null
        return ReleaseInfo(version, p.getString("notes", "") ?: "", url, p.getLong("bytes", -1L), p.getString("sha256", null))
    }

    /** When a check was last tried, whether or not it worked. */
    fun lastAttemptAt(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("attempted", 0L)

    fun markAttempt(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("attempted", now).apply()
    }

    fun lastCheckedAt(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked", 0L)

    /** Whether the last check found a version newer than [installed]. */
    fun updateAvailable(context: Context, installed: String): Boolean =
        lastRelease(context)?.let { isNewer(it.version, installed) } == true
}
