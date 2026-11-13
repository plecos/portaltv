package net.getportaltv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocoderTest {

    @Test
    fun resultsBecomeCitiesWithARegionToTellThemApart() {
        val cities = Geocoder.parse(
            """{"results":[
                {"name":"Portland","admin1":"Oregon","country_code":"US","latitude":45.52,"longitude":-122.68},
                {"name":"Portland","admin1":"Maine","country_code":"US","latitude":43.66,"longitude":-70.26}
            ]}"""
        )
        assertEquals(listOf("Portland", "Portland"), cities.map { it.name })
        assertEquals(listOf("Oregon, US", "Maine, US"), cities.map { it.region })
        assertEquals(45.52, cities[0].lat, 0.0)
        assertEquals(-70.26, cities[1].lon, 0.0)
    }

    @Test
    fun aPlaceWithNoRegionHasNone() {
        val c = Geocoder.parse("""{"results":[{"name":"Nowhere","latitude":1.0,"longitude":2.0}]}""").single()
        assertNull(c.region)
    }

    @Test
    fun nothingFoundIsAnEmptyList() {
        assertEquals(emptyList<Any>(), Geocoder.parse("{}"))
        assertEquals(emptyList<Any>(), Geocoder.parse("""{"results":[]}"""))
    }
}
