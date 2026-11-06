package com.portal.tv.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the catalog's withdrawn list fresh while extensions are installed, so a withdrawal reaches the TV without anyone
 * opening Browse. When the home screen comes up and the kept copy is over a day old, Portal fetches the catalog once. With
 * no extensions installed it never asks. Like browsing, the request carries nothing about the TV.
 */
object CatalogChecks {

    private const val RETRY_MS = 60L * 60 * 1000

    /** Due when there is something to protect, the kept copy is a day old, and no attempt (good or failed) in the last hour. */
    internal fun isDue(hasExtensions: Boolean, fetchedAt: Long, attemptedAt: Long, now: Long): Boolean =
        hasExtensions && CatalogLabels.isStale(fetchedAt, now) && (now - attemptedAt >= RETRY_MS || now < attemptedAt)

    /** Fetches now if one is due. Returns true if it tried. Quiet about failures: the next try is soon enough. */
    suspend fun checkIfDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val has = withContext(Dispatchers.IO) { ExtensionRegistry.scan(app).isNotEmpty() }
        val fetchedAt = CatalogClient.cached(app)?.fetchedAtMs ?: 0L
        if (!isDue(has, fetchedAt, CatalogClient.attemptedAt(app), now)) return false
        runCatching { CatalogClient.fetch(app) }
        return true
    }
}
