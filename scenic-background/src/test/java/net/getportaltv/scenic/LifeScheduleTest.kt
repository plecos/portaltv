package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeScheduleTest {

    @Test
    fun theSameTimeAlwaysGivesTheSameEvents() {
        val a = LifeSchedule.events(1234.5, 10.0, 80.0, 0.3f, 201).map { it.spawn to it.seed }
        val b = LifeSchedule.events(1234.5, 10.0, 80.0, 0.3f, 201).map { it.spawn to it.seed }
        assertEquals(a, b)
    }

    @Test
    fun eventsHappenAboutAsOftenAsTheChanceSays() {
        // Count how many events start in 100 slots: about 30 for a chance of 0.3.
        var starts = 0
        var t = 0.0
        val seen = HashSet<Double>()
        while (t < 1000.0) {
            LifeSchedule.events(t, 10.0, 25.0, 0.3f, 7).forEach { if (seen.add(it.spawn)) starts++ }
            t += 1.0
        }
        assertTrue("starts=$starts", starts in 15..45)
    }

    @Test
    fun anEventIsOnlyReportedWhileItLasts() {
        for (e in LifeSchedule.events(500.0, 20.0, 30.0, 1f, 9)) {
            val age = 500.0 - e.spawn
            assertTrue(age in 0.0..30.0)
        }
    }

    @Test
    fun boostGivesOneInEverySlot() {
        val e = LifeSchedule.events(100.0, 10.0, 25.0, 0f, 11, boost = true)
        assertTrue(e.isNotEmpty())
        assertTrue(LifeSchedule.events(100.0, 10.0, 25.0, 0f, 11).isEmpty())
    }

    @Test
    fun pingPongGoesUpAndComesBack() {
        assertEquals(0.0, LifeSchedule.pingPong(0.0, 10.0), 1e-9)
        assertEquals(10.0, LifeSchedule.pingPong(10.0, 10.0), 1e-9)
        assertEquals(5.0, LifeSchedule.pingPong(15.0, 10.0), 1e-9)
        assertEquals(0.0, LifeSchedule.pingPong(20.0, 10.0), 1e-9)
        assertTrue((0..200).all { LifeSchedule.pingPong(it * 0.37, 12.0) in 0.0..12.0 })
    }
}
