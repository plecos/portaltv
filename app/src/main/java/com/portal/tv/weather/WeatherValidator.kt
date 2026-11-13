package com.portal.tv.weather

import com.portal.tv.extensions.TextSafety
import java.time.OffsetDateTime

/**
 * Checks what a weather source outside Portal sends back. It comes from somebody else's app, so nothing is believed:
 * numbers must be possible, lists short, text plain and bounded, and the things only Portal may decide (where the TV is, what
 * is called an alert from whom, what is allowed to cover an app) are set here, not taken from the answer.
 */
object WeatherValidator {

    /** The most of an answer Portal will read, in characters. A real report is a few thousand. */
    const val MAX_JSON = 200_000

    const val MAX_HOURS = 48
    const val MAX_DAYS = 16
    const val MAX_ALERTS = 20
    /** Most alerts from one source that may cover an app at once. */
    const val MAX_INTERRUPTING = 3

    const val MAX_ID = 80
    const val MAX_EVENT = 60
    const val MAX_HEADLINE = 160
    const val MAX_DESCRIPTION = 2000
    const val MAX_INSTRUCTION = 1000
    const val MAX_AREA = 200

    private val LOCAL_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?")

    /** The report in [json], or null if it isn't valid. The place and its name are Portal's ([place]), whatever the answer says. */
    fun report(json: String, place: WeatherPlace): WeatherReport? {
        if (json.length > MAX_JSON) return null
        val r = try { WeatherJson.decodeReport(json) } catch (e: Exception) { return null }
        if (r.hours.size !in 1..MAX_HOURS || r.days.size !in 1..MAX_DAYS) return null
        if (!temp(r.tempC) || !temp(r.feelsLikeC) || r.humidity !in 0..100 || !between(r.windKmh, 0.0, 400.0)) return null
        if (r.windDirection !in 0..360 || r.code !in 0..99) return null
        if (r.utcOffsetSeconds !in -14 * 3600..14 * 3600) return null
        if (r.gustKmh?.let { !between(it, 0.0, 500.0) } == true) return null
        if (r.pressureHpa?.let { !between(it, 800.0, 1100.0) } == true) return null
        if (r.cloudCover?.let { it !in 0..100 } == true) return null
        if (r.visibilityKm?.let { !between(it, 0.0, 200.0) } == true) return null
        if (r.uvIndex?.let { !between(it, 0.0, 20.0) } == true) return null
        if (r.aqi?.let { it !in 0..500 } == true) return null
        for (h in r.hours) {
            if (h.hour24 !in 0..23 || !temp(h.tempC) || h.code !in 0..99) return null
            if (h.rainChance?.let { it !in 0..100 } == true) return null
        }
        val days = r.days.map { d ->
            if (d.code !in 0..99 || !temp(d.highC) || !temp(d.lowC) || d.rainChance !in 0..100) return null
            if (d.uvMax?.let { !between(it, 0.0, 20.0) } == true) return null
            if (d.precipMm?.let { !between(it, 0.0, 2000.0) } == true) return null
            if (d.windMaxKmh?.let { !between(it, 0.0, 500.0) } == true) return null
            if (d.snowCm?.let { !between(it, 0.0, 1000.0) } == true) return null
            d.copy(sunrise = d.sunrise?.takeIf { LOCAL_TIME.matches(it) }, sunset = d.sunset?.takeIf { LOCAL_TIME.matches(it) })
        }
        return r.copy(city = place.city, latitude = place.lat, longitude = place.lon, days = days)
    }

    /**
     * The alerts in [json] that are valid, most serious first. A bad alert is dropped, not the rest. Each is shown as coming
     * through [sourceName], its id is tied to [packageName] so it can't clash with another source's, and at most
     * [MAX_INTERRUPTING] may cover an app, and only if Severe or Extreme. Null if [json] isn't a list at all.
     */
    fun alerts(json: String, packageName: String, sourceName: String, now: OffsetDateTime = OffsetDateTime.now()): List<WeatherAlert>? {
        if (json.length > MAX_JSON) return null
        val read = try { WeatherJson.decodeAlerts(json) } catch (e: Exception) { return null }
        var interrupting = 0
        val kept = read.take(MAX_ALERTS).mapNotNull { a ->
            val id = a.id.trim().takeIf { it.isNotEmpty() && it.length <= MAX_ID && TextSafety.isPlain(it) } ?: return@mapNotNull null
            val event = text(a.event, MAX_EVENT)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (a.ends?.isBefore(now) == true) return@mapNotNull null
            val wants = a.interrupts && a.severity.rank >= AlertSeverity.SEVERE.rank
            WeatherAlert(
                id = "$packageName:$id",
                event = event,
                severity = a.severity,
                headline = text(a.headline, MAX_HEADLINE).orEmpty(),
                description = text(a.description, MAX_DESCRIPTION).orEmpty(),
                instruction = text(a.instruction, MAX_INSTRUCTION).orEmpty(),
                area = text(a.area, MAX_AREA).orEmpty(),
                ends = a.ends,
                onset = a.onset,
                source = "via $sourceName",
                interrupts = wants
            )
        }.sortedWith(compareByDescending<WeatherAlert> { it.isWarning }.thenByDescending { it.severity.rank }.thenBy { it.event })
        // The most serious get the few places that may cover an app.
        return kept.map { a ->
            if (a.interrupts && interrupting++ >= MAX_INTERRUPTING) a.copy(interrupts = false) else a
        }
    }

    const val MAX_FRAMES = 24
    const val MAX_REF = 150
    const val MAX_CREDIT = 80

    /** The radar index in [json] if it is valid (at least one frame, sensible zooms, plain words), else null. */
    fun radar(json: String): RadarIndex? {
        if (json.length > MAX_JSON) return null
        val r = try { WeatherJson.decodeRadar(json) } catch (e: Exception) { return null }
        if (r.maxZoom !in 1..12 || r.detailZoom !in 1..r.maxZoom) return null
        if (r.frames.isEmpty() || r.frames.size > MAX_FRAMES) return null
        val frames = r.frames.map { f ->
            if (f.time <= 0 || f.ref.isEmpty() || f.ref.length > MAX_REF || !TextSafety.isPlain(f.ref)) return null
            f
        }.sortedBy { it.time }
        val credit = text(r.credit, MAX_CREDIT) ?: return null
        return r.copy(credit = credit, refreshSeconds = r.refreshSeconds.coerceIn(60, 3600), frames = frames)
    }

    /**
     * Whether [bytes] is a PNG of exactly [w] by [h] pixels and no bigger than [WeatherProtocol.MAX_IMAGE_BYTES]. Only the header
     * is read here; Android decodes it later in the page, which is why the size and the claimed dimensions are held to what was asked.
     */
    fun png(bytes: ByteArray, w: Int, h: Int): Boolean {
        if (bytes.size < 33 || bytes.size > WeatherProtocol.MAX_IMAGE_BYTES) return false
        val signature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (signature.indices.any { (bytes[it].toInt() and 0xFF) != signature[it] }) return false
        // The first chunk must be IHDR, which says the size.
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return false
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        return w in 1..WeatherProtocol.MAX_IMAGE_SIDE && h in 1..WeatherProtocol.MAX_IMAGE_SIDE && int(16) == w && int(20) == h
    }

    const val MAX_CITIES = 10
    const val MAX_CITY_NAME = 60

    /** The cities in [json] that are valid, at most [MAX_CITIES]. A bad one is dropped, not the rest. Null if [json] isn't a list at all. */
    fun cities(json: String): List<WeatherCity>? {
        if (json.length > MAX_JSON) return null
        val read = try { WeatherJson.decodeCities(json) } catch (e: Exception) { return null }
        return read.take(MAX_CITIES).mapNotNull { c ->
            val name = text(c.name, MAX_CITY_NAME)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!between(c.lat, -90.0, 90.0) || !between(c.lon, -180.0, 180.0)) return@mapNotNull null
            WeatherCity(name, c.region?.let { text(it, MAX_CITY_NAME) }?.takeIf { it.isNotEmpty() }, c.lat, c.lon)
        }
    }

    /** [value] with its line breaks turned into spaces, or null if it is too long or has anything that can't be shown. */
    private fun text(value: String, max: Int): String? {
        val flat = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return flat.takeIf { it.length <= max && TextSafety.isPlain(it) }
    }

    private fun temp(c: Double) = between(c, -90.0, 70.0)

    private fun between(v: Double, low: Double, high: Double) = !v.isNaN() && v in low..high
}
