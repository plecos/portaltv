package com.portal.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.portal.tv.repo.ProfileCode
import com.portal.tv.repo.ProfileManager
import com.portal.tv.screensaver.ScreensaverFragment
import com.portal.tv.screensaver.ScreensaverSettings
import com.portal.tv.ui.HomeFragment
import com.portal.tv.ui.IntroAnimation
import com.portal.tv.ui.PinPad
import com.portal.tv.ui.OnboardingFragment
import com.portal.tv.ui.ProfilePickerFragment

class PortalActivity : FragmentActivity() {

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_portal)
        // Tidy up after updates that took videos out of the app, without slowing the start.
        kotlin.concurrent.thread(name = "video-tidy") {
            com.portal.tv.background.BackgroundRepository.removeStaleCopies(applicationContext)
            com.portal.tv.video.DownloadedVideos.repairAll(applicationContext)
        }

        if (savedInstanceState == null) {
            // Nothing works without a profile, so start by picking or creating one. A profile with
            // a code is asked for it again on a fresh start, so the picker comes first.
            com.portal.tv.repo.Onboarding.migrate(this)
            val onboarding = !com.portal.tv.repo.Onboarding.isDone(this)
            val active = ProfileManager.getActive(this)
            val toPicker = active == null || ProfileCode.isSet(this, active.id)
            val first = when {
                onboarding -> OnboardingFragment.forFirstRun()
                toPicker -> ProfilePickerFragment.forLaunch()
                else -> HomeFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, first)
                .commit()
            // A fresh start gets the ring intro, laid over whichever screen comes first. The setup has
            // no use for it: it is the first thing a new TV shows.
            if (!onboarding) IntroAnimation.play(findViewById(android.R.id.content), expectHome = !toPicker)
        }
    }

    // ---------- Screen saver ----------

    override fun onResume() {
        super.onResume()
        // Watching for severe weather alerts carries on while another app is open.
        com.portal.tv.alerts.SevereAlertService.ensureRunning(this)
        com.portal.tv.reminders.ReminderService.ensureRunning(this)
        // Keep the TV's start time in step with the screen saver's "Start after", which is the same for every
        // profile. Quietly: it only writes if Portal is already allowed to.
        com.portal.tv.adb.SelfSetup.syncStartAfterQuietly(this, ScreensaverSettings.idleMinutes(this))
        scheduleIdle()
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleRunnable)
    }

    /** Any key or touch restarts the idle countdown. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        scheduleIdle()
    }

    /**
     * Restarts the idle countdown. Used by windows of their own, such as the search panel, whose key presses and touches
     * never reach this activity, and by long jobs such as an answer being written or read aloud.
     */
    fun noteActivity() = scheduleIdle()

    /** The first key press only wakes the screen saver; it doesn't reach whatever is behind it. */
    // Lint wrongly calls this restricted: it is the platform Activity method, overridden here as usual.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any key skips the intro, and is not passed on.
        IntroAnimation.current?.let {
            if (event.action == KeyEvent.ACTION_DOWN) it.skip()
            return true
        }
        if (isScreensaverShowing()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) leaveScreensaver()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isScreensaverShowing() =
        supportFragmentManager.findFragmentById(R.id.container) is ScreensaverFragment

    private fun scheduleIdle() {
        idleHandler.removeCallbacks(idleRunnable)
        val minutes = ScreensaverSettings.idleMinutes(this)
        if (minutes > 0) idleHandler.postDelayed(idleRunnable, minutes * 60_000L)
    }

    /** Starts only from the home screen, so it never interrupts setup, Settings or a PIN prompt. */
    private fun onIdle() {
        if (supportFragmentManager.findFragmentById(R.id.container) is HomeFragment) {
            // The search panel sits over the home screen. Typing, talking and answers all call noteActivity(), so if the
            // countdown still ran out the person has really gone; close the panel and let the screen saver start.
            supportFragmentManager.fragments.filterIsInstance<androidx.fragment.app.DialogFragment>()
                .forEach { it.dismissAllowingStateLoss() }
            startScreensaver()
        } else scheduleIdle()
    }

    /** Whether the running screen saver was started from Settings to look at it, which needs no code to leave. */
    private var previewing = false

    /** A code prompt for the active profile is open, so more key presses don't open another. */
    private var asking = false

    /**
     * Leave the screen saver. If the active profile has a code it is asked for first, and the saver stays up until
     * it is right. A wrong code or Cancel leaves the saver as it was; the next key press asks again.
     */
    private fun leaveScreensaver() {
        val profile = ProfileManager.getActive(this)
        if (previewing || profile == null || !ProfileCode.isSet(this, profile.id)) {
            supportFragmentManager.popBackStack()
            scheduleIdle()
            return
        }
        if (asking) return
        asking = true
        PinPad.ask(
            this, "Enter ${profile.name}'s code",
            onDone = { pin ->
                asking = false
                if (ProfileCode.verify(this, profile.id, pin)) {
                    supportFragmentManager.popBackStack()
                    scheduleIdle()
                } else {
                    android.widget.Toast.makeText(this, "Wrong code", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { asking = false }
        )
    }

    fun startScreensaver(preview: Boolean = false) {
        if (isScreensaverShowing() || supportFragmentManager.isStateSaved) return
        previewing = preview
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, ScreensaverFragment())
            .addToBackStack("screensaver")
            .commit()
    }
}
