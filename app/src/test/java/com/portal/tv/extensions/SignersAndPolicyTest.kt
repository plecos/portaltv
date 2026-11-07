package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignersAndPolicyTest {

    private val k1 = "11".repeat(32)
    private val k2 = "22".repeat(32)
    private val k3 = "33".repeat(32)
    private val day = 24L * 60 * 60 * 1000

    private fun catalog(revoked: List<JSONObject>): Catalog = (CatalogParser.parse(
        JSONObject().put("format", 1).put("serial", 3).put("expires", "2030-01-01").put("extensions", JSONArray()).put("revoked", JSONArray(revoked)).toString()
    ) as CatalogParser.Result.Ok).catalog

    private fun bySigner(signer: String) = JSONObject().put("signer", signer).put("reason", "stolen key")

    // ---- EXT-03 / EXT-10: withdrawals match every certificate

    @Test
    fun withdrawingTheOldKeyReachesAnAppThatRotatedToANewOne() {
        val c = catalog(listOf(bySigner(k1)))
        // The app is signed by k2 now, with k1 in its history.
        assertNotNull(CatalogRules.revocationBySigners(c, "a.b", 1, setOf(k2, k1)))
        // An app that never had k1 is untouched.
        assertNull(CatalogRules.revocationBySigners(c, "a.b", 1, setOf(k2, k3)))
    }

    @Test
    fun withdrawingOneSignerReachesAnAppSignedByTwo() {
        val c = catalog(listOf(bySigner(k1)))
        assertNotNull(CatalogRules.revocationBySigners(c, "a.b", 1, listOf("$k1+$k2")))
        assertNotNull(CatalogRules.revocation(c, "a.b", 1, "$k2+$k1"))
        // ...and a line naming a group applies if any of its keys is on the app.
        val group = catalog(listOf(bySigner("$k1+$k3")))
        assertNotNull(CatalogRules.revocationBySigners(group, "a.b", 1, listOf(k3)))
        assertNull(CatalogRules.revocationBySigners(group, "a.b", 1, listOf(k2)))
    }

    @Test
    fun aLineForAPackageAndSignerNeedsBothToMatch() {
        val c = catalog(listOf(bySigner(k1).put("package", "a.b")))
        assertNotNull(CatalogRules.revocationBySigners(c, "a.b", 1, listOf(k1)))
        assertNull(CatalogRules.revocationBySigners(c, "x.y", 1, listOf(k1)))
    }

    @Test
    fun startCountsTheInstalledAppsHistoryToo() {
        val entry = JSONObject().put("package", "a.b").put("name", "B").put("description", "d").put("author", "a")
            .put("source", "https://example.com/s").put("capabilities", JSONArray()).put("signer", k2)
            .put("versions", JSONArray(listOf(JSONObject().put("version_code", 2).put("version_name", "2")
                .put("apk", "https://example.com/a.apk").put("sha256", "cc".repeat(32)).put("size", 1000).put("contract", 1))))
        val c = (CatalogParser.parse(JSONObject().put("format", 1).put("serial", 3).put("expires", "2030-01-01")
            .put("extensions", JSONArray(listOf(entry))).put("revoked", JSONArray(listOf(bySigner(k1)))).toString()) as CatalogParser.Result.Ok).catalog
        val e = c.extensions.single()
        // Installed: signed by k2 now, but k1 was in its past.
        val (v, why) = CatalogInstallChecks.start(c, e, InstalledFacts(1, k2, setOf(k1, k2)), 0)
        assertNull(v)
        assertTrue(why!!.contains("withdrawn"))
        assertNotNull(CatalogInstallChecks.start(c, e, InstalledFacts(1, k2, setOf(k2)), 0).first)
    }

    @Test
    fun anInstalledAppWhoseSignerCantBeReadIsRefused() {
        val entry = JSONObject().put("package", "a.b").put("name", "B").put("description", "d").put("author", "a")
            .put("source", "https://example.com/s").put("capabilities", JSONArray()).put("signer", k1)
            .put("versions", JSONArray(listOf(JSONObject().put("version_code", 2).put("version_name", "2")
                .put("apk", "https://example.com/a.apk").put("sha256", "cc".repeat(32)).put("size", 1000).put("contract", 1))))
        val c = (CatalogParser.parse(JSONObject().put("format", 1).put("serial", 3).put("expires", "2030-01-01")
            .put("extensions", JSONArray(listOf(entry))).toString()) as CatalogParser.Result.Ok).catalog
        val e = c.extensions.single()
        assertTrue(CatalogInstallChecks.start(c, e, InstalledFacts(1, null), 0).second!!.contains("can't tell who signed"))
        val version = c.extensions.single().versions.first()
        val archive = ArchiveFacts("a.b", 2, k1, setOf(k1), setOf(), emptySet())
        assertTrue(CatalogInstallChecks.archive(c, e, version, archive, InstalledFacts(1, null))!!.contains("can't tell who signed"))
    }

    // ---- EXT-04: serial, expiry, server time

    private fun parsed(vararg extra: Pair<String, Any>): Catalog = (CatalogParser.parse(
        JSONObject().put("format", 1).put("extensions", JSONArray()).also { o -> extra.forEach { (k, v) -> o.put(k, v) } }.toString()
    ) as CatalogParser.Result.Ok).catalog

    private fun at(date: String) = java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun aCatalogNeedsASerialAndAnExpiryNotTooFarAway() {
        val now = at("2026-11-06")
        assertNull(CatalogPolicy.check(parsed("serial" to 5, "expires" to "2027-05-05"), now))
        assertTrue(CatalogPolicy.check(parsed("expires" to "2027-05-05"), now)!!.contains("serial"))
        assertTrue(CatalogPolicy.check(parsed("serial" to 5), now)!!.contains("expiry"))
        assertTrue(CatalogPolicy.check(parsed("serial" to 5, "expires" to "2099-01-01"), now)!!.contains("more than a year"))
        // The most it may claim is a year.
        assertNull(CatalogPolicy.check(parsed("serial" to 5, "expires" to "2027-11-05"), now))
    }

    @Test
    fun aCatalogAlreadyPastItsExpiryIsStillUsableForListing() {
        // Past expiry is not a refusal here: it keeps its withdrawals; only installing is paused (see CatalogLabels.expired).
        assertNull(CatalogPolicy.check(parsed("serial" to 5, "expires" to "2020-01-01"), at("2026-11-06")))
    }

    @Test
    fun timeNeverRunsBehindTheLatestDateAHostHasTold() {
        // A TV whose clock says 1970 can't make an old catalog look current.
        assertEquals(at("2026-11-06"), CatalogPolicy.effectiveNow(0, at("2026-11-06")))
        // A fast clock is believed (an early pause is the safe way to be wrong).
        assertEquals(at("2027-01-01"), CatalogPolicy.effectiveNow(at("2027-01-01"), at("2026-11-06")))
        // A host can't turn time back: the floor only counts when it is later than the clock.
        assertEquals(at("2026-11-06"), CatalogPolicy.effectiveNow(at("2026-11-06"), at("2020-01-01")))
    }

    @Test
    fun expiryIsJudgedByThatTime() {
        val c = parsed("serial" to 5, "expires" to "2026-12-31")
        assertTrue(!CatalogLabels.expired(c, CatalogPolicy.effectiveNow(0, at("2026-12-31"))))
        assertTrue(CatalogLabels.expired(c, CatalogPolicy.effectiveNow(0, at("2027-01-01"))))
    }

    @Test
    fun aCatalogKnowsTheDayItWasPublished() {
        assertEquals(at("2026-11-06"), parsed("serial" to 5, "expires" to "2027-05-05", "updated" to "2026-11-06").updatedAtMs)
        assertEquals(0L, parsed("serial" to 5, "expires" to "2027-05-05", "updated" to "soon").updatedAtMs)
    }

    @Test
    fun aCatalogPublishedAfterItExpiresIsRefused() {
        val text = JSONObject().put("format", 1).put("serial", 5).put("updated", "2062-01-01").put("expires", "2027-05-05").put("extensions", JSONArray()).toString()
        assertTrue(CatalogParser.parse(text) is CatalogParser.Result.Invalid)
    }

    @Test
    fun theMinimumSerialIsAtLeastOne() {
        assertTrue(CatalogPolicy.MIN_SERIAL >= 1)
        assertTrue(CatalogPolicy.check(parsed("serial" to 5, "expires" to "2027-05-05"), at("2026-11-06")) == null)
    }
}
