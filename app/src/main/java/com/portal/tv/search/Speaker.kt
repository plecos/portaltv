package com.portal.tv.search

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Speaks the AI's replies with the TV's own text-to-speech voice. One speaker for the whole app,
 * so a reply that is still being read carries on when the search panel closes (for example while an
 * app the AI was asked to open starts). Sentences are queued as they arrive, so speech begins before
 * the whole answer has been written.
 */
object Speaker {

    private const val TAG = "Portal"

    // Everything runs on its own thread, so a busy screen never holds up the voice.
    private val worker = android.os.Handler(android.os.HandlerThread("portal-speaker").also { it.start() }.looper)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val waiting = mutableListOf<String>()
    private var counter = 0
    private val active = mutableSetOf<String>()
    private val whenReady = mutableListOf<(TextToSpeech) -> Unit>()

    /** The voice to use (an engine voice name), or null for the engine's own default. */
    @Volatile var voiceName: String? = null

    /** Called on the main thread with each word as the voice reaches it. */
    var onWord: ((String) -> Unit)? = null

    private val saying = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Called on the main thread whenever speech starts or stops. */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    val isSpeaking: Boolean get() = synchronized(active) { active.isNotEmpty() }

    /** True when the TV has a text-to-speech engine at all. */
    fun isAvailable(context: Context): Boolean =
        context.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
        ).isNotEmpty()

    /** Queues [text] to be spoken after whatever is being said. Markdown is cleaned out first. */
    fun speak(context: Context, text: String) {
        val clean = SpeechText.clean(text)
        if (clean.isEmpty()) return
        val app = context.applicationContext
        worker.post {
            val engine = tts ?: start(app)
            if (!ready) { synchronized(waiting) { waiting += clean }; return@post }
            say(engine, clean)
        }
    }

    /** Starts the voice engine now, so the first sentence of a reply doesn't wait for it to wake up. */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        worker.post { if (tts == null) start(app) }
    }

    /** Stops speaking and drops anything queued. */
    fun stop() {
        synchronized(waiting) { waiting.clear() }
        worker.post {
            tts?.stop()
            saying.clear()
            synchronized(active) { active.clear() }
            notifyChanged()
        }
    }

    private fun start(app: Context): TextToSpeech {
        val engine = TextToSpeech(app) { status ->
            val e = tts
            if (status == TextToSpeech.SUCCESS && e != null) {
                e.language = Locale.getDefault().takeIf { e.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
                ready = true
                val callbacks = synchronized(whenReady) { whenReady.toList().also { whenReady.clear() } }
                callbacks.forEach { it(e) }
                val queued = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
                queued.forEach { say(e, it) }
            } else {
                Log.w(TAG, "Text-to-speech couldn't start: $status")
            }
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val text = saying[utteranceId] ?: return
                if (start < 0 || end > text.length || start >= end) return
                val word = text.substring(start, end)
                main.post { onWord?.invoke(word) }
            }
            override fun onDone(utteranceId: String) = finished(utteranceId)
            @Deprecated("Required by the interface")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
        tts = engine
        return engine
    }

    /** The voices this TV can use for the current language, best known first. Calls [onList] on the main thread. */
    fun voices(context: Context, onList: (List<Voice>) -> Unit) {
        val app = context.applicationContext
        val deliver: (TextToSpeech) -> Unit = { e ->
            val language = Locale.getDefault().language
            val all = runCatching { e.voices }.getOrNull().orEmpty()
            val installed = all.filter { v -> v.locale.language == language && !v.features.contains("notInstalled") }
            val list = installed.sortedWith(compareBy({ it.locale.country }, { it.isNetworkConnectionRequired }, { it.name }))
            main.post { onList(list) }
        }
        worker.post {
            val engine = tts ?: start(app)
            if (ready) deliver(engine) else synchronized(whenReady) { whenReady += deliver }
        }
    }

    /** A short name for [voice] in a list: a number, its country, and whether it needs the network. */
    fun label(voice: Voice, number: Int): String =
        "Voice $number (" + voice.locale.country.ifEmpty { voice.locale.language.uppercase() } + ")" +
            if (voice.isNetworkConnectionRequired) " · online" else ""

    private fun say(engine: TextToSpeech, text: String) {
        voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } }
        val id = "portal-${counter++}"
        saying[id] = text
        synchronized(active) { active += id }
        notifyChanged()
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(id: String) {
        saying.remove(id)
        synchronized(active) { active -= id }
        notifyChanged()
    }

    private fun notifyChanged() {
        val speaking = isSpeaking
        main.post { onSpeakingChanged?.invoke(speaking) }
    }
}
