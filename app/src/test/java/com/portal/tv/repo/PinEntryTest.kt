package com.portal.tv.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinEntryTest {

    @Test
    fun digitsAreAppendedInOrder() {
        val e = PinEntry()
        "2580".forEach { e.add(it) }
        assertEquals("2580", e.value)
        assertEquals(4, e.length)
    }

    @Test
    fun deleteRemovesTheLastDigitAndIsSafeWhenEmpty() {
        val e = PinEntry()
        e.add('1'); e.add('2')
        e.delete()
        assertEquals("1", e.value)
        e.delete(); e.delete(); e.delete()
        assertEquals("", e.value)
    }

    @Test
    fun entryStopsAtTheFixedLength() {
        val e = PinEntry()
        "123456789".forEach { e.add(it) }
        assertEquals("1234", e.value)
    }

    @Test
    fun nonDigitsAreIgnored() {
        val e = PinEntry()
        e.add('a'); e.add(' '); e.add('-'); e.add('7')
        assertEquals("7", e.value)
    }

    @Test
    fun clearEmptiesTheEntry() {
        val e = PinEntry()
        e.add('1'); e.add('2')
        e.clear()
        assertEquals(0, e.length)
    }

    @Test
    fun rowsMapLeftOkRightToTheThreeNumbersInARow() {
        assertEquals('1', PinEntry.digitAt(0, 0))
        assertEquals('2', PinEntry.digitAt(0, 1))
        assertEquals('3', PinEntry.digitAt(0, 2))
        assertEquals('4', PinEntry.digitAt(1, 0))
        assertEquals('8', PinEntry.digitAt(2, 1))
        assertEquals('9', PinEntry.digitAt(2, 2))
    }

    @Test
    fun theLastRowHasZeroInTheMiddleBetweenDeleteAndCancel() {
        assertNull(PinEntry.digitAt(3, 0))
        assertEquals('0', PinEntry.digitAt(3, 1))
        assertNull(PinEntry.digitAt(3, 2))
    }

    @Test
    fun outOfRangePressesGiveNothing() {
        assertNull(PinEntry.digitAt(4, 0))
        assertNull(PinEntry.digitAt(-1, 1))
        assertNull(PinEntry.digitAt(0, 3))
    }

    @Test
    fun everyDigitCanBeEntered() {
        val reachable = (0..3).flatMap { r -> (0..2).mapNotNull { c -> PinEntry.digitAt(r, c) } }.toSet()
        assertEquals("0123456789".toSet(), reachable)
    }
}
