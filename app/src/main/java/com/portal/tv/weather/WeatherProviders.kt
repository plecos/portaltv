package com.portal.tv.weather

import android.content.Context
import com.portal.tv.extensions.BundledExtension
import com.portal.tv.extensions.ExtensionRegistry

/**
 * The one source Portal gets its weather from: the extension an adult chose in Settings, else the weather extension that ships
 * with Portal. Portal fetches no weather itself, so with neither installed there is no weather and nothing is shown. If one is
 * installed but can't answer, the screens keep showing the last they had, marked as old.
 */
object WeatherProviders {

    /** Call from a background thread: finding the extension reads the package manager. */
    fun active(context: Context): WeatherProvider {
        val pkg = WeatherSource.selected(context)
        // The name only labels alerts and errors; if the extension has gone, asking it fails and says so.
        val name = ExtensionRegistry.find(context, pkg)?.name ?: pkg
        return ExtensionWeatherProvider(context, pkg, name)
    }
}

/** Which weather source Portal uses: an extension an adult chose, else the one that ships with Portal. One at a time. */
object WeatherSource {

    private const val PREFS = "portal_weather_source"

    /** The package of the extension used as the weather source. */
    fun selected(context: Context): String = prefs(context).getString("package", null) ?: BundledExtension.WEATHER_PACKAGE

    /**
     * Whether the extension chosen as the source is installed at all. Without one there is no weather provider, and Portal shows
     * no weather (not what it had kept) until one is installed. A quick package lookup, fine on any thread.
     */
    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(selected(context), 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    /** Whether the choice is the extension that ships with Portal (nothing else chosen). */
    fun isDefault(context: Context): Boolean = selected(context) == BundledExtension.WEATHER_PACKAGE

    /**
     * Chooses the source. What was kept from the old source (its forecast and alerts) is forgotten, so the screens never show
     * one source's weather as another's.
     */
    fun select(context: Context, packageName: String) {
        if (packageName == selected(context)) return
        val edit = prefs(context).edit()
        if (packageName == BundledExtension.WEATHER_PACKAGE) edit.remove("package") else edit.putString("package", packageName)
        edit.apply()
        com.portal.tv.repo.WeatherRepository.forgetCached(context)
        com.portal.tv.repo.WeatherAlerts.forgetCached(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
