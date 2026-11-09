package com.portal.tv.extensions

import android.content.Context

/** What the catalog says is newer than what is installed, found from the copy kept on the TV (no network). */
object ExtensionUpdates {

    /** Each installed extension the catalog has a newer version of, by package: the version name to offer. */
    fun offered(catalog: Catalog, extensions: List<Extension>): Map<String, String> {
        val out = HashMap<String, String>()
        for (ext in extensions) {
            val entry = catalog.find(ext.packageName) ?: continue
            val code = ext.versionCode ?: continue
            if (CatalogLabels.state(catalog, entry, code, ext.signer) != CatalogState.UPDATE_AVAILABLE) continue
            CatalogRules.best(catalog, entry)?.let { out[ext.packageName] = it.versionName }
        }
        return out
    }

    /** The same, read from what is installed and the kept catalog. Off the main thread. Nothing while the catalog is out of date. */
    fun pending(context: Context, now: Long = System.currentTimeMillis()): Map<String, String> {
        val catalog = CatalogClient.cachedCatalog(context) ?: return emptyMap()
        if (CatalogLabels.expired(catalog, now)) return emptyMap()
        return offered(catalog, ExtensionRegistry.scan(context))
    }
}
