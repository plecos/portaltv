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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
 * screen, over whatever app is open, with the name and picture of the profile that set it and the sound it was set to
 * make. A reminder set to go by itself leaves after [BANNER_MS] and doesn't take the remote, so the app keeps working.
 * One set to stay until dismissed, or with Snooze, has buttons and takes the remote until it is dealt with (Back
 * dismisses). Several due together share one card. Without "Display over other apps" the reminder is an ordinary
 * notification instead.
 *
 * It looks at the clock every [TICK_MS] and stops itself when no reminders are left. [BootReceiver] starts it again
 * after the TV restarts. What happens to a reminder that came due while the TV was off is [MissedPolicy].
 */
class ReminderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windows: WindowManager
    private var banner: LinearLayout? = null

    /** A reminder on the card. [until] is when it goes by itself (uptime milliseconds), or null if it waits to be dismissed. */
    private class Active(val firing: Firing, val until: Long?)
    private val active = mutableListOf<Active>()

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
                if (ReminderStore.all(this@ReminderService).isEmpty() && active.isEmpty()) break
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
        active.clear()
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
        val now = SystemClock.uptimeMillis()
        // One that arrives while others are showing joins them.
        active += fired.map { Active(it, if (it.reminder.manual) null else now + BANNER_MS) }
        // One sound for the lot: the first that isn't set to silent.
        fired.firstOrNull { it.reminder.tone != ReminderTones.NONE }?.let { TonePlayer.play(this, it.reminder.tone) }
        render()
    }

    private val expire = Runnable {
        val now = SystemClock.uptimeMillis()
        if (active.removeAll { it.until != null && it.until <= now }) render() else scheduleExpiry()
    }

    private fun scheduleExpiry() {
        handler.removeCallbacks(expire)
        active.mapNotNull { it.until }.minOrNull()?.let { handler.postAtTime(expire, it) }
    }

    private fun dismiss(a: Active) {
        active.remove(a)
        render()
    }

    private fun snooze(a: Active) {
        val later = ReminderLogic.snoozed(a.firing.reminder, System.currentTimeMillis())
        ReminderStore.add(this, later.profileId, later.title, later.atMillis, later.repeat, like = later)
        dismiss(a)
    }

    /** Draws the card for what is [active], or takes it down when nothing is. */
    private fun render() {
        removeBanner()
        scheduleExpiry()
        if (active.isEmpty()) return
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun text(sp: Float, bold: Boolean, color: Int = Color.WHITE) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = if (bold) Typeface.create(resources.getFont(R.font.inter), Typeface.BOLD) else resources.getFont(R.font.inter)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            typeface = resources.getFont(R.font.inter)
            this.text = label
            gravity = Gravity.CENTER
            setPadding(px(26), px(10), px(26), px(10))
            isFocusable = true
            isClickable = true
            fun look(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = px(14).toFloat()
                    setColor(if (focused) Color.rgb(138, 131, 255) else Color.rgb(52, 55, 90))
                    if (focused) setStroke(px(3), Color.WHITE)
                }
            }
            look(false)
            setOnFocusChangeListener { _, focused -> look(focused) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = px(14) }
        }
        val now = System.currentTimeMillis()
        val view = object : LinearLayout(this) {
            // Back dismisses what is on the card (Snooze is the button for putting it off).
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) { active.removeAll { it.firing.reminder.needsButtons }; render() }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(20), px(32), px(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 26, 48))
                cornerRadius = px(20).toFloat()
                setStroke(px(3), Color.rgb(138, 131, 255))
            }
        }
        var firstButton: View? = null
        val shown = active.takeLast(MAX_SHOWN)
        shown.forEachIndexed { i, a ->
            val f = a.firing
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
            if (f.reminder.needsButtons) {
                val buttons = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, px(12), 0, 0)
                }
                if (f.reminder.snoozeMinutes > 0) buttons.addView(button("Snooze ${f.reminder.snoozeMinutes} min") { snooze(a) }.also { if (firstButton == null) firstButton = it })
                buttons.addView(button("Dismiss") { dismiss(a) }.also { if (firstButton == null) firstButton = it })
                words.addView(buttons)
            }
            row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(row)
        }
        if (active.size > shown.size) view.addView(text(16f, false, Color.rgb(176, 176, 192)).apply {
            this.text = "+${active.size - shown.size} more"
            setPadding(0, px(10), 0, 0)
        })
        banner = view
        // A card with buttons takes the remote; one without leaves it to the app underneath.
        val takesRemote = firstButton != null
        windows.addView(view, WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (takesRemote) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(36)
        })
        firstButton?.let { b -> view.post { b.requestFocus() } }
    }

    private fun removeBanner() {
        banner?.let { runCatching { windows.removeView(it) } }
        banner = null
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
