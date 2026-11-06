package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CatalogViewTest {

    private val signer = "aa".repeat(32)
    private val other = "bb".repeat(32)

    private fun entry(pkg: String, name: String, vararg codes: Int) = JSONObject()
        .put("package", pkg).put("name", name).put("description", "d").put("author", "a")
        .put("source", "https://example.com/s").put("capabilities", JSONArray()).put("signer", signer)
        .put("versions", JSONArray(codes.map {
            JSONObject().put("version_code", it).put("version_name", "v$it").put("apk", "https://example.com/$it.apk")
                .put("sha256", "cc".repeat(32)).put("size", 2_500_000).put("contract", 1)
        }))

    private fun catalog(vararg e: JSONObject, revoked: List<JSONObject> = emptyList()) =
        (CatalogParser.parse(JSONObject().put("format", 1).put("extensions", JSONArray(e.toList())).put("revoked", JSONArray(revoked)).toString()) as CatalogParser.Result.Ok).catalog

    @Test
    fun theListIsByNameAndLeavesOutWhatCantBeOffered() {
        val c = catalog(
            entry("a.zed", "Zed", 1), entry("a.alpha", "alpha", 1), entry("a.gone", "Gone", 1),
            revoked = listOf(JSONObject().put("package", "a.gone").put("reason", "x"))
        )
        assertEquals(listOf("alpha", "Zed"), CatalogLabels.shown(c).map { it.name })
    }

    @Test
    fun stateTellsInstalledFromUpdateFromSomeoneElses() {
        val c = catalog(entry("a.b", "B", 3))
        val e = c.extensions.single()
        assertEquals(CatalogState.NOT_INSTALLED, CatalogLabels.state(c, e, null, null))
        assertEquals(CatalogState.INSTALLED, CatalogLabels.state(c, e, 3, signer))
        assertEquals(CatalogState.UPDATE_AVAILABLE, CatalogLabels.state(c, e, 2, signer))
        assertEquals(CatalogState.SIGNED_BY_OTHER, CatalogLabels.state(c, e, 2, other))
        assertEquals("", CatalogLabels.stateText(CatalogState.NOT_INSTALLED))
    }

    @Test
    fun anInstalledExtensionIsListedOnlyWithTheRightSigner() {
        val c = catalog(entry("a.b", "B", 1))
        assertEquals(Listing.LISTED, CatalogLabels.listing(c, "a.b", signer))
        assertEquals(Listing.LISTED_BUT_SIGNED_BY_OTHER, CatalogLabels.listing(c, "a.b", other))
        assertEquals(Listing.LISTED_BUT_SIGNED_BY_OTHER, CatalogLabels.listing(c, "a.b", null))
        assertEquals(Listing.NOT_LISTED, CatalogLabels.listing(c, "x.y", signer))
        assertEquals("Not in the catalog", CatalogLabels.listingText(Listing.NOT_LISTED))
    }

    @Test
    fun theCopyIsStaleAfterADayOrWhenNeverFetchedOrTheClockWentBack() {
        val day = CatalogLabels.REFRESH_AFTER_MS
        assertTrue(CatalogLabels.isStale(0, 1_000_000))
        assertFalse(CatalogLabels.isStale(1_000, 1_000 + day - 1))
        assertTrue(CatalogLabels.isStale(1_000, 1_000 + day))
        assertTrue(CatalogLabels.isStale(5_000, 1_000))
    }

    @Test
    fun sizesAreReadable() {
        assertEquals("2.4 MB", CatalogLabels.sizeText(2_500_000))
        assertEquals("1 KB", CatalogLabels.sizeText(10))
        assertEquals("500 KB", CatalogLabels.sizeText(500 * 1024))
        assertEquals("1 extension", CatalogLabels.count(1))
        assertEquals("0 extensions", CatalogLabels.count(0))
    }

    @Test
    fun aDownloadLargerThanTheCapIsRefused() {
        assertEquals("abc", CatalogClient.readCapped("abc".byteInputStream(), 10))
        try {
            CatalogClient.readCapped(ByteArray(100).inputStream(), 50)
            fail("should be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too big"))
        }
    }
}
