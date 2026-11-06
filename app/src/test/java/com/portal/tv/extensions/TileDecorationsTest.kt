package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDecorationsTest {

    private val declared = setOf("lights", "cams")

    private fun ok(c: TileDecorations.Change) = (c as TileDecorations.Change.Ok).decorations
    private fun err(c: TileDecorations.Change) = (c as TileDecorations.Change.Refused).error

    @Test
    fun settingAndClearingPartsKeepsTheOthers() {
        val a = ok(TileDecorations.update(declared, emptyMap(), "lights", "2", null, hasBadge = true, hasSubtitle = false))
        assertEquals(TileDecor("2", null), a["lights"])
        val b = ok(TileDecorations.update(declared, a, "lights", null, "Living room", hasBadge = false, hasSubtitle = true))
        assertEquals(TileDecor("2", "Living room"), b["lights"])
        val c = ok(TileDecorations.update(declared, b, "lights", "", null, hasBadge = true, hasSubtitle = false))
        assertEquals(TileDecor(null, "Living room"), c["lights"])
        assertTrue(ok(TileDecorations.update(declared, c, "lights", null, "", hasBadge = false, hasSubtitle = true)).isEmpty())
    }

    @Test
    fun onlyDeclaredTilesAndOnlyValidTextAreAccepted() {
        assertEquals(HostApi.Error.NOT_FOUND, err(TileDecorations.update(declared, emptyMap(), "nope", "1", null, true, false)))
        assertEquals(HostApi.Error.BAD_ARGUMENT, err(TileDecorations.update(declared, emptyMap(), "lights", null, null, false, false)))
        assertEquals(HostApi.Error.BAD_ARGUMENT, err(TileDecorations.update(declared, emptyMap(), "lights", "x".repeat(7), null, true, false)))
        assertEquals(HostApi.Error.BAD_ARGUMENT, err(TileDecorations.update(declared, emptyMap(), "lights", 4, null, true, false)))
        assertEquals(HostApi.Error.BAD_ARGUMENT, err(TileDecorations.update(declared, emptyMap(), "lights", null, "a\tb", false, true)))
    }

    @Test
    fun otherTilesAreLeftAlone() {
        val start = mapOf("cams" to TileDecor("1", null))
        val next = ok(TileDecorations.update(declared, start, "lights", "5", null, true, false))
        assertEquals(TileDecor("1", null), next["cams"])
    }

    @Test
    fun savedAndReadBackAndBadDataIsIgnored() {
        val map = mapOf("lights" to TileDecor("2", "x"), "cams" to TileDecor(null, "Garage"))
        assertEquals(map, TileDecorations.decode(TileDecorations.encode(map)))
        assertTrue(TileDecorations.decode("not json").isEmpty())
        assertTrue(TileDecorations.decode(null).isEmpty())
        // A saved value longer than the limits (from a damaged file) is cut, and an invalid id dropped.
        val damaged = TileDecorations.decode("""{"lights":{"badge":"1234567890"},"Bad Id":{"badge":"1"}}""")
        assertEquals(setOf("lights"), damaged.keys)
        assertEquals(TileDecorations.MAX_BADGE, damaged["lights"]!!.badge!!.length)
    }
}
