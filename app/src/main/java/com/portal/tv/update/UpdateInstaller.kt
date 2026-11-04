package com.portal.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.portal.tv.PortalActivity
import java.io.File
import java.io.IOException

/**
 * Hands a downloaded, checked APK to Android's package installer. Android replaces Portal with it and ends this
 * process; [UpdatedReceiver] then opens the new Portal again.
 *
 * Portal must be allowed to install apps ("Install unknown apps"). Setup turns that on over adb, as it does for the
 * other permissions it needs. The first time, Android may ask the person to confirm. Once Portal is the one that
 * installed it, Android can update it without asking, so we ask for that.
 */
object UpdateInstaller {
    const val ACTION_RESULT = "com.portal.tv.update.INSTALL_RESULT"
    private const val TAG = "Portal"
    private const val PREFS = "portal_updates"
    private const val KEY_RELAUNCH = "relaunch"

    /**
     * Told how an install ended when it did not go through (cancelled, or it failed), with a message to show. Set by
     * the Settings page while it is open; without one, the receiver shows a toast.
     */
    @Volatile var onFailed: ((String) -> Unit)? = null

    /** Whether Portal is allowed to install apps. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Installs [apk] over this app. Returns once Android has the file; the result comes to [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("portal.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_RESULT)
                // Mutable, so Android can add the status and the confirmation screen to it.
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RELAUNCH, true).apply()
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw IOException(e.message ?: "Android wouldn't take the update")
        }
    }

    /** True once after an install was started, so Portal opens again when the new version is in place. */
    internal fun takeRelaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val relaunch = prefs.getBoolean(KEY_RELAUNCH, false)
        if (relaunch) prefs.edit().remove(KEY_RELAUNCH).apply()
        return relaunch
    }
}

/** Android's answer to an install: show its confirmation screen if it needs one, or say why it failed. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.d("Portal", "update installed")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.d("Portal", "update failed: status $status, $why")
                context.getSharedPreferences("portal_updates", Context.MODE_PRIVATE).edit().remove("relaunch").apply()
                val message = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update was cancelled."
                else "Couldn't install the update${why?.let { ": $it" } ?: ""}"
                val listener = UpdateInstaller.onFailed
                if (listener != null) listener(message) else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** After Portal has been replaced by an update it started, open it again, so the TV isn't left without a home screen. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!UpdateInstaller.takeRelaunch(context)) return
        runCatching {
            context.startActivity(Intent(context, PortalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.d("Portal", "couldn't reopen after the update", it) }
    }
}
