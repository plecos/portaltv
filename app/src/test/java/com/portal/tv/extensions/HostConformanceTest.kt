package com.portal.tv.extensions

import com.portal.tv.reminders.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Conformance tests for the host API: what a well-behaved extension can do, and above all what a misbehaving one can't.
 * They run the real rules ([HostHandler]) against a fake TV, so every refusal is also checked to have changed nothing.
 */
private class FakeTv : HostEnv {
    var now = 1_700_000_000_000L
    var elapsed = 0L
    val extensions = mutableMapOf<String, HostExtension>()
    var profile: HostProfile? = HostProfile(1, false)
    val reminders = mutableListOf<Reminder>()
    val itemChanges = mutableMapOf<String, List<ItemOverride>>()
    var cardsShown = mutableListOf<List<String>>()
    var cardWorks = true
    var itemEvents = 0
    val tileDecor = mutableMapOf<String, Map<String, TileDecor>>()
    var tileEvents = 0
    private var nextId = 1

    /** Counts everything that changed, so a refusal can be shown to have changed nothing. */
    var writes = 0

    override fun nowMillis() = now
    override fun elapsedMillis() = elapsed
    override fun extension(packageName: String) = extensions[packageName]
    override fun activeProfile() = profile
    override fun remindersFrom(source: String) = reminders.filter { it.source == source }
    override fun addReminder(profileId: Int, title: String, atMillis: Long, like: Reminder): Reminder {
        writes++
        return like.copy(id = nextId++, profileId = profileId, title = title.trim(), atMillis = atMillis).also { reminders += it }
    }
    override fun updateReminder(reminder: Reminder) { writes++; reminders.replaceAll { if (it.id == reminder.id) reminder else it } }
    override fun removeReminder(id: Int) { writes++; reminders.removeAll { it.id == id } }
    override fun itemChanges(packageName: String) = itemChanges[packageName].orEmpty()
    override fun saveItemChanges(packageName: String, changes: List<ItemOverride>) { writes++; itemChanges[packageName] = changes }
    override fun itemsChanged() { itemEvents++ }
    override fun tileDecorations(packageName: String) = tileDecor[packageName].orEmpty()
    override fun saveTileDecorations(packageName: String, decorations: Map<String, TileDecor>) { writes++; tileDecor[packageName] = decorations }
    override fun tilesChanged() { tileEvents++ }
    override fun showCard(from: String, title: String, text: String, seconds: Int): Boolean {
        if (cardWorks) { writes++; cardsShown += listOf(from, title, text, seconds.toString()) }
        return cardWorks
    }

    fun install(pkg: String, granted: Set<String>, items: List<ExtensionItem> = emptyList(), tiles: Set<String> = emptySet()) {
        extensions[pkg] = HostExtension(pkg, "Ext $pkg", granted, items, tiles)
    }
}

private val ALL = Capability.known.toSet()
private const val A = "net.example.a"
private const val B = "net.example.b"

class HostConformanceTest {

    private val tv = FakeTv()
    private fun handler() = HostHandler(tv)
    private val soon get() = tv.now + 60_000L
    private fun add(h: HostHandler, who: String = A, title: String = "Take the bins out") =
        h.call(who, HostApi.REMINDERS_ADD, mapOf("title" to title, "at_millis" to soon))
    private fun HostReply.assertError(code: String) { assertFalse("expected $code but the call worked", ok); assertEquals(code, error) }

    // ---------- who may call ----------

    @Test
    fun aCallerThatIsNotAnExtensionIsRefusedEverything() {
        val h = handler()
        listOf(HostApi.INFO, HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.CARD_SHOW, HostApi.ITEMS_SET, HostApi.TILE_UPDATE, "made.up").forEach {
            h.call("com.android.shell", it, mapOf("title" to "x", "at_millis" to soon, "area" to "bar", "id" to "a", "text" to "x")).assertError(HostApi.Error.NOT_ALLOWED)
        }
        assertEquals(0, tv.writes)
    }

    @Test
    fun anExtensionNothingIsAllowedForCanOnlyAskWhatItMayDo() {
        tv.install(A, emptySet())
        val h = handler()
        val info = h.call(A, HostApi.INFO, emptyMap())
        assertTrue(info.ok)
        assertEquals(emptyList<String>(), info.data["granted"])
        listOf(HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.REMINDERS_UPDATE, HostApi.REMINDERS_DELETE, HostApi.CARD_SHOW, HostApi.TILE_UPDATE).forEach {
            h.call(A, it, mapOf("title" to "x", "at_millis" to soon, "id" to 1, "badge" to "1")).assertError(HostApi.Error.NOT_ALLOWED)
        }
        for (area in listOf("bar", "clock")) listOf(HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST).forEach {
            h.call(A, it, mapOf("area" to area, "id" to "a", "text" to "x")).assertError(HostApi.Error.NOT_ALLOWED)
        }
        h.call(A, "made.up", emptyMap()).assertError(HostApi.Error.NO_SUCH_METHOD)
        assertEquals(0, tv.writes)
    }

    @Test
    fun eachCapabilityOpensOnlyItsOwnCalls() {
        val needs = mapOf(
            Capability.REMINDERS to listOf(HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.REMINDERS_UPDATE, HostApi.REMINDERS_DELETE),
            Capability.CARDS to listOf(HostApi.CARD_SHOW),
            Capability.TILES to listOf(HostApi.TILE_UPDATE)
        )
        val args = mapOf("title" to "x", "at_millis" to soon, "id" to "panel", "badge" to "1")
        for ((capability, methods) in needs) {
            tv.install(A, ALL - capability)
            methods.forEach { handler().call(A, it, args).assertError(HostApi.Error.NOT_ALLOWED) }
        }
        // Bar and clock are separate from each other.
        tv.install(A, setOf(Capability.BAR))
        handler().call(A, HostApi.ITEMS_SET, mapOf("area" to "clock", "id" to "a", "text" to "x")).assertError(HostApi.Error.NOT_ALLOWED)
        assertTrue(handler().call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "a", "text" to "x")).ok)
        // Capabilities that have no calls (settings, background) open nothing here.
        tv.install(A, setOf(Capability.SETTINGS, Capability.BACKGROUND))
        handler().call(A, HostApi.REMINDERS_ADD, args).assertError(HostApi.Error.NOT_ALLOWED)
        handler().call(A, HostApi.TILE_UPDATE, args).assertError(HostApi.Error.NOT_ALLOWED)
    }

    @Test
    fun aKidProfileMeansNothingIsDoneForAnyone() {
        tv.install(A, ALL)
        tv.profile = HostProfile(2, isKids = true)
        val h = handler()
        listOf(HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.CARD_SHOW).forEach {
            h.call(A, it, mapOf("title" to "x", "at_millis" to soon)).assertError(HostApi.Error.KID_PROFILE)
        }
        h.call(A, HostApi.TILE_UPDATE, mapOf("id" to "panel", "badge" to "1")).assertError(HostApi.Error.KID_PROFILE)
        listOf(HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST).forEach {
            h.call(A, it, mapOf("area" to "bar", "id" to "a", "text" to "x")).assertError(HostApi.Error.KID_PROFILE)
        }
        assertEquals(0, tv.writes)
        // It may still ask what Portal supports.
        assertTrue(h.call(A, HostApi.INFO, emptyMap()).ok)
    }

    @Test
    fun withNoProfileThereIsNothingToDoItFor() {
        tv.install(A, ALL); tv.profile = null
        add(handler()).assertError(HostApi.Error.UNAVAILABLE)
        assertEquals(0, tv.writes)
    }

    // ---------- reminders ----------

    @Test
    fun anExtensionOnlyEverSeesAndChangesItsOwnReminders() {
        tv.install(A, ALL); tv.install(B, ALL)
        tv.reminders += Reminder(900, 1, "Portal's own", soon)            // made in Portal
        val h = handler()
        val mine = add(h, A).data["id"] as Int
        val theirs = add(h, B, "B's").data["id"] as Int
        assertEquals(listOf(mine), (h.call(A, HostApi.REMINDERS_LIST, emptyMap()).data["reminders"] as List<*>).map { (it as Map<*, *>)["id"] })
        val before = tv.reminders.toList()
        for (other in listOf(900, theirs)) {
            h.call(A, HostApi.REMINDERS_UPDATE, mapOf("id" to other, "title" to "taken over")).assertError(HostApi.Error.NOT_FOUND)
            h.call(A, HostApi.REMINDERS_DELETE, mapOf("id" to other)).assertError(HostApi.Error.NOT_FOUND)
        }
        assertEquals(before, tv.reminders)
    }

    @Test
    fun aCallerCannotNameWhoItIs() {
        tv.install(A, ALL); tv.install(B, ALL)
        val h = handler()
        // Claims in the arguments mean nothing: the caller is what the system says.
        val id = h.call(A, HostApi.REMINDERS_ADD, mapOf(
            "title" to "x", "at_millis" to soon, "source" to B, "package" to B, "caller" to B, "profile" to 99, "id" to 123
        )).data["id"] as Int
        val made = tv.reminders.single { it.id == id }
        assertEquals(A, made.source)
        assertEquals(1, made.profileId)
        assertTrue(h.call(B, HostApi.REMINDERS_LIST, emptyMap()).data["reminders"] == emptyList<Any>())
        // An update can't move a reminder to another owner or profile either.
        h.call(A, HostApi.REMINDERS_UPDATE, mapOf("id" to id, "source" to B, "profile" to 7, "title" to "renamed")).let { assertTrue(it.ok) }
        val after = tv.reminders.single { it.id == id }
        assertEquals(A, after.source); assertEquals(1, after.profileId); assertEquals("renamed", after.title)
    }

    @Test
    fun badValuesAreRefusedAndChangeNothing() {
        tv.install(A, ALL)
        val h = HostHandler(tv, RateLimiter(10_000, 60_000L))   // so the rate limit isn't what refuses them
        val ok = mapOf("title" to "x", "at_millis" to soon)
        val bad = listOf(
            ok - "title", ok + ("title" to ""), ok + ("title" to "   "), ok + ("title" to "x".repeat(81)), ok + ("title" to "a\nb"),
            ok + ("title" to 5), ok + ("title" to listOf("x")),
            ok - "at_millis", ok + ("at_millis" to "soon"), ok + ("at_millis" to tv.now), ok + ("at_millis" to tv.now - 1),
            ok + ("at_millis" to tv.now + HostLimits.MAX_AHEAD_MS + 10), ok + ("at_millis" to Long.MAX_VALUE), ok + ("at_millis" to Long.MIN_VALUE),
            ok + ("repeat" to "monthly"), ok + ("repeat" to 3),
            ok + ("tone" to "sys:content://media/internal/audio/media/1"), ok + ("tone" to "../../x"), ok + ("tone" to 1), ok + ("tone" to null),
            ok + ("manual" to "yes"), ok + ("snooze_minutes" to 7), ok + ("snooze_minutes" to "5"), ok + ("snooze_minutes" to -5)
        )
        bad.forEach { h.call(A, HostApi.REMINDERS_ADD, it).let { r -> assertFalse("accepted $it", r.ok) } }
        assertEquals(0, tv.writes)
        // A good one with every option works, so the refusals above weren't just everything failing.
        assertTrue(h.call(A, HostApi.REMINDERS_ADD, ok + mapOf("repeat" to "daily", "tone" to "chirp", "manual" to true, "snooze_minutes" to 10)).ok)
    }

    @Test
    fun anExtensionMayKeepFiftyReminders() {
        tv.install(A, ALL)
        val h = HostHandler(tv, RateLimiter(10_000, 60_000L))
        repeat(HostLimits.MAX_REMINDERS) { assertTrue(add(h).ok) }
        add(h).assertError(HostApi.Error.LIMIT)
        // Another extension has its own fifty.
        tv.install(B, ALL)
        assertTrue(add(h, B).ok)
        // Deleting one makes room.
        h.call(A, HostApi.REMINDERS_DELETE, mapOf("id" to tv.remindersFrom(A).first().id)).let { assertTrue(it.ok) }
        assertTrue(add(h).ok)
    }

    @Test
    fun tooManyChangesInAMinuteAreRefusedButReadsAreNot() {
        tv.install(A, ALL)
        val h = handler()
        repeat(HostLimits.WRITES_PER_MINUTE) { assertTrue("call $it", add(h).ok) }
        add(h).assertError(HostApi.Error.RATE_LIMITED)
        h.call(A, HostApi.REMINDERS_DELETE, mapOf("id" to 1)).assertError(HostApi.Error.RATE_LIMITED)
        assertTrue(h.call(A, HostApi.REMINDERS_LIST, emptyMap()).ok)
        assertTrue(h.call(A, HostApi.INFO, emptyMap()).ok)
        // One extension's flood doesn't use up another's allowance, and a minute later it is allowed again.
        tv.install(B, ALL)
        assertTrue(add(h, B).ok)
        tv.elapsed += 60_000L
        assertTrue(add(h).ok)
    }

    // ---------- cards ----------

    @Test
    fun aCardIsLimitedToOneAMinuteAndCheckedFirst() {
        tv.install(A, ALL)
        val h = handler()
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "")).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "x".repeat(61))).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "x", "text" to "y".repeat(141))).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "x", "text" to 5)).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "x", "text" to "a\u0000b")).assertError(HostApi.Error.BAD_ARGUMENT)
        assertTrue(tv.cardsShown.isEmpty())
        // Refused cards didn't use up the minute.
        assertTrue(h.call(A, HostApi.CARD_SHOW, mapOf("title" to "Garage open", "text" to "Sent by A", "seconds" to 100_000)).ok)
        h.call(A, HostApi.CARD_SHOW, mapOf("title" to "Again")).assertError(HostApi.Error.RATE_LIMITED)
        assertEquals(1, tv.cardsShown.size)
        // It is labelled with the extension's name, and the time is capped.
        assertEquals(listOf("Ext $A", "Garage open", "Sent by A", HostLimits.MAX_CARD_SECONDS.toString()), tv.cardsShown[0])
        tv.elapsed += HostLimits.CARD_INTERVAL_MS
        assertTrue(h.call(A, HostApi.CARD_SHOW, mapOf("title" to "Later")).ok)
    }

    @Test
    fun whenTheCardCantBeShownTheAnswerSaysSo() {
        tv.install(A, ALL); tv.cardWorks = false
        handler().call(A, HostApi.CARD_SHOW, mapOf("title" to "x")).assertError(HostApi.Error.UNAVAILABLE)
    }

    // ---------- home screen items ----------

    private val declared = listOf(
        ExtensionItem("lights", ItemArea.BAR, "Lights", ".Lights", 0),
        ExtensionItem("door", ItemArea.CLOCK, "Door closed", null, 0)
    )

    @Test
    fun portalsOwnButtonsCannotBeReachedByAnyName() {
        tv.install(A, ALL, declared)
        // Many refused tries in a row: a high allowance, so the rate limit isn't what refuses them.
        val h = HostHandler(tv, RateLimiter(10_000, 60_000L))
        val names = listOf("profile", "search", "settings", "update", "weather", "time", "alert", "btn_profile", "btn_search", "btn_settings",
            "btn_update", "panel_actions", "panel_weather", "Settings", "SETTINGS", " settings", "settings ", "../settings", "")
        for (area in listOf("bar", "clock")) for (name in names) {
            val r = h.call(A, HostApi.ITEMS_REMOVE, mapOf("area" to area, "id" to name))
            assertFalse("removed $name in $area", r.ok)
        }
        assertEquals(0, tv.writes)
        // Adding a line of text with such a name is just a line of the extension's own, within its two items.
        assertTrue(h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "settings", "text" to "My text")).ok)
        val listed = h.call(A, HostApi.ITEMS_LIST, mapOf("area" to "bar")).data["items"] as List<*>
        assertTrue(listed.all { (it as Map<*, *>)["button"] == false || it["id"] == "lights" })
        assertEquals(setOf("lights", "settings"), listed.map { (it as Map<*, *>)["id"] }.toSet())
    }

    @Test
    fun anExtensionNeverHasMoreThanTwoItemsInAnArea() {
        tv.install(A, ALL, declared)
        val h = handler()
        assertTrue(h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "one", "text" to "x")).ok)
        h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "two", "text" to "x")).assertError(HostApi.Error.LIMIT)
        // Editing one of its own is not adding.
        assertTrue(h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "lights", "text" to "Lamps")).ok)
        // The cap on how many changes it keeps.
        val many = HostLimits.MAX_ITEM_CHANGES + 4
        repeat(many) { h.call(A, HostApi.ITEMS_REMOVE, mapOf("area" to "clock", "id" to "door")); h.call(A, HostApi.ITEMS_SET, mapOf("area" to "clock", "id" to "door", "text" to "d$it")) }
        assertTrue(tv.itemChanges(A).size <= HostLimits.MAX_ITEM_CHANGES)
    }

    @Test
    fun itemChangesAreScopedToTheCallerAndTheirArea() {
        tv.install(A, ALL, declared); tv.install(B, ALL, declared)
        val h = handler()
        assertTrue(h.call(A, HostApi.ITEMS_SET, mapOf("area" to "clock", "id" to "door", "text" to "A's words")).ok)
        val b = h.call(B, HostApi.ITEMS_LIST, mapOf("area" to "clock")).data["items"] as List<*>
        assertEquals("Door closed", (b.single() as Map<*, *>)["text"])
        // A button's label can change but never its screen: there is no way to send one.
        assertTrue(h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "lights", "text" to "Lamps", "activity" to "com.evil.Screen")).ok)
        assertTrue(tv.itemChanges(A).none { it.text?.contains("evil") == true })
        // A new line can't be a button.
        h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "x", "text" to "x", "activity" to ".Evil")).let { assertTrue(it.ok) }
        val listed = h.call(A, HostApi.ITEMS_LIST, mapOf("area" to "bar")).data["items"] as List<*>
        assertEquals(false, (listed.first { (it as Map<*, *>)["id"] == "x" } as Map<*, *>)["button"])
    }

    @Test
    fun badItemValuesAreRefused() {
        tv.install(A, ALL, declared)
        val h = handler()
        val bad = listOf(
            mapOf("area" to "elsewhere", "id" to "a", "text" to "x"), mapOf("area" to 1, "id" to "a", "text" to "x"), mapOf("id" to "a", "text" to "x"),
            mapOf("area" to "bar", "id" to 5, "text" to "x"), mapOf("area" to "bar", "text" to "x"),
            mapOf("area" to "bar", "id" to "Bad Id", "text" to "x"), mapOf("area" to "bar", "id" to "a", "text" to ""),
            mapOf("area" to "bar", "id" to "a", "text" to "x".repeat(41)), mapOf("area" to "bar", "id" to "a", "text" to 9),
            mapOf("area" to "bar", "id" to "lights", "text" to "x".repeat(25)), mapOf("area" to "bar", "id" to "a", "text" to "a\nb")
        )
        bad.forEach { assertFalse("accepted $it", h.call(A, HostApi.ITEMS_SET, it).ok) }
        assertEquals(0, tv.writes)
    }

    // ---------- tile badges ----------

    @Test
    fun aTileCanOnlyBeDecoratedByItsOwnExtensionAndOnlyIfDeclared() {
        tv.install(A, ALL, tiles = setOf("panel")); tv.install(B, ALL, tiles = setOf("other"))
        val h = handler()
        assertTrue(h.call(A, HostApi.TILE_UPDATE, mapOf("id" to "panel", "badge" to "3", "subtitle" to "3 unread")).ok)
        assertEquals(TileDecor("3", "3 unread"), tv.tileDecor[A]!!["panel"])
        // Not a tile it declared: another extension's, Portal's, or made up.
        for (id in listOf("other", "settings", "net.example.b/other", "portal.ext:net.example.b/other", "x", "")) {
            h.call(A, HostApi.TILE_UPDATE, mapOf("id" to id, "badge" to "1")).let { assertFalse("decorated $id", it.ok) }
        }
        assertEquals(setOf("panel"), tv.tileDecor[A]!!.keys)
        assertNull(tv.tileDecor[B])
        assertEquals(1, tv.tileEvents)
    }

    @Test
    fun badgeAndSubtitleAreCheckedClearedAndKeptSeparate() {
        tv.install(A, ALL, tiles = setOf("panel"))
        val h = HostHandler(tv, RateLimiter(10_000, 60_000L))
        fun call(vararg args: Pair<String, Any?>) = h.call(A, HostApi.TILE_UPDATE, mapOf("id" to "panel") + args)
        assertTrue(call("badge" to "9+").ok)
        assertTrue(call("subtitle" to "Front door").ok)
        assertEquals(TileDecor("9+", "Front door"), tv.tileDecor[A]!!["panel"])
        assertTrue(call("badge" to "").ok)                                   // clears just the badge
        assertEquals(TileDecor(null, "Front door"), tv.tileDecor[A]!!["panel"])
        assertTrue(call("subtitle" to "").ok)                                // clearing the last part removes the entry
        assertTrue(tv.tileDecor[A]!!.isEmpty())
        val before = tv.writes
        listOf(
            call(), call("badge" to "1234567"), call("subtitle" to "x".repeat(25)), call("badge" to 5), call("subtitle" to listOf("x")),
            call("badge" to null), call("badge" to "a\nb"), call("subtitle" to "  "), h.call(A, HostApi.TILE_UPDATE, mapOf("badge" to "1")),
            h.call(A, HostApi.TILE_UPDATE, mapOf("id" to 7, "badge" to "1"))
        ).forEach { assertFalse(it.ok) }
        assertEquals(before, tv.writes)
    }

    // ---------- whatever is thrown at it ----------

    @Test
    fun nothingAnExtensionSendsCanMakeItThrowOrAnswerWithAnUnknownError() {
        tv.install(A, ALL, declared, tiles = setOf("panel"))
        val known = setOf(HostApi.Error.NOT_ALLOWED, HostApi.Error.NO_SUCH_METHOD, HostApi.Error.BAD_ARGUMENT, HostApi.Error.LIMIT,
            HostApi.Error.RATE_LIMITED, HostApi.Error.KID_PROFILE, HostApi.Error.NOT_FOUND, HostApi.Error.UNAVAILABLE)
        val methods = listOf(HostApi.INFO, HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.REMINDERS_UPDATE, HostApi.REMINDERS_DELETE,
            HostApi.CARD_SHOW, HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST, HostApi.TILE_UPDATE, "", "x".repeat(10_000), "reminders.add\u0000")
        val keys = listOf("title", "at_millis", "repeat", "tone", "manual", "snooze_minutes", "id", "text", "seconds", "area", "id", "source", "badge", "subtitle", "x")
        val values = listOf<Any?>(null, "", "x", "bar", "clock", 0, 1, -1, Int.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, true, false, 1.5, "x".repeat(100_000),
            listOf("a"), mapOf("a" to 1), intArrayOf(1), Any(), tv.now + 1000, "settings", "lights", "door")
        val random = Random(7)
        val h = HostHandler(tv, RateLimiter(Int.MAX_VALUE, 1), RateLimiter(Int.MAX_VALUE, 1))
        repeat(3000) {
            val args = keys.filter { random.nextBoolean() }.associateWith { values[random.nextInt(values.size)] }
            val method = methods[random.nextInt(methods.size)]
            val reply = h.call(A, method, args)
            assertTrue("$method $args -> ${reply.error}", reply.ok || reply.error in known)
            // Whatever happened, only A's own things exist.
            assertTrue(tv.reminders.all { it.source == A })
            assertTrue(tv.itemChanges.keys.all { it == A })
            assertTrue(tv.tileDecor.keys.all { it == A })
            assertTrue(tv.tileDecor.values.all { m -> m.keys.all { it == "panel" } })
        }
    }

    @Test
    fun aRefusedCallNeverTouchesAnything() {
        tv.install(A, setOf(Capability.CARDS))
        val h = handler()
        h.call(A, HostApi.REMINDERS_ADD, mapOf("title" to "x", "at_millis" to soon)).assertError(HostApi.Error.NOT_ALLOWED)
        h.call(A, HostApi.ITEMS_SET, mapOf("area" to "bar", "id" to "a", "text" to "x")).assertError(HostApi.Error.NOT_ALLOWED)
        assertEquals(0, tv.writes); assertEquals(0, tv.itemEvents); assertNull(tv.itemChanges[A])
    }
}
