package com.portal.tv.weather

/** A source's answer: the value, or the short reason it could not be had (such as "No connection"). */
sealed class ProviderResult<out T> {
    data class Ok<T>(val value: T) : ProviderResult<T>()
    data class Failed(val reason: String) : ProviderResult<Nothing>()
}

/**
 * Where weather comes from. Portal asks the one chosen source on its own schedule and keeps what it gets; screens
 * never talk to a source directly. Implementations do their own network work and must not throw.
 */
interface WeatherProvider {
    /** Who the weather is from, for showing to an adult. */
    val name: String

    suspend fun fetchReport(place: WeatherPlace): ProviderResult<WeatherReport>

    /** Alerts in force at [place], most serious first. A place the source doesn't cover is [ProviderResult.Ok] with an empty list. */
    suspend fun fetchAlerts(place: WeatherPlace): ProviderResult<List<WeatherAlert>>

    /** Places matching a name or zip, for choosing where the weather is for. An empty list means nothing matched. */
    suspend fun searchCities(query: String): ProviderResult<List<WeatherCity>>

    /** The radar pictures there are for [place]. */
    suspend fun radarIndex(place: WeatherPlace): ProviderResult<RadarIndex>

    /**
     * One radar picture as PNG bytes: the frame [ref] (from [radarIndex]) of the window [w] by [h] pixels whose top left is ([x0], [y0])
     * in zoom-[zoom] world pixels. Only a PNG of exactly that size comes back; anything else is a failure.
     */
    suspend fun radarImage(ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): ProviderResult<ByteArray>
}
