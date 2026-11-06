package com.portal.tv.extensions

/** Where a catalog extension stands on this TV. */
enum class CatalogState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    /** Installed, but signed by someone other than the catalog's signer, so it isn't what the catalog lists. */
    SIGNED_BY_OTHER
}

/** Whether an installed extension is in the catalog. */
enum class Listing { LISTED, LISTED_BUT_SIGNED_BY_OTHER, NOT_LISTED }

/** What the Browse pages say, worked out without Android so it can be tested. */
object CatalogLabels {

    /** The catalog is looked at again when it was last fetched more than this long ago. */
    const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000

    fun isStale(fetchedAtMs: Long, nowMs: Long): Boolean = fetchedAtMs <= 0 || nowMs - fetchedAtMs >= REFRESH_AFTER_MS || nowMs < fetchedAtMs

    /** The entries to list: the ones with a version Portal could offer, by name. */
    fun shown(catalog: Catalog): List<CatalogEntry> =
        catalog.extensions.filter { CatalogRules.best(catalog, it) != null }.sortedBy { it.name.lowercase() }

    fun state(catalog: Catalog, entry: CatalogEntry, installedVersionCode: Long?, installedSigner: String?): CatalogState = when {
        installedVersionCode == null -> CatalogState.NOT_INSTALLED
        installedSigner != null && installedSigner != entry.signer -> CatalogState.SIGNED_BY_OTHER
        CatalogRules.updateAvailable(catalog, entry, installedVersionCode) -> CatalogState.UPDATE_AVAILABLE
        else -> CatalogState.INSTALLED
    }

    fun stateText(state: CatalogState): String = when (state) {
        CatalogState.NOT_INSTALLED -> ""
        CatalogState.INSTALLED -> "Installed"
        CatalogState.UPDATE_AVAILABLE -> "Update available"
        CatalogState.SIGNED_BY_OTHER -> "Installed, signed by someone else"
    }

    /** Whether an installed extension is the one the catalog lists: the same package and the same signer. */
    fun listing(catalog: Catalog, packageName: String, signer: String?): Listing {
        val entry = catalog.find(packageName) ?: return Listing.NOT_LISTED
        return if (signer != null && signer == entry.signer) Listing.LISTED else Listing.LISTED_BUT_SIGNED_BY_OTHER
    }

    fun listingText(listing: Listing): String = when (listing) {
        Listing.LISTED -> "Listed in the catalog"
        Listing.LISTED_BUT_SIGNED_BY_OTHER -> "In the catalog, but signed by someone else. This isn't the one listed"
        Listing.NOT_LISTED -> "Not in the catalog"
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "${maxOf(1L, (bytes + 1023) / 1024)} KB"
    }

    /** "3 of the extensions in the catalog", for the top of the list. */
    fun count(n: Int): String = if (n == 1) "1 extension" else "$n extensions"
}
