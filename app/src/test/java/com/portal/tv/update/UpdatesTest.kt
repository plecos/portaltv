package com.portal.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    private val sha = "a".repeat(64)

    private fun release(tag: String = "v1.0.36", assets: String) = """
        {"tag_name":"$tag","body":"What's Changed\n* Pin Bouncy Castle by @plecos in https://github.com/plecos/portaltv/pull/5\n\n**Full Changelog**: https://github.com/plecos/portaltv/compare/v1.0.31...v1.0.36",
         "assets":[$assets]}
    """.trimIndent()

    private fun asset(name: String, digest: String? = "sha256:$sha") =
        """{"name":"$name","size":150000000,"browser_download_url":"https://github.com/plecos/portaltv/releases/download/v1.0.36/$name"${digest?.let { ""","digest":"$it"""" } ?: ""}}"""

    @Test
    fun aNewerVersionIsFoundNumberByNumber() {
        assertTrue(Updates.isNewer("1.0.36", "1.0.33"))
        assertTrue(Updates.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updates.isNewer("1.1.0", "1.0.99"))
        assertTrue(Updates.isNewer("2.0", "1.9.9"))
    }

    @Test
    fun theSameOrAnOlderVersionIsNotNewer() {
        assertFalse(Updates.isNewer("1.0.33", "1.0.33"))
        assertFalse(Updates.isNewer("1.0.9", "1.0.10"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun versionsThatAreNotNumbersAreNeverNewer() {
        assertFalse(Updates.isNewer("1.0.x", "1.0.1"))
        assertFalse(Updates.isNewer("1.0.2", "debug"))
        assertNull(Updates.parseVersion(""))
        assertNull(Updates.parseVersion("1.-2"))
    }

    @Test
    fun theTagLosesItsV() {
        assertEquals("1.0.36", Updates.versionOfTag("v1.0.36"))
        assertEquals("1.0.36", Updates.versionOfTag("1.0.36"))
    }

    @Test
    fun theReleaseIsReadFromGitHubsJson() {
        val info = Updates.parseLatest(release(assets = asset("portal-1.0.36.apk")))
        assertNotNull(info)
        assertEquals("1.0.36", info!!.version)
        assertEquals("https://github.com/plecos/portaltv/releases/download/v1.0.36/portal-1.0.36.apk", info.apkUrl)
        assertEquals(150000000L, info.apkBytes)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun onlyThePortalApkIsChosen() {
        val info = Updates.parseLatest(release(assets = asset("notes.txt") + "," + asset("portal-1.0.36.apk")))
        assertEquals("portal-1.0.36.apk", info!!.apkUrl.substringAfterLast('/'))
    }

    @Test
    fun aReleaseWithoutAnApkOrAUsableTagGivesNothing() {
        assertNull(Updates.parseLatest(release(assets = asset("notes.txt"))))
        assertNull(Updates.parseLatest(release(assets = "")))
        assertNull(Updates.parseLatest(release(tag = "nightly", assets = asset("portal-1.0.36.apk"))))
    }

    @Test
    fun aMissingOrBadDigestMeansNoChecksum() {
        assertNull(Updates.parseLatest(release(assets = asset("portal-1.0.36.apk", digest = null)))!!.sha256)
        assertNull(Updates.parseLatest(release(assets = asset("portal-1.0.36.apk", digest = "sha256:abc")))!!.sha256)
        assertNull(Updates.parseLatest(release(assets = asset("portal-1.0.36.apk", digest = "md5:$sha")))!!.sha256)
    }

    @Test
    fun releaseNotesAreTidiedForADialog() {
        val notes = "## What's Changed\n* Pin Bouncy Castle by @plecos in https://github.com/plecos/portaltv/pull/5\n" +
            "- Fix the lock\n\n**Full Changelog**: https://github.com/x/compare/a...b"
        assertEquals("Pin Bouncy Castle\nFix the lock", Updates.tidyNotes(notes))
        assertEquals("No release notes.", Updates.tidyNotes("  \n"))
    }

    @Test
    fun aChecksumIsLowercaseHex() {
        assertEquals("00ff10", Updates.hex(byteArrayOf(0, -1, 16)))
    }

    @Test
    fun releaseBuildsOnlyFollowHttps() {
        assertTrue(UpdateClient.isTrusted("https://github.com/x.apk", debug = false))
        assertFalse(UpdateClient.isTrusted("http://10.0.2.2:8000/x.apk", debug = false))
        assertTrue(UpdateClient.isTrusted("http://10.0.2.2:8000/x.apk", debug = true))
        assertFalse(UpdateClient.isTrusted("ftp://example.com/x.apk", debug = true))
    }
}
