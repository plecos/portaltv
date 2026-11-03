package com.portal.tv.kids

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.PortalActivity
import com.portal.tv.R
import com.portal.tv.repo.KidsSchedule
import com.portal.tv.repo.ProfileManager
import com.portal.tv.repo.ScheduleLogic

/**
 * Runs while a kid has an app open. It shows the time left in a small pill over whatever app is
 * on screen, but only now and then: when an app opens, then each hour while more than an hour is
 * left, at 30, 15 and 5 minutes, and as a second-by-second countdown for the last minute. It also
 * warns at five minutes and one minute, and when the time is up covers the screen, brings Portal
 * back and stops the app. The overlay needs "Display over other apps"; without it the service
 * still sends the kid back to Portal, there just's no countdown to see.
 *
 * Started when a kid opens an app from Portal, and stopped when Portal is back on screen.
 */
class KidsMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var pill: TextView? = null
    private var banner: TextView? = null
    private var lockView: View? = null
    private var lastWarned: ScheduleLogic.Warning? = null
    private var locked = false

    /** The reminder milestone shown last, so each hour, 30, 15 and 5 minutes shows once. */
    private var lastReminder: Long? = null

    /** The final minute is on screen as a countdown and stays until time is up. */
    private var countingDown = false

    /** Check every second near the end, so the countdown keeps time. */
    private var fast = false

    private val hidePill = Runnable { if (!countingDown) removePill() }

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, if (fast) 1_000L else TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.removeCallbacks(tick)
        tick.run()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlays()
        super.onDestroy()
    }

    private fun kid() = ProfileManager.getActive(this)?.takeIf { it.isKids }

    private fun check() {
        val kid = kid()
        if (kid == null || KidsSchedule.config(this, kid.id).isEmpty) {
            stopSelf()
            return
        }
        when (KidsSchedule.check(this, kid.id)) {
            is ScheduleLogic.Status.Allowed -> {
                if (locked) { locked = false; removeLock() }
                val left = KidsSchedule.timeUntilLock(this, kid.id)
                fast = left != null && left <= ScheduleLogic.COUNTDOWN_MS + 5_000L
                if (left == null) { countingDown = false; removePill(); return }
                showTimeLeft(left)
                // A parent adding time takes the clock back above a milestone, so it can warn again.
                if (lastWarned != null && left > lastWarned!!.ms) lastWarned = null
                ScheduleLogic.nextWarning(left, lastWarned)?.let {
                    lastWarned = it
                    if (it == ScheduleLogic.Warning.FIVE_MINUTES) showBanner("5 minutes left", ScheduleLogic.Tone.YELLOW)
                    else showBanner("1 minute left", ScheduleLogic.Tone.RED)
                }
            }
            else -> lockOut()
        }
    }

    /**
     * Show the time left only when it is worth a glance: briefly at each reminder, and all the time for the last
     * minute, when it counts down second by second.
     */
    private fun showTimeLeft(left: Long) {
        if (left <= ScheduleLogic.COUNTDOWN_MS) {
            countingDown = true
            handler.removeCallbacks(hidePill)
            showPill(KidsSchedule.countdownLabel(left), ScheduleLogic.tone(left))
            return
        }
        if (countingDown) {
            // A parent added time during the last minute.
            countingDown = false
            removePill()
        }
        // A parent adding time takes the clock back above the last reminder, so it can show again.
        if (lastReminder != null && left > lastReminder!!) lastReminder = null
        ScheduleLogic.nextReminder(left, lastReminder)?.let {
            lastReminder = it
            showPill(KidsSchedule.remainingLabel(left), ScheduleLogic.tone(left))
            handler.removeCallbacks(hidePill)
            handler.postDelayed(hidePill, PILL_MS)
        }
    }

    /** Time's up or bedtime: cover the screen and go back to Portal, which then stops the app. */
    private fun lockOut() {
        removePill()
        removeBanner()
        if (!locked) {
            locked = true
            showLock()
        }
        // Portal's full-screen lock takes over once it is in front, and then stops this service.
        startActivity(
            Intent(this, PortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    // ---------- Overlays ----------

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun params(focusable: Boolean, fullScreen: Boolean) = WindowManager.LayoutParams(
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sp: Float, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        typeface = if (bold) android.graphics.Typeface.create(resources.getFont(R.font.inter), android.graphics.Typeface.BOLD)
        else resources.getFont(R.font.inter)
    }

    private fun roundedBackground(alpha: Int) = GradientDrawable().apply {
        setColor(Color.argb(alpha, 15, 15, 26))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(80, 255, 255, 255))
    }

    /** Solid green, yellow or red, with text that reads on it: dark on yellow, white on the others. */
    private fun toneBackground(tone: ScheduleLogic.Tone) = GradientDrawable().apply {
        setColor(
            when (tone) {
                ScheduleLogic.Tone.GREEN -> Color.rgb(36, 150, 84)
                ScheduleLogic.Tone.YELLOW -> Color.rgb(240, 180, 20)
                ScheduleLogic.Tone.RED -> Color.rgb(205, 45, 52)
            }
        )
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(110, 255, 255, 255))
    }

    private fun toneText(tone: ScheduleLogic.Tone) =
        if (tone == ScheduleLogic.Tone.YELLOW) Color.rgb(30, 24, 0) else Color.WHITE

    private fun showPill(text: String, tone: ScheduleLogic.Tone) {
        if (!canDraw()) return
        val view = pill ?: label(22f, bold = true).apply {
            setPadding(dp(18), dp(8), dp(18), dp(10))
            pill = this
            windows.addView(this, params(focusable = false, fullScreen = false).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(48); y = dp(40)
            })
        }
        view.background = toneBackground(tone)
        view.setTextColor(toneText(tone))
        view.text = text
    }

    private fun removePill() { pill?.let { runCatching { windows.removeView(it) } }; pill = null }

    private fun showBanner(text: String, tone: ScheduleLogic.Tone) {
        if (!canDraw()) return
        removeBanner()
        val view = label(40f, bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(24), dp(48), dp(28))
            background = toneBackground(tone)
            setTextColor(toneText(tone))
        }
        banner = view
        windows.addView(view, params(focusable = false, fullScreen = false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        })
        handler.postDelayed({ if (banner === view) removeBanner() }, BANNER_MS)
    }

    private fun removeBanner() { banner?.let { runCatching { windows.removeView(it) } }; banner = null }

    private fun showLock() {
        if (!canDraw() || lockView != null) return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(15, 15, 26))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(label(44f, bold = true).apply { text = "Time to take a break"; gravity = Gravity.CENTER })
            addView(label(22f).apply {
                text = "Going back to Portal…"
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
            // Swallow keys so the app underneath can't be used. Home is handled by the system.
            setOnKeyListener { _, _, _ -> true }
        }
        lockView = view
        windows.addView(view, params(focusable = true, fullScreen = true))
        view.requestFocus()
    }

    private fun removeLock() { lockView?.let { runCatching { windows.removeView(it) } }; lockView = null }

    private fun removeOverlays() { removePill(); removeBanner(); removeLock() }

    // ---------- Notification ----------

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Kids schedule", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Portal is watching the clock")
            .setContentText("Reminding about the time left while an app is open")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Portal"
        private const val CHANNEL = "kids_schedule"
        private const val NOTIFICATION_ID = 41
        private const val TICK_MS = 5_000L
        private const val BANNER_MS = 7_000L
        private const val PILL_MS = 10_000L

        /** Starts watching; call right as a kid opens an app. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KidsMonitorService::class.java))
            }.onFailure { Log.w(TAG, "Couldn't start the kids monitor: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KidsMonitorService::class.java))
        }
    }
}
