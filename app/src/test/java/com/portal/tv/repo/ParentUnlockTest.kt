package com.portal.tv.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentUnlockTest {

    private val minute = 60_000L

    @Test
    fun staysOpenForTheTimeAndThenCloses() {
        val w = ParentUnlock.Window()
        w.remember(profileId = 1, now = 1_000, durationMs = 10 * minute)
        assertTrue(w.isOpen(1, 1_000))
        assertTrue(w.isOpen(1, 1_000 + 10 * minute - 1))
        assertFalse(w.isOpen(1, 1_000 + 10 * minute))
        assertFalse(w.isOpen(1, 1_000 + 11 * minute))
    }

    @Test
    fun isOnlyForTheProfileItWasEnteredOn() {
        val w = ParentUnlock.Window()
        w.remember(1, 0, 10 * minute)
        assertFalse(w.isOpen(2, 1))
        assertTrue(w.isOpen(1, 1))
    }

    @Test
    fun clearingForgetsItAtOnce() {
        val w = ParentUnlock.Window()
        w.remember(1, 0, 10 * minute)
        w.clear()
        assertFalse(w.isOpen(1, 1))
    }

    @Test
    fun enteringAgainStartsANewTime() {
        val w = ParentUnlock.Window()
        w.remember(1, 0, 10 * minute)
        w.remember(1, 9 * minute, 10 * minute)
        assertTrue(w.isOpen(1, 15 * minute))
        assertFalse(w.isOpen(1, 19 * minute + 1))
    }

    @Test
    fun aNewWindowIsClosed() {
        assertFalse(ParentUnlock.Window().isOpen(1, 0))
        assertFalse(ParentUnlock.Window().isOpen(-1, 0))
    }
}
