package net.getportaltv.scenic

import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost

/**
 * Draws the home screen background for Portal. Portal binds here and sends MSG_START with a size and a host token; this
 * service puts the scene in a SurfaceControlViewHost and sends Portal the SurfacePackage to show. Everything runs in this
 * app's own process: Portal only displays the picture.
 */
class ScenicService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_START -> start(msg)
                MSG_STOP -> release()
            }
        }
    }
    private val messenger = Messenger(handler)
    private var host: SurfaceControlViewHost? = null

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return false
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun start(msg: Message) {
        release()
        val data = msg.data ?: return
        val width = data.getInt("width", 1920)
        val height = data.getInt("height", 1080)
        val token = data.getBinder("host_token") ?: return
        // The most Portal allows; the rate itself is the one chosen in this extension's settings, and is read again as it changes.
        val allowed = data.getInt("max_fps", 30).coerceIn(1, 120)
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt("display_id", 0)) ?: return
        val context = createDisplayContext(display)
        val viewHost = SurfaceControlViewHost(context, display, token)
        var config = Settings.load(this)
        val prefs = Settings.prefs(this)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> config = Settings.load(this) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        listenerKept = listener
        viewHost.setView(ScenicView(context, { minOf(config.fps, allowed) }, { config }, PortalWeather(this)), width, height)
        host = viewHost
        runCatching {
            msg.replyTo?.send(Message.obtain(null, MSG_READY).apply { this.data = Bundle().apply { putParcelable("surface_package", viewHost.surfacePackage) } })
        }
    }

    /** Held so the preferences' listener isn't dropped while the picture is showing. */
    private var listenerKept: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun release() {
        listenerKept?.let { Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(it) }
        listenerKept = null
        host?.release()
        host = null
    }

    private companion object {
        const val MSG_START = 1
        const val MSG_READY = 2
        const val MSG_STOP = 3
    }
}
