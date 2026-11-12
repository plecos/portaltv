package com.portal.tv.weather

import android.content.Context

/** The weather that comes with Portal: the forecast from Open-Meteo and alerts from the US National Weather Service. */
class BuiltInWeatherProvider internal constructor(
    private val forecast: OpenMeteoSource = OpenMeteoSource(),
    private val alerts: NwsAlertsSource = NwsAlertsSource()
) : WeatherProvider {

    override val name = "Open-Meteo and the US National Weather Service"

    override suspend fun fetchReport(place: WeatherPlace) = forecast.fetch(place)

    override suspend fun fetchAlerts(place: WeatherPlace) = alerts.fetch(place)
}

/** The one source Portal gets its weather from: the extension an adult chose in Settings, else the built-in one. */
object WeatherProviders {

    /** Call from a background thread: finding the extension reads the package manager. */
    fun active(context: Context): WeatherProvider {
        WeatherSource.selected(context)?.let { pkg ->
            // The name only labels alerts and errors; if the extension has gone, asking it fails and says so.
            val name = com.portal.tv.extensions.ExtensionRegistry.find(context, pkg)?.name ?: pkg
            return ExtensionWeatherProvider(context, pkg, name)
        }
        return builtIn(context)
    }

    private fun builtIn(context: Context): WeatherProvider {
        if (!com.portal.tv.BuildConfig.DEBUG) return BuiltInWeatherProvider()
        // Debug builds can point each service at a test server, to try odd forecasts and a severe alert.
        fun override(file: String, key: String, default: String) =
            context.getSharedPreferences(file, Context.MODE_PRIVATE).getString(key, null)?.trimEnd('/') ?: default
        return BuiltInWeatherProvider(
            OpenMeteoSource(override("weather_debug", "forecast_base", OpenMeteoSource.DEFAULT_BASE)),
            NwsAlertsSource(override("alerts_debug", "base", NwsAlertsSource.DEFAULT_BASE))
        )
    }
}
