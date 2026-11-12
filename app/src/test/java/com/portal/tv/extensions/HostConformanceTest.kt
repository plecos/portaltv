package com.portal.tv.extensions

import com.portal.tv.reminders.Reminder
import com.portal.tv.repo.WeatherRepository
import com.portal.tv.weather.AlertSeverity
import com.portal.tv.weather.WeatherAlert
import com.portal.tv.weather.WeatherDay
import com.portal.tv.weather.WeatherJson
import com.portal.tv.weather.WeatherReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    var weather: WeatherSnapshot? = null
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
    override fun weather() = weather
    var alerts = listOf<WeatherAlert>()
    var metric = true
    override fun alerts() = alerts
    override fun metricUnits() = metric
    var pageBackground = true
    var showAlerts = true
    var refreshes = 0
    var radar: com.portal.tv.weather.ProviderResult<com.portal.tv.weather.RadarIndex> = com.portal.tv.weather.ProviderResult.Failed("No radar")
    var radarPicture: com.portal.tv.weather.ProviderResult<ByteArray> = com.portal.tv.weather.ProviderResult.Failed("No picture")
    val radarAsked = mutableListOf<List<Any>>()
    var spot: Pair<Double, Double>? = null
    override fun pageBackground() = pageBackground
    override fun showAlerts() = showAlerts
    override fun refreshWeather() { refreshes++ }
    override fun radarIndex(lat: Double, lon: Double) = radar
    override fun radarImage(ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): com.portal.tv.weather.ProviderResult<ByteArray> {
        radarAsked += listOf(ref, zoom, x0, y0, w, h)
        return radarPicture
    }
    override fun setSpot(lat: Double, lon: Double) { writes++; spot = lat to lon }
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
            Capability.TILES to listOf(HostApi.TILE_UPDATE),
            Capability.WEATHER to listOf(HostApi.WEATHER_GET)
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
        assertEquals(listOf("Ext $A · $A", "Garage open", "Sent by A", HostLimits.MAX_CARD_SECONDS.toString()), tv.cardsShown[0])
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
            HostApi.CARD_SHOW, HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST, HostApi.TILE_UPDATE, HostApi.WEATHER_GET, HostApi.WEATHER_REPORT, HostApi.WEATHER_ALERTS, "", "x".repeat(10_000), "reminders.add\u0000")
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

    // ---------- weather ----------

    private fun report(code: Int = 61, lat: Double = 47.6062, lon: Double = -122.3321) = WeatherReport(
        city = "Seattle", tempC = 11.5, feelsLikeC = 10.0, humidity = 80, windKmh = 14.0, windDirection = 225, code = code, isDay = true,
        hours = emptyList(),
        days = listOf(WeatherDay(java.time.LocalDate.of(2023, 11, 14), code, 12.0, 6.0, 60, "2023-11-14T07:05", "2023-11-14T16:30")),
        latitude = lat, longitude = lon, utcOffsetSeconds = -28800, cloudCover = 90, visibilityKm = 8.0
    )

    @Test
    fun weatherNeedsItsOwnCapabilityButAnyProfileMayHaveIt() {
        tv.weather = WeatherSnapshot(report(), tv.now - 60_000)
        tv.install(A, ALL - Capability.WEATHER)
        handler().call(A, HostApi.WEATHER_GET, emptyMap()).assertError(HostApi.Error.NOT_ALLOWED)
        tv.install(A, setOf(Capability.WEATHER))
        assertTrue(handler().call(A, HostApi.WEATHER_GET, emptyMap()).ok)
        // Kids see the weather on Portal's own page, so a kid profile (or none at all) is no reason to refuse it.
        tv.profile = HostProfile(2, true)
        assertTrue(handler().call(A, HostApi.WEATHER_GET, emptyMap()).ok)
        tv.profile = null
        assertTrue(handler().call(A, HostApi.WEATHER_GET, emptyMap()).ok)
    }

    @Test
    fun weatherAnswersWithNumbersAndAnApproximatePlace() {
        tv.weather = WeatherSnapshot(report(), tv.now - 60_000)
        tv.install(A, setOf(Capability.WEATHER))
        val reply = handler().call(A, HostApi.WEATHER_GET, emptyMap())
        assertTrue(reply.ok)
        val d = reply.data
        assertEquals("rain", d["condition"])
        assertEquals(47.6, d["latitude"] as Double, 0.0)      // a tenth of a degree: about 10 km
        assertEquals(-122.3, d["longitude"] as Double, 0.0)
        assertEquals(false, d["stale"])
        assertEquals(0.35, d["rain"] as Double, 0.0)
        assertTrue((d["cloud"] as Double) >= 0.7)
        assertEquals(-28800, d["utc_offset_seconds"])
        // The moments are the local times at the place's offset.
        assertEquals(java.time.LocalDateTime.parse("2023-11-14T07:05").toInstant(java.time.ZoneOffset.ofHours(-8)).toEpochMilli(), d["sunrise_millis"])
        // It carries nothing that could name the place more closely.
        assertFalse(d.keys.any { it == "city" || it == "name" || it == "address" })
    }

    @Test
    fun weatherIsMarkedStaleWhenOldAndUnavailableWhenThereIsNone() {
        tv.install(A, setOf(Capability.WEATHER))
        handler().call(A, HostApi.WEATHER_GET, emptyMap()).assertError(HostApi.Error.UNAVAILABLE)
        tv.weather = WeatherSnapshot(report(), tv.now - 4 * 60 * 60 * 1000L)
        assertEquals(true, handler().call(A, HostApi.WEATHER_GET, emptyMap()).data["stale"])
    }

    @Test
    fun anUnknownPlaceIsLeftOutAndWeatherReadsAreNotCountedAsChanges() {
        tv.weather = WeatherSnapshot(report(lat = 0.0, lon = 0.0), tv.now)
        tv.install(A, setOf(Capability.WEATHER))
        val h = handler()
        repeat(60) { assertTrue(h.call(A, HostApi.WEATHER_GET, emptyMap()).let { r -> r.ok && !r.data.containsKey("latitude") }) }
    }

    // ---------- weather page: the full report and the alerts ----------

    @Test
    fun theFullReportAndAlertsNeedTheWeatherPageCapabilityNotJustWeather() {
        tv.weather = WeatherSnapshot(report(), tv.now - 60_000)
        tv.install(A, ALL - Capability.WEATHER_PAGE)
        handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).assertError(HostApi.Error.NOT_ALLOWED)
        handler().call(A, HostApi.WEATHER_ALERTS, emptyMap()).assertError(HostApi.Error.NOT_ALLOWED)
        tv.install(A, setOf(Capability.WEATHER_PAGE))
        assertTrue(handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).ok)
        assertTrue(handler().call(A, HostApi.WEATHER_ALERTS, emptyMap()).ok)
        // A weather page is not given the plain numbers unless it also asked for them.
        handler().call(A, HostApi.WEATHER_GET, emptyMap()).assertError(HostApi.Error.NOT_ALLOWED)
    }

    @Test
    fun theReportIsGivenInPortalsFormatWithThePlaceRoundedAndTheUnitsTheyChose() {
        tv.weather = WeatherSnapshot(report(), tv.now - 60_000)
        tv.metric = false
        tv.install(A, setOf(Capability.WEATHER_PAGE))
        val data = handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).data
        val back = WeatherJson.decodeReport(data["json"] as String)
        assertEquals("Seattle", back.city)
        assertEquals(47.6, back.latitude, 0.0)       // a tenth of a degree, as weather.get
        assertEquals(-122.3, back.longitude, 0.0)
        assertEquals(11.5, back.tempC, 0.0)
        assertEquals(false, data["metric"])
        assertEquals(false, data["stale"])
        assertEquals(tv.now - 60_000, data["as_of_millis"])
    }

    @Test
    fun theReportIsMarkedOldAndUnavailableWhenThereIsNone() {
        tv.install(A, setOf(Capability.WEATHER_PAGE))
        handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).assertError(HostApi.Error.UNAVAILABLE)
        tv.weather = WeatherSnapshot(report(), tv.now - 4 * 60 * 60 * 1000L)
        assertEquals(true, handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).data["stale"])
    }

    @Test
    fun alertsAreGivenInTheSameFormatASourceSendsAndNoneIsAnEmptyList() {
        tv.install(A, setOf(Capability.WEATHER_PAGE))
        assertEquals(emptyList<WeatherAlert>(), WeatherJson.decodeAlerts(handler().call(A, HostApi.WEATHER_ALERTS, emptyMap()).data["json"] as String))
        tv.alerts = listOf(WeatherAlert("a1", "Flood Warning", AlertSeverity.SEVERE, "h", "d", "i", "area", null, null, "NWS", true))
        val back = WeatherJson.decodeAlerts(handler().call(A, HostApi.WEATHER_ALERTS, emptyMap()).data["json"] as String)
        assertEquals(tv.alerts, back)
    }

    @Test
    fun aWeatherPageIsServedOnAnyProfileAndReadsAreNotChanges() {
        tv.weather = WeatherSnapshot(report(), tv.now)
        tv.install(A, setOf(Capability.WEATHER_PAGE))
        tv.profile = HostProfile(2, true)
        assertTrue(handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).ok)
        tv.profile = null
        val h = handler()
        repeat(60) { assertTrue(h.call(A, HostApi.WEATHER_ALERTS, emptyMap()).ok) }
        assertEquals(0, tv.writes)
    }

    // ---------- weather page: radar, refresh and the spot ----------

    private val page = setOf(Capability.WEATHER_PAGE)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun radarAndTheSpotNeedTheWeatherPageCapability() {
        tv.install(A, ALL - Capability.WEATHER_PAGE)
        val h = handler()
        h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to 47.6, "lon" to -122.3)).assertError(HostApi.Error.NOT_ALLOWED)
        h.call(A, HostApi.WEATHER_RADAR_IMAGE, emptyMap()).assertError(HostApi.Error.NOT_ALLOWED)
        h.call(A, HostApi.WEATHER_SPOT, mapOf("lat" to 47.6, "lon" to -122.3)).assertError(HostApi.Error.NOT_ALLOWED)
        assertNull(tv.spot)
    }

    @Test
    fun theRadarIndexComesBackAsJsonForAValidPlaceOnly() {
        tv.radar = com.portal.tv.weather.ProviderResult.Ok(com.portal.tv.weather.RadarIndex("Radar: x", 10, 7, 300, listOf(com.portal.tv.weather.RadarFrame(1L, "r1"))))
        tv.install(A, page)
        val h = handler()
        val back = WeatherJson.decodeRadar(h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to 47.6, "lon" to -122.3)).data["json"] as String)
        assertEquals("Radar: x", back.credit)
        h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to 95.0, "lon" to 0.0)).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to 10.0)).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to "10", "lon" to "10")).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.WEATHER_RADAR, mapOf("lat" to Double.NaN, "lon" to 1.0)).assertError(HostApi.Error.BAD_ARGUMENT)
    }

    @Test
    fun aSourceWithNoRadarIsUnavailableNotAnError() {
        tv.install(A, page)
        handler().call(A, HostApi.WEATHER_RADAR, mapOf("lat" to 1.0, "lon" to 1.0)).assertError(HostApi.Error.UNAVAILABLE)
    }

    private fun picture(vararg extra: Pair<String, Any?>) =
        mapOf<String, Any?>("ref" to "nws:t", "z" to 7, "x0" to 100.0, "y0" to 200.0, "w" to 640, "h" to 360) + extra

    @Test
    fun aRadarPictureIsPassedOnWithEveryArgumentChecked() {
        tv.radarPicture = com.portal.tv.weather.ProviderResult.Ok(png)
        tv.install(A, page)
        val h = handler()
        val reply = h.call(A, HostApi.WEATHER_RADAR_IMAGE, picture())
        assertTrue(reply.ok)
        assertTrue((reply.data["image"] as ByteArray).contentEquals(png))
        assertEquals(listOf<Any>("nws:t", 7, 100.0, 200.0, 640, 360), tv.radarAsked.single())
    }

    @Test
    fun aRadarPictureWithABadArgumentNeverReachesTheSource() {
        tv.radarPicture = com.portal.tv.weather.ProviderResult.Ok(png)
        tv.install(A, page)
        val h = handler()
        for (bad in listOf(
            picture("ref" to ""), picture("ref" to "x".repeat(500)), picture("ref" to "bad\u202Eref"), picture("ref" to 5),
            picture("z" to 13), picture("z" to -1), picture("w" to 0), picture("h" to 5000), picture("w" to "640"),
            picture("x0" to Double.NaN), picture("y0" to 1e12), picture("x0" to 5)
        )) {
            h.call(A, HostApi.WEATHER_RADAR_IMAGE, bad).assertError(HostApi.Error.BAD_ARGUMENT)
        }
        assertTrue(tv.radarAsked.isEmpty())
    }

    @Test
    fun radarCallsAreCountedApartFromOtherCallsAndLimitedOnTheirOwn() {
        tv.radarPicture = com.portal.tv.weather.ProviderResult.Ok(png)
        tv.install(A, page + Capability.WEATHER)
        val h = handler()
        // A dozen pictures for one view is normal and costs nothing against the ordinary limit.
        repeat(HostLimits.CALLS_PER_MINUTE) { assertTrue(h.admit(A, HostApi.WEATHER_RADAR_IMAGE)) }
        assertTrue(h.admit(A, HostApi.WEATHER_GET))
        // But radar has its own ceiling.
        var allowed = HostLimits.CALLS_PER_MINUTE
        while (allowed < HostLimits.RADAR_PER_MINUTE + 5 && h.admit(A, HostApi.WEATHER_RADAR_IMAGE)) allowed++
        assertEquals(HostLimits.RADAR_PER_MINUTE, allowed)
    }

    @Test
    fun askingForFreshWeatherIsCountedAsAChange() {
        tv.weather = WeatherSnapshot(report(), tv.now)
        tv.install(A, page)
        val h = HostHandler(tv, RateLimiter(2, 60_000L), RateLimiter(1, 60_000L))
        assertTrue(h.call(A, HostApi.WEATHER_REPORT, mapOf("refresh" to true)).ok)
        assertTrue(h.call(A, HostApi.WEATHER_ALERTS, mapOf("refresh" to true)).ok)
        h.call(A, HostApi.WEATHER_REPORT, mapOf("refresh" to true)).assertError(HostApi.Error.RATE_LIMITED)
        assertEquals(2, tv.refreshes)
        // Reading what Portal already holds is not limited that way.
        assertTrue(h.call(A, HostApi.WEATHER_REPORT, emptyMap()).ok)
        assertEquals(2, tv.refreshes)
    }

    @Test
    fun theReportCarriesWhatThePersonChoseForThePage() {
        tv.weather = WeatherSnapshot(report(), tv.now)
        tv.pageBackground = false
        tv.showAlerts = false
        tv.install(A, page)
        val d = handler().call(A, HostApi.WEATHER_REPORT, emptyMap()).data
        assertEquals(false, d["page_background"])
        assertEquals(false, d["show_alerts"])
    }

    @Test
    fun theSpotIsSavedOnAnAdultsProfileOnlyAndCountsAsAChange() {
        tv.install(A, page)
        val h = handler()
        assertTrue(h.call(A, HostApi.WEATHER_SPOT, mapOf("lat" to 47.61, "lon" to -122.33)).ok)
        assertEquals(47.61 to -122.33, tv.spot)
        h.call(A, HostApi.WEATHER_SPOT, mapOf("lat" to 95.0, "lon" to 0.0)).assertError(HostApi.Error.BAD_ARGUMENT)
        h.call(A, HostApi.WEATHER_SPOT, mapOf("lat" to 1)).assertError(HostApi.Error.BAD_ARGUMENT)
        tv.spot = null
        tv.profile = HostProfile(2, true)
        h.call(A, HostApi.WEATHER_SPOT, mapOf("lat" to 47.61, "lon" to -122.33)).assertError(HostApi.Error.KID_PROFILE)
        assertNull(tv.spot)
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

/** Fixes from the review of the whole branch. */
class HostReviewFixesTest {

    @Test
    fun theHandlerKnowsOnlyInstalledExtensions() {
        val tv = FakeTv().apply { install(A, ALL) }
        val h = HostHandler(tv)
        assertNotNull(h.lookup(A))
        assertNull(h.lookup("net.example.stranger"))
    }

    @Test
    fun theArgumentNamesAreTheOnesTheApiDefines() {
        assertTrue(HostApi.ARG_KEYS.containsAll(listOf("title", "at_millis", "repeat", "tone", "manual", "snooze_minutes", "id", "seconds", "area", "text", "badge", "subtitle")))
        assertEquals(HostApi.ARG_KEYS.size, HostApi.ARG_KEYS.toSet().size)
    }

    @Test
    fun parallelAddsNeverPassTheReminderLimit() {
        val tv = FakeTv().apply { install(A, ALL) }
        val h = HostHandler(tv, writes = RateLimiter(100_000, 60_000L))
        val at = tv.now + 3_600_000L
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..8).map { t ->
            Thread { repeat(10) { n -> if (h.call(A, HostApi.REMINDERS_ADD, mapOf("title" to "r$t-$n", "at_millis" to at)).ok) ok.incrementAndGet() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(HostLimits.MAX_REMINDERS, ok.get())
        assertEquals(HostLimits.MAX_REMINDERS, tv.remindersFrom(A).size)
    }

    @Test
    fun anyKindOfCallIsLimitedBeforeAnythingIsLookedUp() {
        val tv = FakeTv().apply { install(A, ALL); install(B, ALL) }
        val h = HostHandler(tv)
        repeat(HostLimits.CALLS_PER_MINUTE) { assertTrue(h.admit(A)) }
        assertFalse(h.admit(A))
        // Each extension has its own allowance, and a minute later it is back.
        assertTrue(h.admit(B))
        tv.elapsed += 60_000L
        assertTrue(h.admit(A))
    }

    @Test
    fun aHostCallWithAnExtensionAlreadyLookedUpDoesNotLookItUpAgain() {
        val tv = CountingTv(FakeTv().apply { install(A, ALL) })
        val h = HostHandler(tv)
        val ext = h.lookup(A)!!
        repeat(5) { h.call(ext, HostApi.INFO, emptyMap()) }
        assertEquals(1, tv.lookups)
    }
}

/** A fake TV that counts how often an extension is looked up. */
private class CountingTv(private val inner: FakeTv) : HostEnv by inner {
    var lookups = 0
    override fun extension(packageName: String): HostExtension? { lookups++; return inner.extension(packageName) }
}
