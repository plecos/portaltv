package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogSearchTest {

    private val signer = "aa".repeat(32)

    private fun entry(pkg: String, name: String, description: String, vararg tags: String, icon: String? = null) = JSONObject()
        .put("package", pkg).put("name", name).put("description", description).put("author", "a")
        .put("source", "https://example.com/s").put("capabilities", JSONArray()).put("signer", signer)
        .put("tags", JSONArray(tags.toList()))
        .also { if (icon != null) it.put("icon", icon) }
        .put("versions", JSONArray(listOf(JSONObject().put("version_code", 1).put("version_name", "1").put("apk", "https://example.com/a.apk")
            .put("sha256", "cc".repeat(32)).put("size", 1000).put("contract", 1))))

    private fun parse(vararg e: JSONObject) =
        (CatalogParser.parse(JSONObject().put("format", 1).put("extensions", JSONArray(e.toList())).toString()) as CatalogParser.Result.Ok)

    private val all = parse(
        entry("a.garage", "Garage door", "Open and close the garage from the couch.", "home", "garage"),
        entry("a.weather", "Weather radar", "A bigger radar for storms.", "weather", "radar"),
        entry("a.kids", "Bedtime stories", "Stories for kids at night. Works with the garage lights.", "kids", "home")
    ).catalog.extensions

    private fun names(query: String, tag: String? = null) = CatalogSearch.filter(all, query, tag).map { it.name }

    @Test
    fun noQueryShowsEverythingByName() {
        assertEquals(listOf("Bedtime stories", "Garage door", "Weather radar"), names(""))
        assertEquals(names(""), names("   "))
    }

    @Test
    fun wordsMatchTitleDescriptionAndTagsIgnoringCase() {
        assertEquals(listOf("Weather radar"), names("WEATHER"))
        assertEquals(listOf("Weather radar"), names("storms"))
        assertEquals(listOf("Bedtime stories"), names("kids"))
    }

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf("Garage door"), names("garage couch"))
        assertEquals(emptyList<String>(), names("garage storms"))
    }

    @Test
    fun aTitleMatchComesBeforeADescriptionMatch() {
        // "garage" is in Garage door's title and in Bedtime stories' description only.
        assertEquals(listOf("Garage door", "Bedtime stories"), names("garage"))
    }

    @Test
    fun aTagLimitsTheList() {
        assertEquals(listOf("Bedtime stories", "Garage door"), names("", "home"))
        assertEquals(listOf("Bedtime stories"), names("stories", "home"))
        assertEquals(emptyList<String>(), names("radar", "home"))
    }

    @Test
    fun tagsAreListedMostCommonFirst() {
        assertEquals("home", CatalogSearch.tags(all).first())
        assertEquals(setOf("home", "garage", "weather", "radar", "kids"), CatalogSearch.tags(all).toSet())
    }

    @Test
    fun tagsAreCheckedAndKeptLowercaseOnce() {
        val ok = parse(entry("a.b", "B", "d", "Home", "home", "kids"))
        assertEquals(listOf("home", "kids"), ok.catalog.extensions.single().tags)
        assertEquals(1, parse(entry("a.b", "B", "d", "has space")).problems.size)
        assertEquals(1, parse(entry("a.b", "B", "d", "a", "b", "c", "d", "e", "f")).problems.size)
        assertEquals(1, parse(entry("a.b", "B", "d", "x".repeat(21))).problems.size)
        assertEquals(0, parse(entry("a.b", "B", "d")).problems.size)
    }

    @Test
    fun anIconIsAPngPathInsideTheCatalogsFolder() {
        assertEquals("icons/garage.png", parse(entry("a.b", "B", "d", icon = "icons/garage.png")).catalog.extensions.single().iconPath)
        assertEquals("", parse(entry("a.b", "B", "d")).catalog.extensions.single().iconPath)
        for (bad in listOf("https://evil.example/x.png", "../x.png", "/x.png", "icons//x.png", "x.jpg", "icons/a b.png", "Icons/X.png")) {
            assertEquals(bad, 1, parse(entry("a.b", "B", "d", icon = bad)).problems.size)
        }
    }

    @Test
    fun theIconAddressIsBesideTheCatalog() {
        assertEquals("https://getportaltv.net/extensions/icons/a.png", CatalogSearch.iconUrl("https://getportaltv.net/extensions/catalog.json", "icons/a.png"))
        assertNull(CatalogSearch.iconUrl("https://getportaltv.net/extensions/catalog.json", ""))
    }

    @Test
    fun largePicturesAreShrunk() {
        assertEquals(1, CatalogIcons.sampleSize(128, 128))
        assertEquals(1, CatalogIcons.sampleSize(256, 256))
        assertEquals(2, CatalogIcons.sampleSize(512, 300))
        assertEquals(8, CatalogIcons.sampleSize(2048, 2048))
    }

    @Test
    fun anExtensionCanNameItsOwnIcon() {
        val node = xml("""<portal-extension contract="1" id="p" label="P" icon="@drawable/x"/>""")
        val ok = ManifestParser.parse(node, "a.b") as ManifestParser.Result.Ok
        assertEquals(0x7f080001, ok.manifest.iconRes)
        val none = ManifestParser.parse(xml("""<portal-extension contract="1" id="p" label="P"/>"""), "a.b") as ManifestParser.Result.Ok
        assertEquals(0, none.manifest.iconRes)
    }
}
