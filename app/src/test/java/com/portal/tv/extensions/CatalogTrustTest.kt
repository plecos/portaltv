package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CatalogTrustTest {

    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicRaw = pair.public.encoded.takeLast(32).toByteArray()
    private val key = CatalogTrust.Key("k1", Base64.getEncoder().encodeToString(publicRaw))
    private val catalog = """{"format":1,"serial":1,"extensions":[]}""".toByteArray()

    private fun sign(bytes: ByteArray, keyId: String = "k1", with: java.security.PrivateKey = pair.private): String {
        val s = Signature.getInstance("Ed25519").apply { initSign(with); update(bytes) }
        return JSONObject().put("key_id", keyId).put("signature", Base64.getEncoder().encodeToString(s.sign())).toString()
    }

    @Test
    fun aGoodSignatureFromATrustedKeyIsAccepted() {
        assertEquals(CatalogTrust.Result.Ok("k1"), CatalogTrust.verify(catalog, sign(catalog), listOf(key)))
    }

    @Test
    fun aChangedCatalogIsRefused() {
        val signature = sign(catalog)
        val changed = catalog.copyOf().also { it[it.size - 3] = '9'.code.toByte() }
        assertTrue(CatalogTrust.verify(changed, signature, listOf(key)) is CatalogTrust.Result.Refused)
    }

    @Test
    fun aSignatureFromAnotherKeyIsRefusedEvenWithTheRightKeyId() {
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val forged = sign(catalog, "k1", other.private)
        assertTrue(CatalogTrust.verify(catalog, forged, listOf(key)) is CatalogTrust.Result.Refused)
    }

    @Test
    fun anUnknownKeyIdIsRefused() {
        val r = CatalogTrust.verify(catalog, sign(catalog, "somebody-else"), listOf(key)) as CatalogTrust.Result.Refused
        assertTrue(r.reason.contains("doesn't know"))
    }

    @Test
    fun noKeysNoSignatureOrJunkAreRefusedWithoutThrowing() {
        assertTrue(CatalogTrust.verify(catalog, sign(catalog), emptyList()) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, null, listOf(key)) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, "not json", listOf(key)) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, """{"key_id":"k1","signature":"!!!"}""", listOf(key)) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, """{"key_id":"k1"}""", listOf(key)) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, "x".repeat(CatalogTrust.MAX_SIGNATURE_BYTES + 1), listOf(key)) is CatalogTrust.Result.Refused)
        assertTrue(CatalogTrust.verify(catalog, sign(catalog), listOf(CatalogTrust.Key("k1", "AAAA"))) is CatalogTrust.Result.Refused)
    }

    @Test
    fun releaseBuildsTrustOnlyTheReleaseKeys() {
        assertEquals(CatalogTrust.RELEASE_KEYS, CatalogTrust.keys(debug = false))
        assertTrue(CatalogTrust.keys(debug = true).containsAll(CatalogTrust.TEST_KEYS))
        assertTrue(CatalogTrust.RELEASE_KEYS.none { r -> CatalogTrust.TEST_KEYS.any { it.publicKey == r.publicKey } })
    }

    @Test
    fun theTestKeyBuiltIntoDebugBuildsIsTheOneInTheRepository() {
        // Unit tests run in the app folder; the test key is in the repository's docs.
        val private = File("../docs/catalog/test/test-signing-key.private").readText().trim()
        val signer = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(private)))
        assertEquals(CatalogTrust.Result.Ok("test-1"), CatalogTrust.verify(catalog, sign(catalog, "test-1", signer), CatalogTrust.TEST_KEYS))
    }

    @Test
    fun theSampleAndTestCatalogsCarryASerial() {
        for (path in listOf("../docs/catalog/sample-catalog.json", "../docs/catalog/test/catalog.json")) {
            val ok = CatalogParser.parse(File(path).readText()) as CatalogParser.Result.Ok
            assertTrue(path, ok.catalog.serial >= 1)
        }
    }
}

class CatalogSerialAndExpiryTest {

    private fun catalog(vararg extra: Pair<String, Any>): String =
        JSONObject().put("format", 1).put("extensions", JSONArray()).also { o -> extra.forEach { (k, v) -> o.put(k, v) } }.toString()

    @Test
    fun aSerialAndAnExpiryAreRead() {
        val ok = CatalogParser.parse(catalog("serial" to 7, "expires" to "2027-01-31")) as CatalogParser.Result.Ok
        assertEquals(7L, ok.catalog.serial)
        // The end of 31 January, UTC.
        assertEquals(java.time.LocalDate.of(2027, 2, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), ok.catalog.expiresAtMs)
    }

    @Test
    fun withoutThemTheyAreZero() {
        val ok = CatalogParser.parse(catalog()) as CatalogParser.Result.Ok
        assertEquals(0L, ok.catalog.serial)
        assertEquals(0L, ok.catalog.expiresAtMs)
    }

    @Test
    fun aBadSerialOrExpiryMakesTheFileInvalid() {
        for (bad in listOf("serial" to 0, "serial" to "7", "serial" to -1, "expires" to "soon", "expires" to 5)) {
            assertTrue(bad.toString(), CatalogParser.parse(catalog(bad)) is CatalogParser.Result.Invalid)
        }
    }

    @Test
    fun anExpiredCatalogPausesInstallsButNothingElse() {
        val text = JSONObject().put("format", 1).put("serial", 1).put("expires", "2027-01-31").put("extensions", JSONArray(listOf(
            JSONObject().put("package", "a.b").put("name", "B").put("description", "d").put("author", "a")
                .put("source", "https://example.com/s").put("capabilities", JSONArray()).put("signer", "aa".repeat(32))
                .put("versions", JSONArray(listOf(JSONObject().put("version_code", 1).put("version_name", "1")
                    .put("apk", "https://example.com/a.apk").put("sha256", "cc".repeat(32)).put("size", 1000).put("contract", 1))))
        ))).toString()
        val c = (CatalogParser.parse(text) as CatalogParser.Result.Ok).catalog
        val e = c.extensions.single()
        val before = java.time.LocalDate.of(2027, 1, 31).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val after = java.time.LocalDate.of(2027, 2, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertNotNull(CatalogInstallChecks.start(c, e, null, before).first)
        val (version, why) = CatalogInstallChecks.start(c, e, null, after)
        assertNull(version)
        assertTrue(why!!.contains("paused"))
        // It can still be listed, and a withdrawal in it would still apply.
        assertEquals(1, CatalogLabels.shown(c).size)
    }

    @Test
    fun aKeptFileSplitsIntoItsSignatureLineAndTheCatalog() {
        val stored = "{\"key_id\":\"k\",\"signature\":\"s\"}\n{\"format\":1}".toByteArray()
        val (signature, body) = CatalogClient.split(stored)!!
        assertEquals("{\"key_id\":\"k\",\"signature\":\"s\"}", signature)
        assertEquals("{\"format\":1}", String(body))
        assertNull(CatalogClient.split("{\"format\":1}".toByteArray()))
        assertNull(CatalogClient.split("\n{}".toByteArray()))
    }
}
