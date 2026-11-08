package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One downloadable version of a catalog extension. The APK is hosted by its author; the catalog holds a link and a checksum. */
data class CatalogVersion(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK file. */
    val sha256: String,
    val sizeBytes: Long,
    /** The extension contract number this version needs; compare with [ManifestParser.CONTRACT_VERSION]. */
    val contract: Int
)

/** An extension the catalog lists. */
data class CatalogEntry(
    val packageName: String,
    val name: String,
    val description: String,
    val author: String,
    /** Where the source is, required. */
    val sourceUrl: String,
    /** Shown to the person, not judged by Portal. Empty if not given. */
    val license: String,
    /** What the extension asks to be allowed to do. An installed APK may declare no more than this. */
    val capabilities: Set<String>,
    /** The signing certificate, in the form [ExtensionAccess.combineSigners] gives. */
    val signer: String,
    /** Short lowercase words for finding it, like "weather" or "kids". Up to [CatalogParser.MAX_TAGS]. */
    val tags: List<String>,
    /**
     * A small PNG in the catalog's own folder, as a path relative to catalog.json (like `icons/garage.png`), or empty.
     * Always the catalog's host, so browsing never contacts the author's server.
     */
    val iconPath: String,
    val screenshots: List<String>,
    /** Newest first. */
    val versions: List<CatalogVersion>
)

/** A line in the catalog's revoked list: an extension, version or signer that must not be used any more. */
data class CatalogRevocation(
    /** The package this applies to, or null for everything signed by [signer]. */
    val packageName: String?,
    /** Just this version, or null for every version of the package. */
    val versionCode: Long?,
    /** Everything signed by this certificate, or null. */
    val signer: String?,
    val reason: String
)

data class Catalog(
    val updated: String,
    val extensions: List<CatalogEntry>,
    val revoked: List<CatalogRevocation>,
    /**
     * Goes up with every published catalog. Portal never goes back to a lower one, so an old signed copy (from before a
     * withdrawal, say) can't be put back in front of it. 0 if the file has none.
     */
    val serial: Long = 0,
    /** When installing from this catalog is paused until a newer one is published (end of that day, UTC); 0 for never. */
    val expiresAtMs: Long = 0,
    /** The day it was published (start of that day, UTC), from the signed file itself; 0 if `updated` isn't a date. */
    val updatedAtMs: Long = 0
) {
    fun find(packageName: String): CatalogEntry? = extensions.firstOrNull { it.packageName == packageName }
}

/**
 * Reads and checks `catalog.json`. Everything in it comes from the network, so every value is checked and bounded. An
 * entry that is wrong is left out and reported in [Result.Ok.problems] (so one bad entry can't hide the rest in Portal,
 * and the catalog repo's checks can refuse any problem at all). A file that isn't a catalog at all is [Result.Invalid].
 */
object CatalogParser {

    /** The catalog format this Portal reads. A file with a higher number is not read. */
    const val FORMAT = 1

    const val MAX_FILE_CHARS = 2_000_000
    const val MAX_ENTRIES = 500
    const val MAX_REVOKED = 1000
    const val MAX_VERSIONS = 20
    const val MAX_SCREENSHOTS = 4
    const val MAX_TAGS = 5
    const val MAX_TAG = 20
    const val MAX_URL = 300
    const val MAX_AUTHOR = 60
    const val MAX_LICENSE = 40
    const val MAX_REASON = 200
    /** The largest APK the catalog may list, so a listing can't fill the TV's storage. */
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val TAG = Regex("[a-z0-9][a-z0-9-]{0,19}")
    private val ICON = Regex("[a-z0-9][a-z0-9_./-]{0,99}\\.png")

    sealed class Result {
        data class Ok(val catalog: Catalog, val problems: List<String>) : Result()
        data class Invalid(val reason: String) : Result()
        data class NeedsNewerPortal(val format: Int) : Result()
    }

    /**
     * [allowHttp] is for a test server in debug builds only; the catalog and every link in it are https otherwise.
     * [checkNames] is for the checks on catalog pull requests: it refuses a listing whose name or package name could pass for
     * Portal's own, unless its signer is one of [firstPartySigners]. Portal itself reads a signed catalog without it: the
     * signature is what says the maintainer put each listing there, and Portal's own extensions are listed under Portal's names.
     */
    fun parse(text: String, allowHttp: Boolean = false, checkNames: Boolean = false, firstPartySigners: Set<String> = emptySet()): Result {
        if (text.length > MAX_FILE_CHARS) return Result.Invalid("The catalog is too big.")
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Result.Invalid("The catalog isn't a JSON object.")
        }
        val format = (root.opt("format") as? Int)?.takeIf { it >= 1 } ?: return Result.Invalid("It doesn't say which format it uses.")
        if (format > FORMAT) return Result.NeedsNewerPortal(format)

        val problems = mutableListOf<String>()
        val updated = (root.opt("updated") as? String).orEmpty().take(40)
        val serial = if (root.has("serial")) (root.opt("serial") as? Number)?.toLong()?.takeIf { it >= 1 } ?: return Result.Invalid("Its serial isn't a whole number from 1.") else 0L
        val expiresAt = if (root.has("expires")) {
            val day = (root.opt("expires") as? String)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?: return Result.Invalid("Its expires isn't a date like 2027-01-31.")
            day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } else 0L

        val entries = mutableListOf<CatalogEntry>()
        val list = root.opt("extensions") as? JSONArray ?: return Result.Invalid("It has no list of extensions.")
        if (list.length() > MAX_ENTRIES) return Result.Invalid("It lists more than $MAX_ENTRIES extensions.")
        for (i in 0 until list.length()) {
            val obj = list.opt(i) as? JSONObject
            if (obj == null) { problems += "Extension ${i + 1} isn't an object."; continue }
            when (val r = entry(obj, allowHttp, checkNames, firstPartySigners)) {
                is Entry.Bad -> problems += "${obj.optString("package", "Extension ${i + 1}")}: ${r.reason}"
                is Entry.Good -> {
                    if (entries.any { it.packageName == r.entry.packageName }) problems += "${r.entry.packageName}: listed twice."
                    else entries += r.entry
                }
            }
        }

        val revoked = mutableListOf<CatalogRevocation>()
        val rl = root.opt("revoked")
        if (rl != null && rl !is JSONArray) return Result.Invalid("Its revoked list isn't a list.")
        if (rl is JSONArray) {
            if (rl.length() > MAX_REVOKED) return Result.Invalid("Its revoked list is longer than $MAX_REVOKED.")
            for (i in 0 until rl.length()) {
                val r = revocation(rl.opt(i) as? JSONObject)
                if (r.second != null) problems += "Revoked line ${i + 1}: ${r.second}" else revoked += r.first!!
            }
        }
        // A publication day after the expiry would lift the time floor past the point the catalog stops installing.
        val updatedAt = runCatching { java.time.LocalDate.parse(updated).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)
        if (updatedAt > 0 && expiresAt > 0 && updatedAt >= expiresAt) return Result.Invalid("Its updated date is after its expires date.")
        return Result.Ok(Catalog(updated, entries, revoked, serial, expiresAt, updatedAt), problems)
    }

    private sealed class Entry {
        data class Good(val entry: CatalogEntry) : Entry()
        data class Bad(val reason: String) : Entry()
    }

    private fun entry(o: JSONObject, allowHttp: Boolean, checkNames: Boolean, firstPartySigners: Set<String>): Entry {
        fun bad(why: String) = Entry.Bad(why)
        val pkg = o.opt("package") as? String ?: return bad("it has no package.")
        if (pkg.length > 100 || !PACKAGE.matches(pkg)) return bad("the package name isn't valid.")
        val name = text(o, "name", ManifestParser.MAX_LABEL) ?: return bad("it has no name, or one that is too long.")
        val description = text(o, "description", ManifestParser.MAX_DESCRIPTION) ?: return bad("it has no description, or one that is too long.")
        val author = text(o, "author", MAX_AUTHOR) ?: return bad("it has no author, or one that is too long.")
        val source = url(o.opt("source"), allowHttp) ?: return bad("it needs a source link that is an https address.")
        val license = if (o.has("license")) (text(o, "license", MAX_LICENSE) ?: return bad("its license is empty or too long.")) else ""

        val caps = o.opt("capabilities") as? JSONArray ?: return bad("it lists no capabilities (an empty list is fine).")
        val capabilities = linkedSetOf<String>()
        for (i in 0 until caps.length()) {
            val c = caps.opt(i) as? String ?: return bad("a capability isn't text.")
            if (c !in Capability.known) return bad("it asks for \"$c\", which this Portal doesn't know.")
            capabilities += c
        }

        val signer = signer(o.opt("signer")) ?: return bad("its signer isn't a SHA-256 fingerprint.")
        // The same rules Portal applies to an installed extension, run on catalog pull requests so a refused name is found with the
        // reason there instead of on a TV. Portal's own extensions (signed by a first-party key) may use Portal's names.
        if (checkNames && signer !in firstPartySigners) {
            Impersonation.reason(name)?.let { return bad("its name \"$name\" $it") }
            if (Impersonation.isReservedPackage(pkg)) return bad("its package name is reserved for Portal's own apps (unless it is signed with a first-party key; see docs/CATALOG.md).")
        }

        val tags = mutableListOf<String>()
        val tl = o.opt("tags")
        if (tl != null) {
            if (tl !is JSONArray || tl.length() > MAX_TAGS) return bad("its tags aren't a list of at most $MAX_TAGS.")
            for (i in 0 until tl.length()) {
                val t = (tl.opt(i) as? String)?.lowercase()?.takeIf { TAG.matches(it) }
                    ?: return bad("a tag isn't lowercase letters, digits and dashes, up to $MAX_TAG characters.")
                if (t !in tags) tags += t
            }
        }
        val icon = if (o.has("icon")) {
            (o.opt("icon") as? String)?.takeIf { ICON.matches(it) && ".." !in it && "//" !in it }
                ?: return bad("its icon isn't a .png path inside the catalog's folder.")
        } else ""

        val shots = mutableListOf<String>()
        val sl = o.opt("screenshots")
        if (sl != null) {
            if (sl !is JSONArray || sl.length() > MAX_SCREENSHOTS) return bad("its screenshots aren't a list of at most $MAX_SCREENSHOTS.")
            for (i in 0 until sl.length()) shots += url(sl.opt(i), allowHttp) ?: return bad("a screenshot isn't an https address.")
        }

        val vl = o.opt("versions") as? JSONArray ?: return bad("it has no versions.")
        if (vl.length() == 0 || vl.length() > MAX_VERSIONS) return bad("it needs between 1 and $MAX_VERSIONS versions.")
        val versions = mutableListOf<CatalogVersion>()
        for (i in 0 until vl.length()) {
            val v = vl.opt(i) as? JSONObject ?: return bad("a version isn't an object.")
            val code = (v.opt("version_code") as? Number)?.toLong()?.takeIf { it >= 1 } ?: return bad("a version has no version_code.")
            if (versions.any { it.versionCode == code }) return bad("version $code is listed twice.")
            val vname = text(v, "version_name", 40) ?: return bad("version $code has no version_name.")
            val apk = url(v.opt("apk"), allowHttp) ?: return bad("version $code needs an apk link that is an https address.")
            val sha = (v.opt("sha256") as? String)?.lowercase()?.takeIf { HEX64.matches(it) } ?: return bad("version $code has no valid sha256.")
            val size = (v.opt("size") as? Number)?.toLong()?.takeIf { it in 1..MAX_APK_BYTES } ?: return bad("version $code has no size, or one over ${MAX_APK_BYTES / 1024 / 1024} MB.")
            val contract = (v.opt("contract") as? Int)?.takeIf { it >= 1 } ?: return bad("version $code doesn't say which contract it needs.")
            versions += CatalogVersion(code, vname, apk, sha, size, contract)
        }
        return Entry.Good(
            CatalogEntry(pkg, name, description, author, source, license, capabilities, signer, tags, icon, shots, versions.sortedByDescending { it.versionCode })
        )
    }

    private fun revocation(o: JSONObject?): Pair<CatalogRevocation?, String?> {
        if (o == null) return null to "it isn't an object."
        val pkg = o.opt("package")?.let { (it as? String)?.takeIf { p -> p.length <= 100 && PACKAGE.matches(p) } ?: return null to "the package isn't valid." }
        val signer = o.opt("signer")?.let { signer(it) ?: return null to "the signer isn't a SHA-256 fingerprint." }
        if (pkg == null && signer == null) return null to "it names neither a package nor a signer."
        val code = o.opt("version_code")?.let { (it as? Number)?.toLong()?.takeIf { c -> c >= 1 } ?: return null to "the version_code isn't valid." }
        if (code != null && pkg == null) return null to "a version_code needs a package."
        val reason = text(o, "reason", MAX_REASON) ?: return null to "it gives no reason, or one that is too long."
        return CatalogRevocation(pkg as String?, code, signer, reason) to null
    }

    /** Plain text of 1 to [max] characters with nothing that can't be shown. */
    private fun text(o: JSONObject, key: String, max: Int): String? {
        val s = (o.opt(key) as? String)?.trim() ?: return null
        return s.takeIf { it.isNotEmpty() && it.length <= max && TextSafety.isPlain(s) }
    }

    /** An https address (http too when allowed) with a host and no name or password in it. */
    private fun url(value: Any?, allowHttp: Boolean): String? {
        val s = value as? String ?: return null
        if (s.length > MAX_URL || s.any { it.isISOControl() || it == ' ' } || !TextSafety.isPlain(s)) return null
        val rest = when {
            s.startsWith("https://") -> s.removePrefix("https://")
            allowHttp && s.startsWith("http://") -> s.removePrefix("http://")
            else -> return null
        }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isEmpty() || '@' in host) return null
        return s
    }

    /** One or more SHA-256 fingerprints joined by "+", put in the order [ExtensionAccess.combineSigners] uses. */
    private fun signer(value: Any?): String? {
        val parts = (value as? String)?.lowercase()?.split('+') ?: return null
        if (parts.isEmpty() || parts.any { !HEX64.matches(it) }) return null
        return ExtensionAccess.combineSigners(parts)
    }
}

/** What the catalog's lines mean for a package or an install. All pure, so it is the same everywhere it is used. */
object CatalogRules {

    /** The first revocation that applies to this package, version and signer, or null. A package that isn't listed can still be revoked. */
    fun revocation(catalog: Catalog, packageName: String, versionCode: Long?, signer: String?): CatalogRevocation? =
        revocationBySigners(catalog, packageName, versionCode, listOfNotNull(signer))

    /**
     * Like [revocation] for an app that may have been signed by several keys, or by a key it has since rotated away
     * from: a line that names a signer applies if ANY of the app's certificates (each joined group is split into its
     * certificates) is that signer or one of the signers in the line. So withdrawing one key is not dodged by rotating to
     * another, or by adding a second signer.
     */
    fun revocationBySigners(catalog: Catalog, packageName: String, versionCode: Long?, signers: Collection<String>): CatalogRevocation? {
        val have = signers.flatMap { it.split('+') }.toSet()
        // A line applies when everything it names matches; what it leaves out matches anything.
        return catalog.revoked.firstOrNull { r ->
            (r.packageName == null || r.packageName == packageName) &&
                (r.signer == null || r.signer.split('+').any { it in have }) &&
                (r.versionCode == null || r.versionCode == versionCode)
        }
    }

    /** The version to offer: the newest this Portal can use and that isn't revoked. Null if none. */
    fun best(catalog: Catalog, entry: CatalogEntry, contract: Int = ManifestParser.CONTRACT_VERSION): CatalogVersion? =
        entry.versions.firstOrNull {
            it.contract <= contract && revocation(catalog, entry.packageName, it.versionCode, entry.signer) == null
        }

    /** True when the catalog offers something newer than what is installed. */
    fun updateAvailable(catalog: Catalog, entry: CatalogEntry, installedVersionCode: Long, contract: Int = ManifestParser.CONTRACT_VERSION): Boolean {
        val newest = best(catalog, entry, contract) ?: return false
        return newest.versionCode > installedVersionCode
    }

    /** An installed extension's capabilities must be no more than the catalog said; the extras, if any, are returned. */
    fun undeclared(entry: CatalogEntry, declared: Set<String>): Set<String> = declared - entry.capabilities
}
