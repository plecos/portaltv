package com.portal.tv.settings

import android.content.Context
import android.media.AudioManager

/**
 * How loud background and screen saver videos are. They play silently unless the profile turns sound on, and the
 * level here is a share of the TV's own volume, so the TV's remote still has the last word.
 */
object VideoSound {
    const val OFF = 0
    const val LOW = 1
    const val NORMAL = 2

    /** The choices shown in Settings. */
    val levels = listOf("Off" to OFF, "Low" to LOW, "Normal" to NORMAL)

    /** The player volume (0 to 1) for a level. */
    fun volume(level: Int): Float = when (level) {
        LOW -> 0.25f
        NORMAL -> 1f
        else -> 0f
    }

    /** True while another app is playing music or video sound, in which case a video here stays silent. */
    fun othersPlaying(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.isMusicActive == true
}
