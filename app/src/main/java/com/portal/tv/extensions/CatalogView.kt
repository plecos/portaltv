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

    /** The catalog says it should have been replaced by now; installing from it is paused (withdrawals still apply). */
    fun expired(catalog: Catalog, nowMs: Long): Boolean = catalog.expiresAtMs > 0 && nowMs >= catalog.expiresAtMs

    /** The entries to list: the ones with a version Portal could offer, and the Google Play ones (not withdrawn), by name. */
    fun shown(catalog: Catalog): List<CatalogEntry> =
        catalog.extensions.filter { if (it.isPlay) CatalogRules.revocation(catalog, it.packageName, null, it.signer) == null else CatalogRules.best(catalog, it) != null }.sortedBy { it.name.lowercase() }

    /** The card's second line: who made it. */
    fun byLine(entry: CatalogEntry): String = entry.author

    /** The card's last line for an entry that isn't installed: what it costs, for a Google Play one. Empty otherwise. */
    fun priceLine(entry: CatalogEntry): String = if (entry.isPlay) entry.price.ifEmpty { "On Google Play" } else ""

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

/** Finding extensions in the list: by words in the title, the description or the tags, and by one tag. */
object CatalogSearch {

    /**
     * The entries that match, best first. Every word of [query] must be in the title, the description or a tag (capitals
     * don't matter); [tag], if given, must be one of the entry's tags. An empty query and no tag shows them all by name.
     */
    fun filter(entries: List<CatalogEntry>, query: String, tag: String? = null): List<CatalogEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries
            .filter { tag == null || tag in it.tags }
            .mapNotNull { e ->
                val name = e.name.lowercase()
                var score = 0
                for (w in words) {
                    score += when {
                        name.startsWith(w) -> 4
                        w in name -> 3
                        e.tags.any { it == w } -> 3
                        e.tags.any { w in it } -> 2
                        w in e.description.lowercase() -> 1
                        else -> return@mapNotNull null
                    }
                }
                e to score
            }
            .sortedWith(compareByDescending<Pair<CatalogEntry, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    /** The tags in use among [entries], the most common first, so the chooser starts with what is likely wanted. */
    fun tags(entries: List<CatalogEntry>): List<String> =
        entries.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** Where an entry's icon is: next to catalog.json, so it comes from the same host as the catalog. */
    fun iconUrl(catalogUrl: String, iconPath: String): String? =
        if (iconPath.isEmpty()) null else catalogUrl.substringBeforeLast('/') + "/" + iconPath
}
