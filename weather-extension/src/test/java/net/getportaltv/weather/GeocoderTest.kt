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

    @Test
    fun aUsZipOrCanadianPostalCodeIsLookedUpByItself() {
        assertEquals("us/97205", Geocoder.postalPath("97205"))
        assertEquals("us/97205", Geocoder.postalPath(" 97205-1234 "))
        assertEquals("ca/V6B", Geocoder.postalPath("v6b 1a1"))
        assertNull(Geocoder.postalPath("Portland"))
        assertNull(Geocoder.postalPath("9720"))
        assertNull(Geocoder.postalPath("Portland 97205"))
    }

    @Test
    fun aZipBecomesThePlaceOfThatZipWithItsNumberInTheRegion() {
        val c = Geocoder.parseZip(
            """{"post code":"97205","country abbreviation":"US","places":[
                {"place name":"Portland","longitude":"-122.6888","state":"Oregon","latitude":"45.5207"}]}"""
        )!!
        assertEquals("Portland", c.name)
        assertEquals("Oregon, US (97205)", c.region)
        assertEquals(45.5207, c.lat, 0.0)
        assertEquals(-122.6888, c.lon, 0.0)
    }

    @Test
    fun aZipAnswerWithNoPlacesIsNothing() {
        assertNull(Geocoder.parseZip("{}"))
        assertNull(Geocoder.parseZip("""{"places":[]}"""))
    }
}
