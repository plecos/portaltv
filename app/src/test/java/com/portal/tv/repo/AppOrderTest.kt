package com.portal.tv.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {

    private val alphabetical = listOf("cast", "play", "pluto", "youtube")

    @Test
    fun sort_withNoSavedOrder_keepsTheOriginalOrder() {
        assertEquals(alphabetical, AppOrder.sort(alphabetical, emptyList()) { it })
    }

    @Test
    fun sort_followsTheSavedOrder() {
        val saved = listOf("youtube", "cast", "pluto", "play")
        assertEquals(saved, AppOrder.sort(alphabetical, saved) { it })
    }

    @Test
    fun sort_putsAppsMissingFromTheOrderAfterTheOthers_inTheirOriginalOrder() {
        val saved = listOf("youtube", "pluto")
        assertEquals(
            listOf("youtube", "pluto", "cast", "play"),
            AppOrder.sort(alphabetical, saved) { it }
        )
    }

    @Test
    fun sort_ignoresSavedAppsThatAreNoLongerInstalled() {
        val saved = listOf("gone", "pluto", "alsoGone", "cast")
        assertEquals(
            listOf("pluto", "cast", "play", "youtube"),
            AppOrder.sort(alphabetical, saved) { it }
        )
    }

    @Test
    fun moveTo_movesAnAppToTheNewIndex() {
        assertEquals(
            listOf("youtube", "cast", "play", "pluto"),
            AppOrder.moveTo(alphabetical, emptyList(), "youtube", 0)
        )
        assertEquals(
            listOf("play", "cast", "pluto", "youtube"),
            AppOrder.moveTo(alphabetical, emptyList(), "cast", 1)
        )
        assertEquals(
            listOf("cast", "pluto", "youtube", "play"),
            AppOrder.moveTo(alphabetical, emptyList(), "play", 3)
        )
    }

    @Test
    fun moveTo_clampsAnOutOfRangeIndex() {
        assertEquals(
            listOf("play", "pluto", "youtube", "cast"),
            AppOrder.moveTo(alphabetical, emptyList(), "cast", 99)
        )
        assertEquals(
            listOf("pluto", "cast", "play", "youtube"),
            AppOrder.moveTo(alphabetical, emptyList(), "pluto", -5)
        )
    }

    @Test
    fun moveTo_keepsSavedEntriesThatAreNotOnScreen() {
        // "netflix" is currently in the frequent row, so it isn't in the grid being reordered.
        val saved = listOf("cast", "netflix", "play")
        assertEquals(
            listOf("play", "cast", "pluto", "youtube", "netflix"),
            AppOrder.moveTo(alphabetical, saved, "play", 0)
        )
    }

    @Test
    fun moveEarlierThenLater_returnsToTheStart() {
        val moved = AppOrder.moveTo(alphabetical, emptyList(), "pluto", 1)
        val back = AppOrder.moveTo(moved, moved, "pluto", 2)
        assertEquals(alphabetical, back)
    }
}
