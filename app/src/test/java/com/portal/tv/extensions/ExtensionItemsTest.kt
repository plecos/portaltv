package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionItemsParseTest {

    private val pkg = "net.example.panel"
    private fun parse(body: String) = ManifestParser.parse(xml("""<portal-extension contract="1" id="p" label="P">$body</portal-extension>"""), pkg)
    private fun ok(body: String) = (parse(body) as ManifestParser.Result.Ok).manifest
    private fun reason(body: String) = (parse(body) as ManifestParser.Result.Invalid).reason

    @Test
    fun buttonsAndTextAreReadForBothAreas() {
        val m = ok("""
            <bar-button id="lights" label="Lights" icon="@drawable/l" activity=".LightsActivity"/>
            <bar-text id="mode" text="Home: day"/>
            <clock-text id="door" text="Garage closed"/>
            <clock-button id="cams" label="Cameras" activity="net.other.Cams"/>
        """)
        assertEquals(4, m.items.size)
        val lights = m.items[0]
        assertEquals(ItemArea.BAR, lights.area)
        assertTrue(lights.isButton)
        assertEquals("net.example.panel.LightsActivity", lights.activity)
        assertEquals(0x7f080001, lights.iconRes)
        assertFalse(m.items[1].isButton)
        assertEquals("Home: day", m.items[1].text)
        assertEquals(ItemArea.CLOCK, m.items[2].area)
        assertEquals("net.other.Cams", m.items[3].activity)
    }

    @Test
    fun itemsImplyTheirCapabilities() {
        assertEquals(setOf(Capability.BAR), ok("""<bar-text id="a" text="x"/>""").capabilities)
        assertEquals(setOf(Capability.BAR, Capability.CLOCK), ok("""<bar-text id="a" text="x"/><clock-text id="b" text="y"/>""").capabilities)
        assertTrue(ok("").items.isEmpty())
    }

    @Test
    fun aTextItemHasNoScreenAndNoIcon() {
        val t = ok("""<bar-text id="a" text="x" activity=".Evil" icon="@drawable/l"/>""").items[0]
        assertFalse(t.isButton)
        assertEquals(0, t.iconRes)
    }

    @Test
    fun atMostTwoItemsPerArea() {
        ok("""<bar-text id="a" text="x"/><bar-text id="b" text="y"/><clock-text id="a" text="x"/><clock-text id="b" text="y"/>""")
        assertTrue(reason("""<bar-text id="a" text="x"/><bar-text id="b" text="y"/><bar-button id="c" label="z" activity=".A"/>""").contains("more than 2"))
    }

    @Test
    fun badItemsAreReported() {
        assertTrue(reason("""<bar-text text="x"/>""").contains("no id"))
        assertTrue(reason("""<bar-text id="Bad Id" text="x"/>""").contains("lowercase"))
        assertTrue(reason("""<bar-text id="a" text="x"/><bar-button id="a" label="y" activity=".A"/>""").contains("share the id"))
        assertTrue(reason("""<bar-text id="a"/>""").contains("no text"))
        assertTrue(reason("""<bar-button id="a" activity=".A"/>""").contains("no label"))
        assertTrue(reason("""<bar-button id="a" label="x"/>""").contains("no activity"))
        assertTrue(reason("""<clock-text id="a" text="${"x".repeat(41)}"/>""").contains("longer than 40"))
        assertTrue(reason("""<clock-button id="a" label="${"x".repeat(25)}" activity=".A"/>""").contains("longer than 24"))
        assertTrue(reason("""<bar-text id="a" text="one&#10;two"/>""").contains("can't be shown"))
    }

    @Test
    fun theSameIdMayBeUsedInBothAreasAndAsATile() {
        val m = ok("""<tile id="a" label="T" activity=".A"/><bar-text id="a" text="x"/><clock-text id="a" text="y"/>""")
        assertEquals(3, m.tiles.size + m.items.size)
    }
}

class ExtensionItemsPickTest {

    private fun item(id: String, area: ItemArea, button: Boolean = false) =
        ExtensionItem(id, area, id, if (button) ".A" else null, 0)

    private fun ext(pkg: String, name: String, granted: Set<String>, vararg items: ExtensionItem) = Extension(
        pkg, name, ManifestParser.Result.Invalid("x"), emptyList(), null, items.toList(), emptyList(), "ab", ExtensionState.ALLOWED, granted
    )

    @Test
    fun onlyAllowedAreasAreShown() {
        val e = ext("a", "A", setOf(Capability.BAR), item("one", ItemArea.BAR), item("two", ItemArea.CLOCK))
        assertEquals(listOf("one"), ExtensionItems.pick(listOf(e), ItemArea.BAR).map { it.item.id })
        assertTrue(ExtensionItems.pick(listOf(e), ItemArea.CLOCK).isEmpty())
    }

    @Test
    fun anExtensionNotAllowedShowsNothing() {
        val e = ext("a", "A", emptySet(), item("one", ItemArea.BAR))
        assertTrue(ExtensionItems.pick(listOf(e), ItemArea.BAR).isEmpty())
    }

    @Test
    fun theTotalIsCappedPerArea() {
        val list = (1..5).map { ext("p$it", "P$it", setOf(Capability.BAR, Capability.CLOCK), item("a", ItemArea.BAR), item("b", ItemArea.BAR), item("c", ItemArea.CLOCK)) }
        assertEquals(4, ExtensionItems.pick(list, ItemArea.BAR).size)
        assertEquals(3, ExtensionItems.pick(list, ItemArea.CLOCK).size)
        // The first extensions in the list win, and each keeps its own order.
        assertEquals(listOf("p1", "p1", "p2", "p2"), ExtensionItems.pick(list, ItemArea.BAR).map { it.packageName })
    }

    @Test
    fun eachItemSaysWhoseItIs() {
        val e = ext("a", "Alpha", setOf(Capability.BAR), item("one", ItemArea.BAR))
        val shown = ExtensionItems.pick(listOf(e), ItemArea.BAR).single()
        assertEquals("a", shown.packageName)
        assertEquals("Alpha", shown.extensionName)
    }
}
