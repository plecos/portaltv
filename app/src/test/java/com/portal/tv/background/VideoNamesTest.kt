package com.portal.tv.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNamesTest {

    @Test
    fun label_isMadeFromTheFileName() {
        assertEquals("Default", BackgroundRepository.labelFor("default.mp4"))
        assertEquals("Space 1", BackgroundRepository.labelFor("space1.mp4"))
        assertEquals("Aurora 2", BackgroundRepository.labelFor("aurora2.mp4"))
        assertEquals("Space 1", BackgroundRepository.labelFor("space_1.mp4"))
        assertEquals("Northern Lights", BackgroundRepository.labelFor("northernLights.mp4"))
        assertEquals("Northern Lights 2", BackgroundRepository.labelFor("northern-lights-2.mp4"))
    }

    @Test
    fun naturalCompare_ordersNumbersByValue() {
        assertTrue(BackgroundRepository.naturalCompare("space2.mp4", "space10.mp4") < 0)
        assertTrue(BackgroundRepository.naturalCompare("space10.mp4", "space2.mp4") > 0)
        assertEquals(0, BackgroundRepository.naturalCompare("space1.mp4", "space1.mp4"))
        assertTrue(BackgroundRepository.naturalCompare("aurora1.mp4", "space1.mp4") < 0)
    }

    @Test
    fun orderVideoNames_putsDefaultFirstAndSkipsSpecials() {
        // Whichever video is the default comes first, whatever its name.
        val first = BackgroundRepository.DEFAULT_VIDEO
        val others = listOf("aurora1.mp4", "space10.mp4", "zebra.mp4", "space3.mp4").filter { it != first }
        val listing = others.reversed() + listOf(first, "birthday.mp4", "special", ".DS_Store", "notes.txt")
        assertEquals(
            listOf(first) + others.sortedWith { a, b -> BackgroundRepository.naturalCompare(a, b) },
            BackgroundRepository.orderVideoNames(listing)
        )
    }

    @Test
    fun orderVideoNames_handlesAnEmptyFolder() {
        assertEquals(emptyList<String>(), BackgroundRepository.orderVideoNames(emptyList()))
    }
}
