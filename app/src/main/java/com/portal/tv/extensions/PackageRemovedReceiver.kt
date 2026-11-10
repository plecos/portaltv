package com.portal.tv.extensions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** An app was uninstalled: if it was an extension, what an adult allowed for it and what it made (reminders, item changes) goes with it. */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the system sends this, and only for an app that is really gone (not one being updated); anything else is ignored.
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        ExtensionRegistry.forget(context, packageName)
    }
}
