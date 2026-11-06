package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/** Parses XML text into the same tree the app builds from an extension's resource file. */
internal fun xml(text: String): XmlNode {
    fun node(e: org.w3c.dom.Element): XmlNode {
        val attrs = (0 until e.attributes.length).associate {
            val a = e.attributes.item(it)
            a.nodeName to XmlAttr(a.nodeValue, if (a.nodeName == "icon") 0x7f080001 else 0)
        }
        val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().map { node(it) }
        return XmlNode(e.tagName, attrs, kids)
    }
    return node(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream()).documentElement)
}

class ExtensionManifestTest {

    private val pkg = "net.example.panel"

    private fun parse(text: String) = ManifestParser.parse(xml(text), pkg)
    private fun ok(text: String) = (parse(text) as ManifestParser.Result.Ok).manifest
    private fun reason(text: String) = (parse(text) as ManifestParser.Result.Invalid).reason

    private val full = """
        <portal-extension contract="1" id="home-panel" label="Home panel" description="Lights and cameras">
          <uses capability="reminders"/>
          <uses capability="cards"/>
          <tile id="lights" label="Lights" icon="@drawable/lights" activity=".LightsActivity"/>
          <tile id="cameras" label="Cameras" activity="net.other.CamerasActivity"/>
          <settings activity="SettingsActivity"/>
        </portal-extension>
    """

    @Test
    fun aCompleteFileIsRead() {
        val m = ok(full)
        assertEquals("home-panel", m.id)
        assertEquals("Home panel", m.label)
        assertEquals("Lights and cameras", m.description)
        assertEquals(1, m.contract)
        assertEquals(listOf("lights", "cameras"), m.tiles.map { it.id })
        assertEquals("net.example.panel.SettingsActivity", m.settingsActivity)
        assertEquals(0x7f080001, m.tiles[0].iconRes)
        assertEquals(0, m.tiles[1].iconRes)
    }

    @Test
    fun activityNamesAreMadeFull() {
        val m = ok(full)
        assertEquals("net.example.panel.LightsActivity", m.tiles[0].activity)
        // Already has a package in it: used as written.
        assertEquals("net.other.CamerasActivity", m.tiles[1].activity)
    }

    @Test
    fun tilesAndASettingsPageImplyTheirCapabilities() {
        val m = ok(full)
        assertEquals(listOf("tiles", "settings", "reminders", "cards"), m.capabilities.toList())
        val bare = ok("""<portal-extension contract="1" id="a" label="A"/>""")
        assertTrue(bare.capabilities.isEmpty())
    }

    @Test
    fun capabilitiesThisPortalDoesntKnowAreSetAsideAndNeverMixedIn() {
        val m = ok("""<portal-extension contract="1" id="a" label="A"><uses capability="reminders"/><uses capability="teleport"/></portal-extension>""")
        assertEquals(setOf("reminders"), m.capabilities)
        assertEquals(setOf("teleport"), m.unknownCapabilities)
    }

    @Test
    fun aNewerContractCantBeUsed() {
        val r = parse("""<portal-extension contract="2" id="a" label="A"/>""")
        assertEquals(ManifestParser.Result.NeedsNewerPortal(2), r)
    }

    @Test
    fun elementsFromALaterRevisionAreLeftAlone() {
        assertEquals("a", ok("""<portal-extension contract="1" id="a" label="A"><gizmo size="3"/></portal-extension>""").id)
    }

    @Test
    fun problemsAreReportedNotGuessedAt() {
        assertTrue(reason("""<something contract="1" id="a" label="A"/>""").contains("portal-extension"))
        assertTrue(reason("""<portal-extension id="a" label="A"/>""").contains("contract"))
        assertTrue(reason("""<portal-extension contract="0" id="a" label="A"/>""").contains("contract"))
        assertTrue(reason("""<portal-extension contract="x" id="a" label="A"/>""").contains("contract"))
        assertTrue(reason("""<portal-extension contract="1" label="A"/>""").contains("id"))
        assertTrue(reason("""<portal-extension contract="1" id="Bad Id" label="A"/>""").contains("Bad Id"))
        assertTrue(reason("""<portal-extension contract="1" id="a"/>""").contains("name"))
        assertTrue(reason("""<portal-extension contract="1" id="a" label="${"x".repeat(41)}"/>""").contains("name"))
        assertTrue(reason("""<portal-extension contract="1" id="a" label="A" description="${"x".repeat(201)}"/>""").contains("description"))
        assertTrue(reason("""<portal-extension contract="1" id="a" label="A"><uses/></portal-extension>""").contains("capability"))
    }

    @Test
    fun tileProblemsAreReported() {
        fun tiles(inner: String) = """<portal-extension contract="1" id="a" label="A">$inner</portal-extension>"""
        assertTrue(reason(tiles("""<tile label="L" activity=".A"/>""")).contains("no id"))
        assertTrue(reason(tiles("""<tile id="x" activity=".A"/>""")).contains("no name"))
        assertTrue(reason(tiles("""<tile id="x" label="L"/>""")).contains("no activity"))
        assertTrue(reason(tiles("""<tile id="X!" label="L" activity=".A"/>""")).contains("X!"))
        assertTrue(reason(tiles("""<tile id="x" label="${"l".repeat(25)}" activity=".A"/>""")).contains("longer"))
        assertTrue(reason(tiles("""<tile id="x" label="L" activity=".A"/><tile id="x" label="M" activity=".B"/>""")).contains("share"))
        val nine = (1..9).joinToString("") { """<tile id="t$it" label="T$it" activity=".A$it"/>""" }
        assertTrue(reason(tiles(nine)).contains("more than"))
    }

    @Test
    fun settingsProblemsAreReported() {
        fun s(inner: String) = """<portal-extension contract="1" id="a" label="A">$inner</portal-extension>"""
        assertTrue(reason(s("""<settings/>""")).contains("settings"))
        assertTrue(reason(s("""<settings activity=".A"/><settings activity=".B"/>""")).contains("more than one"))
    }

    @Test
    fun valuesAreTrimmed() {
        assertEquals("A", ok("""<portal-extension contract="1" id=" a " label="  A  "/>""").label)
    }

    @Test
    fun everyKnownCapabilityHasAPlainDescription() {
        Capability.known.forEach { assertTrue(it, Capability.describe(it).isNotBlank() && !Capability.describe(it).contains("doesn't know")) }
        assertTrue(Capability.describe("teleport").contains("teleport"))
    }
}
