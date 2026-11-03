package com.portal.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.portal.tv.repo.ProfileManager

/**
 * Settings kept separately for each profile, so switching profiles also switches them.
 *
 * Writes go to the active profile's own file. Reads use that file, and where the profile has not
 * saved a value yet they fall back to the shared file the TV used before profiles had their own
 * settings. A profile that has never changed a setting therefore keeps the look it always had, and
 * a new profile starts from the TV's existing choices.
 */
class ProfilePrefs(private val context: Context, private val base: String) {

    private fun id(): String = "profile_${ProfileManager.getActiveId(context) ?: "default"}"

    private val own: SharedPreferences
        get() = context.getSharedPreferences("$base.${id()}", Context.MODE_PRIVATE)

    private val shared: SharedPreferences
        get() = context.getSharedPreferences(base, Context.MODE_PRIVATE)

    /** The file to read [key] from: the profile's own if it has saved it, else the shared one. */
    private fun source(key: String): SharedPreferences = own.takeIf { it.contains(key) } ?: shared

    fun string(key: String, default: String): String = source(key).getString(key, default) ?: default
    fun int(key: String, default: Int): Int = source(key).getInt(key, default)
    fun float(key: String, default: Float): Float = source(key).getFloat(key, default)
    fun boolean(key: String, default: Boolean): Boolean = source(key).getBoolean(key, default)
    fun stringSet(key: String): Set<String>? = source(key).getStringSet(key, null)

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        own.edit().apply(block).apply()
    }

    companion object {
        /** Saves values straight into [profileId]'s own file under [base], whoever is the active profile. */
        fun write(context: Context, base: String, profileId: Int, block: SharedPreferences.Editor.() -> Unit) {
            context.getSharedPreferences("$base.profile_$profileId", Context.MODE_PRIVATE).edit().apply(block).apply()
        }

        /** Forgets everything saved for [profileId] under [base], e.g. when the profile is deleted. */
        fun clear(context: Context, base: String, profileId: Int) {
            context.getSharedPreferences("$base.profile_$profileId", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
