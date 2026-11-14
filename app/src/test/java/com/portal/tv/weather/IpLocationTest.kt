package com.portal.tv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpLocationTest {

    @Test
    fun eachServiceIsReadInItsOwnFormat() {
        val a = IpLocation.parseIpwho("""{"success":true,"city":"Portland","latitude":45.52,"longitude":-122.68}""")!!
        assertEquals(WeatherPlace(45.52, -122.68, "Portland"), a)
        val b = IpLocation.parseIpinfo("""{"city":"Portland","loc":"45.5234,-122.6762"}""")!!
        assertEquals(WeatherPlace(45.5234, -122.6762, "Portland"), b)
        val c = IpLocation.parseIpapi("""{"city":"Portland","latitude":45.5,"longitude":-122.7}""")!!
        assertEquals(WeatherPlace(45.5, -122.7, "Portland"), c)
    }

    @Test
    fun refusalsAndNonsenseAreNotPlaces() {
        assertNull(IpLocation.parseIpapi("""{"error":true,"reason":"RateLimited"}"""))
        assertNull(IpLocation.parseIpwho("""{"success":false,"message":"Too many requests"}"""))
        assertNull(IpLocation.parseIpinfo("""{"bogon":true}"""))
        assertNull(IpLocation.parseIpapi("""{"latitude":0.0,"longitude":0.0}"""))
        assertNull(IpLocation.parseIpwho("""{"latitude":200,"longitude":10}"""))
    }

    @Test
    fun aNameIsOptional() {
        assertNull(IpLocation.parseIpwho("""{"latitude":45.0,"longitude":-122.0,"city":""}""")!!.city)
    }

    @Test
    fun theNextServiceIsTriedWhenOneFailsOrIsRefused() {
        val asked = mutableListOf<String>()
        val place = IpLocation.lookup({ url ->
            asked += url
            when (url) {
                IpLocation.SERVICES[0].url -> null // no answer
                IpLocation.SERVICES[1].url -> """{"bogon":true}""" // an answer with no place
                else -> """{"city":"Portland","latitude":45.5,"longitude":-122.7}"""
            }
        })
        assertEquals(WeatherPlace(45.5, -122.7, "Portland"), place)
        assertEquals(IpLocation.SERVICES.map { it.url }, asked)
    }

    @Test
    fun firstGoodAnswerEndsTheSearchAndNoAnswerAtAllIsNull() {
        val asked = mutableListOf<String>()
        IpLocation.lookup({ asked += it; """{"success":true,"latitude":1.0,"longitude":2.0}""" })
        assertEquals(1, asked.size)
        assertNull(IpLocation.lookup({ null }))
    }
}
