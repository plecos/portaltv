package com.portal.tv.weather

/**
 * How Portal asks an extension for weather. The extension declares `<weather-source service=".X"/>`; Portal binds to that
 * service (action [ACTION]) for one question at a time, sends a message and waits for one reply. Portal does the asking on
 * its own schedule; the extension only answers, so it needs no timer or background work of its own.
 *
 * Request ([MSG_REPORT] or [MSG_ALERTS], `replyTo` set): [KEY_LAT], [KEY_LON] (rounded to a tenth of a degree) and, when Portal
 * has one, [KEY_CITY]. Reply ([MSG_REPLY]): [KEY_JSON], the answer in the format of [WeatherJson], or [KEY_ERROR], a few words
 * saying why not. Nothing else in the message is read.
 */
object WeatherProtocol {
    const val ACTION = "net.getportaltv.WEATHER_SOURCE"

    const val MSG_REPORT = 1
    const val MSG_ALERTS = 2
    const val MSG_REPLY = 3

    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"
    const val KEY_CITY = "city"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"

    /** How long an extension has to answer before Portal gives up and keeps what it had. */
    const val ANSWER_TIMEOUT_MS = 20_000L

    /** The longest reason from an extension that Portal will show. */
    const val MAX_ERROR = 40

    /** Where the TV is, as far as a source outside Portal is told: a tenth of a degree, about 10 km. */
    fun rounded(degrees: Double): Double = Math.round(degrees * 10.0) / 10.0
}
