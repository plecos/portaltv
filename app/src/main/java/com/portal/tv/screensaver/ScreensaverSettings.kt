package com.portal.tv.screensaver

import android.content.Context
import com.portal.tv.background.BackgroundRepository

/** Screen saver preferences, shared by the whole device (like the background mode). */
object ScreensaverSettings {

    const val PREFS = "screensaver_settings"

    const val NIGHT_NORMAL = "normal"
    const val NIGHT_DIM = "dim"
    const val NIGHT_BLACK = "black"

    /** Night runs from [NIGHT_START] to [NIGHT_END] (hours, 24h clock). */
    const val NIGHT_START = 22
    const val NIGHT_END = 6

    /** True for an [hour] (0 to 23) inside the overnight window. */
    fun isNight(hour: Int): Boolean = hour >= NIGHT_START || hour < NIGHT_END

    private fun prefs(context: Context) = com.portal.tv.settings.ProfilePrefs(context, PREFS)

    /** Minutes of inactivity on the home screen before the screen saver starts; 0 = never. */
    fun idleMinutes(context: Context): Int = prefs(context).int("idle_minutes", 10)

    fun setIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit { putInt("idle_minutes", minutes) }
    }

    /** How loud the screen saver's videos are: a [com.portal.tv.settings.VideoSound] level, off until chosen. */
    fun sound(context: Context): Int = prefs(context).int("sound", com.portal.tv.settings.VideoSound.OFF)

    fun setSound(context: Context, level: Int) {
        prefs(context).edit { putInt("sound", level) }
    }

    /** What the screen saver does overnight: play as usual, play dimmed, or go black. */
    fun night(context: Context): String = prefs(context).string("night", NIGHT_DIM)

    fun setNight(context: Context, mode: String) {
        prefs(context).edit { putString("night", mode) }
    }

    /** Bundled videos the screen saver may play; every available video until the user narrows it. */
    fun enabledVideos(context: Context): List<String> {
        val available = BackgroundRepository.availableVideos(context).map { it.asset }
        val saved = prefs(context).stringSet("videos") ?: return available
        return available.filter { it in saved }.ifEmpty { available }
    }

    /** Turns [asset] on or off; the last enabled video can't be turned off. */
    fun setVideoEnabled(context: Context, asset: String, enabled: Boolean) {
        val now = enabledVideos(context).toMutableSet()
        if (enabled) now.add(asset) else if (now.size > 1) now.remove(asset)
        prefs(context).edit { putStringSet("videos", now) }
    }

    /** Forgets this profile's screen saver choices, e.g. when it is deleted. */
    fun clearForProfile(context: Context, profileId: Int) =
        com.portal.tv.settings.ProfilePrefs.clear(context, PREFS, profileId)
}
