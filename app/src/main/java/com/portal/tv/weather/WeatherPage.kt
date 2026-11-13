package com.portal.tv.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.portal.tv.extensions.BundledExtension
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.ExtensionRegistry

/**
 * Which weather page opens when the time and weather panel is pressed: the one in the extension that ships with Portal, or the one
 * of another extension an adult chose. Portal has no weather page of its own. It is chosen separately from where the weather
 * comes from ([WeatherSource]), so a page and a source can be mixed. Every profile gets the chosen page, kids included.
 */
object WeatherPage {

    private const val PREFS = "portal_weather_page"

    /** The package of the extension used as the weather page. */
    fun selected(context: Context): String = prefs(context).getString("package", null) ?: BundledExtension.WEATHER_PACKAGE

    /** Chooses the page: an extension's package. */
    fun select(context: Context, packageName: String) {
        val edit = prefs(context).edit()
        if (packageName == BundledExtension.WEATHER_PACKAGE) edit.remove("package") else edit.putString("package", packageName)
        edit.apply()
    }

    /**
     * Where the chosen page opens, checked again now (still installed, still signed by who was allowed, still allowed to be the
     * weather page, and its screen still there), or null if it can't be opened. Reads the package manager, so not on the main thread.
     */
    fun intent(context: Context): Intent? {
        val chosen = selected(context)
        val ext = ExtensionRegistry.find(context, chosen) ?: return null
        val screen = ext.weatherPage ?: return null
        if (Capability.WEATHER_PAGE !in ext.granted) return null
        return Intent(Intent.ACTION_MAIN).setComponent(ComponentName(chosen, screen)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
