package net.getportaltv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A picture's ref comes back by way of the page, so what the source will build an address from has to be strict. */
class RadarSourceTest {

    private val source = RadarSource()

    private fun refused(ref: String, zoom: Int = 7, x0: Double = 100.0, y0: Double = 100.0, w: Int = 100, h: Int = 100) =
        source.image(ref, zoom, x0, y0, w, h) is Fetched.Failed

    @Test
    fun aMosaicTimeMustLookLikeATime() {
        assertTrue(RadarSource.ISO.matches("2026-11-12T20:00:00.000Z"))
        assertTrue(RadarSource.ISO.matches("2026-11-12T20:00:00Z"))
        assertFalse(RadarSource.ISO.matches("2026-11-12T20:00:00Z&layers=other"))
        assertFalse(RadarSource.ISO.matches("../../etc"))
        assertFalse(RadarSource.ISO.matches(""))
    }

    @Test
    fun aRainViewerPathMustBeSimpleSegments() {
        assertTrue(RadarSource.PATH.matches("/v2/radar/1700000000"))
        assertFalse(RadarSource.PATH.matches("/v2/../radar"))
        assertFalse(RadarSource.PATH.matches("//evil.example/x"))
        assertFalse(RadarSource.PATH.matches("/v2/radar?key=1"))
        assertFalse(RadarSource.PATH.matches("/v2/radar/1 2"))
        assertFalse(RadarSource.PATH.matches("v2/radar"))
    }

    @Test
    fun aRefThatIsNotOurOwnKindIsRefusedBeforeAnyRequest() {
        assertTrue(refused("http://evil.example/x"))
        assertTrue(refused(""))
        assertTrue(refused("nws:not-a-time"))
        assertTrue(refused("rv:https://evil.example/x"))
        assertTrue(refused("rv:/v2/../x"))
    }

    @Test
    fun aWindowThatCouldNotBeRealIsRefused() {
        val good = "nws:2026-11-12T20:00:00.000Z"
        assertTrue(refused(good, zoom = -1))
        assertTrue(refused(good, zoom = 13))
        assertTrue(refused(good, w = 0))
        assertTrue(refused(good, h = 5000))
        assertTrue(refused(good, x0 = Double.NaN))
        assertTrue(refused(good, y0 = 1e12))
    }

    @Test
    fun theFailureIsAShortReasonNotAnException() {
        assertEquals("Bad picture", (source.image("junk", 7, 0.0, 0.0, 10, 10) as Fetched.Failed).reason)
        assertEquals("Bad window", (source.image("nws:2026-11-12T20:00:00Z", 99, 0.0, 0.0, 10, 10) as Fetched.Failed).reason)
    }
}
