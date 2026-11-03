package com.portal.tv.repo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class WeatherAlertsTest {

    private val now = OffsetDateTime.parse("2026-10-31T10:00:00-07:00")

    private fun alert(event: String, severity: String, ends: String? = "2026-10-31T12:00:00-07:00", expires: String? = null) =
        JSONObject().put("properties", JSONObject()
            .put("id", event)
            .put("event", event).put("severity", severity)
            .put("headline", "$event issued today")
            .put("description", "Details about $event.")
            .put("instruction", JSONObject.NULL)
            .put("areaDesc", "Portland Metro")
            .put("ends", ends ?: JSONObject.NULL)
            .put("expires", expires ?: JSONObject.NULL))

    private fun feed(vararg a: JSONObject) = JSONObject().put("features", JSONArray(a.toList())).toString()

    @Test
    fun anEmptyOrOddResponseGivesNoAlerts() {
        assertTrue(WeatherAlerts.parse("{}", now).isEmpty())
        assertTrue(WeatherAlerts.parse(feed(), now).isEmpty())
    }

    @Test
    fun theFieldsAreRead() {
        val a = WeatherAlerts.parse(feed(alert("Dense Fog Advisory", "Moderate")), now).single()
        assertEquals("Dense Fog Advisory", a.event)
        assertEquals(WeatherAlerts.Severity.MODERATE, a.severity)
        assertEquals("Portland Metro", a.area)
        assertEquals("Details about Dense Fog Advisory.", a.description)
        assertEquals("", a.instruction)          // a JSON null is no text, not the word "null"
        assertEquals(OffsetDateTime.parse("2026-10-31T12:00:00-07:00"), a.ends)
    }

    @Test
    fun anAlertThatAlreadyEndedIsLeftOut() {
        val old = alert("Wind Advisory", "Minor", ends = "2026-10-31T09:00:00-07:00")
        val current = alert("Frost Advisory", "Minor")
        assertEquals(listOf("Frost Advisory"), WeatherAlerts.parse(feed(old, current), now).map { it.event })
    }

    @Test
    fun theExpiryIsUsedWhenThereIsNoEndTime() {
        val a = WeatherAlerts.parse(feed(alert("Special Weather Statement", "Moderate", ends = null,
            expires = "2026-10-31T11:00:00-07:00")), now).single()
        assertEquals(OffsetDateTime.parse("2026-10-31T11:00:00-07:00"), a.ends)
    }

    @Test
    fun warningsComeBeforeWatchesAndAdvisories() {
        val list = WeatherAlerts.parse(feed(
            alert("Dense Fog Advisory", "Moderate"),
            alert("Tornado Watch", "Severe"),
            alert("Flash Flood Warning", "Severe"),
            alert("Frost Advisory", "Minor")
        ), now)
        assertEquals("Flash Flood Warning", list.first().event)
        assertEquals(listOf("Flash Flood Warning", "Tornado Watch", "Dense Fog Advisory", "Frost Advisory"), list.map { it.event })
        assertTrue(list.first().isWarning)
        assertTrue(list[1].isWatch)
        assertFalse(list.last().isWarning)
    }

    @Test
    fun onlySevereWarningsAndTornadoWatchesCoverAnApp() {
        val list = WeatherAlerts.parse(feed(
            alert("Tornado Warning", "Extreme"),
            alert("Severe Thunderstorm Warning", "Severe"),
            alert("Flash Flood Warning", "Severe"),
            alert("Tornado Watch", "Severe"),
            alert("Severe Thunderstorm Watch", "Severe"),
            alert("Flood Warning", "Moderate"),
            alert("Winter Weather Advisory", "Minor"),
            alert("Flood Watch", "Moderate"),
            alert("Special Weather Statement", "Moderate")
        ), now)
        val severe = list.filter { it.isSevere }.map { it.event }.toSet()
        assertEquals(setOf("Tornado Warning", "Severe Thunderstorm Warning", "Flash Flood Warning", "Tornado Watch"), severe)
    }
}
