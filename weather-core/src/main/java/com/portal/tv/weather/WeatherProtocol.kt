package com.portal.tv.weather

/**
 * How Portal asks an extension for weather. The extension declares `<weather-source service=".X"/>`; Portal binds to that
 * service (action [ACTION]) for one question at a time, sends a message and waits for one reply. Portal does the asking on
 * its own schedule; the extension only answers, so it needs no timer or background work of its own.
 *
 * Request ([MSG_REPORT] or [MSG_ALERTS], `replyTo` set): [KEY_LAT], [KEY_LON] (rounded to a tenth of a degree) and, when Portal
 * has one, [KEY_CITY]. Reply ([MSG_REPLY]): [KEY_JSON], the answer in the format of [WeatherJson], or [KEY_ERROR], a few words
 * saying why not. Nothing else in the message is read.
 *
 * A city search ([MSG_SEARCH_CITIES]) carries [KEY_QUERY], what the person typed, and is answered with a JSON array of
 * `{name, region, lat, lon}`.
 *
 * Radar goes through Portal too, picture by picture, so whatever a source needs to reach its radar service (a key, say) never
 * leaves it. [MSG_RADAR_INDEX] carries [KEY_LAT] and [KEY_LON] and is answered with the JSON of a [RadarIndex]. [MSG_RADAR_IMAGE]
 * asks for one picture, a PNG with transparency: [KEY_REF] (a frame's `ref` from the index), the map level [KEY_ZOOM] and the
 * window to draw, whose top left is [KEY_X] and [KEY_Y] (doubles, in pixels of the whole world at that level, 256 pixels to a
 * tile, as in a web map) and whose size is [KEY_W] by [KEY_H] pixels. It is answered with the PNG bytes in [KEY_IMAGE].
 */
object WeatherProtocol {
    const val ACTION = "net.getportaltv.WEATHER_SOURCE"

    const val MSG_REPORT = 1
    const val MSG_ALERTS = 2
    const val MSG_REPLY = 3
    const val MSG_SEARCH_CITIES = 4
    const val MSG_RADAR_INDEX = 5
    const val MSG_RADAR_IMAGE = 6

    const val KEY_REF = "ref"
    const val KEY_ZOOM = "z"
    const val KEY_X = "x0"
    const val KEY_Y = "y0"
    const val KEY_W = "w"
    const val KEY_H = "h"
    const val KEY_IMAGE = "image"

    /** The most of a radar picture Portal will take, in bytes. A reply travels in one transaction, which is about a megabyte at most for everything in flight. */
    const val MAX_IMAGE_BYTES = 400_000
    /** The biggest picture, in pixels each way. */
    const val MAX_IMAGE_SIDE = 2048

    const val KEY_QUERY = "query"
    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"
    const val KEY_CITY = "city"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"

    /** How long an extension has to answer before Portal gives up and keeps what it had. */
    const val ANSWER_TIMEOUT_MS = 40_000L

    /** The longest reason from an extension that Portal will show. */
    const val MAX_ERROR = 40

    /** Where the TV is, as far as a source outside Portal is told: a tenth of a degree, about 10 km. */
    fun rounded(degrees: Double): Double = Math.round(degrees * 10.0) / 10.0
}
