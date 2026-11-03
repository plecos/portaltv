package com.portal.tv.screensaver

import android.service.dreams.DreamService

/**
 * Registers Portal as a system screen saver, so it also runs when other apps are open. The user
 * picks it in the TV's Settings → Device Preferences → Screen saver; the system decides when to
 * start it and ends it on any key press.
 */
class ScreensaverService : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(ScreensaverView(this))
    }
}
