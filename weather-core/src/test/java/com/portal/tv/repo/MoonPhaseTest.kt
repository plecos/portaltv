package com.portal.tv.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MoonPhaseTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val hour = 3_600_000L

    @Test
    fun aNewMoonIsAlmostUnlit() {
        val s = MoonPhase.stateAt(ms("2000-01-06T18:14:00Z"))
        assertTrue("illumination ${s.illumination}", s.illumination < 0.01)
        assertEquals("New Moon", s.name)
    }

    @Test
    fun aFullMoonIsAlmostFullyLit() {
        val s = MoonPhase.stateAt(ms("2024-12-15T09:02:00Z"))
        assertTrue("illumination ${s.illumination}", s.illumination > 0.995)
        assertEquals("Full Moon", s.name)
    }

    @Test
    fun firstQuarterIsAboutHalfLitAndWaxing() {
        val s = MoonPhase.stateAt(ms("2024-12-08T15:26:00Z"))
        assertEquals(90.0, s.elongation, 5.0)
        assertEquals(0.5, s.illumination, 0.05)
        assertTrue(s.waxing)
        assertEquals("First Quarter", s.name)
    }

    @Test
    fun theWaningCrescentComesBeforeTheNewMoon() {
        val s = MoonPhase.stateAt(ms("2025-01-26T12:00:00Z"))
        assertEquals("Waning Crescent", s.name)
        assertTrue(!s.waxing)
    }

    @Test
    fun theNextFullMoonIsFoundToWithinAFewHours() {
        val found = MoonPhase.nextPhase(ms("2024-12-01T00:00:00Z"), full = true)
        assertEquals(ms("2024-12-15T09:02:00Z").toDouble(), found.toDouble(), 3.0 * hour)
    }

    @Test
    fun theNextNewMoonIsFoundToWithinAFewHours() {
        val found = MoonPhase.nextPhase(ms("2025-01-01T00:00:00Z"), full = false)
        assertEquals(ms("2025-01-29T12:36:00Z").toDouble(), found.toDouble(), 3.0 * hour)
    }

    @Test
    fun thePhaseAfterAFullMoonIsAMonthAway() {
        val full = ms("2024-12-15T09:02:00Z")
        val next = MoonPhase.nextPhase(full + 12 * hour, full = true)
        assertEquals(29.5, (next - full) / (24.0 * hour), 1.0)
    }

    @Test
    fun aFullMoonRisesAroundSunsetInNewYork() {
        // New York, 15 December 2024. The full moon rose at about 4:40 PM (UTC-5).
        val dayStart = LocalDate.of(2024, 12, 15).atStartOfDay().toInstant(ZoneOffset.ofHours(-5)).toEpochMilli()
        val rs = MoonPhase.riseSet(dayStart, 40.71, -74.01)
        val rise = rs.rise
        assertNotNull(rise)
        val hoursIn = (rise!! - dayStart) / hour.toDouble()
        assertTrue("rose $hoursIn hours after midnight", hoursIn in 16.0..17.5)
    }

    @Test
    fun theMoonDoesNotAlwaysRiseAndSetOnTheSameDay() {
        // Over a month the moon rises about 50 minutes later each day, so some days skip a rise.
        val start = LocalDate.of(2024, 12, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val missing = (0 until 30).count {
            MoonPhase.riseSet(start + it * 24 * hour, 40.71, -74.01).rise == null
        }
        assertTrue("days without a rise: $missing", missing in 0..2)
    }

    @Test
    fun daysUntilRoundsDownAndNeverGoesNegative() {
        assertEquals(2, MoonPhase.daysUntil(0, 2 * 24 * hour + hour))
        assertEquals(0, MoonPhase.daysUntil(10, 5))
    }

    @Test
    fun theMoonLeansTheOtherWayWhenItRisesThanWhenItSets() {
        // New York, 15 December 2024: the full moon rose at about 4:40 PM and set the next morning.
        val dayStart = LocalDate.of(2024, 12, 15).atStartOfDay().toInstant(ZoneOffset.ofHours(-5)).toEpochMilli()
        val rs = MoonPhase.riseSet(dayStart, 40.71, -74.01)
        val nextDay = MoonPhase.riseSet(dayStart + 24 * hour, 40.71, -74.01)
        val rising = MoonPhase.tiltAt(rs.rise!!, 40.71, -74.01).rotation
        val setting = MoonPhase.tiltAt(nextDay.set!!, 40.71, -74.01).rotation
        assertTrue("rising $rising", rising < -20)
        assertTrue("setting $setting", setting > 20)
    }

    @Test
    fun theMoonIsNotTiltedWhenItCrossesTheMeridian() {
        // Over a day the moon crosses the meridian once; there the tilt passes through zero.
        val start = ms("2024-12-15T00:00:00Z")
        val smallest = (0 until 24 * 6).minOf { kotlin.math.abs(MoonPhase.tiltAt(start + it * 10 * 60_000L, 40.71, -74.01).rotation) }
        assertTrue("closest to upright was $smallest", smallest < 3)
    }

    @Test
    fun theLitEdgeFacesWestWhileWaxingAndEastWhileWaning() {
        val waxing = MoonPhase.tiltAt(ms("2024-12-08T15:26:00Z"), 40.71, -74.01).brightLimb   // first quarter
        val waning = MoonPhase.tiltAt(ms("2024-12-23T19:18:00Z"), 40.71, -74.01).brightLimb   // last quarter
        assertTrue("waxing $waxing", waxing in 230.0..310.0)
        assertTrue("waning $waning", waning in 50.0..130.0)
    }
}
