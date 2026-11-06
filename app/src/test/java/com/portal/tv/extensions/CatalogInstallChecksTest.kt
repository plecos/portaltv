package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogInstallChecksTest {

    private val signer = "aa".repeat(32)
    private val other = "bb".repeat(32)
    private val sha = "cc".repeat(32)

    private fun catalog(revoked: List<JSONObject> = emptyList(), vararg codes: Int = intArrayOf(3, 2)): Catalog {
        val entry = JSONObject().put("package", "a.b").put("name", "B").put("description", "d").put("author", "a")
            .put("source", "https://example.com/s").put("capabilities", JSONArray(listOf("tiles", "cards"))).put("signer", signer)
            .put("versions", JSONArray(codes.map {
                JSONObject().put("version_code", it).put("version_name", "v$it").put("apk", "https://example.com/$it.apk")
                    .put("sha256", sha).put("size", 1000).put("contract", 1)
            }))
        val text = JSONObject().put("format", 1).put("extensions", JSONArray(listOf(entry))).put("revoked", JSONArray(revoked)).toString()
        return (CatalogParser.parse(text) as CatalogParser.Result.Ok).catalog
    }

    private fun facts(
        pkg: String = "a.b", code: Long = 3, signer: String? = this.signer, caps: Set<String>? = setOf("tiles"), unknown: Set<String> = emptySet()
    ) = ArchiveFacts(pkg, code, signer, caps, unknown)

    private fun check(c: Catalog, a: ArchiveFacts, installed: InstalledFacts? = null): String? {
        val e = c.extensions.single()
        return CatalogInstallChecks.archive(c, e, CatalogRules.best(c, e)!!, a, installed)
    }

    @Test
    fun startPicksTheNewestVersion() {
        val c = catalog()
        val (v, why) = CatalogInstallChecks.start(c, c.extensions.single(), null)
        assertEquals(3L, v?.versionCode)
        assertNull(why)
    }

    @Test
    fun startRefusesAWithdrawnOrUnusableOrUpToDateExtension() {
        val withdrawn = catalog(listOf(JSONObject().put("package", "a.b").put("reason", "Stole data")))
        assertTrue(CatalogInstallChecks.start(withdrawn, withdrawn.extensions.single(), null).second!!.contains("Stole data"))
        val bySigner = catalog(listOf(JSONObject().put("signer", signer).put("reason", "Key stolen")))
        assertNotNull(CatalogInstallChecks.start(bySigner, bySigner.extensions.single(), null).second)
        val c = catalog()
        val e = c.extensions.single()
        assertNotNull(CatalogInstallChecks.start(c, e, InstalledFacts(3, signer)).second)
        assertNull(CatalogInstallChecks.start(c, e, InstalledFacts(2, signer)).second)
        assertTrue(CatalogInstallChecks.start(c, e, InstalledFacts(2, other)).second!!.contains("signed by someone else"))
    }

    @Test
    fun startWithEveryVersionRevokedHasNothingToInstall() {
        val c = catalog(listOf(JSONObject().put("package", "a.b").put("version_code", 3).put("reason", "x"), JSONObject().put("package", "a.b").put("version_code", 2).put("reason", "x")))
        // The package-wide check doesn't fire (each line names a version), so what is left is "no version".
        assertEquals("There isn't a version of it this Portal can install.", CatalogInstallChecks.start(c, c.extensions.single(), null).second)
    }

    @Test
    fun theFileMustBeTheListedSizeAndChecksum() {
        val c = catalog()
        val v = c.extensions.single().versions.first()
        assertNull(CatalogInstallChecks.file(v, 1000, sha))
        assertNull(CatalogInstallChecks.file(v, 1000, sha.uppercase()))
        assertTrue(CatalogInstallChecks.file(v, 999, sha)!!.contains("incomplete"))
        assertTrue(CatalogInstallChecks.file(v, 1000, "dd".repeat(32))!!.contains("checksum"))
    }

    @Test
    fun aGoodArchivePasses() {
        assertNull(check(catalog(), facts()))
        assertNull(check(catalog(), facts(code = 3), InstalledFacts(2, signer)))
    }

    @Test
    fun aWrongPackageVersionOrSignerIsRefused() {
        val c = catalog()
        assertTrue(check(c, facts(pkg = "x.y"))!!.contains("different app"))
        assertTrue(check(c, facts(code = 2))!!.contains("different version"))
        assertTrue(check(c, facts(signer = null))!!.contains("isn't signed"))
        assertTrue(check(c, facts(signer = other))!!.contains("isn't signed by the person"))
    }

    @Test
    fun anArchiveCantAskForMoreThanTheCatalogLists() {
        val c = catalog()
        assertTrue(check(c, facts(caps = setOf("tiles", "background")))!!.contains("background"))
        assertTrue(check(c, facts(caps = setOf("tiles"), unknown = setOf("root")))!!.contains("root"))
        assertNull(check(c, facts(caps = emptySet())))
        assertNull(check(c, facts(caps = setOf("tiles", "cards"))))
    }

    @Test
    fun aFileThatIsntAnExtensionIsRefused() {
        assertTrue(check(catalog(), facts(caps = null))!!.contains("isn't a Portal extension"))
    }

    @Test
    fun anInstalledAppMustHaveTheSameSignerAndBeOlder() {
        val c = catalog()
        assertTrue(check(c, facts(), InstalledFacts(2, other))!!.contains("signed by someone else"))
        assertTrue(check(c, facts(), InstalledFacts(3, signer))!!.contains("isn't newer"))
        assertTrue(check(c, facts(), InstalledFacts(9, signer))!!.contains("isn't newer"))
    }

    @Test
    fun aVersionWithdrawnWhileDownloadingIsRefused() {
        val c = catalog(listOf(JSONObject().put("package", "a.b").put("version_code", 3).put("reason", "Bad build")))
        // Version 3 is withdrawn, so best() is 2; an APK of version 3 is then "a different version", and one of 2 is fine.
        assertTrue(check(c, facts(code = 3))!!.contains("different version"))
        assertNull(check(c, facts(code = 2)))
    }
}
