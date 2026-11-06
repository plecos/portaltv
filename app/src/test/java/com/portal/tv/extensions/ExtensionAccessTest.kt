package com.portal.tv.extensions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionAccessTest {

    private val declared = setOf("tiles", "settings", "reminders", "cards")
    private val signer = "ab".repeat(32)

    @Test
    fun nothingIsGrantedUntilAnAdultAllowsIt() {
        assertTrue(ExtensionAccess.granted(declared, null, signerMatches = true).isEmpty())
        assertTrue(ExtensionAccess.granted(declared, Allowance(signer, emptySet()), true).isEmpty())
    }

    @Test
    fun onlyWhatIsBothAskedForAndAllowedIsGranted() {
        val allowance = Allowance(signer, setOf("tiles", "cards", "teleport"))
        assertEquals(listOf("tiles", "cards"), ExtensionAccess.granted(declared, allowance, true).toList())
        // Allowed earlier, but an update stopped asking for it.
        assertEquals(setOf("tiles"), ExtensionAccess.granted(setOf("tiles"), allowance, true))
    }

    @Test
    fun aCapabilityAddedByAnUpdateStaysOffUntilTurnedOn() {
        val allowance = Allowance(signer, setOf("tiles"))
        assertEquals(setOf("tiles"), ExtensionAccess.granted(declared, allowance, true))
    }

    @Test
    fun aChangedSignerGrantsNothing() {
        assertTrue(ExtensionAccess.granted(declared, Allowance(signer, declared), signerMatches = false).isEmpty())
    }

    @Test
    fun theStateFollowsWhatWasAllowed() {
        val ok = ManifestParser.Result.Ok(ExtensionManifest("a", "A", "", 1, declared, emptySet(), emptyList(), null))
        assertEquals(ExtensionState.NOT_ALLOWED, ExtensionAccess.state(ok, null, true))
        assertEquals(ExtensionState.NOT_ALLOWED, ExtensionAccess.state(ok, Allowance(signer, emptySet()), true))
        assertEquals(ExtensionState.ALLOWED, ExtensionAccess.state(ok, Allowance(signer, setOf("tiles")), true))
        assertEquals(ExtensionState.SIGNER_CHANGED, ExtensionAccess.state(ok, Allowance(signer, setOf("tiles")), false))
        assertEquals(ExtensionState.INVALID, ExtensionAccess.state(ManifestParser.Result.Invalid("x"), null, true))
        assertEquals(ExtensionState.UNSUPPORTED, ExtensionAccess.state(ManifestParser.Result.NeedsNewerPortal(2), null, true))
    }

    @Test
    fun aFingerprintIsTheSha256OfTheCertificateInHex() {
        // The SHA-256 of the empty input is a well-known value.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ExtensionAccess.sha256Hex(ByteArray(0)))
        assertEquals(64, ExtensionAccess.sha256Hex("anything".toByteArray()).length)
    }

    @Test
    fun hexRoundTripsAndRefusesJunk() {
        val bytes = byteArrayOf(0, 1, 15, 16, -1, 127, -128)
        assertArrayEquals(bytes, ExtensionAccess.fromHex(ExtensionAccess.hex(bytes)))
        assertNull(ExtensionAccess.fromHex(""))
        assertNull(ExtensionAccess.fromHex("abc"))
        assertNull(ExtensionAccess.fromHex("zz"))
        assertNull(ExtensionAccess.fromHex("AB"))
    }

    @Test
    fun aShortFingerprintIsReadableAndComparable() {
        assertEquals("AB:CD:EF:12:34:56:78:9A", ExtensionAccess.shortFingerprint("abcdef123456789a0000"))
    }

    @Test
    fun severalSignersCombineTheSameWayWhateverTheOrder() {
        assertEquals(ExtensionAccess.combineSigners(listOf("bb", "aa")), ExtensionAccess.combineSigners(listOf("aa", "bb")))
        assertEquals("aa+bb", ExtensionAccess.combineSigners(listOf("bb", "aa")))
        assertEquals("aa", ExtensionAccess.combineSigners(listOf("aa")))
    }
}
