package com.portal.tv.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.repo.WeatherAlerts
import com.portal.tv.ui.AlertStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches for severe weather alerts (see [WeatherAlerts.Alert.isSevere]) while Portal is running, and
 * when a new one arrives shows a red banner across the top of the screen, over whatever app is open,
 * with a tone. The banner doesn't take the remote, so the app keeps working, and it goes by itself
 * after [BANNER_MS]. Each alert is announced once.
 *
 * It checks the National Weather Service every [CHECK_MS]. The banner needs "Display over other
 * apps"; without it the service has nothing to show and stops itself.
 */
class SevereAlertService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windows: WindowManager
    private var banner: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!wanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (looping) return START_STICKY
        looping = true
        scope.launch {
            while (true) {
                if (!wanted(this@SevereAlertService)) break
                runCatching { check() }.onFailure { Log.w(TAG, "Alert check failed: ${it.message}") }
                delay(CHECK_MS)
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        looping = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        removeBanner()
        super.onDestroy()
    }

    private suspend fun check() {
        val alerts = WeatherAlerts.load(this, force = true)
        val fresh = WeatherAlerts.takeNewSevere(this, alerts)
        if (fresh.isNotEmpty()) show(fresh)
    }

    private fun show(alerts: List<WeatherAlerts.Alert>) {
        if (!Settings.canDrawOverlays(this)) return
        removeBanner()
        // On Portal's home screen the red banner is already there, so only the tone is needed.
        if (homeShowing) { tone(); return }
        val first = alerts.first()
        val d = resources.displayMetrics.density
        fun text(sp: Float, bold: Boolean) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(Color.WHITE)
            typeface = if (bold) android.graphics.Typeface.create(resources.getFont(R.font.inter), android.graphics.Typeface.BOLD)
            else resources.getFont(R.font.inter)
            gravity = Gravity.CENTER
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((40 * d).toInt(), (18 * d).toInt(), (40 * d).toInt(), (20 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(200, 28, 36))
                cornerRadius = 18 * d
                setStroke((3 * d).toInt(), Color.WHITE)
            }
            addView(text(36f, true).apply {
                this.text = "⚠  " + first.event.uppercase() + if (alerts.size > 1) "  +${alerts.size - 1}" else ""
            })
            val sub = listOf(AlertStyle.until(this@SevereAlertService, first), first.area).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (sub.isNotEmpty()) addView(text(20f, false).apply {
                this.text = sub
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
        }
        banner = view
        windows.addView(view, WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.8f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the app underneath keeps the remote. (Not marked "not touchable": Android
            // thins out such a window to 80%, and this one should be solid.)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (36 * d).toInt()
        })
        tone()
        handler.postDelayed({ if (banner === view) removeBanner() }, BANNER_MS)
    }

    private fun removeBanner() {
        banner?.let { runCatching { windows.removeView(it) } }
        banner = null
    }

    /** Three short beeps, on the alarm volume so they're heard even when the TV's media is quiet. */
    private fun tone() {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            repeat(3) { i -> handler.postDelayed({ generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350) }, i * 600L) }
            handler.postDelayed({ generator.release() }, 3_000L)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Severe weather alerts", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Portal is watching for severe weather")
            .setContentText("A banner shows over your app if a severe alert is issued")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Portal"
        private const val CHANNEL = "severe_alerts"
        private const val NOTIFICATION_ID = 42
        private const val CHECK_MS = 2 * 60_000L
        const val BANNER_MS = 30_000L

        @Volatile private var looping = false

        /** True while Portal's home screen is in front, where the alert has a red banner of its own. */
        @Volatile var homeShowing = false

        /** True when the service has a reason to run: the switch is on and Portal may draw over other apps. */
        fun wanted(context: Context) = WeatherAlerts.overApps(context) && Settings.canDrawOverlays(context)

        /** Starts watching if wanted; safe to call again and again. */
        fun ensureRunning(context: Context) {
            if (!wanted(context)) return
            runCatching {
                context.startForegroundService(Intent(context, SevereAlertService::class.java))
            }.onFailure { Log.w(TAG, "Couldn't start the alert watcher: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SevereAlertService::class.java))
        }
    }
}
