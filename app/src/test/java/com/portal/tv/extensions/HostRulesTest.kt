package com.portal.tv.extensions

import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderJson
import com.portal.tv.reminders.ReminderLogic
import com.portal.tv.reminders.MissedPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChecksTest {

    @Test
    fun textMustBePlainAndNotTooLong() {
        assertNull(HostChecks.text("Call mum", 80))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.text(null, 80))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.text("   ", 80))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.text("x".repeat(81), 80))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.text("a\nb", 80))
        assertNull(HostChecks.text("x".repeat(80), 80))
    }

    @Test
    fun reminderTimeMustBeInTheFutureButNotTooFar() {
        val now = 1_000_000_000_000L
        assertNull(HostChecks.reminderTime(now + 1000, now))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.reminderTime(now, now))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.reminderTime(now - 5, now))
        assertEquals(HostApi.Error.BAD_ARGUMENT, HostChecks.reminderTime(now + HostLimits.MAX_AHEAD_MS + 1, now))
        assertNull(HostChecks.reminderTime(now + HostLimits.MAX_AHEAD_MS, now))
    }

    @Test
    fun repeatToneAndSnoozeAreChecked() {
        assertEquals(Reminder.Repeat.NONE, HostChecks.repeat(null))
        assertEquals(Reminder.Repeat.DAILY, HostChecks.repeat("daily"))
        assertEquals(Reminder.Repeat.WEEKLY, HostChecks.repeat("WEEKLY"))
        assertNull(HostChecks.repeat("monthly"))
        assertTrue(HostChecks.tone(null))
        assertTrue(HostChecks.tone("chirp"))
        // A sound from the TV's own list is an address on the TV: not for an extension to name.
        assertFalse(HostChecks.tone("sys:content://media/internal/audio/media/1"))
        assertFalse(HostChecks.tone("../../etc"))
        assertTrue(HostChecks.snooze(0)); assertTrue(HostChecks.snooze(10)); assertFalse(HostChecks.snooze(7))
    }

    @Test
    fun cardSecondsDefaultsAndIsCapped() {
        assertEquals(10, HostChecks.cardSeconds(0))
        assertEquals(10, HostChecks.cardSeconds(-4))
        assertEquals(5, HostChecks.cardSeconds(5))
        assertEquals(30, HostChecks.cardSeconds(3600))
    }
}

class RateLimiterTest {

    @Test
    fun allowsTheLimitThenRefusesUntilTheWindowPasses() {
        val limiter = RateLimiter(3, 60_000)
        assertTrue(limiter.allow("a", 0)); assertTrue(limiter.allow("a", 1)); assertTrue(limiter.allow("a", 2))
        assertFalse(limiter.allow("a", 3))
        assertFalse(limiter.allow("a", 59_999))
        assertTrue(limiter.allow("a", 60_000))
    }

    @Test
    fun eachKeyHasItsOwnAllowance() {
        val limiter = RateLimiter(1, 60_000)
        assertTrue(limiter.allow("a", 0))
        assertTrue(limiter.allow("b", 0))
        assertFalse(limiter.allow("a", 1))
    }
}

class ItemOverridesTest {

    private val button = ExtensionItem("lights", ItemArea.BAR, "Lights", ".A", 0)
    private val barText = ExtensionItem("mode", ItemArea.BAR, "Home: day", null, 0)
    private val clockText = ExtensionItem("door", ItemArea.CLOCK, "Door closed", null, 0)
    private val declared = listOf(button, barText, clockText)

    private fun ok(c: ItemOverrides.Change) = (c as ItemOverrides.Change.Ok).overrides
    private fun error(c: ItemOverrides.Change) = (c as ItemOverrides.Change.Refused).error

    @Test
    fun nothingChangedShowsTheDeclaredItems() {
        assertEquals(declared, ItemOverrides.apply(declared, emptyList()))
    }

    @Test
    fun settingTheWordsOfADeclaredItemEditsIt() {
        val o = ok(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "door", "Door open"))
        assertEquals("Door open", ItemOverrides.apply(declared, o).first { it.id == "door" }.text)
        // A button keeps its screen when its label changes.
        val b = ok(ItemOverrides.set(declared, emptyList(), ItemArea.BAR, "lights", "Lamps"))
        val shown = ItemOverrides.apply(declared, b).first { it.id == "lights" }
        assertEquals("Lamps", shown.text); assertEquals(".A", shown.activity)
    }

    @Test
    fun removingHidesADeclaredItemUntilItIsSetAgain() {
        val removed = ok(ItemOverrides.remove(declared, emptyList(), ItemArea.CLOCK, "door"))
        assertTrue(ItemOverrides.apply(declared, removed).none { it.id == "door" })
        val back = ok(ItemOverrides.set(declared, removed, ItemArea.CLOCK, "door", "Door closed"))
        assertTrue(ItemOverrides.apply(declared, back).any { it.id == "door" })
    }

    @Test
    fun anExtensionCanAddLinesOfTextButNotButtons() {
        val added = ok(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "extra", "Front door locked"))
        val shown = ItemOverrides.apply(declared, added).first { it.id == "extra" }
        assertEquals("Front door locked", shown.text)
        assertFalse(shown.isButton)
        assertEquals(0, shown.iconRes)
    }

    @Test
    fun twoItemsPerAreaIsTheLimitForAddedLines() {
        // The bar already has its two declared items.
        assertEquals(HostApi.Error.LIMIT, error(ItemOverrides.set(declared, emptyList(), ItemArea.BAR, "extra", "x")))
        // The clock area has one, so one more fits and a third doesn't.
        val one = ok(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "a", "x"))
        assertEquals(HostApi.Error.LIMIT, error(ItemOverrides.set(declared, one, ItemArea.CLOCK, "b", "y")))
        // Removing one makes room.
        val room = ok(ItemOverrides.remove(declared, one, ItemArea.CLOCK, "a"))
        assertTrue(ItemOverrides.set(declared, room, ItemArea.CLOCK, "b", "y") is ItemOverrides.Change.Ok)
    }

    @Test
    fun badIdsAndWordsAreRefused() {
        assertEquals(HostApi.Error.BAD_ARGUMENT, error(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "Bad Id", "x")))
        assertEquals(HostApi.Error.BAD_ARGUMENT, error(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "door", "")))
        assertEquals(HostApi.Error.BAD_ARGUMENT, error(ItemOverrides.set(declared, emptyList(), ItemArea.CLOCK, "door", "x".repeat(41))))
        // A button's label is held to the shorter limit.
        assertEquals(HostApi.Error.BAD_ARGUMENT, error(ItemOverrides.set(declared, emptyList(), ItemArea.BAR, "lights", "x".repeat(25))))
    }

    @Test
    fun removingSomethingThatIsntThereIsNotFound() {
        assertEquals(HostApi.Error.NOT_FOUND, error(ItemOverrides.remove(declared, emptyList(), ItemArea.BAR, "settings")))
        assertEquals(HostApi.Error.NOT_FOUND, error(ItemOverrides.remove(declared, emptyList(), ItemArea.CLOCK, "mode")))
    }

    @Test
    fun anExtensionCanOnlyReachItsOwnItems() {
        // Portal's own buttons aren't in the list it is given, so no id of theirs can be changed or removed.
        for (id in listOf("settings", "search", "profile", "update", "btn_settings", "btn_search", "btn_profile", "weather")) {
            assertEquals(HostApi.Error.NOT_FOUND, error(ItemOverrides.remove(declared, emptyList(), ItemArea.BAR, id)))
        }
    }

    @Test
    fun theNumberOfChangesIsCapped() {
        val many = (1..HostLimits.MAX_ITEM_CHANGES).map { ItemOverride(ItemArea.CLOCK, "i$it", null, removed = true) }
        assertEquals(HostApi.Error.LIMIT, error(ItemOverrides.set(declared, many, ItemArea.CLOCK, "door", "x")))
    }

    @Test
    fun changesAreSavedAndReadBack() {
        val list = listOf(ItemOverride(ItemArea.CLOCK, "door", "Open"), ItemOverride(ItemArea.BAR, "mode", null, removed = true))
        assertEquals(list, ItemOverrides.decode(ItemOverrides.encode(list)))
        assertTrue(ItemOverrides.decode("not json").isEmpty())
        assertTrue(ItemOverrides.decode(null).isEmpty())
    }
}

class ReminderSourceTest {

    private fun reminder(id: Int, source: String?) = Reminder(id, 1, "t$id", 1000L, source = source)

    @Test
    fun theSourceIsSavedAndReadBack() {
        val list = listOf(reminder(1, "net.example.panel"), reminder(2, null))
        val back = ReminderJson.decode(ReminderJson.encode(list))
        assertEquals("net.example.panel", back.first { it.id == 1 }.source)
        assertNull(back.first { it.id == 2 }.source)
    }

    @Test
    fun remindersSavedBeforeSourcesExistedReadBackWithNone() {
        val old = """[{"id":3,"profile":1,"title":"x","at":5,"repeat":"NONE"}]"""
        assertNull(ReminderJson.decode(old).single().source)
    }

    @Test
    fun aReminderWhoseExtensionIsNoLongerAllowedIsDroppedAndOthersFire() {
        val list = listOf(reminder(1, "net.example.gone"), reminder(2, "net.example.ok"), reminder(3, null))
        val r = ReminderLogic.resolve(list, 5000L, MissedPolicy.ALWAYS, sourceAllowed = { it == "net.example.ok" })
        assertEquals(listOf(2, 3), r.fire.map { it.reminder.id })
        assertTrue(r.keep.none { it.id == 1 })
    }
}
