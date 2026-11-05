package com.portal.tv.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.repo.ProfileManager
import com.portal.tv.ui.ProfileAvatar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps time for reminders while there is one to wait for. When one is due it shows a card across the top of the
 * screen, over whatever app is open, with the name and picture of the profile that set it and a tone. The card doesn't
 * take the remote, so the app keeps working, and it goes by itself after [BANNER_MS]. Without "Display over other
 * apps" the reminder is an ordinary notification instead.
 *
 * It looks at the clock every [TICK_MS] and stops itself when no reminders are left. [BootReceiver] starts it again
 * after the TV restarts. What happens to a reminder that came due while the TV was off is [MissedPolicy].
 */
class ReminderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windows: WindowManager
    private var banner: LinearLayout? = null
    private val showing = mutableListOf<Firing>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        if (ReminderStore.all(this).isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (looping) return START_STICKY
        looping = true
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.w(TAG, "Reminder check failed: ${it.message}") }
                // Nothing left to wait for, and no card still on screen (stopping would take it down).
                if (ReminderStore.all(this@ReminderService).isEmpty() && banner == null) break
                delay(TICK_MS)
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

    private fun tick() {
        val all = ReminderStore.all(this)
        val resolution = ReminderLogic.resolve(
            all, System.currentTimeMillis(), policy = ReminderStore.missedPolicy(this),
            exists = { ProfileManager.getById(this, it) != null }
        )
        if (resolution.keep == all) return
        ReminderStore.replace(this, resolution.keep)
        if (resolution.fire.isNotEmpty()) show(resolution.fire)
    }

    private fun show(fired: List<Firing>) {
        if (Settings.canDrawOverlays(this)) showBanner(fired) else fired.forEach { notify(it) }
    }

    // ---------- Card over the app ----------

    private fun showBanner(fired: List<Firing>) {
        // One that arrives while another is showing joins it; the card stays up for a fresh [BANNER_MS].
        showing += fired
        removeBanner()
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(sp: Float, bold: Boolean, color: Int = Color.WHITE) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (bold) Typeface.create(resources.getFont(R.font.inter), Typeface.BOLD) else resources.getFont(R.font.inter)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val now = System.currentTimeMillis()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(20), px(32), px(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 26, 48))
                cornerRadius = px(20).toFloat()
                setStroke(px(3), Color.rgb(138, 131, 255))
            }
        }
        val shown = showing.takeLast(MAX_SHOWN)
        shown.forEachIndexed { i, f ->
            val profile = ProfileManager.getById(this, f.reminder.profileId)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (i > 0) setPadding(0, px(14), 0, 0)
            }
            if (profile != null) row.addView(ImageView(this).apply {
                setImageDrawable(ProfileAvatar.drawable(this@ReminderService, profile))
                layoutParams = LinearLayout.LayoutParams(px(64), px(64)).apply { marginEnd = px(20) }
            })
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(text(16f, false, Color.rgb(176, 176, 192)).apply {
                this.text = (profile?.name ?: "Reminder").uppercase() + "  ·  " +
                    if (f.late) "MISSED, WAS DUE ${ReminderLogic.whenLabel(f.reminder.atMillis, now).uppercase()}" else "REMINDER"
            })
            words.addView(text(30f, true).apply { this.text = f.reminder.title })
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(row)
        }
        if (showing.size > shown.size) view.addView(text(16f, false, Color.rgb(176, 176, 192)).apply {
            this.text = "+${showing.size - shown.size} more"
            setPadding(0, px(10), 0, 0)
        })
        banner = view
        windows.addView(view, WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the app underneath keeps the remote.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(36)
        })
        tone()
        handler.removeCallbacksAndMessages(HIDE)
        handler.postAtTime({ if (banner === view) { removeBanner(); showing.clear() } }, HIDE, android.os.SystemClock.uptimeMillis() + BANNER_MS)
    }

    private fun removeBanner() {
        banner?.let { runCatching { windows.removeView(it) } }
        banner = null
    }

    /** Two short beeps, on the alarm volume so they're heard even when the TV's media is quiet. */
    private fun tone() {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            repeat(2) { i -> handler.postDelayed({ generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 350) }, i * 600L) }
            handler.postDelayed({ generator.release() }, 2_000L)
        }
    }

    // ---------- Without permission to draw over apps ----------

    private fun notify(f: Firing) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val who = ProfileManager.getById(this, f.reminder.profileId)?.name
        manager.notify(
            ALERT_ID_BASE + f.reminder.id,
            Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(f.reminder.title)
                .setContentText((who ?: "Reminder") + if (f.late) " · missed, was due ${ReminderLogic.whenLabel(f.reminder.atMillis, System.currentTimeMillis())}" else "")
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Portal is waiting for a reminder")
            .setContentText("A card shows over your app when one is due")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Portal"
        private const val CHANNEL = "reminders_watch"
        private const val ALERT_CHANNEL = "reminders"
        private const val NOTIFICATION_ID = 43
        private const val ALERT_ID_BASE = 1000
        private const val TICK_MS = 10_000L
        private const val MAX_SHOWN = 3
        const val BANNER_MS = 30_000L
        private val HIDE = Any()

        @Volatile private var looping = false

        /** Starts watching if there are reminders to wait for; safe to call again and again. */
        fun ensureRunning(context: Context) {
            if (ReminderStore.all(context).isEmpty()) return
            runCatching {
                context.startForegroundService(Intent(context, ReminderService::class.java))
            }.onFailure { Log.w(TAG, "Couldn't start the reminder watcher: ${it.message}") }
        }
    }
}
