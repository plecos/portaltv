package com.portal.tv.screensaver

import android.service.dreams.DreamService

/**
 * Registers Portal as a system screen saver, so it also runs when other apps are open. The user
 * picks it in the TV's Settings → Device Preferences → Screen saver; the system decides when to
 * start it, and it ends on any key press except left and right when an extension draws it and asked for them.
 */
class ScreensaverService : DreamService() {

    private var saver: ScreensaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // When an extension draws the screen saver, it is interactive, so Portal sees the keys itself: left and right can go to the
        // extension if it asked for them, and every other key (and a touch or mouse) ends it here, as the system does when it is
        // not interactive. With videos or shaders nothing changes: the system ends it on any input.
        val extension = ScreensaverSettings.look(this) == ScreensaverSettings.LOOK_EXTENSION
        isInteractive = extension
        isFullscreen = true
        saver = ScreensaverView(this).also { setContentView(it) }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!isInteractive) return super.dispatchKeyEvent(event)
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0 && saver?.handleKey(event.keyCode) == true) return true
            if (event.repeatCount == 0 || event.keyCode == android.view.KeyEvent.KEYCODE_BACK) wakeUp()
        }
        return true
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!isInteractive) return super.dispatchTouchEvent(event)
        wakeUp()
        return true
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (!isInteractive) return super.dispatchGenericMotionEvent(event)
        wakeUp()
        return true
    }

    override fun onDetachedFromWindow() {
        saver = null
        super.onDetachedFromWindow()
    }
}
