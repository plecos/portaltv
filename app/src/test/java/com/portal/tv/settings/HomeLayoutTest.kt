package com.portal.tv.settings

import com.portal.tv.settings.HomeLayout.Corner
import com.portal.tv.settings.HomeLayout.Group
import com.portal.tv.settings.HomeLayout.IconSize
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun defaultsAreTheOriginalLayout() {
        assertEquals(Corner.TOP_LEFT, HomeLayout.DEFAULT_CLOCK_CORNER)
        assertEquals(Corner.TOP_RIGHT, HomeLayout.DEFAULT_ACTIONS_CORNER)
        assertEquals(IconSize.MEDIUM, IconSize.fromKey(null))
        assertEquals(1f, IconSize.MEDIUM.scale, 0f)
    }

    @Test
    fun anUnknownKeyFallsBackToTheDefault() {
        assertEquals(Corner.TOP_RIGHT, Corner.fromKey("middle", Corner.TOP_RIGHT))
        assertEquals(Corner.BOTTOM_LEFT, Corner.fromKey("bottom_left", Corner.TOP_RIGHT))
        assertEquals(IconSize.MEDIUM, IconSize.fromKey("huge"))
        assertEquals(IconSize.LARGE, IconSize.fromKey("large"))
    }

    @Test
    fun eachGroupLandsInItsOwnCorner() {
        assertEquals(listOf(Group.CLOCK), HomeLayout.groupsIn(Corner.TOP_LEFT, Corner.TOP_LEFT, Corner.TOP_RIGHT))
        assertEquals(listOf(Group.ACTIONS), HomeLayout.groupsIn(Corner.TOP_RIGHT, Corner.TOP_LEFT, Corner.TOP_RIGHT))
        assertEquals(emptyList<Group>(), HomeLayout.groupsIn(Corner.BOTTOM_LEFT, Corner.TOP_LEFT, Corner.TOP_RIGHT))
    }

    @Test
    fun groupsInTheSameCornerStackWithTheClockFirst() {
        Corner.values().forEach { corner ->
            assertEquals(listOf(Group.CLOCK, Group.ACTIONS), HomeLayout.groupsIn(corner, corner, corner))
        }
    }

    @Test
    fun everyGroupIsPlacedExactlyOnce() {
        for (clock in Corner.values()) for (actions in Corner.values()) {
            val placed = Corner.values().flatMap { HomeLayout.groupsIn(it, clock, actions) }
            assertEquals(listOf(Group.CLOCK, Group.ACTIONS).toSet(), placed.toSet())
            assertEquals(2, placed.size)
        }
    }

    @Test
    fun cornersKnowWhichEdgesTheyAreOn() {
        assertEquals(true, Corner.TOP_LEFT.top && Corner.TOP_LEFT.start)
        assertEquals(true, !Corner.BOTTOM_RIGHT.top && !Corner.BOTTOM_RIGHT.start)
    }

    @Test
    fun iconSizesScaleTheOriginalSize() {
        assertEquals(72, HomeLayout.scaled(96, IconSize.SMALL))
        assertEquals(96, HomeLayout.scaled(96, IconSize.MEDIUM))
        assertEquals(120, HomeLayout.scaled(96, IconSize.LARGE))
    }

    @Test
    fun columnsAreWholeAndAtLeastOne() {
        assertEquals(7, HomeLayout.columns(1000, 140))
        assertEquals(1, HomeLayout.columns(100, 140))
        assertEquals(1, HomeLayout.columns(0, 140))
    }
}
