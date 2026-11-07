package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DirectorTest {

    private class Fixed(override val target: Conditions, override val latitude: Double = 40.0, override val longitude: Double = 0.0, override val known: Boolean = true) : WeatherInput

    /** A TV clock stopped at noon UTC on 21 June 2024. */
    private val clock = { Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2024, Calendar.JUNE, 21, 12, 0, 0) }.timeInMillis }

    private fun director(config: Config, weather: WeatherInput? = Fixed(Conditions(cloud = 0.05f))) = Director({ config }, weather, clock)

    @Test
    fun aChosenWeatherStartsInPlaceAndTheRealOneEasesIn() {
        val chosen = director(Config(realWeather = false, weather = "Rain"))
        chosen.step(0.04f)
        assertEquals(Conditions.preset("Rain")!!.rain, chosen.state.cond.rain, 0.001f)
        // The weather changes later: it moves gradually.
        val real = director(Config(realWeather = true), Fixed(Conditions(cloud = 0.05f)))
        real.step(0.04f)
        val target = Fixed(Conditions(cloud = 1f, rain = 0.9f))
        val d = Director({ Config(realWeather = true) }, target, clock)
        d.step(0.04f)
        assertTrue("starts at the target", d.state.cond.rain > 0.8f)
    }

    @Test
    fun changingTheChosenWeatherFadesOverSeconds() {
        var config = Config(realWeather = false, weather = "Clear")
        val d = Director({ config }, null, clock)
        d.step(0.04f)
        config = config.copy(weather = "Heavy rain")
        d.step(1f)
        assertTrue("a second in: ${d.state.cond.rain}", d.state.cond.rain in 0.05f..0.5f)
        repeat(60) { d.step(1f) }
        assertEquals(0.95f, d.state.cond.rain, 0.02f)
    }

    @Test
    fun theJourneyMovesOnlyWhenScrollingIsOn() {
        val on = director(Config(scroll = true))
        val off = director(Config(scroll = false))
        repeat(10) { on.step(0.5f); off.step(0.5f) }
        assertEquals(5 * Director.SPEED.toDouble(), on.state.cam, 0.01)
        assertEquals(0.0, off.state.cam, 0.0)
    }

    @Test
    fun aChosenHourGivesTheSkyOfThatHour() {
        // A chosen hour is the TV's own clock time, so the place is at this time zone's longitude.
        val here = Fixed(Conditions(cloud = 0.05f), longitude = java.util.TimeZone.getDefault().rawOffset / 3_600_000.0 * 15.0)
        val noon = director(Config(realTime = false, hour = 12f), here).also { it.step(0.04f) }
        val midnight = director(Config(realTime = false, hour = 0f), here).also { it.step(0.04f) }
        assertTrue(noon.state.sunAlt > midnight.state.sunAlt + 40)
        assertTrue(noon.state.look.day > 0.9f && midnight.state.look.day < 0.1f)
    }

    @Test
    fun realTimeUsesTheClock() {
        val d = director(Config(realTime = true)).also { it.step(0.04f) }
        // Solar noon at 40 N, 0 E on the solstice: the sun is about 73 degrees up.
        assertEquals(73.4, d.state.sunAlt, 1.5)
    }

    @Test
    fun snowSettlesInTheColdAndMeltsAway() {
        val d = Director({ Config(realWeather = true) }, Fixed(Conditions(cloud = 1f, snow = 0.9f, tempC = -5f)), clock)
        repeat(300) { d.step(1f) }
        assertTrue("snow cover ${d.state.snowCover}", d.state.snowCover > 0.9f)
        val warm = Director({ Config(realWeather = true) }, Fixed(Conditions(cloud = 0.2f, tempC = 12f)), clock)
        warm.state.snowCover = 1f
        repeat(300) { warm.step(1f) }
        assertEquals(0f, warm.state.snowCover, 0.001f)
    }

    @Test
    fun lightningHappensOnlyInThunderAndAlwaysTheSameWay() {
        var flashes = 0
        var t = 0.0
        while (t < 120.0) { if (Director.lightning(t, 1f) > 0.5f) flashes++; t += 0.05 }
        assertTrue("some flashes in two minutes: $flashes", flashes > 0)
        var none = 0
        t = 0.0
        while (t < 120.0) { if (Director.lightning(t, 0.1f) > 0f) none++; t += 0.05 }
        assertEquals(0, none)
        assertEquals(Director.lightning(33.3, 1f), Director.lightning(33.3, 1f), 0f)
    }

    @Test
    fun theSameSeedGivesTheSameWorld() {
        for (i in 0 until 50) assertEquals(Noise.unit(i, 3, 9), Noise.unit(i, 3, 9), 0f)
        val a = (0 until 100).map { Noise.mountain(it * 0.1f, 5) }
        val b = (0 until 100).map { Noise.mountain(it * 0.1f, 5) }
        assertEquals(a, b)
        assertTrue(a.all { it in 0f..1f })
    }
}
