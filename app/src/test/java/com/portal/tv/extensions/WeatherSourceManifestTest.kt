package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSourceManifestTest {

    private val pkg = "net.example.skies"

    private fun parse(body: String) =
        ManifestParser.parse(xml("""<portal-extension contract="1" id="skies" label="Skies">$body</portal-extension>"""), pkg)

    private fun ok(body: String) = (parse(body) as ManifestParser.Result.Ok).manifest

    @Test
    fun aWeatherSourceIsReadAndImpliesItsCapability() {
        val m = ok("""<weather-source service=".SkiesService"/>""")
        assertEquals("net.example.skies.SkiesService", m.weatherService)
        assertTrue(Capability.WEATHER_SOURCE in m.capabilities)
    }

    @Test
    fun withoutOneThereIsNoServiceAndNoCapability() {
        val m = ok("""<settings activity=".S"/>""")
        assertNull(m.weatherService)
        assertTrue(Capability.WEATHER_SOURCE !in m.capabilities)
    }

    @Test
    fun askingForTheCapabilityAloneIsNotEnough() {
        // It is granted only if it also names a service; the line alone just asks, and the service is what Portal binds to.
        val m = ok("""<uses capability="weather-source"/>""")
        assertNull(m.weatherService)
    }

    @Test
    fun twoWeatherSourcesAreRefused() {
        val r = parse("""<weather-source service=".A"/><weather-source service=".B"/>""")
        assertTrue(r is ManifestParser.Result.Invalid)
    }

    @Test
    fun aWeatherSourceWithoutAServiceIsRefused() {
        assertTrue(parse("""<weather-source/>""") is ManifestParser.Result.Invalid)
    }

    @Test
    fun theCapabilityIsKnownAndDescribed() {
        assertTrue(Capability.WEATHER_SOURCE in Capability.known)
        assertTrue(Capability.describe(Capability.WEATHER_SOURCE).contains("10 km"))
    }
}
