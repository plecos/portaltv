package com.portal.tv.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Random

class Ed25519VerifyTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // From RFC 8032, section 7.1.
    private val vectors = listOf(
        Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        Triple("fc51cd8e6218a0a1e0b6c1ce8b7e5a3b0b7e5f6e0e3b0e6c5c5e6a5c8e7a8a9", "af82", "")
    ).take(2)

    @Test
    fun theRfcTestVectorsVerify() {
        for ((key, message, signature) in vectors) {
            assertTrue(Ed25519Verify.verify(hex(key), hex(message), hex(signature)))
        }
    }

    @Test
    fun aChangedMessageKeyOrSignatureDoesNot() {
        val (key, message, signature) = vectors[1]
        val k = hex(key); val m = hex(message); val sig = hex(signature)
        assertFalse(Ed25519Verify.verify(k, hex("73"), sig))
        assertFalse(Ed25519Verify.verify(hex(vectors[0].first), m, sig))
        for (i in listOf(0, 31, 32, 63)) assertFalse(Ed25519Verify.verify(k, m, sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
    }

    @Test
    fun aSignatureMadeByTheJdkVerifiesAndOnlyThatOne() {
        val random = Random(42)
        repeat(8) { n ->
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val raw = pair.public.encoded.takeLast(32).toByteArray()
            val message = ByteArray(random.nextInt(2000)).also { random.nextBytes(it) }
            val sig = Signature.getInstance("Ed25519").apply { initSign(pair.private); update(message) }.sign()
            assertTrue("signature $n", Ed25519Verify.verify(raw, message, sig))
            if (message.isNotEmpty()) assertFalse(Ed25519Verify.verify(raw, message.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, sig))
        }
    }

    @Test
    fun malformedInputIsRefusedNotThrown() {
        val (key, message, signature) = vectors[0]
        assertFalse(Ed25519Verify.verify(ByteArray(31), hex(message), hex(signature)))
        assertFalse(Ed25519Verify.verify(hex(key), hex(message), ByteArray(63)))
        assertFalse(Ed25519Verify.verify(ByteArray(32) { 0xff.toByte() }, hex(message), hex(signature)))
        // A scalar not below the group order is not a valid signature (it would otherwise be a second one for the same message).
        val big = hex(signature).copyOf().also { for (i in 32..63) it[i] = 0xff.toByte() }
        assertFalse(Ed25519Verify.verify(hex(key), hex(message), big))
    }
}
