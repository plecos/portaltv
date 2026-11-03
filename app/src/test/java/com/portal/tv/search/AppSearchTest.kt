package com.portal.tv.search

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private val apps = listOf("Cast moderator", "Disney+", "Google Play Store", "Pluto TV", "Plex", "Prime Video", "YouTube", "YouTube Kids")

    private fun find(q: String) = AppSearch.match(apps, q) { it }

    @Test
    fun emptyQueryMatchesNothing() {
        assertEquals(emptyList<String>(), find(""))
        assertEquals(emptyList<String>(), find("   "))
    }

    @Test
    fun matchingIgnoresCaseAndSpaces() {
        assertEquals(listOf("YouTube", "YouTube Kids"), find("you"))
        assertEquals(listOf("YouTube", "YouTube Kids"), find("YOUTUBE"))
        assertEquals(listOf("Pluto TV"), find("plutotv"))
        assertEquals(listOf("Pluto TV"), find("pluto tv"))
    }

    @Test
    fun namesThatStartWithTheTextComeBeforeWordsAndContains() {
        // "play" starts nothing, but is a word in "Google Play Store".
        assertEquals(listOf("Google Play Store"), find("play"))
        // "pl" starts Pluto and Plex, and is also the start of the word "Play": those two come first.
        assertEquals(listOf("Pluto TV", "Plex", "Google Play Store"), find("pl"))
        // A name that starts with the text beats one that only has it as a later word.
        val list = listOf("My Kids App", "Kids Zone")
        assertEquals(listOf("Kids Zone", "My Kids App"), AppSearch.match(list, "kids") { it })
    }

    @Test
    fun wordStartBeatsMidWordContains() {
        val list = listOf("Spotify Music", "Gamestop", "Mystop")
        // "stop": word-start none; contains in both. "music" word start.
        assertEquals(listOf("Spotify Music"), AppSearch.match(list, "mus") { it })
        assertEquals(listOf("Gamestop", "Mystop"), AppSearch.match(list, "stop") { it })
    }

    @Test
    fun equalMatchesKeepTheirOrder() {
        assertEquals(listOf("YouTube", "YouTube Kids"), find("youtube"))
    }

    @Test
    fun noMatchGivesAnEmptyList() {
        assertEquals(emptyList<String>(), find("what is the weather like"))
        assertEquals(emptyList<String>(), find("zzz"))
    }

    @Test
    fun punctuationInNamesDoesNotGetInTheWay() {
        assertEquals(listOf("Disney+"), find("disney"))
        assertEquals(listOf("Disney+"), find("disney+"))
    }
}
