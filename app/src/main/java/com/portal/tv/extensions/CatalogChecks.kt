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

    /**
     * Due when there is something to protect, the kept copy is a day old plus this TV's own [jitterMs], no attempt (good or
     * failed) in the last hour, and the host hasn't asked Portal to wait ([retryUntil]).
     */
    internal fun isDue(
        hasExtensions: Boolean, fetchedAt: Long, attemptedAt: Long, now: Long, jitterMs: Long = 0, retryUntil: Long = 0
    ): Boolean {
        if (!hasExtensions) return false
        val old = fetchedAt <= 0 || now < fetchedAt || now - fetchedAt >= CatalogLabels.REFRESH_AFTER_MS + jitterMs
        val rested = now - attemptedAt >= RETRY_MS || now < attemptedAt
        // A "wait until" far in the future is a clock that moved, not a request to wait for ever.
        val allowed = now >= retryUntil || retryUntil - now > CatalogHttp.MAX_WAIT_MS
        return old && rested && allowed
    }

    /** Fetches now if one is due. Returns true if it tried. Quiet about failures: the next try is soon enough. */
    suspend fun checkIfDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        // Cheap things first: this runs every time the home screen comes back, and is usually not due.
        val fetchedAt = withContext(Dispatchers.IO) { CatalogClient.cached(app)?.fetchedAtMs ?: 0L }
        if (!isDue(true, fetchedAt, CatalogClient.attemptedAt(app), now, CatalogClient.jitterMs(app), CatalogClient.retryUntil(app))) return false
        if (!withContext(Dispatchers.IO) { ExtensionRegistry.anyInstalled(app) }) return false
        runCatching { CatalogClient.fetch(app) }
        return true
    }
}

/** Small rules for being a considerate client of the host that serves the catalog. */
object CatalogHttp {

    const val MAX_WAIT_MS = 24L * 60 * 60 * 1000
    private const val MIN_WAIT_MS = 60L * 1000
    private const val DEFAULT_WAIT_MS = 60L * 60 * 1000
    private const val MAX_JITTER_MS = 6L * 60 * 60 * 1000

    /** A random extra wait of up to six hours, so TVs that started together don't keep asking together. */
    fun newJitterMs(random: kotlin.random.Random): Long = random.nextLong(0, MAX_JITTER_MS + 1)

    /**
     * How long to stay away after a "busy" answer: the `Retry-After` header as seconds or as a date, kept between a minute
     * and a day; an hour when there is none or it can't be read.
     */
    fun retryAfterMs(header: String?, now: Long): Long {
        val text = header?.trim().orEmpty()
        val wait = text.toLongOrNull()?.let { it * 1000 }
            ?: runCatching {
                java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
            }.getOrNull()
            ?: DEFAULT_WAIT_MS
        return wait.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }
}
