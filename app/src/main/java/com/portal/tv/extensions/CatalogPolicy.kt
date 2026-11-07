package com.portal.tv.extensions

/**
 * The rules a signed catalog must meet on top of its signature. A signature says who published a file, not that it is
 * current, so the catalog also says how new it is (`serial`) and how long it may be relied on (`expires`).
 */
object CatalogPolicy {

    /**
     * No catalog with a lower serial is read. Raised with each Portal release to the serial current at the time, so a
     * freshly installed Portal (which has seen no catalog yet) can't be shown a catalog older than its own release.
     */
    const val MIN_SERIAL = 1L

    /** The most a catalog may say it is good for, counted from now. The signing tool's default is half of this. */
    const val MAX_VALIDITY_DAYS = 365L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * What time it is for the purpose of expiry: the later of the TV's clock and the latest publication day (`updated`) of a
     * signed catalog Portal has used. A TV whose clock was reset to long ago (or never set) can't make an old catalog look
     * current, and nothing unsigned can move it. A clock that runs fast can cause an early pause, which a corrected clock ends.
     */
    fun effectiveNow(deviceNowMs: Long, latestServerDateMs: Long): Long = maxOf(deviceNowMs, latestServerDateMs)

    /** Why a signed catalog can't be used, in words for the person, or null if it meets the rules. */
    fun check(catalog: Catalog, nowMs: Long): String? = when {
        catalog.serial < MIN_SERIAL -> "The catalog has no serial number, or an old one, so it was not used."
        catalog.expiresAtMs <= 0 -> "The catalog has no expiry date, so it was not used."
        catalog.expiresAtMs - nowMs > MAX_VALIDITY_DAYS * DAY_MS -> "The catalog's expiry date is more than a year away, so it was not used."
        else -> null
    }
}
