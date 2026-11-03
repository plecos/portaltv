package com.portal.tv.settings

import android.content.Context
import com.portal.tv.repo.ProfileManager

/**
 * General app settings, scoped per active profile.
 *
 * Each profile keeps its own settings (e.g. weather units), so switching
 * profiles in the "Who's watching?" picker also switches preferences.
 * Falls back to a shared "default" namespace when no profile is active.
 */
object AppSettings {

    private const val BASE_PREFS = "app_settings"

    private fun scope(context: Context): String =
        "profile_${ProfileManager.getActiveId(context) ?: "default"}"

    private fun prefs(context: Context) =
        context.getSharedPreferences("$BASE_PREFS.${scope(context)}", Context.MODE_PRIVATE)

    /** "metric" (°C, km/h) or "imperial" (°F, mph) */
    fun getWeatherUnits(context: Context): String =
        prefs(context).getString("weather_units", "metric") ?: "metric"

    fun setWeatherUnits(context: Context, units: String) {
        prefs(context).edit().putString("weather_units", units).apply()
    }

    fun isMetric(context: Context): Boolean =
        getWeatherUnits(context) == "metric"

    /** Whether this profile shows US weather alerts on the home screen and the weather page. */
    fun showWeatherAlerts(context: Context): Boolean =
        prefs(context).getBoolean("weather_alerts", true)

    fun setShowWeatherAlerts(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("weather_alerts", show).apply()
    }

    /** Which map the weather radar sits on: a name from `RadarTiles.MapStyle`. */
    fun getRadarMap(context: Context): String =
        prefs(context).getString("radar_map", "DARK") ?: "DARK"

    fun setRadarMap(context: Context, name: String) {
        prefs(context).edit().putString("radar_map", name).apply()
    }

    /** The radar's zoom level for this profile, or 0 when it has not been changed (the radar then opens at its closest detail). */
    fun getRadarZoom(context: Context): Int = prefs(context).getInt("radar_zoom", 0)

    fun setRadarZoom(context: Context, zoom: Int) {
        prefs(context).edit().putInt("radar_zoom", zoom).apply()
    }

    // ---------- Apps display ----------

    const val LAYOUT_TILES = "tiles"   // icon with title (the original look)
    const val LAYOUT_ICONS = "icons"   // icon only
    const val LAYOUT_LIST = "list"     // vertical list: icon with its title beside it

    /** How apps are drawn: [LAYOUT_TILES], [LAYOUT_ICONS] or [LAYOUT_LIST]. */
    fun getAppLayout(context: Context): String =
        prefs(context).getString("app_layout", LAYOUT_TILES) ?: LAYOUT_TILES

    fun setAppLayout(context: Context, layout: String) {
        prefs(context).edit().putString("app_layout", layout).apply()
    }

    /** True to split apps into "Frequently Used" and "All Apps"; false for one plain list. */
    fun isGrouped(context: Context): Boolean =
        prefs(context).getBoolean("group_apps", true)

    fun setGrouped(context: Context, grouped: Boolean) {
        prefs(context).edit().putBoolean("group_apps", grouped).apply()
    }

    /** True to show the "Frequently Used" / "All Apps" titles; only matters when [isGrouped]. */
    fun showGroupLabels(context: Context): Boolean =
        prefs(context).getBoolean("show_group_labels", true)

    fun setShowGroupLabels(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("show_group_labels", show).apply()
    }

    // ---------- Hidden apps and custom order ----------

    /** Packages hidden from this profile's home screen. */
    fun hiddenApps(context: Context): Set<String> =
        prefs(context).getStringSet("hidden_apps", emptySet()) ?: emptySet()

    fun hideApp(context: Context, packageName: String) {
        prefs(context).edit().putStringSet("hidden_apps", hiddenApps(context) + packageName).apply()
    }

    fun unhideApp(context: Context, packageName: String) {
        prefs(context).edit().putStringSet("hidden_apps", hiddenApps(context) - packageName).apply()
    }

    /** Saved package order for the All Apps list; empty means alphabetical. */
    fun appOrder(context: Context): List<String> =
        prefs(context).getString("app_order", "")!!.split('\n').filter { it.isNotEmpty() }

    fun setAppOrder(context: Context, order: List<String>) {
        prefs(context).edit().putString("app_order", order.joinToString("\n")).apply()
    }

    fun resetAppOrder(context: Context) {
        prefs(context).edit().remove("app_order").apply()
    }

    /** Wipe settings for a profile (used when a profile is deleted). */
    fun clearForProfile(context: Context, profileId: Int) {
        context.getSharedPreferences(
            "$BASE_PREFS.profile_$profileId", Context.MODE_PRIVATE
        ).edit().clear().apply()
    }
}
