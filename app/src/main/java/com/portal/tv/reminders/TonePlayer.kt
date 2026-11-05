package com.portal.tv.reminders

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Plays the sounds in [ReminderTones]: the built-in ones from the TV's tone generator, and any sound from the TV's own
 * list. All of it on the alarm volume, so it is heard even when the TV's media is quiet.
 */
object TonePlayer {

    data class Choice(val key: String, val label: String)

    private val handler = Handler(Looper.getMainLooper())

    /** The built-in tones followed by the TV's notification and alarm sounds, if it has any. */
    fun choices(context: Context): List<Choice> =
        ReminderTones.builtIn.map { Choice(it.first, it.second) } + systemSounds(context)

    /** The name to show for [key]; a sound that has gone from the TV shows as the default. */
    fun label(context: Context, key: String): String {
        val fallback = ReminderTones.builtIn.first { it.first == ReminderTones.DEFAULT }.second
        ReminderTones.builtIn.firstOrNull { it.first == key }?.let { return it.second }
        return if (key.startsWith(ReminderTones.SYSTEM)) systemSounds(context).firstOrNull { it.key == key }?.label ?: fallback else fallback
    }

    private fun systemSounds(context: Context): List<Choice> = runCatching {
        val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM) }
        val cursor = manager.cursor
        val found = mutableListOf<Choice>()
        while (cursor.moveToNext() && found.size < MAX_SYSTEM_SOUNDS) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
            val uri = manager.getRingtoneUri(cursor.position)
            found += Choice(ReminderTones.SYSTEM + uri, title)
        }
        found.distinctBy { it.key }
    }.getOrDefault(emptyList())

    /** Plays [key] once. A sound that has gone from the TV plays the default instead. */
    fun play(context: Context, key: String) {
        when {
            key == ReminderTones.NONE -> Unit
            key.startsWith(ReminderTones.SYSTEM) -> playSystem(context, key.removePrefix(ReminderTones.SYSTEM))
            else -> playBuiltIn(key)
        }
    }

    private fun playBuiltIn(key: String) {
        val (tone, times, gapMs) = when (key) {
            ReminderTones.CHIRP -> Triple(ToneGenerator.TONE_PROP_ACK, 2, 700L)
            ReminderTones.PROMPT -> Triple(ToneGenerator.TONE_PROP_PROMPT, 1, 0L)
            ReminderTones.ALERT -> Triple(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3, 600L)
            else -> Triple(ToneGenerator.TONE_PROP_BEEP2, 2, 600L)
        }
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            repeat(times) { i -> handler.postDelayed({ generator.startTone(tone, 350) }, i * gapMs) }
            handler.postDelayed({ generator.release() }, times * gapMs + 1_500L)
        }
    }

    private fun playSystem(context: Context, uri: String) {
        runCatching {
            val sound = RingtoneManager.getRingtone(context.applicationContext, Uri.parse(uri))
            if (sound == null) { playBuiltIn(ReminderTones.DEFAULT); return }
            sound.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            sound.play()
            handler.postDelayed({ runCatching { sound.stop() } }, SYSTEM_SOUND_MS)
        }.onFailure { playBuiltIn(ReminderTones.DEFAULT) }
    }

    private const val MAX_SYSTEM_SOUNDS = 20
    private const val SYSTEM_SOUND_MS = 4_000L
}
