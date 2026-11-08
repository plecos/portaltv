package net.getportaltv.scenic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlacesTest {
    @Test fun chosenPlaceIsFixed() {
        for (j in listOf(0.0, 5999.0, 123456.0)) {
            assertEquals(1f, Places.forest(j, Places.FOREST), 0f)
            assertEquals(0f, Places.forest(j, Places.COUNTRYSIDE), 0f)
        }
    }

    @Test fun journeyStartsInTheCountryside() {
        assertEquals(0f, Places.forest(0.0, Places.JOURNEY), 0f)
        assertEquals(0f, Places.forest(1000.0, Places.JOURNEY), 0f)
    }

    @Test fun journeyChangesGraduallyAndNeverLeapsAndStaysInRange() {
        var previous = Places.forest(0.0, Places.JOURNEY)
        var sawForest = false
        var sawCountry = false
        var biggestStep = 0f
        var j = 0.0
        while (j < Places.STRETCH * 60) {
            val f = Places.forest(j, Places.JOURNEY)
            assertTrue(f in 0f..1f)
            biggestStep = maxOf(biggestStep, abs(f - previous))
            if (f > 0.99f) sawForest = true
            if (f < 0.01f) sawCountry = true
            previous = f
            j += 5.0
        }
        assertTrue(sawForest && sawCountry)
        // Over five pixels of travel the amount can only move by a sliver.
        assertTrue("step $biggestStep", biggestStep < 0.01f)
    }

    @Test fun changeTakesTheBlendDistance() {
        // Find a boundary where the forest starts and check the middle of the blend is half way.
        for (stretch in 1..60) {
            val before = Places.forest(stretch * Places.STRETCH - Places.BLEND, Places.JOURNEY)
            val after = Places.forest(stretch * Places.STRETCH + Places.BLEND, Places.JOURNEY)
            if (before < 0.01f && after > 0.99f) {
                assertEquals(0.5f, Places.forest(stretch * Places.STRETCH, Places.JOURNEY), 0.01f)
                return
            }
        }
        throw AssertionError("no country to forest boundary in sixty stretches")
    }

    @Test fun suburbiaComesRightBeforeAndAfterTheCity() {
        assertEquals(Places.COUNTRYSIDE, Places.placeOf(0))
        assertEquals(Places.FOREST, Places.placeOf(1))
        assertEquals(Places.COUNTRYSIDE, Places.placeOf(2))
        assertEquals(Places.SUBURBIA, Places.placeOf(3))
        assertEquals(Places.CITY, Places.placeOf(4))
        assertEquals(Places.SUBURBIA, Places.placeOf(5))
        assertEquals(Places.AMUSEMENT, Places.placeOf(6))
        assertEquals(Places.COUNTRYSIDE, Places.placeOf(7))
        assertEquals(Places.BEACH, Places.placeOf(8))
        // ...and then it all comes round again.
        assertEquals(Places.COUNTRYSIDE, Places.placeOf(9))
        assertEquals(Places.FOREST, Places.placeOf(10))
        assertEquals(Places.CITY, Places.placeOf(13))
    }

    @Test fun cityAndForestNeverMixAndEachReachesFull() {
        var sawCity = false
        var j = 0.0
        while (j < Places.STRETCH * 12) {
            val f = Places.forest(j, Places.JOURNEY)
            val c = Places.city(j, Places.JOURNEY)
            val b = Places.beach(j, Places.JOURNEY)
            assertTrue("f $f c $c b $b", f + c + b <= 1.0001f)
            if (c > 0.99f) sawCity = true
            j += 25.0
        }
        assertTrue(sawCity)
    }
}
