package com.portal.tv.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the reminder watcher again after the TV restarts, which would otherwise leave reminders waiting until someone
 * opened Portal. Nothing is started if there are no reminders.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderService.ensureRunning(context)
    }
}
