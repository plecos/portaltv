package com.portal.tv.extensions

import java.security.MessageDigest

/** What an adult approved for one extension: the signer it was approved with, and the capabilities turned on. */
data class Allowance(val signer: String, val capabilities: Set<String>)

/** Where an extension stands. */
enum class ExtensionState {
    /** Found, but nothing has been allowed. */
    NOT_ALLOWED,
    /** Something is allowed and the signer is the one that was approved. */
    ALLOWED,
    /** It was allowed, but it is now signed by someone else; nothing is granted until an adult allows it again. */
    SIGNER_CHANGED,
    /** It needs a newer Portal. */
    UNSUPPORTED,
    /** Its extension file is wrong. */
    INVALID,
    /** The catalog has withdrawn it (this version, or everything from this signer). It is switched off until it is removed. */
    REVOKED
}

/** The rules for what an extension may do, and helpers for the signer fingerprints those rules are pinned to. */
object ExtensionAccess {

    /**
     * The capabilities an extension may use right now: those it asks for, that an adult turned on, and only while it is
     * still signed by who was approved. Something added by an update stays off until it is turned on.
     */
    fun granted(declared: Set<String>, allowance: Allowance?, signerMatches: Boolean): Set<String> {
        if (allowance == null || !signerMatches) return emptySet()
        return Capability.known.filter { it in declared && it in allowance.capabilities }.toCollection(linkedSetOf())
    }

    /** A withdrawn extension is switched off whatever was allowed: nothing is granted, and its state says why. */
    fun withRevocation(state: ExtensionState, granted: Set<String>, revocation: CatalogRevocation?): Pair<ExtensionState, Set<String>> =
        if (revocation != null) ExtensionState.REVOKED to emptySet() else state to granted

    fun state(parsed: ManifestParser.Result, allowance: Allowance?, signerMatches: Boolean): ExtensionState = when (parsed) {
        is ManifestParser.Result.Invalid -> ExtensionState.INVALID
        is ManifestParser.Result.NeedsNewerPortal -> ExtensionState.UNSUPPORTED
        is ManifestParser.Result.Ok -> when {
            allowance == null || allowance.capabilities.isEmpty() -> ExtensionState.NOT_ALLOWED
            !signerMatches -> ExtensionState.SIGNER_CHANGED
            else -> ExtensionState.ALLOWED
        }
    }

    // ---------- Signer fingerprints ----------

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The bytes of a hex string, or null if it isn't one. */
    fun fromHex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** The start of a fingerprint in a form a person can compare, like `AB:CD:EF:12:34:56:78:9A`. */
    fun shortFingerprint(fingerprint: String): String =
        fingerprint.take(16).uppercase().chunked(2).joinToString(":")

    /**
     * One fingerprint for everything that signed an app. Most apps have one signer; for several, the fingerprints are
     * put in order and joined so the result doesn't depend on the order Android lists them in.
     */
    fun combineSigners(fingerprints: List<String>): String = fingerprints.sorted().joinToString("+")
}
