package com.portal.tv.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.ExtensionRegistry
import com.portal.tv.repo.ProfileManager

/**
 * Which weather page opens when the time and weather panel is pressed: Portal's own, or the one of an extension an adult
 * chose. It is chosen separately from where the weather comes from ([WeatherSource]), so a page and a source can be mixed.
 */
object WeatherPage {

    private const val PREFS = "portal_weather_page"

    /** The package of the extension chosen as the weather page, or null for Portal's own. */
    fun selected(context: Context): String? = prefs(context).getString("package", null)

    fun select(context: Context, packageName: String?) {
        val edit = prefs(context).edit()
        if (packageName == null) edit.remove("package") else edit.putString("package", packageName)
        edit.apply()
    }

    /**
     * Where the chosen page opens, checked again now (still installed, still signed by who was allowed, still allowed to be
     * the weather page, and its screen still there), or null if Portal's own page should open. A kid profile always gets
     * Portal's own page. Reads the package manager, so not on the main thread.
     */
    fun intent(context: Context): Intent? {
        val chosen = selected(context) ?: return null
        if (ProfileManager.isActiveKids(context)) return null
        val ext = ExtensionRegistry.find(context, chosen) ?: return null
        val screen = ext.weatherPage ?: return null
        if (Capability.WEATHER_PAGE !in ext.granted) return null
        return Intent(Intent.ACTION_MAIN).setComponent(ComponentName(chosen, screen)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
