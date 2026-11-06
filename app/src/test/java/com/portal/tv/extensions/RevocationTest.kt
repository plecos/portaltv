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
}
