package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevocationTest {

    private val line = CatalogRevocation("a.b", null, null, "Stole data")

    @Test
    fun aWithdrawnExtensionIsOffWhateverWasAllowed() {
        val (state, granted) = ExtensionAccess.withRevocation(ExtensionState.ALLOWED, setOf("tiles", "cards"), line)
        assertEquals(ExtensionState.REVOKED, state)
        assertTrue(granted.isEmpty())
    }

    @Test
    fun anExtensionNobodyWithdrewIsUntouched() {
        val (state, granted) = ExtensionAccess.withRevocation(ExtensionState.ALLOWED, setOf("tiles"), null)
        assertEquals(ExtensionState.ALLOWED, state)
        assertEquals(setOf("tiles"), granted)
    }

    @Test
    fun theStatusWordsSayWithdrawn() {
        assertTrue(ExtensionLabels.status(ExtensionState.REVOKED, emptySet()).startsWith("Withdrawn"))
    }

    @Test
    fun aCheckIsDueOnlyWhenThereIsSomethingToProtectAndTheCopyIsOld() {
        val day = CatalogLabels.REFRESH_AFTER_MS
        val hour = 60L * 60 * 1000
        val now = 10 * day
        assertFalse(CatalogChecks.isDue(hasExtensions = false, fetchedAt = 0, attemptedAt = 0, now = now))
        assertTrue(CatalogChecks.isDue(hasExtensions = true, fetchedAt = 0, attemptedAt = 0, now = now))
        assertFalse(CatalogChecks.isDue(true, fetchedAt = now - day + 1, attemptedAt = 0, now = now))
        assertTrue(CatalogChecks.isDue(true, fetchedAt = now - day, attemptedAt = 0, now = now))
    }

    @Test
    fun aFailedTryIsNotRepeatedForAnHour() {
        val day = CatalogLabels.REFRESH_AFTER_MS
        val hour = 60L * 60 * 1000
        val now = 10 * day
        assertFalse(CatalogChecks.isDue(true, fetchedAt = 0, attemptedAt = now - hour + 1, now = now))
        assertTrue(CatalogChecks.isDue(true, fetchedAt = 0, attemptedAt = now - hour, now = now))
        // A clock that went backwards doesn't block checks for ever.
        assertTrue(CatalogChecks.isDue(true, fetchedAt = 0, attemptedAt = now + hour, now = now))
    }

    @Test
    fun theLineMatchesByPackageVersionAndSigner() {
        val signer = "aa".repeat(32)
        val c = Catalog("", emptyList(), listOf(
            CatalogRevocation("a.b", 3, null, "version"),
            CatalogRevocation(null, null, signer, "key")
        ))
        assertEquals("version", CatalogRules.revocation(c, "a.b", 3, null)?.reason)
        assertNull(CatalogRules.revocation(c, "a.b", 4, "bb".repeat(32)))
        assertEquals("key", CatalogRules.revocation(c, "x.y", 1, signer)?.reason)
    }

    @Test
    fun aTvWaitsItsOwnExtraTimeBeforeTheDailyCheck() {
        val day = CatalogLabels.REFRESH_AFTER_MS
        val hour = 60L * 60 * 1000
        val now = 10 * day
        val fetched = now - day - hour  // a day and an hour old
        assertTrue(CatalogChecks.isDue(true, fetched, 0, now, jitterMs = 0))
        assertTrue(CatalogChecks.isDue(true, fetched, 0, now, jitterMs = hour))
        assertFalse(CatalogChecks.isDue(true, fetched, 0, now, jitterMs = 2 * hour))
    }

    @Test
    fun aHostThatAskedForTimeIsLeftAloneUntilThen() {
        val day = CatalogLabels.REFRESH_AFTER_MS
        val hour = 60L * 60 * 1000
        val now = 10 * day
        assertFalse(CatalogChecks.isDue(true, 0, 0, now, retryUntil = now + hour))
        assertTrue(CatalogChecks.isDue(true, 0, 0, now, retryUntil = now))
        // A date days away means the clock moved, so don't wait for it.
        assertTrue(CatalogChecks.isDue(true, 0, 0, now, retryUntil = now + 5 * day))
    }

    @Test
    fun jitterStaysInsideSixHours() {
        val random = kotlin.random.Random(1)
        repeat(200) { assertTrue(CatalogHttp.newJitterMs(random) in 0..6L * 60 * 60 * 1000) }
        assertTrue((1..50).map { CatalogHttp.newJitterMs(random) }.toSet().size > 10)
    }

    @Test
    fun retryAfterIsReadAsSecondsOrADateAndKeptReasonable() {
        val now = java.time.ZonedDateTime.parse("Fri, 06 Nov 2026 12:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(120_000L, CatalogHttp.retryAfterMs("120", now))
        assertEquals(30 * 60_000L, CatalogHttp.retryAfterMs("Fri, 06 Nov 2026 12:30:00 GMT", now))
        assertEquals(60_000L, CatalogHttp.retryAfterMs("1", now))
        assertEquals(CatalogHttp.MAX_WAIT_MS, CatalogHttp.retryAfterMs("99999999", now))
        assertEquals(60L * 60_000, CatalogHttp.retryAfterMs(null, now))
        assertEquals(60L * 60_000, CatalogHttp.retryAfterMs("soon", now))
        assertEquals(60_000L, CatalogHttp.retryAfterMs("Fri, 06 Nov 2026 11:00:00 GMT", now))
    }
}
