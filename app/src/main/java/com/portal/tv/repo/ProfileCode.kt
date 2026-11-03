package com.portal.tv.repo

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An optional code on a single profile. Choosing a profile that has one asks for it first, so
 * nobody can step into someone else's profile. There is no override: a forgotten code means
 * deleting the profile. Only a salted hash is stored.
 */
object ProfileCode {

    private const val PREFS = "portal_profile_codes"

    const val MIN_LENGTH = ParentPin.LENGTH

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(context: Context, profileId: Int): Boolean =
        prefs(context).contains("hash_$profileId")

    fun set(context: Context, profileId: Int, code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString("salt_$profileId", salt.toHex())
            .putString("hash_$profileId", hash(salt, code))
            .apply()
    }

    fun clear(context: Context, profileId: Int) {
        prefs(context).edit().remove("salt_$profileId").remove("hash_$profileId").apply()
    }

    fun verify(context: Context, profileId: Int, code: String): Boolean {
        val p = prefs(context)
        val salt = p.getString("salt_$profileId", null)?.hexToBytes() ?: return false
        val stored = p.getString("hash_$profileId", null) ?: return false
        return MessageDigest.isEqual(hash(salt, code).toByteArray(), stored.toByteArray())
    }

    private fun hash(salt: ByteArray, code: String): String =
        MessageDigest.getInstance("SHA-256").digest(salt + code.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
