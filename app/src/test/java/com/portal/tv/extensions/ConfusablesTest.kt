package com.portal.tv.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfusablesTest {

    @Test
    fun theListIsLoaded() {
        assertTrue("the confusables file wasn't found or is empty", Confusables.size() > 1000)
    }

    @Test
    fun charactersDrawnLikeLatinLettersAreMappedToThem() {
        assertEquals("s", Confusables.lookalikeOf(0xA4E2))   // Lisu SA
        assertEquals("t", Confusables.lookalikeOf(0x13A2))   // Cherokee I (drawn like T)
        assertEquals("p", Confusables.lookalikeOf(0x13E2))   // Cherokee, drawn like P
        assertEquals("p", Confusables.lookalikeOf(0x1BF))    // wynn
        assertEquals("o", Confusables.lookalikeOf(0x43E))    // Cyrillic o
    }

    @Test
    fun latinLookalikesOnlyTheUnicodeListCoversAreCaught() {
        for (bad in listOf("P\u1d11rtal", "\u00feortal", "S\uab32ttings", "\uff33ettings")) assertNotNull(bad, Impersonation.reason(bad))
    }

    @Test
    fun aNameInLookalikeLettersFoldsToTheWordItPassesFor() {
        // "SETTINGS" and "PORTAL" in Lisu letters, found by the list alone (not by the rule on rare alphabets).
        // (Lisu capital I is drawn like l, which the name check treats as the same as i)
        assertEquals("settings", Impersonation.looksLike("ꓢꓰꓔꓔꓲꓠꓖꓢ").replace('l', 'i'))
        assertEquals("portal", Impersonation.looksLike("ꓑꓳꓣꓔꓮꓡ"))
        assertNotNull(Impersonation.reason("ꓢꓰꓔꓔꓲꓠꓖꓢ"))
    }
}
