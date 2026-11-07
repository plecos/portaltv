package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSafetyTest {

    @Test
    fun ordinaryTextIsPlain() {
        for (ok in listOf("Garage door closed", "Café", "日本語のテキスト", "Wetter: 21°C ☀", "Ünï", "naïve résumé", "A. Person", "50% off")) {
            assertTrue(ok, TextSafety.isPlain(ok))
        }
    }

    @Test
    fun textThatReordersHidesOrSmearsIsNot() {
        assertFalse(TextSafety.isPlain("abc‮def"))      // right-to-left override
        assertFalse(TextSafety.isPlain("a⁦b⁩"))     // isolates
        assertFalse(TextSafety.isPlain("Pay​ment"))      // zero-width space
        assertFalse(TextSafety.isPlain("﻿hello"))        // byte order mark
        assertFalse(TextSafety.isPlain("a\nb"))
        assertFalse(TextSafety.isPlain("a\u0007b"))
        assertFalse(TextSafety.isPlain("ab"))           // private use
        assertFalse(TextSafety.isPlain("x y"))           // line separator
        assertFalse(TextSafety.isPlain("e" + "́".repeat(TextSafety.MAX_COMBINING_RUN + 1)))
        assertTrue(TextSafety.isPlain("e" + "́".repeat(TextSafety.MAX_COMBINING_RUN)))
    }

    @Test
    fun namesThatPassForPortalOrAndroidAreRefused() {
        for (bad in listOf("Portal", "portal", "PORTAL", "Settings", "Portal Settings", "Parent PIN", "Android System", "Google TV",
            "Update", "Profile", "Search", "Launcher", "S e t t i n g s", "Portal-TV", "ＰＯＲＴＡＬ", "Pоrtal", "Ѕettings", "p0rtal", "My Settings page", "S\u03b5ttings", "Parental controls", "P\u00f6rtal", "Sett\u0131ngs", "\u0420arent PIN", "\u0397elp Settings")) {
            assertNotNull(bad, Impersonation.reason(bad))
        }
    }

    @Test
    fun packageNamesOfPortalsOwnAppsAreReserved() {
        assertTrue(Impersonation.isReservedPackage("net.getportaltv.settings"))
        assertTrue(Impersonation.isReservedPackage("com.portal.tv"))
        assertTrue(Impersonation.isReservedPackage("com.portal.tv.debug"))
        assertFalse(Impersonation.isReservedPackage("net.example.panel"))
        assertFalse(Impersonation.isReservedPackage("net.getportaltvfake.x"))
    }

    @Test
    fun ordinaryNamesAreFine() {
        for (ok in listOf("Garage door", "Photo frame", "Doorbell Security Cam", "Weather radar", "Lights", "Home: day", "Updates for my pets",
            "Kids chores", "Sample extension", "Searchlight", "Pinball")) {
            assertNull(ok, Impersonation.reason(ok))
        }
    }

    @Test
    fun aManifestWithSuchANameOrButtonIsInvalid() {
        fun parse(body: String, label: String = "Panel") = ManifestParser.parse(
            xml("""<portal-extension contract="1" id="p" label="$label">$body</portal-extension>"""), "net.example.panel"
        )
        assertTrue(parse("", "Portal") is ManifestParser.Result.Invalid)
        assertTrue(parse("""<tile id="t" label="Settings" activity=".A"/>""") is ManifestParser.Result.Invalid)
        assertTrue(parse("""<bar-button id="b" label="Parent PIN" activity=".A"/>""") is ManifestParser.Result.Invalid)
        assertTrue(parse("""<clock-button id="b" label="Update" activity=".A"/>""") is ManifestParser.Result.Invalid)
        // Lines of text aren't buttons: they can say anything plain.
        assertTrue(parse("""<bar-text id="t" text="Update available"/>""") is ManifestParser.Result.Ok)
        assertTrue(parse("""<bar-button id="b" label="Lights" activity=".A"/>""") is ManifestParser.Result.Ok)
        assertTrue(parse("", "Pa‮nel") is ManifestParser.Result.Invalid)
    }

    @Test
    fun aHostCallCantRenameAButtonToSettings() {
        val button = ExtensionItem("b", ItemArea.BAR, "Lights", ".Lights", 0)
        val refused = ItemOverrides.set(listOf(button), emptyList(), ItemArea.BAR, "b", "Settings")
        assertEquals(HostApi.Error.BAD_ARGUMENT, (refused as ItemOverrides.Change.Refused).error)
        assertTrue(ItemOverrides.set(listOf(button), emptyList(), ItemArea.BAR, "b", "Porch light") is ItemOverrides.Change.Ok)
        // Text is not a button, so it isn't held to the names rule; it is still plain text.
        assertTrue(ItemOverrides.set(emptyList(), emptyList(), ItemArea.BAR, "t", "Update available") is ItemOverrides.Change.Ok)
        assertTrue(ItemOverrides.set(emptyList(), emptyList(), ItemArea.BAR, "t", "a‮b") is ItemOverrides.Change.Refused)
    }

    @Test
    fun hostTextChecksUseTheSameRules() {
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.text("a​b", 40))
        assertNull(HostChecks.text("Ordinary words", 40))
    }

    @Test
    fun aCatalogNameWithHiddenCharactersMakesTheEntryInvalid() {
        val entry = org.json.JSONObject().put("package", "a.b").put("name", "Pa‮nel").put("description", "d").put("author", "a")
            .put("source", "https://example.com/s").put("capabilities", org.json.JSONArray()).put("signer", "aa".repeat(32))
            .put("versions", org.json.JSONArray(listOf(org.json.JSONObject().put("version_code", 1).put("version_name", "1")
                .put("apk", "https://example.com/a.apk").put("sha256", "cc".repeat(32)).put("size", 1000).put("contract", 1))))
        val r = CatalogParser.parse(org.json.JSONObject().put("format", 1).put("extensions", org.json.JSONArray(listOf(entry))).toString()) as CatalogParser.Result.Ok
        assertEquals(1, r.problems.size)
        assertTrue(r.catalog.extensions.isEmpty())
    }
}

class AllowCheckTest {
    @Test
    fun anApprovalIsOnlyForWhatWasShown() {
        val caps = setOf("tiles", "cards")
        assertTrue(ExtensionAccess.sameAsShown("A", "A", caps, setOf("tiles", "cards")))
        // The app was replaced by one with another signer, or one asking for more, while the page was open.
        assertFalse(ExtensionAccess.sameAsShown("A", "B", caps, caps))
        assertFalse(ExtensionAccess.sameAsShown("A", "A", caps, caps + "background"))
        assertFalse(ExtensionAccess.sameAsShown("A", null, caps, caps))
        assertFalse(ExtensionAccess.sameAsShown("A", "A", caps, null))
    }

    @Test
    fun picturesFromOtherAppsAreKeptSmall() {
        assertTrue(SafeIcons.allowed(96, 96))
        assertTrue(SafeIcons.allowed(SafeIcons.MAX_SIDE, SafeIcons.MAX_SIDE))
        assertFalse(SafeIcons.allowed(SafeIcons.MAX_SIDE + 1, 10))
        assertFalse(SafeIcons.allowed(10000, 10000))
        assertFalse(SafeIcons.allowed(0, 10))
        assertFalse(SafeIcons.allowed(-1, -1))
    }
}
