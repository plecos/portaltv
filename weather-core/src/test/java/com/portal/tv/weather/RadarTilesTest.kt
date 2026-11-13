package com.portal.tv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RadarTilesTest {

    @Test fun `the world is one tile wide at zoom 0 and the equator is its middle`() {
        assertEquals(128.0, RadarTiles.worldX(0.0, 0), 1e-6)
        assertEquals(128.0, RadarTiles.worldY(0.0, 0), 1e-6)
        assertEquals(256.0, RadarTiles.worldX(0.0, 1), 1e-6)
    }

    @Test fun `Portland falls in tile 10 22 at zoom 6`() {
        assertEquals(10, (RadarTiles.worldX(-122.68, 6) / RadarTiles.TILE).toInt())
        assertEquals(22, (RadarTiles.worldY(45.52, 6) / RadarTiles.TILE).toInt())
    }

    @Test fun `pixels and degrees convert both ways`() {
        for (z in listOf(4, 10, 14)) {
            assertEquals(-122.68, RadarTiles.lonFromWorldX(RadarTiles.worldX(-122.68, z), z), 1e-9)
            assertEquals(45.52, RadarTiles.latFromWorldY(RadarTiles.worldY(45.52, z), z), 1e-9)
        }
        assertEquals(-170.0, RadarTiles.lonFromWorldX(RadarTiles.worldX(190.0, 6), 6), 1e-9)
    }

    @Test fun `north is up`() {
        assertEquals(true, RadarTiles.worldY(60.0, 5) < RadarTiles.worldY(30.0, 5))
    }

    @Test fun `columns wrap around the world`() {
        assertEquals(63, RadarTiles.wrapX(-1, 6))
        assertEquals(0, RadarTiles.wrapX(64, 6))
        assertEquals(10, RadarTiles.wrapX(10, 6))
    }

    @Test fun `tile addresses`() {
        val index = RadarTiles.Index("https://tilecache.rainviewer.com", listOf(RadarTiles.Frame(1, "/v2/radar/abc")))
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/abc/256/6/10/22/2/1_1.png",
            RadarTiles.radarUrl(index, index.frames[0], 6, 10, 22)
        )
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/6/22/10",
            RadarTiles.baseUrl(6, 10, 22)
        )
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/6/22/63",
            RadarTiles.labelsUrl(6, -1, 22)
        )
    }

    @Test fun `each map style has its own tiles and only the grey ones have a names layer`() {
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/6/22/10",
            RadarTiles.baseUrl(6, 10, 22, RadarTiles.MapStyle.SATELLITE)
        )
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Reference/MapServer/tile/6/22/10",
            RadarTiles.labelsUrl(6, 10, 22, RadarTiles.MapStyle.LIGHT)
        )
        assertNull(RadarTiles.labelsUrl(6, 10, 22, RadarTiles.MapStyle.TOPO))
        assertEquals(RadarTiles.MapStyle.DARK, RadarTiles.MapStyle.TOPO.next())
        assertEquals(RadarTiles.MapStyle.LIGHT, RadarTiles.MapStyle.DARK.next())
    }

    private val sample = """{"version":"2.0","host":"https://tilecache.rainviewer.com","radar":{"past":[
        {"time":300,"path":"/v2/radar/c"},{"time":100,"path":"/v2/radar/a"},{"time":200,"path":"/v2/radar/b"}],"nowcast":[]}}"""

    @Test fun `frames come back oldest first and trimmed to the newest few`() {
        val all = RadarTiles.parseIndex(sample)!!
        assertEquals(listOf(100L, 200L, 300L), all.frames.map { it.time })
        assertEquals("https://tilecache.rainviewer.com", all.host)
        assertEquals(listOf(200L, 300L), RadarTiles.parseIndex(sample, count = 2)!!.frames.map { it.time })
    }

    @Test fun `an unexpected answer gives null`() {
        assertNull(RadarTiles.parseIndex("""{"host":"http://insecure","radar":{"past":[{"time":1,"path":"/x"}]}}"""))
        assertNull(RadarTiles.parseIndex("""{"host":"https://h","radar":{"past":[]}}"""))
        assertNull(RadarTiles.parseIndex("""{"host":"https://h"}"""))
        assertNotNull(RadarTiles.parseIndex(sample))
    }

    private val caps = """<Layer><Dimension name="time" default="x" units="ISO8601">2026-10-31T19:00:00.000Z,2026-10-31T19:02:00.000Z,
        2026-10-31T19:04:00.000Z,2026-10-31T19:06:00.000Z,2026-10-31T19:08:00.000Z,2026-10-31T19:10:00.000Z</Dimension></Layer>"""

    @Test fun `NWS times are every few frames counting back from the newest`() {
        val index = RadarTiles.parseWmsTimes(caps, count = 2, stride = 5)!!
        assertEquals(RadarTiles.Source.NWS, index.source)
        assertEquals(listOf("2026-10-31T19:00:00.000Z", "2026-10-31T19:10:00.000Z"), index.frames.map { it.path })
        assertNull(RadarTiles.parseWmsTimes("<nothing/>"))
    }

    @Test fun `the whole world at zoom 0 spans the full web mercator square`() {
        val url = RadarTiles.wmsUrl(RadarTiles.Frame(1, "2026-10-31T19:10:00.000Z"), 0, 0.0, 0.0, 256, 256)
        assertEquals(true, url.contains("bbox=-2.0037508342789244E7,-2.0037508342789244E7,2.0037508342789244E7,2.0037508342789244E7"))
        assertEquals(true, url.contains("time=2026-10-31T19:10:00.000Z"))
        assertEquals(true, url.contains("width=256&height=256"))
    }

    @Test fun `the NWS mosaic covers the lower 48 only`() {
        assertEquals(true, RadarTiles.inContiguousUs(45.52, -122.68))
        assertEquals(false, RadarTiles.inContiguousUs(61.2, -149.9))
        assertEquals(false, RadarTiles.inContiguousUs(51.5, -0.1))
    }
}
