package com.portal.tv.extensions

import com.portal.tv.BuildConfig
import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderTones

/** An answer to a host call: `ok`, or `error` from [HostApi.Error], plus any values (strings, numbers, lists of strings or of maps). */
class HostReply(val ok: Boolean, val error: String? = null, val data: Map<String, Any?> = emptyMap()) {
    companion object {
        fun ok(data: Map<String, Any?> = emptyMap()) = HostReply(true, null, data)
        fun fail(error: String) = HostReply(false, error)
    }
}

/** An installed extension as the host API sees it. */
class HostExtension(
    val packageName: String,
    val name: String,
    /** What it may do right now (empty if not allowed or its signer changed). */
    val granted: Set<String>,
    /** The home screen items it declared that Portal can use. */
    val declaredItems: List<ExtensionItem>,
    /** The ids of the tiles it declared. */
    val declaredTiles: Set<String> = emptySet()
)

class HostProfile(val id: Int, val isKids: Boolean)

/** Everything the host API touches outside itself, so its rules can be tested without Android. */
interface HostEnv {
    fun nowMillis(): Long
    fun elapsedMillis(): Long
    /** The extension installed as [packageName], or null if there isn't one. */
    fun extension(packageName: String): HostExtension?
    fun activeProfile(): HostProfile?
    fun remindersFrom(source: String): List<Reminder>
    fun addReminder(profileId: Int, title: String, atMillis: Long, like: Reminder): Reminder
    fun updateReminder(reminder: Reminder)
    fun removeReminder(id: Int)
    fun itemChanges(packageName: String): List<ItemOverride>
    fun saveItemChanges(packageName: String, changes: List<ItemOverride>)
    fun itemsChanged()
    fun tileDecorations(packageName: String): Map<String, TileDecor>
    fun saveTileDecorations(packageName: String, decorations: Map<String, TileDecor>)
    fun tilesChanged()
    fun showCard(from: String, title: String, text: String, seconds: Int): Boolean
    /** The weather Portal last fetched (no network is touched), or null if it has none. */
    fun weather(): WeatherSnapshot?
}

/**
 * The rules of the host API: who may call what, and what each call does. [caller] is whoever the system says is calling
 * (the provider works that out; nothing the caller sends can change it). Every call is checked, in this order, before
 * anything happens: the caller is an installed extension, the method exists, an adult allowed it the capability the call
 * needs, no kid profile is in use, and it hasn't made too many changes lately.
 */
class HostHandler(
    private val env: HostEnv,
    private val writes: RateLimiter = RateLimiter(HostLimits.WRITES_PER_MINUTE, 60_000L),
    private val cards: RateLimiter = RateLimiter(1, HostLimits.CARD_INTERVAL_MS)
) {

    /** Calls of any kind (reads too) an extension may make, counted before anything is looked up. */
    private val callLimit = RateLimiter(HostLimits.CALLS_PER_MINUTE, 60_000L)

    /** Whether [caller] may make a call now at all. Checked first, because everything after it costs something. */
    fun admit(caller: String): Boolean = callLimit.allow(caller, env.elapsedMillis())

    /** The installed extension [caller] is, or null. Looked up once per call; see [call] taking it. */
    fun lookup(caller: String): HostExtension? = env.extension(caller)

    /** One at a time, so two calls can't both see room for a reminder and both add one. */
    private val remindersLock = Any()

    fun call(caller: String, method: String, args: Map<String, Any?>): HostReply {
        val ext = lookup(caller) ?: return HostReply.fail(HostApi.Error.NOT_ALLOWED)
        return call(ext, method, args)
    }

    /** As [call], for an extension that has already been looked up. */
    fun call(ext: HostExtension, method: String, args: Map<String, Any?>): HostReply {
        val caller = ext.packageName
        if (method == HostApi.INFO) return info(ext)
        val needs = when (method) {
            HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.REMINDERS_UPDATE, HostApi.REMINDERS_DELETE -> Capability.REMINDERS
            HostApi.CARD_SHOW -> Capability.CARDS
            HostApi.TILE_UPDATE -> Capability.TILES
            HostApi.WEATHER_GET -> Capability.WEATHER
            HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST -> area(args)?.capability ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
            else -> return HostReply.fail(HostApi.Error.NO_SUCH_METHOD)
        }
        if (needs !in ext.granted) return HostReply.fail(HostApi.Error.NOT_ALLOWED)
        // The weather is shown to everyone on Portal's own weather page, kids included, so it is given to whoever is using the TV.
        if (method == HostApi.WEATHER_GET) return weather()
        val profile = env.activeProfile() ?: return HostReply.fail(HostApi.Error.UNAVAILABLE)
        if (profile.isKids) return HostReply.fail(HostApi.Error.KID_PROFILE)
        val changes = method != HostApi.REMINDERS_LIST && method != HostApi.ITEMS_LIST
        if (changes && !writes.allow(caller, env.elapsedMillis())) return HostReply.fail(HostApi.Error.RATE_LIMITED)
        return when (method) {
            HostApi.REMINDERS_ADD -> addReminder(ext, profile.id, args)
            HostApi.REMINDERS_LIST -> listReminders(ext)
            HostApi.REMINDERS_UPDATE -> updateReminder(ext, args)
            HostApi.REMINDERS_DELETE -> deleteReminder(ext, args)
            HostApi.CARD_SHOW -> showCard(ext, args)
            HostApi.TILE_UPDATE -> updateTile(ext, args)
            else -> items(ext, method, args)
        }
    }

    // ---------- values from the caller: anything can be passed, so each is checked for its type ----------

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Int)
    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) { is Long -> v; is Int -> v.toLong(); else -> null }
    private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean

    private fun area(args: Map<String, Any?>): ItemArea? = ItemArea.values().firstOrNull { it.key == args.string("area") }

    // ---------- host.info ----------

    private fun info(ext: HostExtension) = HostReply.ok(
        mapOf(
            "contract" to ManifestParser.CONTRACT_VERSION,
            "portal_version_code" to BuildConfig.VERSION_CODE,
            "granted" to ext.granted.toList(),
            "max_reminders" to HostLimits.MAX_REMINDERS
        )
    )

    // ---------- weather ----------

    private fun weather(): HostReply {
        val snapshot = env.weather() ?: return HostReply.fail(HostApi.Error.UNAVAILABLE)
        return HostReply.ok(WeatherForExtensions.from(snapshot, env.nowMillis()))
    }

    // ---------- reminders ----------

    private fun addReminder(ext: HostExtension, profileId: Int, args: Map<String, Any?>): HostReply {
        val title = args.string("title")
        HostChecks.text(title, HostLimits.MAX_REMINDER_TITLE)?.let { return HostReply.fail(it) }
        val at = args.long("at_millis") ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        HostChecks.reminderTime(at, env.nowMillis())?.let { return HostReply.fail(it) }
        val like = reminderFields(args, Reminder(0, profileId, "", at, source = ext.packageName)) ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val made = synchronized(remindersLock) {
            if (env.remindersFrom(ext.packageName).size >= HostLimits.MAX_REMINDERS) return HostReply.fail(HostApi.Error.LIMIT)
            env.addReminder(profileId, title!!, at, like)
        }
        return HostReply.ok(mapOf("id" to made.id))
    }

    /** [base] with the optional fields in [args] applied, or null if one is wrong. */
    private fun reminderFields(args: Map<String, Any?>, base: Reminder): Reminder? {
        var r = base
        // A value of the wrong type is refused, never read as "not given".
        if (args.containsKey("repeat")) r = r.copy(repeat = HostChecks.repeat(args.string("repeat") ?: return null) ?: return null)
        if (args.containsKey("tone")) {
            val tone = args.string("tone")
            if (tone == null || !HostChecks.tone(tone)) return null
            r = r.copy(tone = tone)
        }
        if (args.containsKey("manual")) r = r.copy(manual = args.bool("manual") ?: return null)
        if (args.containsKey("snooze_minutes")) {
            val minutes = args.int("snooze_minutes") ?: return null
            if (!HostChecks.snooze(minutes)) return null
            r = r.copy(snoozeMinutes = minutes)
        }
        return r
    }

    private fun listReminders(ext: HostExtension) = HostReply.ok(
        mapOf("reminders" to env.remindersFrom(ext.packageName).map { r ->
            mapOf(
                "id" to r.id, "title" to r.title, "at_millis" to r.atMillis,
                "repeat" to r.repeat.name.lowercase(), "tone" to r.tone,
                "manual" to r.manual, "snooze_minutes" to r.snoozeMinutes
            )
        })
    )

    private fun updateReminder(ext: HostExtension, args: Map<String, Any?>): HostReply {
        val id = args.int("id") ?: return HostReply.fail(HostApi.Error.NOT_FOUND)
        val own = env.remindersFrom(ext.packageName).firstOrNull { it.id == id } ?: return HostReply.fail(HostApi.Error.NOT_FOUND)
        var r = reminderFields(args, own) ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        if (args.containsKey("title")) {
            HostChecks.text(args.string("title"), HostLimits.MAX_REMINDER_TITLE)?.let { return HostReply.fail(it) }
            r = r.copy(title = args.string("title")!!.trim())
        }
        if (args.containsKey("at_millis")) {
            val at = args.long("at_millis") ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
            HostChecks.reminderTime(at, env.nowMillis())?.let { return HostReply.fail(it) }
            r = r.copy(atMillis = at)
        }
        // Whatever the caller sent, the reminder stays its own and on the same profile.
        env.updateReminder(r.copy(id = own.id, profileId = own.profileId, source = own.source))
        return HostReply.ok()
    }

    private fun deleteReminder(ext: HostExtension, args: Map<String, Any?>): HostReply {
        val id = args.int("id") ?: return HostReply.fail(HostApi.Error.NOT_FOUND)
        if (env.remindersFrom(ext.packageName).none { it.id == id }) return HostReply.fail(HostApi.Error.NOT_FOUND)
        env.removeReminder(id)
        return HostReply.ok()
    }

    // ---------- cards ----------

    private fun showCard(ext: HostExtension, args: Map<String, Any?>): HostReply {
        val title = args.string("title")
        HostChecks.text(title, HostLimits.MAX_CARD_TITLE)?.let { return HostReply.fail(it) }
        val text = args.string("text").orEmpty()
        if (args.containsKey("text") && args["text"] !is String) return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        if (text.isNotEmpty()) HostChecks.text(text, HostLimits.MAX_CARD_TEXT)?.let { return HostReply.fail(it) }
        if (!cards.allow(ext.packageName, env.elapsedMillis())) return HostReply.fail(HostApi.Error.RATE_LIMITED)
        val shown = env.showCard(ext.name + " · " + ext.packageName, title!!.trim(), text.trim(), HostChecks.cardSeconds(args.int("seconds") ?: 0))
        return if (shown) HostReply.ok() else HostReply.fail(HostApi.Error.UNAVAILABLE)
    }

    // ---------- tile badges ----------

    private fun updateTile(ext: HostExtension, args: Map<String, Any?>): HostReply {
        val id = args.string("id") ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val change = TileDecorations.update(
            ext.declaredTiles, env.tileDecorations(ext.packageName), id,
            args["badge"], args["subtitle"], args.containsKey("badge"), args.containsKey("subtitle")
        )
        return when (change) {
            is TileDecorations.Change.Refused -> HostReply.fail(change.error)
            is TileDecorations.Change.Ok -> {
                env.saveTileDecorations(ext.packageName, change.decorations)
                env.tilesChanged()
                HostReply.ok()
            }
        }
    }

    // ---------- home screen items ----------

    private fun items(ext: HostExtension, method: String, args: Map<String, Any?>): HostReply {
        val area = area(args) ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val saved = env.itemChanges(ext.packageName)
        if (method == HostApi.ITEMS_LIST) return HostReply.ok(
            mapOf("items" to ItemOverrides.apply(ext.declaredItems, saved).filter { it.area == area }.map { item ->
                mapOf("id" to item.id, "text" to item.text, "button" to item.isButton)
            })
        )
        val id = args.string("id") ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val change = if (method == HostApi.ITEMS_SET) ItemOverrides.set(ext.declaredItems, saved, area, id, args.string("text"))
        else ItemOverrides.remove(ext.declaredItems, saved, area, id)
        return when (change) {
            is ItemOverrides.Change.Refused -> HostReply.fail(change.error)
            is ItemOverrides.Change.Ok -> {
                env.saveItemChanges(ext.packageName, change.overrides)
                env.itemsChanged()
                HostReply.ok()
            }
        }
    }
}
