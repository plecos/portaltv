package com.portal.tv.extensions

import com.portal.tv.BuildConfig
import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderTones
import com.portal.tv.weather.WeatherAlert
import com.portal.tv.weather.WeatherJson

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
    /** The alerts Portal last fetched that are still in force (no network is touched). */
    fun alerts(): List<WeatherAlert>
    /** Whether the person shows the weather in metric units; a weather page follows it. */
    fun metricUnits(): Boolean
    /** Whether the person wants the weather page to have an animated scene behind it. */
    fun pageBackground(): Boolean
    /** Whether the person wants weather alerts shown on the weather page. */
    fun showAlerts(): Boolean
    /** Asks the weather source for fresh weather and alerts now, and waits for it (a little). The result is read afterwards with [weather] and [alerts]. */
    fun refreshWeather()
    /** The radar pictures there are around ([lat], [lon]), from the weather source. */
    fun radarIndex(lat: Double, lon: Double): com.portal.tv.weather.ProviderResult<com.portal.tv.weather.RadarIndex>
    /** One radar picture (PNG bytes) from the weather source; see [com.portal.tv.weather.WeatherProvider.radarImage]. */
    fun radarImage(ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): com.portal.tv.weather.ProviderResult<ByteArray>
    /** Saves an exact spot, such as one picked on the radar map, as the place the weather is for. */
    fun setSpot(lat: Double, lon: Double)
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
    private val radarCalls = RateLimiter(HostLimits.RADAR_PER_MINUTE, 60_000L)

    /** Whether [caller] may make a call to [method] now at all. Checked first, because everything after it costs something. */
    fun admit(caller: String, method: String = ""): Boolean =
        if (method == HostApi.WEATHER_RADAR || method == HostApi.WEATHER_RADAR_IMAGE) radarCalls.allow(caller, env.elapsedMillis())
        else callLimit.allow(caller, env.elapsedMillis())

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
            HostApi.WEATHER_REPORT, HostApi.WEATHER_ALERTS, HostApi.WEATHER_RADAR, HostApi.WEATHER_RADAR_IMAGE, HostApi.WEATHER_SPOT -> Capability.WEATHER_PAGE
            HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST -> area(args)?.capability ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
            else -> return HostReply.fail(HostApi.Error.NO_SUCH_METHOD)
        }
        if (needs !in ext.granted) return HostReply.fail(HostApi.Error.NOT_ALLOWED)
        // The weather is shown to everyone on Portal's own weather page, kids included, so it is given to whoever is using the TV.
        if (method == HostApi.WEATHER_GET) return weather()
        if (method == HostApi.WEATHER_REPORT) return weatherReport(ext, args)
        if (method == HostApi.WEATHER_ALERTS) return weatherAlerts(ext, args)
        if (method == HostApi.WEATHER_RADAR) return radarIndex(args)
        if (method == HostApi.WEATHER_RADAR_IMAGE) return radarImage(args)
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
            HostApi.WEATHER_SPOT -> setSpot(args)
            else -> items(ext, method, args)
        }
    }

    // ---------- values from the caller: anything can be passed, so each is checked for its type ----------

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Int)
    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) { is Long -> v; is Int -> v.toLong(); else -> null }
    private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
    private fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) { is Double -> v.takeIf { !it.isNaN() && !it.isInfinite() }; else -> null }

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

    /**
     * The whole report, for a weather page: Portal's own format (see [WeatherJson]), with the place rounded to a tenth of a
     * degree like `weather.get` (the city's name is the one the person chose or Portal worked out, so it is given as it is).
     */
    private fun weatherReport(ext: HostExtension, args: Map<String, Any?>): HostReply {
        refreshIfAsked(ext, args)?.let { return it }
        val snapshot = env.weather() ?: return HostReply.fail(HostApi.Error.UNAVAILABLE)
        val r = snapshot.report
        val rounded = r.copy(latitude = WeatherForExtensions.tenth(r.latitude), longitude = WeatherForExtensions.tenth(r.longitude))
        val now = env.nowMillis()
        return HostReply.ok(
            mapOf(
                "json" to WeatherJson.encode(rounded),
                "as_of_millis" to snapshot.updatedAtMs,
                "stale" to (now - snapshot.updatedAtMs > WeatherForExtensions.STALE_AFTER_MS),
                "metric" to env.metricUnits(),
                "page_background" to env.pageBackground(),
                "show_alerts" to env.showAlerts()
            )
        )
    }

    /** The alerts in force, for a weather page, in the same format an alert source sends (see [WeatherJson]). */
    private fun weatherAlerts(ext: HostExtension, args: Map<String, Any?>): HostReply {
        refreshIfAsked(ext, args)?.let { return it }
        return HostReply.ok(mapOf("json" to WeatherJson.encodeAlerts(env.alerts())))
    }

    /** When the caller asks for fresh weather (`refresh`), has the source asked now, counted as a change so it can't be hammered; null when all is well. */
    private fun refreshIfAsked(ext: HostExtension, args: Map<String, Any?>): HostReply? {
        if (args.bool("refresh") != true) return null
        if (!writes.allow(ext.packageName, env.elapsedMillis())) return HostReply.fail(HostApi.Error.RATE_LIMITED)
        env.refreshWeather()
        return null
    }

    /** A latitude and longitude from the arguments, or null if either is missing, the wrong type or off the map. */
    private fun place(args: Map<String, Any?>): Pair<Double, Double>? {
        val lat = args.double("lat") ?: return null
        val lon = args.double("lon") ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    /** The radar pictures there are around a place, from the weather source in use (see [com.portal.tv.weather.RadarIndex]). */
    private fun radarIndex(args: Map<String, Any?>): HostReply {
        val (lat, lon) = place(args) ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        return when (val r = env.radarIndex(lat, lon)) {
            is com.portal.tv.weather.ProviderResult.Ok -> HostReply.ok(mapOf("json" to WeatherJson.encodeRadar(r.value)))
            is com.portal.tv.weather.ProviderResult.Failed -> HostReply.fail(HostApi.Error.UNAVAILABLE)
        }
    }

    /** One radar picture, as PNG bytes in `image`, for a frame of the index and a window of the map. Every argument is checked. */
    private fun radarImage(args: Map<String, Any?>): HostReply {
        val ref = args.string("ref")
        if (ref == null || ref.isEmpty() || ref.length > com.portal.tv.weather.WeatherValidator.MAX_REF || !TextSafety.isPlain(ref)) return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val zoom = args.int("z")?.takeIf { it in 0..12 } ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val side = com.portal.tv.weather.WeatherProtocol.MAX_IMAGE_SIDE
        val w = args.int("w")?.takeIf { it in 1..side } ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val h = args.int("h")?.takeIf { it in 1..side } ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val x0 = args.double("x0")?.takeIf { Math.abs(it) <= 1e8 } ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        val y0 = args.double("y0")?.takeIf { Math.abs(it) <= 1e8 } ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        return when (val r = env.radarImage(ref, zoom, x0, y0, w, h)) {
            is com.portal.tv.weather.ProviderResult.Ok -> HostReply.ok(mapOf("image" to r.value))
            is com.portal.tv.weather.ProviderResult.Failed -> HostReply.fail(HostApi.Error.UNAVAILABLE)
        }
    }

    /** Saves an exact spot (such as one picked on the radar) as the place the weather is for. Only on an adult's profile, and counted as a change. */
    private fun setSpot(args: Map<String, Any?>): HostReply {
        val (lat, lon) = place(args) ?: return HostReply.fail(HostApi.Error.BAD_ARGUMENT)
        env.setSpot(lat, lon)
        return HostReply.ok()
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
