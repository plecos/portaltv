package com.portal.tv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class WeatherJsonTest {

    private val full = WeatherReport(
        city = "Seattle", tempC = 11.5, feelsLikeC = 9.0, humidity = 82, windKmh = 14.0, windDirection = 200,
        code = 61, isDay = false,
        hours = listOf(
            WeatherHour(21, 11.0, 61, false, 70),
            WeatherHour(22, 10.5, 3, false, null)
        ),
        days = listOf(
            WeatherDay(
                LocalDate.of(2026, 11, 12), 61, 13.0, 7.0, 60, "2026-11-12T07:12", "2026-11-12T16:40",
                uvMax = 1.5, precipMm = 4.2, windMaxKmh = 30.0, snowCm = 0.0
            ),
            WeatherDay(LocalDate.of(2026, 11, 13), 3, 12.0, 6.0, 20)
        ),
        latitude = 47.61, longitude = -122.33, utcOffsetSeconds = -28800,
        gustKmh = 25.0, pressureHpa = 1012.0, cloudCover = 90, visibilityKm = 9.5, uvIndex = 0.0, aqi = 42
    )

    @Test
    fun aReportSurvivesBeingSavedAndRead() {
        assertEquals(full, WeatherJson.decodeReport(WeatherJson.encode(full)))
    }

    @Test
    fun aReportWithNothingOptionalSurvivesToo() {
        val bare = WeatherReport(
            city = null, tempC = 0.0, feelsLikeC = -3.0, humidity = 50, windKmh = 0.0, windDirection = 0,
            code = 0, isDay = true, hours = emptyList(), days = emptyList()
        )
        val back = WeatherJson.decodeReport(WeatherJson.encode(bare))
        assertEquals(bare, back)
        assertNull(back.aqi)
        assertNull(back.city)
    }

    @Test
    fun aSavedReportMissingAnEssentialFieldIsRefused() {
        assertThrows(Exception::class.java) { WeatherJson.decodeReport("""{"city":"x"}""") }
    }

    @Test
    fun alertsSurviveBeingSavedAndRead() {
        val alerts = listOf(
            WeatherAlert(
                "a1", "Tornado Warning", AlertSeverity.EXTREME, "headline", "description", "take cover", "King County",
                OffsetDateTime.parse("2026-11-12T20:00:00-08:00"), OffsetDateTime.parse("2026-11-12T18:00:00-08:00"),
                "National Weather Service", true
            ),
            WeatherAlert("a2", "Frost Advisory", AlertSeverity.MINOR, "", "", "", "", null)
        )
        assertEquals(alerts, WeatherJson.decodeAlerts(WeatherJson.encodeAlerts(alerts)))
    }

    @Test
    fun citiesSurviveBeingSentAndRead() {
        val cities = listOf(WeatherCity("Portland", "Oregon, US", 45.5, -122.7), WeatherCity("Portland", null, 43.7, -70.3))
        assertEquals(cities, WeatherJson.decodeCities(WeatherJson.encodeCities(cities)))
        assertEquals(emptyList<WeatherCity>(), WeatherJson.decodeCities("[]"))
    }

    @Test
    fun aRadarIndexSurvivesBeingSentAndRead() {
        val index = RadarIndex("Radar: somebody", 10, 7, 300, listOf(RadarFrame(1_700_000_000L, "nws:2026-11-12T20:00:00Z"), RadarFrame(1_700_000_300L, "b")))
        assertEquals(index, WeatherJson.decodeRadar(WeatherJson.encodeRadar(index)))
    }

    @Test
    fun aRadarIndexWithoutZoomsOrFramesIsRefused() {
        assertThrows(Exception::class.java) { WeatherJson.decodeRadar("""{"credit":"x","frames":[]}""") }
        assertThrows(Exception::class.java) { WeatherJson.decodeRadar("""{"max_zoom":7,"detail_zoom":7}""") }
    }

    @Test
    fun aCityWithoutANameOrPlaceIsRefused() {
        assertThrows(Exception::class.java) { WeatherJson.decodeCities("""[{"region":"x"}]""") }
        assertThrows(Exception::class.java) { WeatherJson.decodeCities("""[{"name":"x"}]""") }
    }

    @Test
    fun anUnknownSeverityReadsAsUnknownRatherThanFailing() {
        val read = WeatherJson.decodeAlerts("""[{"id":"x","event":"Odd","severity":"Cosmic"}]""").single()
        assertEquals(AlertSeverity.UNKNOWN, read.severity)
        assertEquals("Odd", read.event)
    }
}
