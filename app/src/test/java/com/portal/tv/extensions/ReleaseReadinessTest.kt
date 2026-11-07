package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/**
 * What must be true before a release is published. It only runs when `PORTAL_RELEASE_CHECK=1`, which the release workflow
 * sets, so ordinary builds (which use the test key and serial) are not held up. It reads the real values, so it can't be
 * fooled by how they are written in the source.
 */
class ReleaseReadinessTest {

    private fun releaseCheck() = assumeTrue(System.getenv("PORTAL_RELEASE_CHECK") == "1")

    @Test
    fun aCatalogKeyIsBuiltIn() {
        releaseCheck()
        assertTrue("CatalogTrust.RELEASE_KEYS is empty: add the catalog's public key (java scripts/CatalogSign.java keygen)", CatalogTrust.RELEASE_KEYS.isNotEmpty())
        for (key in CatalogTrust.RELEASE_KEYS) {
            assertEquals("key ${key.id} is not a 32-byte Ed25519 public key", 32, Base64.getDecoder().decode(key.publicKey).size)
        }
        assertEquals("two release keys share an id", CatalogTrust.RELEASE_KEYS.size, CatalogTrust.RELEASE_KEYS.map { it.id }.toSet().size)
        val testKeys = CatalogTrust.TEST_KEYS.map { it.publicKey }.toSet()
        assertTrue("a release key is the test key, whose private half is in the repository", CatalogTrust.RELEASE_KEYS.none { it.publicKey in testKeys })
    }

    @Test
    fun theMinimumSerialHasBeenRaisedFromTheTestValue() {
        releaseCheck()
        // Serial 1 belongs to the test catalog. The real catalog starts above it, and the minimum is raised to the serial
        // current at each release so a fresh install can't be shown an older catalog.
        assertTrue("CatalogPolicy.MIN_SERIAL is still the test value", CatalogPolicy.MIN_SERIAL >= 2)
    }
}
