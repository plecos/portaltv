package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeasonTest {
    private fun at(day: Float, lat: Double = 40.0, calendar: Boolean = true) = SeasonState().also { Seasons.update(it, day, lat, calendar) }

    @Test fun summerIsFullLeafAndWinterIsBare() {
        val july = at(196f)
        assertEquals(1f, july.leaf, 0.001f); assertEquals(0f, july.autumn, 0.001f); assertEquals(0f, july.winter, 0.001f)
        val january = at(20f)
        assertEquals(0f, january.leaf, 0.001f); assertEquals(1f, january.winter, 0.001f)
    }

    @Test fun autumnTurnsBeforeTheLeavesDrop() {
        val earlyOctober = at(278f)
        assertTrue(earlyOctober.autumn > 0.3f && earlyOctober.leaf > 0.99f)
        val lateOctober = at(305f)
        assertTrue(lateOctober.autumn > 0.99f && lateOctober.leaf < 0.9f)
    }

    @Test fun springBlossomsComeBeforeTheLeaf() {
        val april = at(94f)
        assertTrue(april.bloom > 0.5f && april.leaf < 0.1f)
    }

    @Test fun theSouthernHemisphereIsSixMonthsOut() {
        // 20 January is deep winter in the north and high summer in the south.
        assertEquals(1f, at(20f, 40.0).winter, 0.001f)
        assertEquals(0f, at(20f, -35.0).winter, 0.001f)
        assertEquals(1f, at(20f, -35.0).leaf, 0.001f)
    }

    @Test fun theTropicsDoNotChange() {
        for (d in listOf(5f, 100f, 200f, 300f)) {
            val s = at(d, 10.0)
            assertEquals(1f, s.leaf, 0f); assertEquals(0f, s.autumn, 0f); assertEquals(0f, s.winter, 0f); assertEquals(0f, s.bloom, 0f)
        }
    }

    @Test fun aChosenSeasonIgnoresTheCalendarAndHemisphere() {
        val s = at(Seasons.dayFor(Seasons.WINTER), -35.0, calendar = false)
        assertEquals(1f, s.winter, 0.001f)
    }

    @Test fun theYearChangesSmoothlyWithNoJumpsAndWrapsAround() {
        var previous = at(1f)
        var d = 1f
        while (d <= 366f) {
            val s = at(d)
            assertTrue("leaf jump at $d", abs(s.leaf - previous.leaf) < 0.06f)
            assertTrue("winter jump at $d", abs(s.winter - previous.winter) < 0.06f)
            assertTrue("hill jump at $d", abs(s.hillR - previous.hillR) < 0.03f)
            previous = s
            d += 1f
        }
        // The last day of the year runs into the first.
        assertEquals(at(366f).leaf, at(1f).leaf, 0.01f)
        assertEquals(at(366f).winter, at(1f).winter, 0.01f)
    }
}
