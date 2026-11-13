package com.portal.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.portal.tv.extensions.BundledExtension
import com.portal.tv.extensions.CatalogInstaller
import com.portal.tv.repo.ProfileManager

/**
 * For a Portal that was upgraded (setup isn't shown again): the weather now comes from an extension that ships inside Portal,
 * so the home screen explains that once and asks whether to install it. Nothing is installed without the person choosing Install,
 * and Android still asks to confirm. A person who chooses Not now (or Back) isn't asked again; Settings, Weather installs it later.
 */
object WeatherOffer {

    @Volatile private var showing: PortalDialog? = null
    @Volatile private var waitingForPermission = false

    /** Looks, off the main thread, whether to ask, and asks from an adult's home screen. Kids are never asked. */
    fun checkAndShow(activity: Activity) {
        val app = activity.applicationContext
        if (ProfileManager.getActive(app)?.isKids != false) return
        if (showing?.isShowing == true) return
        kotlin.concurrent.thread(name = "weather-offer") {
            // Back from Android's "Install unknown apps" page, now allowed: carry straight on to the install.
            if (waitingForPermission && CatalogInstaller.canInstall(app)) {
                waitingForPermission = false
                activity.runOnUiThread { if (!activity.isFinishing) install(activity) }
                return@thread
            }
            waitingForPermission = false
            if (BundledExtension.needsOffer(app)) {
                activity.runOnUiThread { if (!activity.isFinishing && showing?.isShowing != true) show(activity) }
            }
        }
    }

    private fun show(activity: Activity) {
        BundledExtension.markOffered(activity)
        showing = PortalDialog.Builder(activity)
            .setTitle("Weather is now an extension")
            .setMessage(
                "Portal's weather now comes from an extension that ships with Portal: Portal TV Weather. It gets the forecast from " +
                    "Open-Meteo and US alerts from the National Weather Service, and is told roughly where your TV is (to within about " +
                    "10 km). Without it there is no weather on the home screen.\n\nAndroid will ask you to confirm the install, and may " +
                    "first ask you to let Portal install apps (it calls them \"unknown apps\" because they don't come from the Play Store). " +
                    "You can install it later from Settings, Weather."
            )
            .setPositiveButton("Install") { _, _ -> install(activity) }
            .setNegativeButton("Not now") { _, _ -> BundledExtension.setDeclined(activity, true) }
            .setOnCancelListener { BundledExtension.setDeclined(activity, true) }
            .show()
    }

    private fun install(activity: Activity) {
        val app = activity.applicationContext
        kotlin.concurrent.thread(name = "weather-install") {
            val result = BundledExtension.installIfNeeded(app, force = true)
            activity.runOnUiThread {
                val say = when (result) {
                    BundledExtension.Result.STARTED -> "Confirm the install on the screen Android shows"
                    BundledExtension.Result.UP_TO_DATE -> "The weather extension is already installed"
                    BundledExtension.Result.NOT_ALLOWED -> {
                        waitingForPermission = true
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
                        }.onFailure { waitingForPermission = false }
                        "Select Portal's switch to turn it on, then press Back"
                    }
                    BundledExtension.Result.FOREIGN -> "Another app uses the weather extension's name, so Portal left it alone"
                    else -> "This copy of Portal doesn't have the weather extension"
                }
                Toast.makeText(app, say, Toast.LENGTH_LONG).show()
            }
        }
    }
}
