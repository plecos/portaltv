package com.portal.tv.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class WeatherValidatorTest {

    private val place = WeatherPlace(47.6, -122.3, "Seattle")
    private val now = OffsetDateTime.parse("2026-11-12T12:00:00-08:00")

    private fun report(
        tempC: Double = 10.0, humidity: Int = 70, code: Int = 3, hours: Int = 12, days: Int = 7,
        lat: Double = 0.0, city: String? = "Somewhere else"
    ) = WeatherReport(
        city = city, tempC = tempC, feelsLikeC = tempC - 2, humidity = humidity, windKmh = 10.0, windDirection = 180,
        code = code, isDay = true,
        hours = (0 until hours).map { WeatherHour(it % 24, tempC, 3, true, 10) },
        days = (0 until days).map { WeatherDay(LocalDate.of(2026, 11, 12).plusDays(it.toLong()), 3, tempC + 2, tempC - 2, 10, "2026-11-12T07:12", "garbage") },
        latitude = lat, longitude = lat
    )

    private fun valid(r: WeatherReport) = WeatherValidator.report(WeatherJson.encode(r), place)

    // ---------- Report ----------

    @Test
    fun aSensibleReportIsAccepted() {
        assertNotNull(valid(report()))
    }

    @Test
    fun thePlaceAndItsNameAreAlwaysPortalsNotTheSources() {
        val r = valid(report(lat = 12.0, city = "Atlantis"))!!
        assertEquals("Seattle", r.city)
        assertEquals(47.6, r.latitude, 0.0)
        assertEquals(-122.3, r.longitude, 0.0)
    }

    @Test
    fun impossibleNumbersAreRefused() {
        assertNull(valid(report(tempC = 200.0)))
        assertNull(valid(report(tempC = -120.0)))
        assertNull(valid(report(humidity = 150)))
        assertNull(valid(report(code = 500)))
    }

    @Test
    fun emptyOrHugeListsAreRefused() {
        assertNull(valid(report(hours = 0)))
        assertNull(valid(report(days = 0)))
        assertNull(valid(report(hours = WeatherValidator.MAX_HOURS + 1)))
        assertNull(valid(report(days = WeatherValidator.MAX_DAYS + 1)))
        assertNotNull(valid(report(hours = WeatherValidator.MAX_HOURS, days = WeatherValidator.MAX_DAYS)))
    }

    @Test
    fun aBadSunTimeIsDroppedButTheReportKept() {
        val d = valid(report())!!.days.first()
        assertEquals("2026-11-12T07:12", d.sunrise)
        assertNull(d.sunset)
    }

    @Test
    fun junkAndOversizeAnswersAreRefused() {
        assertNull(WeatherValidator.report("not json", place))
        assertNull(WeatherValidator.report("{}", place))
        assertNull(WeatherValidator.report(" ".repeat(WeatherValidator.MAX_JSON + 1), place))
    }

    // ---------- Alerts ----------

    private fun alert(
        id: String = "a1", event: String = "Flood Warning", severity: AlertSeverity = AlertSeverity.SEVERE,
        interrupts: Boolean = false, ends: OffsetDateTime? = now.plusHours(3), description: String = "Water.\nLots of it.",
        source: String = "National Weather Service"
    ) = WeatherAlert(id, event, severity, "Headline", description, "Move to high ground", "King County", ends, null, source, interrupts)

    private fun alerts(vararg a: WeatherAlert) = WeatherValidator.alerts(WeatherJson.encodeAlerts(a.toList()), "net.example.skies", "Skies", now)

    @Test
    fun alertsAreShownAsComingThroughTheExtensionAndTheirIdsAreTiedToIt() {
        val a = alerts(alert())!!.single()
        assertEquals("via Skies", a.source)
        assertEquals("net.example.skies:a1", a.id)
    }

    @Test
    fun aSourceCannotClaimToBeTheNationalWeatherService() {
        // Whatever it writes as the issuer, what is shown is who actually sent it.
        assertEquals("via Skies", alerts(alert(source = "National Weather Service"))!!.single().source)
    }

    @Test
    fun lineBreaksBecomeSpaces() {
        assertEquals("Water. Lots of it.", alerts(alert())!!.single().description)
    }

    @Test
    fun overAlertsAreDropped() {
        assertTrue(alerts(alert(ends = now.minusMinutes(1)))!!.isEmpty())
    }

    @Test
    fun anAlertThatCannotBeShownIsDroppedAndTheRestKept() {
        val list = alerts(alert(id = "good"), alert(id = "bad", event = "Evil‮gnirts"), alert(id = ""))!!
        assertEquals(listOf("net.example.skies:good"), list.map { it.id })
    }

    @Test
    fun oneBadFieldBlanksThatFieldNotTheAlert() {
        val a = alerts(alert(description = "x".repeat(WeatherValidator.MAX_DESCRIPTION + 1)))!!.single()
        assertEquals("", a.description)
        assertEquals("Flood Warning", a.event)
    }

    @Test
    fun onlySevereOrExtremeAlertsMayCoverAnApp() {
        val list = alerts(
            alert(id = "s", severity = AlertSeverity.SEVERE, interrupts = true),
            alert(id = "m", event = "Wind Advisory", severity = AlertSeverity.MODERATE, interrupts = true),
            alert(id = "q", event = "Dense Fog", severity = AlertSeverity.SEVERE, interrupts = false)
        )!!.associateBy { it.id.substringAfter(':') }
        assertTrue(list.getValue("s").isSevere)
        assertFalse(list.getValue("m").isSevere)
        assertFalse(list.getValue("q").isSevere)
    }

    @Test
    fun noMoreThanAFewMayCoverAnAppAtOnce() {
        val many = (1..8).map { alert(id = "x$it", event = "Event $it Warning", interrupts = true) }.toTypedArray()
        val list = alerts(*many)!!
        assertEquals(WeatherValidator.MAX_INTERRUPTING, list.count { it.isSevere })
        assertEquals(8, list.size)
    }

    @Test
    fun theMostSeriousComeFirstAndGetTheInterruptions() {
        val list = alerts(
            alert(id = "w", event = "Wind Watch", severity = AlertSeverity.SEVERE, interrupts = true),
            alert(id = "t", event = "Tornado Warning", severity = AlertSeverity.EXTREME, interrupts = true)
        )!!
        assertEquals("Tornado Warning", list.first().event)
    }

    @Test
    fun aTooLongListIsTrimmed() {
        val many = (1..WeatherValidator.MAX_ALERTS + 10).map { alert(id = "i$it", event = "E$it Advisory") }.toTypedArray()
        assertEquals(WeatherValidator.MAX_ALERTS, alerts(*many)!!.size)
    }

    @Test
    fun notAListIsRefused() {
        assertNull(WeatherValidator.alerts("""{"a":1}""", "p", "n", now))
        assertNull(WeatherValidator.alerts("nope", "p", "n", now))
        assertNull(WeatherValidator.alerts(JSONArray().put(JSONObject()).toString().padEnd(WeatherValidator.MAX_JSON + 1), "p", "n", now))
    }
}
