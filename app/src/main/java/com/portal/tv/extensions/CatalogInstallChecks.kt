package com.portal.tv.extensions

/** What is known about an APK once it has been downloaded, read from the file itself and not from what anyone claims. */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    /** Who signed it, in the form [ExtensionAccess.combineSigners] gives; null if it can't be told. */
    val signer: String?,
    /** Every certificate in its signing history (a key it has rotated away from still counts), one hash each. */
    val signers: Set<String> = emptySet(),
    /** What its extension file asks for, or null if there is no extension file or it can't be read. */
    val capabilities: Set<String>?,
    /** Capabilities it asks for that this Portal doesn't know. */
    val unknownCapabilities: Set<String>
)

/** The same extension as it is installed on this TV now. */
data class InstalledFacts(val versionCode: Long, val signer: String?, val signers: Set<String> = emptySet())

/**
 * Every check made before a catalog extension is installed, without Android so each can be tested. Each returns the reason
 * in words for the person, or null when the check passes. Nothing here trusts the catalog alone: the catalog says what
 * the file should be, and the file is checked to be that.
 */
object CatalogInstallChecks {

    /** Before anything is downloaded: is there something to install, and is it allowed? */
    fun start(
        catalog: Catalog, entry: CatalogEntry, installed: InstalledFacts?, nowMs: Long = System.currentTimeMillis()
    ): Pair<CatalogVersion?, String?> {
        if (CatalogLabels.expired(catalog, nowMs)) {
            return null to "The catalog hasn't been updated for a while, so installing from it is paused until it is."
        }
        // Withdrawn by package, or by any key the listing or the installed app has ever been signed with.
        val keys = listOf(entry.signer) + installed?.signers.orEmpty() + listOfNotNull(installed?.signer)
        CatalogRules.revocationBySigners(catalog, entry.packageName, null, keys)?.let {
            return null to "This extension has been withdrawn from the catalog: ${it.reason}"
        }
        val version = CatalogRules.best(catalog, entry)
            ?: return null to "There isn't a version of it this Portal can install."
        if (installed != null) {
            // An installed app whose signer can't be read can't be shown to be the listed one: refuse rather than assume.
            if (installed.signer == null) return null to "Portal can't tell who signed the app on this TV, so it can't be replaced from here."
            if (installed.signer != entry.signer) {
                return null to "The app on this TV is signed by someone else, so it isn't the listed extension and can't be replaced from here."
            }
            if (installed.versionCode >= version.versionCode) return null to "You already have the newest version."
        }
        return version to null
    }

    /** The downloaded file is the size and has the checksum the catalog gives. */
    fun file(version: CatalogVersion, actualSize: Long, actualSha256: String): String? = when {
        actualSize != version.sizeBytes -> "The download was incomplete or isn't the file that was listed, so it was thrown away."
        !actualSha256.equals(version.sha256, ignoreCase = true) -> "The download didn't match its checksum, so it was thrown away."
        else -> null
    }

    /** The file, opened, is the extension the catalog says, signed by who the catalog says, asking for no more than listed. */
    fun archive(catalog: Catalog, entry: CatalogEntry, version: CatalogVersion, archive: ArchiveFacts, installed: InstalledFacts?): String? {
        if (archive.packageName != entry.packageName) return "The download is a different app from the one listed."
        if (archive.versionCode != version.versionCode) return "The download is a different version from the one listed."
        if (archive.signer == null) return "The download isn't signed."
        if (archive.signer != entry.signer) return "The download isn't signed by the person the catalog lists."
        CatalogRules.revocationBySigners(catalog, archive.packageName, archive.versionCode, archive.signers + listOfNotNull(archive.signer))?.let {
            return "This version has been withdrawn from the catalog: ${it.reason}"
        }
        if (installed != null) {
            if (installed.signer == null) return "Portal can't tell who signed the app on this TV, so this can't replace it."
            if (installed.signer != archive.signer) return "The app on this TV is signed by someone else, so this can't replace it."
            if (archive.versionCode <= installed.versionCode) return "The download isn't newer than what is installed."
        }
        val capabilities = archive.capabilities ?: return "The download isn't a Portal extension, or its extension file can't be read."
        val extra = CatalogRules.undeclared(entry, capabilities) + archive.unknownCapabilities
        if (extra.isNotEmpty()) return "The download asks for more than the catalog lists (${extra.joinToString(", ")}), so it was thrown away."
        return null
    }
}
