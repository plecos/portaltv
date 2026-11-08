package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsTest {

    @Test
    fun weatherMovesTowardItsTargetWithoutJumping() {
        val c = Conditions(cloud = 0f)
        val target = Conditions(cloud = 1f)
        c.approach(target, 1f, 20f)
        assertTrue("one second should move a little: ${c.cloud}", c.cloud in 0.02f..0.12f)
        repeat(200) { c.approach(target, 1f, 20f) }
        assertEquals(1f, c.cloud, 0.01f)
    }

    @Test
    fun theWindTurnsTheShortWayRound() {
        val c = Conditions(windDir = 350f)
        c.approach(Conditions(windDir = 10f), 5f, 5f)
        assertTrue("went ${c.windDir}", c.windDir > 350f || c.windDir < 10f)
        repeat(200) { c.approach(Conditions(windDir = 10f), 1f, 5f) }
        assertEquals(10f, c.windDir, 1f)
    }

    @Test
    fun everyPresetStaysInRange() {
        for ((name, c) in Conditions.PRESETS) {
            for (v in listOf(c.cloud, c.rain, c.snow, c.fog, c.thunder)) assertTrue("$name $v", v in 0f..1f)
            assertTrue("$name: rain and snow together", c.rain == 0f || c.snow == 0f)
        }
        assertTrue(Conditions.preset("Rain") != null && Conditions.preset("nope") == null)
    }
}
