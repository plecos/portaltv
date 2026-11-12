package net.getportaltv.weather.page

import com.portal.tv.weather.WeatherDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DayNoteTest {

    private fun day(
        code: Int = 3, rain: Int = 0, mm: Double? = 0.0, wind: Double? = 10.0, snow: Double? = 0.0, uv: Double? = 2.0
    ) = WeatherDay(
        date = LocalDate.of(2026, 11, 1), code = code, highC = 12.0, lowC = 4.0, rainChance = rain,
        uvMax = uv, precipMm = mm, windMaxKmh = wind, snowCm = snow
    )

    @Test fun `a dry day has no precipitation sentence, whatever the chance`() {
        assertNull(DayNote.precipitation(day(code = 3, rain = 40, mm = 0.5), "overcast", true))
        assertNull(DayNote.precipitation(day(code = 45, rain = 11), "fog", false))
    }

    @Test fun `rain says the chance and the amount in the viewer's units`() {
        val d = day(code = 61, rain = 70, mm = 6.35)
        assertEquals("70% chance of light rain, about 6.4 mm", DayNote.precipitation(d, "light rain", true))
        assertEquals("70% chance of light rain, about 0.25 in", DayNote.precipitation(d, "light rain", false))
        assertEquals("90% chance of rain, about 18 mm", DayNote.precipitation(day(code = 63, rain = 90, mm = 18.3), "rain", true))
        assertEquals("90% chance of rain, about 1.1 in", DayNote.precipitation(day(code = 63, rain = 90, mm = 28.0), "rain", false))
    }

    @Test fun `a trace of rain gives no amount`() {
        assertEquals("53% chance of drizzle", DayNote.precipitation(day(code = 51, rain = 53, mm = 0.1), "drizzle", true))
        assertEquals("53% chance of drizzle", DayNote.precipitation(day(code = 51, rain = 53, mm = null), "drizzle", false))
    }

    @Test fun `snow uses the snowfall, not the water`() {
        val d = day(code = 73, rain = 80, mm = 9.0, snow = 5.08)
        assertEquals("80% chance of snow, about 5.1 cm", DayNote.precipitation(d, "snow", true))
        assertEquals("80% chance of snow, about 2.0 in", DayNote.precipitation(d, "snow", false))
        assertEquals("80% chance of snow", DayNote.precipitation(day(code = 71, rain = 80, snow = 0.1), "snow", true))
    }

    @Test fun `a wet day with no chance says only what falls`() {
        assertEquals("Drizzle", DayNote.precipitation(day(code = 51, rain = 0, mm = 0.0), "drizzle", true))
    }

    @Test fun `only wet weather codes count as precipitation`() {
        listOf(51, 55, 61, 65, 67, 71, 75, 77, 80, 82, 85, 86, 95, 99).forEach { assertEquals(it.toString(), true, DayNote.wet(it)) }
        listOf(0, 1, 2, 3, 45, 48).forEach { assertEquals(it.toString(), false, DayNote.wet(it)) }
    }

    @Test fun `a plain day has no note`() {
        assertNull(DayNote.of(day(), metric = true))
        assertNull(DayNote.of(day(code = 63, rain = 90, mm = 20.0), metric = true))
    }

    @Test fun `wind then sun give a note`() {
        assertEquals("Wind 40 km/h", DayNote.of(day(wind = 40.0, uv = 9.0), metric = true))
        assertEquals("Wind 25 mph", DayNote.of(day(wind = 40.0), metric = false))
        assertEquals("UV 7", DayNote.of(day(uv = 6.6), metric = true))
    }

    @Test fun `missing values are ignored`() {
        assertNull(DayNote.of(day(mm = null, wind = null, snow = null, uv = null), metric = true))
    }
}
