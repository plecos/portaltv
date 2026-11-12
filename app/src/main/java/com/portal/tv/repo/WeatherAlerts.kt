package com.portal.tv.repo

import android.content.Context
import android.util.Log
import com.portal.tv.weather.ProviderResult
import com.portal.tv.weather.WeatherAlert
import com.portal.tv.weather.WeatherJson
import com.portal.tv.weather.WeatherPlace
import com.portal.tv.weather.WeatherProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

/**
 * Portal's side of weather alerts: what the weather source ([WeatherProviders.active]) last said is kept here, asked for
 * again only when it is a few minutes old or the place changed, and the last good answer is kept if a refresh fails. Which
 * alerts exist, and for where, is the source's business.
 */
object WeatherAlerts {

    private const val TAG = "Portal"
    private const val PREFS = "portal_alerts"
    private const val FRESH_MS = 10 * 60_000L

    /** Where the saved alerts are kept, in Portal's own format (the older "body" key held one service's raw answer). */
    private const val ALERTS_KEY = "alerts_json"

    /** The alerts last saved that are still in force, without the network. */
    fun cached(context: Context, now: OffsetDateTime = OffsetDateTime.now()): List<WeatherAlert> {
        val body = prefs(context).getString(ALERTS_KEY, null) ?: return emptyList()
        return runCatching { WeatherJson.decodeAlerts(body) }.getOrDefault(emptyList())
            .filter { it.ends == null || !it.ends.isBefore(now) }
    }

    /** Alerts for the weather location, from the cache when fresh (unless [force]), else from the weather source. */
    suspend fun load(context: Context, force: Boolean = false): List<WeatherAlert> = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val place = WeatherRepository.knownLocation(context) ?: return@withContext emptyList()
        val key = "%.2f,%.2f".format(java.util.Locale.US, place.first, place.second)
        val age = System.currentTimeMillis() - p.getLong("time", 0L)
        if (!force && p.getString("key", null) == key && age in 0 until FRESH_MS) return@withContext cached(context)
        try {
            when (val result = WeatherProviders.active(context).fetchAlerts(WeatherPlace(place.first, place.second, null))) {
                is ProviderResult.Failed -> cached(context)
                is ProviderResult.Ok -> {
                    p.edit().putString(ALERTS_KEY, WeatherJson.encodeAlerts(result.value))
                        .putString("key", key).putLong("time", System.currentTimeMillis()).apply()
                    result.value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Weather alerts failed: ${e.message}")
            cached(context)
        }
    }

    // ---------- Severe alerts over apps ----------

    /** Whether a new severe alert covers whatever app is open. One choice for the whole TV. */
    fun overApps(context: Context): Boolean = prefs(context).getBoolean("over_apps", true)

    fun setOverApps(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("over_apps", on).apply()
    }

    /**
     * The severe alerts among [alerts] that have not been announced yet, most serious first. They are
     * remembered as announced, so each is only ever shown once.
     */
    fun takeNewSevere(context: Context, alerts: List<WeatherAlert>): List<WeatherAlert> {
        val p = prefs(context)
        val shown = p.getStringSet("announced", emptySet()).orEmpty()
        val fresh = alerts.filter { it.isSevere && it.id.isNotEmpty() && it.id !in shown }
        if (fresh.isNotEmpty()) {
            // Only the ones still active are worth remembering, so the list can't grow without end.
            val keep = (shown + fresh.map { it.id }).toList().takeLast(60).toSet()
            p.edit().putStringSet("announced", keep).apply()
        }
        return fresh
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
