package net.getportaltv.sample

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.View
import kotlin.math.sin

/**
 * Draws the home screen background for Portal. Portal binds here and sends MSG_START with a size and a host token; this
 * service makes a SurfaceControlViewHost, puts a view in it, and sends Portal the SurfacePackage to show. Everything
 * runs in this app's own process: Portal only displays the picture.
 */
class BackgroundService : Service() {

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
        val fps = data.getInt("max_fps", 30).coerceIn(1, 60)
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt("display_id", 0)) ?: return
        val context = createDisplayContext(display)
        val viewHost = SurfaceControlViewHost(context, display, token)
        viewHost.setView(Drift(context, 1000L / fps), width, height)
        host = viewHost
        runCatching {
            msg.replyTo?.send(Message.obtain(null, MSG_READY).apply { this.data = Bundle().apply { putParcelable("surface_package", viewHost.surfacePackage) } })
        }
    }

    private fun release() {
        host?.release()
        host = null
    }

    /** Slow drifting colour blobs on a dark gradient, redrawn at the speed Portal asked for. */
    private class Drift(context: Context, private val frameMs: Long) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val start = System.nanoTime()
        private val colors = intArrayOf(Color.argb(150, 90, 110, 255), Color.argb(130, 200, 90, 230), Color.argb(130, 60, 200, 190))

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = LinearGradient(0f, 0f, w, h, Color.rgb(10, 12, 34), Color.rgb(30, 14, 52), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            val t = (System.nanoTime() - start) / 1e9f
            colors.forEachIndexed { i, c ->
                paint.color = c
                val x = w * (0.5f + 0.35f * sin(t * (0.15f + 0.07f * i) + i * 2f))
                val y = h * (0.5f + 0.30f * sin(t * (0.11f + 0.05f * i) + i))
                canvas.drawCircle(x, y, h * (0.22f + 0.05f * i), paint)
            }
            postInvalidateDelayed(frameMs)
        }
    }

    private companion object {
        const val MSG_START = 1
        const val MSG_READY = 2
        const val MSG_STOP = 3
    }
}
