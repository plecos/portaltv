package com.portal.tv.repo

import android.content.Context
import android.os.SystemClock
import com.portal.tv.settings.ProfilePrefs

/**
 * Remembering the parent PIN for a while, so an adult who has just entered it isn't asked again for each thing they do.
 *
 * Only on an adult profile, only in memory (a restart of Portal forgets it), and only for the profile it was entered on:
 * switching profiles forgets it at once, and so does the time running out. It is a setting of each profile (on or off, and for
 * how long). A kid profile never skips the PIN, whatever was entered before. Changing or removing the PIN itself always asks.
 */
object ParentUnlock {

    private const val BASE = "portal_pin_remember"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MINUTES = "minutes"

    const val DEFAULT_MINUTES = 10

    /** The lengths offered in Settings. */
    val CHOICES_MINUTES = listOf(1, 5, 10, 15, 30, 60)

    /** The window itself, without Android, so its rules can be tested. Times are on any steady clock, in milliseconds. */
    class Window {
        private var profileId = -1
        private var until = 0L

        fun remember(profileId: Int, now: Long, durationMs: Long) {
            this.profileId = profileId
            until = now + durationMs
        }

        fun isOpen(profileId: Int, now: Long): Boolean = this.profileId == profileId && now < until

        fun clear() {
            profileId = -1
            until = 0L
        }
    }

    private val window = Window()

    private fun prefs(context: Context) = ProfilePrefs(context, BASE)

    /** Whether the active profile remembers the PIN (on unless turned off). */
    fun enabled(context: Context): Boolean = prefs(context).boolean(KEY_ENABLED, true)

    /** How many minutes the active profile remembers it for. */
    fun minutes(context: Context): Int = prefs(context).int(KEY_MINUTES, DEFAULT_MINUTES).coerceIn(1, 120)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        if (!on) clear()
    }

    fun setMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt(KEY_MINUTES, minutes.coerceIn(1, 120)) }
    }

    /** True if the PIN was entered recently enough, on this adult profile, and the profile remembers it. */
    @Synchronized
    fun isUnlocked(context: Context): Boolean {
        val profile = ProfileManager.getActive(context) ?: return false
        if (profile.isKids || !enabled(context)) return false
        return window.isOpen(profile.id, SystemClock.elapsedRealtime())
    }

    /** Called when the PIN has just been entered (or set). Does nothing on a kid profile or when the profile doesn't remember it. */
    @Synchronized
    fun remember(context: Context) {
        val profile = ProfileManager.getActive(context) ?: return
        if (profile.isKids || !enabled(context)) return
        window.remember(profile.id, SystemClock.elapsedRealtime(), minutes(context) * 60_000L)
    }

    /** Forgets it now: the profile was switched, the PIN was changed or removed, or the setting was turned off. */
    @Synchronized
    fun clear() = window.clear()
}
