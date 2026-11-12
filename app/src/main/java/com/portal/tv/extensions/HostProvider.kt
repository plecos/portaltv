package com.portal.tv.extensions

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderStore
import com.portal.tv.reminders.ReminderService
import com.portal.tv.repo.ProfileManager

/**
 * The one door extensions use to call Portal (`content://net.getportaltv.host`, through `ContentResolver.call`). It
 * holds no data. Anybody can reach it, so every call is checked before anything happens: the caller is the app Android
 * says it is, it is an installed extension, an adult allowed it the capability the call needs and it is still signed by
 * who they allowed, no kid profile is active, and it hasn't made too many changes lately. Whatever is wrong, the
 * answer is `ok=false` and an `error` code from [HostApi.Error], and nothing is changed.
 */
class HostProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return toBundle(HostReply.fail(HostApi.Error.UNAVAILABLE))
        val caller = verifiedCaller(context) ?: return toBundle(HostReply.fail(HostApi.Error.NOT_ALLOWED))
        return try {
            dispatch(context, caller, method, extras ?: Bundle.EMPTY)
        } catch (e: Exception) {
            android.util.Log.w("Portal", "Host call $method from $caller failed: ${e.message}")
            toBundle(HostReply.fail(HostApi.Error.UNAVAILABLE))
        }
    }

    /** The package Android says is calling, only if that package really owns the caller's uid. */
    private fun verifiedCaller(context: Context): String? {
        val pkg = callingPackage ?: return null
        val owners = context.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return null
        return pkg.takeIf { it in owners }
    }

    // ---------- the provider is only a door: the rules are in HostHandler ----------

    private fun dispatch(context: Context, caller: String, method: String, args: Bundle): Bundle {
        val handler = handler(context)
        // Cheapest first: anybody can reach this door and each step after this one costs more than the one before.
        if (!handler.admit(caller, method)) return toBundle(HostReply.fail(HostApi.Error.RATE_LIMITED))
        // Then: is it an installed extension, found once for the whole call?
        val ext = handler.lookup(caller) ?: return toBundle(HostReply.fail(HostApi.Error.NOT_ALLOWED))
        // Nothing the caller sent is looked at unless an adult has allowed it something. (host.info needs no argument.)
        if (method != HostApi.INFO && ext.granted.isEmpty()) return toBundle(HostReply.fail(HostApi.Error.NOT_ALLOWED))
        // After that only the keys the API defines are read, each with a typed getter: the Bundle's other entries are
        // never unparcelled, so a caller can't make Portal deserialize objects it didn't ask for.
        val values = if (method == HostApi.INFO) emptyMap() else HostArgs.read(args)
        return toBundle(handler.call(ext, method, values))
    }

    private fun handler(context: Context): HostHandler =
        shared ?: synchronized(HostProvider::class.java) { shared ?: HostHandler(RealHostEnv(context.applicationContext)).also { shared = it } }

    private fun toBundle(reply: HostReply): Bundle = Bundle().apply {
        putBoolean("ok", reply.ok)
        reply.error?.let { putString("error", it) }
        reply.data.forEach { (key, value) ->
            when (value) {
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Double -> putDouble(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is ByteArray -> putByteArray(key, value)
                is List<*> -> if (value.all { it is Map<*, *> }) {
                    putParcelableArrayList(key, ArrayList(value.map { m -> toBundle(HostReply.ok(@Suppress("UNCHECKED_CAST") (m as Map<String, Any?>))).apply { remove("ok") } }))
                } else putStringArrayList(key, ArrayList(value.map { it.toString() }))
            }
        }
    }

    // ---------- nothing else is offered ----------

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        /** One handler for the whole process, so the rate limits are shared between calls. */
        @Volatile var shared: HostHandler? = null
    }
}

/**
 * Reads the arguments of a host call out of the Bundle the caller sent. Only [HostApi.ARG_KEYS] are read, each with typed
 * getters, so a value of any other type is `null` ("given, but wrong"; the handler refuses it) and nothing else in the
 * Bundle is touched.
 */
internal object HostArgs {
    fun read(args: Bundle): Map<String, Any?> {
        val values = HashMap<String, Any?>()
        for (key in HostApi.ARG_KEYS) {
            if (!args.containsKey(key)) continue
            values[key] = value(args, key)
        }
        return values
    }

    private fun value(args: Bundle, key: String): Any? {
        args.getString(key)?.let { return it }
        val i = args.getInt(key, Int.MIN_VALUE)
        if (i != Int.MIN_VALUE) return i
        val l = args.getLong(key, Long.MIN_VALUE)
        if (l != Long.MIN_VALUE) return l
        // A real boolean gives the same answer for either default; any other type gives each default back.
        val b = args.getBoolean(key, false)
        if (b == args.getBoolean(key, true)) return b
        // A double, if that is what it is (any other type gives NaN back).
        val d = args.getDouble(key, Double.NaN)
        if (!d.isNaN()) return d
        return null
    }
}

/** The real TV behind [HostEnv]. */
private class RealHostEnv(private val context: Context) : HostEnv {
    override fun nowMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = SystemClock.elapsedRealtime()
    override fun extension(packageName: String): HostExtension? = ExtensionRegistry.find(context, packageName)?.let {
        HostExtension(it.packageName, it.name, it.granted, it.manifest?.items.orEmpty(), it.manifest?.tiles.orEmpty().map { t -> t.id }.toSet())
    }
    override fun activeProfile() = ProfileManager.getActive(context)?.let { HostProfile(it.id, it.isKids) }
    override fun remindersFrom(source: String) = ReminderStore.forSource(context, source)
    override fun addReminder(profileId: Int, title: String, atMillis: Long, like: Reminder) =
        ReminderStore.add(context, profileId, title, atMillis, like.repeat, like = like)
    override fun updateReminder(reminder: Reminder) = ReminderStore.update(context, reminder)
    override fun removeReminder(id: Int) = ReminderStore.remove(context, id)
    override fun itemChanges(packageName: String) = ExtensionItemStore.get(context, packageName)
    override fun saveItemChanges(packageName: String, changes: List<ItemOverride>) = ExtensionItemStore.put(context, packageName, changes)
    override fun itemsChanged() = HostEvents.itemsChanged()
    override fun tileDecorations(packageName: String) = ExtensionTileStore.get(context, packageName)
    override fun saveTileDecorations(packageName: String, decorations: Map<String, TileDecor>) = ExtensionTileStore.put(context, packageName, decorations)
    override fun tilesChanged() = HostEvents.tilesChanged()
    override fun showCard(from: String, title: String, text: String, seconds: Int) = ReminderService.showCard(context, from, title, text, seconds)

    override fun alerts() = com.portal.tv.repo.WeatherAlerts.cached(context)
    override fun metricUnits() = com.portal.tv.settings.AppSettings.isMetric(context)
    override fun pageBackground() = com.portal.tv.settings.BackgroundSettings.getWeatherBackground(context)
    override fun showAlerts() = com.portal.tv.settings.AppSettings.showWeatherAlerts(context)

    override fun refreshWeather() {
        // The call comes in on a binder thread, which may wait: a source that doesn't answer in time is given up on.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(REFRESH_WAIT_MS) {
                com.portal.tv.repo.WeatherRepository.load(context)
                com.portal.tv.repo.WeatherAlerts.load(context, force = true)
            }
        }
    }

    override fun setSpot(lat: Double, lon: Double) = com.portal.tv.repo.WeatherRepository.setCustomSpot(context, lat, lon)

    // The radar pictures Portal has just had from the source are kept briefly, since a page asks again as it is panned and replayed.
    private val radarIndexes = HashMap<String, Pair<Long, com.portal.tv.weather.RadarIndex>>()
    private val radarImages = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        var bytes = 0
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = false
    }

    private fun source() = com.portal.tv.weather.WeatherProviders.active(context)

    @Synchronized
    override fun radarIndex(lat: Double, lon: Double): com.portal.tv.weather.ProviderResult<com.portal.tv.weather.RadarIndex> {
        val key = "%.1f,%.1f".format(java.util.Locale.US, lat, lon)
        val now = SystemClock.elapsedRealtime()
        radarIndexes[key]?.let { (at, index) -> if (now - at < INDEX_KEEP_MS) return com.portal.tv.weather.ProviderResult.Ok(index) }
        val result = kotlinx.coroutines.runBlocking { source().radarIndex(com.portal.tv.weather.WeatherPlace(lat, lon, null)) }
        if (result is com.portal.tv.weather.ProviderResult.Ok) radarIndexes[key] = now to result.value
        return result
    }

    override fun radarImage(ref: String, zoom: Int, x0: Double, y0: Double, w: Int, h: Int): com.portal.tv.weather.ProviderResult<ByteArray> {
        val key = "%s|%d|%.1f|%.1f|%d|%d".format(java.util.Locale.US, ref, zoom, x0, y0, w, h)
        synchronized(radarImages) { radarImages[key]?.let { return com.portal.tv.weather.ProviderResult.Ok(it) } }
        // Asked of the source outside the lock, so several pictures can be fetched at once.
        val result = kotlinx.coroutines.runBlocking { source().radarImage(ref, zoom, x0, y0, w, h) }
        if (result is com.portal.tv.weather.ProviderResult.Ok) synchronized(radarImages) {
            radarImages[key] = result.value
            radarImages.bytes += result.value.size
            val it = radarImages.entries.iterator()
            while (radarImages.bytes > IMAGE_KEEP_BYTES && it.hasNext()) { val e = it.next(); radarImages.bytes -= e.value.size; it.remove() }
        }
        return result
    }

    /** The saved weather, read again only when a new one was saved, because extensions may ask often. */
    private var weatherMemo: Pair<Long, WeatherSnapshot?>? = null

    @Synchronized
    override fun weather(): WeatherSnapshot? {
        val stamp = com.portal.tv.repo.WeatherRepository.cachedStamp(context)
        weatherMemo?.let { if (it.first == stamp) return it.second }
        val snapshot = com.portal.tv.repo.WeatherRepository.cached(context)?.report?.let { WeatherSnapshot(it, stamp) }
        weatherMemo = stamp to snapshot
        return snapshot
    }

    private companion object {
        /** How long a call that asks for fresh weather waits for the source, at most. */
        const val REFRESH_WAIT_MS = 25_000L
        /** How long a list of radar pictures is reused, and how much of the pictures are kept. */
        const val INDEX_KEEP_MS = 2 * 60_000L
        const val IMAGE_KEEP_BYTES = 6_000_000
    }
}
