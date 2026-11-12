package com.portal.tv.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OpenMeteoSourceTest {

    /** A response shaped like Open-Meteo's, with 48 hourly rows starting at midnight on Oct 30. */
    private fun sample(nowHour: String = "2026-10-30T13:00", isDay: Int = 1): JSONObject {
        val times = JSONArray()
        val temps = JSONArray()
        val codes = JSONArray()
        val days = JSONArray()
        for (h in 0 until 48) {
            val date = if (h < 24) "2026-10-30" else "2026-10-31"
            times.put("%sT%02d:00".format(date, h % 24))
            temps.put(10.0 + h)
            codes.put(if (h % 2 == 0) 0 else 3)
            days.put(if (h in 6..18 || h in 30..42) 1 else 0)
        }
        return JSONObject()
            .put("current", JSONObject()
                .put("time", nowHour)
                .put("temperature_2m", 21.4)
                .put("apparent_temperature", 19.9)
                .put("relative_humidity_2m", 52)
                .put("weather_code", 3)
                .put("wind_speed_10m", 14.2)
                .put("wind_direction_10m", 315)
                .put("is_day", isDay))
            .put("hourly", JSONObject()
                .put("time", times).put("temperature_2m", temps)
                .put("weather_code", codes).put("is_day", days))
            .put("daily", JSONObject()
                .put("time", JSONArray(listOf("2026-10-30", "2026-10-31", "2026-11-01")))
                .put("weather_code", JSONArray(listOf(0, 61, 95)))
                .put("temperature_2m_max", JSONArray(listOf(23.0, 18.5, 16.0)))
                .put("temperature_2m_min", JSONArray(listOf(10.0, 9.5, 8.0)))
                .put("precipitation_probability_max", JSONArray(listOf(0, 70, 90))))
    }

    @Test
    fun parse_readsTheCurrentConditions() {
        val r = OpenMeteoSource.parse(sample(), "Washington")
        assertEquals("Washington", r.city)
        assertEquals(21.4, r.tempC, 0.001)
        assertEquals(19.9, r.feelsLikeC, 0.001)
        assertEquals(52, r.humidity)
        assertEquals(14.2, r.windKmh, 0.001)
        assertEquals(315, r.windDirection)
        assertEquals(3, r.code)
        assertTrue(r.isDay)
    }

    @Test
    fun parse_marksNightWhenIsDayIsZero() {
        assertFalse(OpenMeteoSource.parse(sample(isDay = 0), null).isDay)
    }

    @Test
    fun parse_startsTheHoursAtTheCurrentHourAndTakesTwelve() {
        val r = OpenMeteoSource.parse(sample("2026-10-30T13:00"), null)
        assertEquals(12, r.hours.size)
        assertEquals(13, r.hours.first().hour24)
        assertEquals(10.0 + 13, r.hours.first().tempC, 0.001)
        // 13:00 through 23:00 and then midnight, crossing into the next day.
        assertEquals((13..23).toList() + listOf(0), r.hours.map { it.hour24 })
    }

    @Test
    fun parse_stopsAtTheEndOfTheHourlyData() {
        val r = OpenMeteoSource.parse(sample("2026-10-31T20:00"), null)
        assertEquals(4, r.hours.size) // 20:00 to 23:00 are all that's left
    }

    @Test
    fun parse_readsTheDailyForecast() {
        val r = OpenMeteoSource.parse(sample(), null)
        assertEquals(3, r.days.size)
        assertEquals(LocalDate.of(2026, 10, 31), r.days[1].date)
        assertEquals(61, r.days[1].code)
        assertEquals(18.5, r.days[1].highC, 0.001)
        assertEquals(9.5, r.days[1].lowC, 0.001)
        assertEquals(70, r.days[1].rainChance)
    }

    @Test
    fun parse_allowsAMissingCity() {
        assertEquals(null, OpenMeteoSource.parse(sample(), null).city)
    }

    @Test
    fun parse_readsTheExtraDetailsWhenPresent() {
        val root = sample()
        root.put("latitude", 38.9).put("longitude", -77.0).put("utc_offset_seconds", -14400)
        root.getJSONObject("current")
            .put("wind_gusts_10m", 31.5).put("pressure_msl", 1013.2).put("cloud_cover", 64)
        val hourly = root.getJSONObject("hourly")
        hourly.put("precipitation_probability", JSONArray(List(48) { it % 100 }))
        hourly.put("uv_index", JSONArray(List(48) { it / 10.0 }))
        hourly.put("visibility", JSONArray(List(48) { 24000.0 }))
        val daily = root.getJSONObject("daily")
        daily.put("sunrise", JSONArray(listOf("2026-10-30T07:12", "2026-10-31T07:13", "2026-11-01T07:14")))
        daily.put("sunset", JSONArray(listOf("2026-10-30T17:58", "2026-10-31T17:57", "2026-11-01T17:55")))
        daily.put("uv_index_max", JSONArray(listOf(3.4, 2.0, 1.0)))
        daily.put("precipitation_sum", JSONArray(listOf(0.0, 5.5, 12.0)))

        val r = OpenMeteoSource.parse(root, null)
        assertEquals(38.9, r.latitude, 0.001)
        assertEquals(-14400, r.utcOffsetSeconds)
        assertEquals(31.5, r.gustKmh!!, 0.001)
        assertEquals(1013.2, r.pressureHpa!!, 0.001)
        assertEquals(64, r.cloudCover)
        assertEquals(24.0, r.visibilityKm!!, 0.001)
        assertEquals(1.3, r.uvIndex!!, 0.001)               // the 13:00 row
        assertEquals(13, r.hours.first().rainChance)
        assertEquals("2026-10-30T07:12", r.days[0].sunrise)
        assertEquals("2026-10-30T17:58", r.days[0].sunset)
        assertEquals(3.4, r.days[0].uvMax!!, 0.001)
        assertEquals(5.5, r.days[1].precipMm!!, 0.001)
    }

    @Test
    fun parse_leavesTheExtrasEmptyForAnOlderSavedResponse() {
        val r = OpenMeteoSource.parse(sample(), null)
        assertEquals(null, r.gustKmh)
        assertEquals(null, r.uvIndex)
        assertEquals(null, r.visibilityKm)
        assertEquals(null, r.hours.first().rainChance)
        assertEquals(null, r.days[0].sunrise)
        assertEquals(null, r.aqi)
    }
}
