package com.portal.tv.repo

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The parent PIN that guards leaving a kid profile and editing profiles.
 * Only a salted hash is stored. It protects Portal's own screens, not the
 * rest of the system.
 */
object ParentPin {

    private const val PREFS = "portal_security"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_SALT = "pin_salt"

    /** Every PIN is exactly this long, and the PIN pad submits on the last digit. */
    const val LENGTH = 4
    const val MIN_LENGTH = LENGTH
    const val MAX_LENGTH = LENGTH

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    fun set(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(salt, pin))
            .apply()
    }

    /** Forgets the PIN. Callers check the old PIN first, and that no kid profile still depends on it. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_SALT, null)?.hexToBytes() ?: return false
        return MessageDigest.isEqual(
            hash(salt, pin).toByteArray(),
            (p.getString(KEY_HASH, null) ?: return false).toByteArray()
        )
    }

    private fun hash(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
