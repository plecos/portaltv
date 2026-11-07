package com.portal.tv.extensions

import org.json.JSONObject
import java.util.Base64

/**
 * Who may publish the catalog. The catalog is a file on somebody's web host, so it is signed: Portal reads a catalog only
 * if its bytes carry a valid Ed25519 signature from a key built into Portal. Whoever controls the host or the repository
 * can't make Portal install an app or withdraw one without the private key, which is kept offline.
 *
 * The signature is a second small file next to the catalog (`catalog.json.sig`):
 * `{"key_id": "...", "signature": "<base64 over the exact bytes of catalog.json>"}`. [keyId] picks the public key, so a
 * new key can be added to Portal before the old one retires.
 */
object CatalogTrust {

    class Key(val id: String, /** The 32-byte Ed25519 public key, base64. */ val publicKey: String)

    /**
     * The keys that may sign the catalog in a release build: the public halves of keys made with
     * `java scripts/CatalogSign.java keygen` (see docs/CATALOG.md). The private half is kept offline by the maintainer.
     */
    val RELEASE_KEYS: List<Key> = listOf(Key("portal-catalog-1", "am2qirMYwo56iE7KRih13KSraXqBX4rJUo9GbRYrdLQ="))

    /** Trusted by debug builds only. Its private half is in the repository on purpose, so tests and the test script can sign. */
    val TEST_KEYS: List<Key> = listOf(Key("test-1", "fG2NO/oNai5WEX7PPdj+r5H3tXjjAIwqsOAu39zliwc="))

    fun keys(debug: Boolean): List<Key> = RELEASE_KEYS + if (debug) TEST_KEYS else emptyList()

    /** A signature file is a line or two; anything bigger is not one. */
    const val MAX_SIGNATURE_BYTES = 2048

    sealed class Result {
        data class Ok(val keyId: String) : Result()
        data class Refused(val reason: String) : Result()
    }

    /** Checks [catalog] against [signatureFile] (its text) and the [keys] Portal trusts. Never throws. */
    fun verify(catalog: ByteArray, signatureFile: String?, keys: List<Key>): Result {
        if (keys.isEmpty()) return Result.Refused("This version of Portal has no catalog key, so it can't check the catalog.")
        if (signatureFile == null || signatureFile.length > MAX_SIGNATURE_BYTES) return Result.Refused("The catalog has no signature.")
        return try {
            val json = JSONObject(signatureFile)
            val keyId = json.optString("key_id", "")
            val signature = Base64.getDecoder().decode(json.optString("signature", ""))
            val key = keys.firstOrNull { it.id == keyId }
                ?: return Result.Refused("The catalog is signed with a key this Portal doesn't know.")
            val raw = Base64.getDecoder().decode(key.publicKey)
            if (Ed25519Verify.verify(raw, catalog, signature)) Result.Ok(keyId) else Result.Refused("The catalog's signature isn't valid.")
        } catch (e: Exception) {
            Result.Refused("The catalog's signature can't be read.")
        }
    }
}
