package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AstronomyTest {

    private fun ms(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun theSunIsHighestAtSolarNoonAndSouthInTheNorthernSummer() {
        // 2024-06-21 at 40 N, 0 E: solar noon is about 12:00 UTC; the sun is about 73 degrees up, due south.
        val noon = Astronomy.sun(ms("2024-06-21T12:00:00"), 40.0, 0.0)
        assertEquals(73.4, noon.altDeg, 1.0)
        assertEquals(180.0, noon.azDeg, 3.0)
    }

    @Test
    fun theSunRisesInTheEastAndSetsInTheWest() {
        val morning = Astronomy.sun(ms("2024-03-20T08:00:00"), 40.0, 0.0)
        val evening = Astronomy.sun(ms("2024-03-20T16:00:00"), 40.0, 0.0)
        assertTrue("morning az ${morning.azDeg}", morning.azDeg in 90.0..170.0)
        assertTrue("evening az ${evening.azDeg}", evening.azDeg in 190.0..270.0)
        assertTrue(morning.altDeg > 0 && evening.altDeg > 0)
    }

    @Test
    fun theSunIsBelowTheHorizonAtMidnight() {
        assertTrue(Astronomy.sun(ms("2024-03-20T00:00:00"), 40.0, 0.0).altDeg < -30)
    }

    @Test
    fun theSunIsOverheadOnTheEquatorAtTheEquinoxNoon() {
        assertEquals(90.0, Astronomy.sun(ms("2024-03-20T12:00:00"), 0.0, 0.0).altDeg, 3.0)
    }

    @Test
    fun thePhasesMatchKnownNewAndFullMoons() {
        // New moon 2024-04-08 18:21 UTC, full moon 2024-01-25 17:54 UTC, first quarter 2024-01-18 03:52 UTC.
        assertTrue(Astronomy.elongationDeg(ms("2024-04-08T18:21:00")).let { it < 3 || it > 357 })
        assertEquals(180.0, Astronomy.elongationDeg(ms("2024-01-25T17:54:00")), 3.0)
        assertEquals(90.0, Astronomy.elongationDeg(ms("2024-01-18T03:52:00")), 4.0)
    }

    @Test
    fun theLitPartOfTheMoonFollowsThePhase() {
        val full = Astronomy.moon(ms("2024-01-25T17:54:00"), 40.0, 0.0)
        val newMoon = Astronomy.moon(ms("2024-04-08T18:21:00"), 40.0, 0.0)
        val quarter = Astronomy.moon(ms("2024-01-18T03:52:00"), 40.0, 0.0)
        assertTrue(full.illumination > 0.97)
        assertTrue(newMoon.illumination < 0.03)
        assertEquals(0.5, quarter.illumination, 0.06)
    }

    @Test
    fun aWaxingMoonInTheEveningIsLitOnTheSideTowardTheSetSun() {
        // First quarter, early evening at 40 N, 0 E (about 18:00 local solar time in January): the moon is high in the
        // south and the sun has set in the south-west, so the lit side points right (west) and down.
        val m = Astronomy.moon(ms("2024-01-18T18:00:00"), 40.0, 0.0)
        assertTrue("moon should be up: ${m.altDeg}", m.altDeg > 20)
        assertTrue("lit side should point right: ${m.litX}", m.litX > 0.5)
        assertTrue("and down: ${m.litY}", m.litY < 0.2)
    }

    @Test
    fun aWaningMoonBeforeDawnIsLitOnTheSideTowardTheRisingSun() {
        // Last quarter 2024-01-04 03:30 UTC; at 06:00 the moon is high in the south and the sun is about to rise in the south-east: lit side left.
        val m = Astronomy.moon(ms("2024-01-04T06:00:00"), 40.0, 0.0)
        assertTrue("moon up: ${m.altDeg}", m.altDeg > 10)
        assertTrue("lit side should point left: ${m.litX}", m.litX < -0.3)
    }
}
