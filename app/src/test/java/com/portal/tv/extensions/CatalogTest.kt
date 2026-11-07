package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

private const val SIGNER = "aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11"
private const val OTHER = "bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22"
private const val SHA = "cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33cc33"

private fun version(code: Int, contract: Int = 1) = JSONObject()
    .put("version_code", code).put("version_name", "v$code").put("apk", "https://example.com/a$code.apk")
    .put("sha256", SHA).put("size", 1000).put("contract", contract)

private fun entry(pkg: String = "net.example.panel", vararg versions: JSONObject = arrayOf(version(1))) = JSONObject()
    .put("package", pkg).put("name", "Panel").put("description", "A panel.").put("author", "Example")
    .put("source", "https://example.com/src").put("license", "MIT")
    .put("capabilities", JSONArray(listOf("tiles", "cards"))).put("signer", SIGNER)
    .put("versions", JSONArray(versions.toList()))

private fun catalog(vararg entries: JSONObject, revoked: List<JSONObject>? = null, format: Any = 1) = JSONObject()
    .put("format", format).put("extensions", JSONArray(entries.toList()))
    .also { if (revoked != null) it.put("revoked", JSONArray(revoked)) }.toString()

private fun ok(text: String, http: Boolean = false) = CatalogParser.parse(text, http) as CatalogParser.Result.Ok

class CatalogParserTest {

    @Test
    fun aGoodCatalogReads() {
        val r = ok(catalog(entry()))
        assertTrue(r.problems.isEmpty())
        val e = r.catalog.find("net.example.panel")!!
        assertEquals("Panel", e.name)
        assertEquals(setOf("tiles", "cards"), e.capabilities)
        assertEquals(SIGNER, e.signer)
        assertEquals(1, e.versions.size)
    }

    @Test
    fun versionsComeNewestFirst() {
        val e = ok(catalog(entry(versions = arrayOf(version(2), version(5), version(3))))).catalog.extensions.single()
        assertEquals(listOf(5L, 3L, 2L), e.versions.map { it.versionCode })
    }

    @Test
    fun notACatalogIsInvalid() {
        assertTrue(CatalogParser.parse("nope") is CatalogParser.Result.Invalid)
        assertTrue(CatalogParser.parse("[]") is CatalogParser.Result.Invalid)
        assertTrue(CatalogParser.parse("""{"extensions":[]}""") is CatalogParser.Result.Invalid)
        assertTrue(CatalogParser.parse("""{"format":1}""") is CatalogParser.Result.Invalid)
        assertTrue(CatalogParser.parse("x".repeat(CatalogParser.MAX_FILE_CHARS + 1)) is CatalogParser.Result.Invalid)
    }

    @Test
    fun aNewerFormatIsNotRead() {
        assertEquals(CatalogParser.Result.NeedsNewerPortal(2), CatalogParser.parse(catalog(format = 2)))
    }

    @Test
    fun oneBadEntryIsLeftOutAndReportedTheOthersStay() {
        val bad = entry("net.example.bad").put("signer", "short")
        val r = ok(catalog(entry(), bad))
        assertEquals(listOf("net.example.panel"), r.catalog.extensions.map { it.packageName })
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0].startsWith("net.example.bad"))
    }

    @Test
    fun aPackageListedTwiceKeepsTheFirst() {
        val r = ok(catalog(entry(), entry().put("name", "Copy")))
        assertEquals("Panel", r.catalog.extensions.single().name)
        assertEquals(1, r.problems.size)
    }

    @Test
    fun entriesAreCheckedOneFieldAtATime() {
        fun problems(change: (JSONObject) -> Unit) = ok(catalog(entry().also(change))).problems.size
        assertEquals(1, problems { it.put("package", "NoDots") })
        assertEquals(1, problems { it.remove("source") })
        assertEquals(1, problems { it.put("source", "http://example.com/src") })
        assertEquals(1, problems { it.put("source", "https://user:pw@example.com/src") })
        assertEquals(1, problems { it.put("name", "x".repeat(ManifestParser.MAX_LABEL + 1)) })
        assertEquals(1, problems { it.put("name", "bad\u0007name") })
        assertEquals(1, problems { it.put("capabilities", JSONArray(listOf("tiles", "root"))) })
        assertEquals(1, problems { it.put("versions", JSONArray()) })
        assertEquals(1, problems { it.put("screenshots", JSONArray(List(5) { "https://example.com/s.png" })) })
        assertEquals(0, problems { it.put("capabilities", JSONArray()) })
        assertEquals(0, problems { it.remove("license") })
    }

    @Test
    fun versionsAreChecked() {
        fun problems(change: (JSONObject) -> Unit) = ok(catalog(entry(versions = arrayOf(version(1).also(change))))).problems.size
        assertEquals(1, problems { it.put("sha256", "abc") })
        assertEquals(1, problems { it.put("size", 0) })
        assertEquals(1, problems { it.put("size", CatalogParser.MAX_APK_BYTES + 1) })
        assertEquals(1, problems { it.put("apk", "ftp://example.com/a.apk") })
        assertEquals(1, problems { it.put("version_code", 0) })
        assertEquals(1, problems { it.remove("contract") })
        assertEquals(1, ok(catalog(entry(versions = arrayOf(version(1), version(1))))).problems.size)
    }

    @Test
    fun aChecksumInCapitalsIsKeptAsLowercase() {
        val e = ok(catalog(entry(versions = arrayOf(version(1).put("sha256", SHA.uppercase()))))).catalog.extensions.single()
        assertEquals(SHA, e.versions.single().sha256)
    }

    @Test
    fun httpIsAcceptedOnlyWhenAsked() {
        val text = catalog(entry().put("source", "http://localhost:8000/src"))
        assertEquals(1, ok(text).problems.size)
        assertEquals(0, ok(text, http = true).problems.size)
    }

    @Test
    fun severalSignersAreKeptInOneOrder() {
        val e = ok(catalog(entry().put("signer", "$OTHER+${SIGNER.uppercase()}"))).catalog.extensions.single()
        assertEquals(ExtensionAccess.combineSigners(listOf(SIGNER, OTHER)), e.signer)
    }

    @Test
    fun revokedLinesAreChecked() {
        fun r(vararg lines: JSONObject) = ok(catalog(revoked = lines.toList()))
        assertEquals(1, r(JSONObject().put("reason", "x")).problems.size)
        assertEquals(1, r(JSONObject().put("package", "a.b")).problems.size)
        assertEquals(1, r(JSONObject().put("signer", SIGNER).put("version_code", 3).put("reason", "x")).problems.size)
        assertEquals(0, r(JSONObject().put("package", "a.b").put("reason", "x")).problems.size)
        assertEquals(0, r(JSONObject().put("signer", SIGNER).put("reason", "stolen key")).problems.size)
        assertEquals(1, r(JSONObject().put("package", "a.b").put("version_code", 0).put("reason", "x")).problems.size)
        assertTrue(CatalogParser.parse(JSONObject().put("format", 1).put("extensions", JSONArray()).put("revoked", "no").toString()) is CatalogParser.Result.Invalid)
    }

    @Test
    fun noRevokedListMeansNothingRevoked() {
        assertTrue(ok(catalog(entry())).catalog.revoked.isEmpty())
    }
}

class CatalogRulesTest {

    private fun cat(vararg revoked: JSONObject, v: List<JSONObject> = listOf(version(3), version(2), version(1))) =
        ok(catalog(entry(versions = v.toTypedArray()), revoked = revoked.toList())).catalog

    private fun line() = JSONObject().put("reason", "bad")

    @Test
    fun theNewestVersionIsOffered() {
        val c = cat()
        assertEquals(3L, CatalogRules.best(c, c.extensions.single())?.versionCode)
    }

    @Test
    fun aRevokedVersionIsSkippedAndTheNextOneOffered() {
        val c = cat(line().put("package", "net.example.panel").put("version_code", 3))
        assertEquals(2L, CatalogRules.best(c, c.extensions.single())?.versionCode)
    }

    @Test
    fun aRevokedPackageOrSignerLeavesNothingToOffer() {
        val byPackage = cat(line().put("package", "net.example.panel"))
        assertNull(CatalogRules.best(byPackage, byPackage.extensions.single()))
        val bySigner = cat(line().put("signer", SIGNER))
        assertNull(CatalogRules.best(bySigner, bySigner.extensions.single()))
        val otherSigner = cat(line().put("signer", OTHER))
        assertNotNull(CatalogRules.best(otherSigner, otherSigner.extensions.single()))
    }

    @Test
    fun aVersionNeedingANewerContractIsNotOffered() {
        val c = cat(v = listOf(version(3, contract = 2), version(2, contract = 1)))
        assertEquals(2L, CatalogRules.best(c, c.extensions.single())?.versionCode)
        val onlyNew = cat(v = listOf(version(3, contract = 2)))
        assertNull(CatalogRules.best(onlyNew, onlyNew.extensions.single()))
    }

    @Test
    fun aPackageNotInTheCatalogCanStillBeRevoked() {
        val c = cat(line().put("package", "net.other.thing").put("version_code", 4))
        assertNotNull(CatalogRules.revocation(c, "net.other.thing", 4, null))
        assertNull(CatalogRules.revocation(c, "net.other.thing", 5, null))
        assertNull(CatalogRules.revocation(c, "net.other.thing", null, null))
    }

    @Test
    fun aSignerAndAPackageTogetherNeedBothToMatch() {
        val c = cat(line().put("package", "a.b").put("signer", SIGNER))
        assertNotNull(CatalogRules.revocation(c, "a.b", 1, SIGNER))
        assertNull(CatalogRules.revocation(c, "a.b", 1, OTHER))
        assertNull(CatalogRules.revocation(c, "c.d", 1, SIGNER))
    }

    @Test
    fun updatesAreOfferedOnlyForNewerVersions() {
        val c = cat()
        val e = c.extensions.single()
        assertTrue(CatalogRules.updateAvailable(c, e, 2))
        assertTrue(!CatalogRules.updateAvailable(c, e, 3))
        assertTrue(!CatalogRules.updateAvailable(c, e, 9))
    }

    @Test
    fun capabilitiesBeyondTheListingAreFound() {
        val e = cat().extensions.single()
        assertEquals(emptySet<String>(), CatalogRules.undeclared(e, setOf("tiles")))
        assertEquals(setOf("bar"), CatalogRules.undeclared(e, setOf("tiles", "bar")))
    }
}

/**
 * Checks real catalog files with the same parser Portal uses, refusing any problem at all. It always checks the sample;
 * set CATALOG_FILE to check another (the catalog repo's checks do this).
 */
class CatalogFileTest {

    private fun firstPartySigners(): Set<String> =
        File("../docs/catalog/first-party-signers.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.substringBefore('#').trim().lowercase() }.filter { it.length == 64 }.toSet()

    private fun check(file: File) {
        val result = CatalogParser.parse(file.readText(), checkNames = true, firstPartySigners = firstPartySigners())
        assertTrue("${file.path} isn't a usable catalog: $result", result is CatalogParser.Result.Ok)
        val problems = (result as CatalogParser.Result.Ok).problems
        assertTrue("${file.path} has problems:\n" + problems.joinToString("\n"), problems.isEmpty())
        // A published catalog also needs what Portal checks for freshness: a serial, and an expiry that isn't too far away.
        CatalogPolicy.check(result.catalog, System.currentTimeMillis())?.let { fail("${file.path}: $it (run: java scripts/CatalogSign.java stamp ${file.name})") }
    }

    @Test
    fun theSampleCatalogIsValid() {
        // Unit tests run in the app folder; the sample is in the repository's docs.
        check(File("../docs/catalog/sample-catalog.json"))
    }

    @Test
    fun theCatalogNamedInTheEnvironmentIsValid() {
        val path = System.getenv("CATALOG_FILE")
        assumeTrue("CATALOG_FILE isn't set", !path.isNullOrBlank())
        check(File(path!!))
    }
}

class CatalogOwnNamesTest {

    private val firstParty = "11".repeat(32)

    private fun catalog(pkg: String, name: String, signer: String) = """
        {"format":1,"serial":3,"expires":"2099-01-01","extensions":[{"package":"$pkg","name":"$name","description":"d","author":"a",
        "source":"https://example.com/s","capabilities":[],"signer":"$signer",
        "versions":[{"version_code":1,"version_name":"1","apk":"https://example.com/a.apk","sha256":"${"cd".repeat(32)}","size":10,"contract":1}]}]}
    """.trimIndent()

    private fun problems(text: String, check: Boolean) =
        (CatalogParser.parse(text, checkNames = check, firstPartySigners = setOf(firstParty)) as CatalogParser.Result.Ok).let { it.problems to it.catalog.extensions.size }

    @Test
    fun portalReadsASignedCatalogWithPortalsOwnNames() {
        val (problems, count) = problems(catalog("net.getportaltv.scenic", "Scenic Travel", "ab".repeat(32)), check = false)
        assertTrue(problems.isEmpty())
        assertEquals(1, count)
    }

    @Test
    fun theCatalogChecksRefuseAnImpersonatingListing() {
        val (p1, n1) = problems(catalog("net.getportaltv.scenic", "Scenic Travel", "ab".repeat(32)), check = true)
        assertEquals(0, n1)
        assertTrue(p1.single(), p1.single().contains("reserved"))
        val (p2, n2) = problems(catalog("net.example.panel", "Portal Settings", "ab".repeat(32)), check = true)
        assertEquals(0, n2)
        assertTrue(p2.single(), p2.single().contains("Portal or Android"))
    }

    @Test
    fun aListingSignedByAFirstPartyKeyMayUsePortalsNames() {
        val (problems, count) = problems(catalog("net.getportaltv.scenic", "Scenic Travel", firstParty), check = true)
        assertTrue(problems.isEmpty())
        assertEquals(1, count)
    }
}
