package com.portal.tv.weather

import android.content.Context

/** Which weather source Portal uses: the one built in, or an extension an adult chose. One at a time. */
object WeatherSource {

    private const val PREFS = "portal_weather_source"

    /** The package of the extension chosen as the weather source, or null for the built-in one. */
    fun selected(context: Context): String? = prefs(context).getString("package", null)

    /**
     * Chooses the source: an extension's package, or null for the built-in one. What was kept from the old source (its
     * forecast and alerts) is forgotten, so the screens never show one source's weather as another's.
     */
    fun select(context: Context, packageName: String?) {
        if (packageName == selected(context)) return
        val edit = prefs(context).edit()
        if (packageName == null) edit.remove("package") else edit.putString("package", packageName)
        edit.apply()
        com.portal.tv.repo.WeatherRepository.forgetCached(context)
        com.portal.tv.repo.WeatherAlerts.forgetCached(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
