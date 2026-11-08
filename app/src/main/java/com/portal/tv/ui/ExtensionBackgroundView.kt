package com.portal.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.portal.tv.extensions.BackgroundChoice
import com.portal.tv.extensions.BackgroundProtocol
import com.portal.tv.extensions.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows a background that an extension draws. The extension draws in its own process (it makes a SurfaceControlViewHost
 * and sends Portal the picture), so none of its code runs inside Portal; this view only displays what comes back. It is
 * behind the home screen's other views, can't take the remote or a touch, and stays hidden until the first picture
 * arrives. If the extension doesn't answer in time, goes away, or is no longer allowed, [onFailed] runs once and Portal
 * puts its own background back.
 */
class ExtensionBackgroundView(context: Context) : FrameLayout(context) {

    private val surface = SurfaceView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var service: Messenger? = null
    private var shown: SurfaceControlViewHost.SurfacePackage? = null
    private var running = false

    /** Runs when the first picture from the extension is showing. */
    var onReady: (() -> Unit)? = null
    /** Runs, once per [start], when the extension can't be used. */
    var onFailed: ((String) -> Unit)? = null

    private val timeout = Runnable { fail("It didn't answer in time.") }

    init {
        // Never in the way: not focusable, not clickable, and invisible until there is something to show.
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.isFocusable = false
        surface.isClickable = false
        surface.visibility = View.INVISIBLE
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Asks [packageName] to draw. Safe to call again after [stop]. [scope] is for the check that it is still allowed. */
    fun start(packageName: String, scope: CoroutineScope) {
        if (running) return
        running = true
        handler.postDelayed(timeout, BackgroundProtocol.READY_TIMEOUT_MS)
        scope.launch {
            // Checked again now: still installed, still signed by who was allowed, still allowed to draw.
            val ext = withContext(Dispatchers.IO) { ExtensionRegistry.find(context, packageName) }
            // A kid profile uses it only if a parent turned it on for that kid.
            val profile = com.portal.tv.repo.ProfileManager.getActive(context)
            val kid = profile?.isKids == true
            val kidAllowed = kid && profile != null && com.portal.tv.settings.BackgroundSettings.kidCanPickExtension(context, profile.id)
            val use = BackgroundChoice.resolve(packageName, ext?.granted.orEmpty(), ext?.backgroundService != null, kid = kid, kidAllowed = kidAllowed)
            val serviceName = ext?.backgroundService
            if (!running) return@launch
            if (use !is BackgroundChoice.Use.Extension || serviceName == null) { fail((use as? BackgroundChoice.Use.Own)?.reason ?: "It can't be used."); return@launch }
            bind(packageName, serviceName)
        }
    }

    private fun bind(packageName: String, serviceName: String) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder)
                begin()
            }
            override fun onServiceDisconnected(name: ComponentName) = fail("It stopped.")
            override fun onBindingDied(name: ComponentName) = fail("It stopped.")
            override fun onNullBinding(name: ComponentName) = fail("It didn't offer a background.")
        }
        connection = conn
        val bound = runCatching {
            context.bindService(Intent(BackgroundProtocol.ACTION).setComponent(ComponentName(packageName, serviceName)), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) fail("Portal couldn't connect to it.")
    }

    /** Sends the size, the display and the token the extension needs to draw into this view's surface. */
    private fun begin() {
        val messenger = service ?: return
        // The token exists once the view is on a window; wait for the first layout if it isn't yet.
        if (!isAttachedToWindow || width == 0) { post { if (running && service != null) begin() }; return }
        val (w, h) = BackgroundChoice.size(width, height)
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != BackgroundProtocol.MSG_READY || !running || shown != null) return
                val pkg = msg.data?.getParcelable(BackgroundProtocol.KEY_PACKAGE, SurfaceControlViewHost.SurfacePackage::class.java)
                    ?: return fail("It sent nothing to show.")
                shown = pkg
                surface.setChildSurfacePackage(pkg)
                surface.visibility = View.VISIBLE
                handler.removeCallbacks(timeout)
                onReady?.invoke()
            }
        })
        val message = Message.obtain(null, BackgroundProtocol.MSG_START).apply {
            replyTo = replies
            data = Bundle().apply {
                putInt(BackgroundProtocol.KEY_WIDTH, w)
                putInt(BackgroundProtocol.KEY_HEIGHT, h)
                putBinder(BackgroundProtocol.KEY_HOST_TOKEN, surface.hostToken)
                putInt(BackgroundProtocol.KEY_DISPLAY_ID, display?.displayId ?: 0)
                putInt(BackgroundProtocol.KEY_MAX_FPS, BackgroundProtocol.MAX_FPS)
            }
        }
        runCatching { messenger.send(message) }.onFailure { fail("Portal couldn't reach it.") }
    }

    private fun fail(reason: String) {
        if (!running) return
        Log.w("Portal", "Extension background failed: $reason")
        stop()
        onFailed?.invoke(reason)
    }

    /** Lets go of the extension and what it drew. The view stays, hidden, and can be started again. */
    fun stop() {
        running = false
        handler.removeCallbacks(timeout)
        runCatching { service?.send(Message.obtain(null, BackgroundProtocol.MSG_STOP)) }
        service = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        shown?.let { runCatching { it.release() } }
        shown = null
        surface.visibility = View.INVISIBLE
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
