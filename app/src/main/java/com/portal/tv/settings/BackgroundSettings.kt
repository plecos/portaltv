package com.portal.tv.settings

import android.content.Context

/**
 * Background configuration (mode, video, holiday videos, shader color), kept per profile.
 * See [ProfilePrefs] for how profiles fall back to the TV's earlier shared choices.
 */
object BackgroundSettings {

    const val PREFS = "background_settings"

    private fun prefs(context: Context) = ProfilePrefs(context, PREFS)

    /** "video", "shader", or "off" */
    fun getMode(context: Context): String =
        prefs(context).string("mode", "video")

    fun setMode(context: Context, mode: String) {
        prefs(context).edit { putString("mode", mode) }
    }

    /** The extension whose drawing is the background when the mode is "extension" (a package name), or null. */
    fun getExtension(context: Context): String? =
        prefs(context).string("extension", "").takeIf { it.isNotEmpty() }

    fun setExtension(context: Context, packageName: String?) {
        prefs(context).edit { putString("extension", packageName.orEmpty()) }
    }

    /** 0.25, 0.5, 0.75, 1.0 — how bright the video is (overlay dimming) */
    fun getBrightness(context: Context): Float =
        prefs(context).float("brightness", 0.75f)

    fun setBrightness(context: Context, value: Float) {
        prefs(context).edit { putFloat("brightness", value) }
    }

    fun getShuffle(context: Context): Boolean =
        prefs(context).boolean("shuffle", true)

    fun setShuffle(context: Context, shuffle: Boolean) {
        prefs(context).edit { putBoolean("shuffle", shuffle) }
    }

    /** Minutes between video rotation. 0 = no rotation. */
    fun getRotationMinutes(context: Context): Int =
        prefs(context).int("rotation_min", 60)

    fun setRotationMinutes(context: Context, min: Int) {
        prefs(context).edit { putInt("rotation_min", min) }
    }

    /** Extra dim applied while user is actively interacting. */
    fun getInteractDim(context: Context): Boolean =
        prefs(context).boolean("interact_dim", true)

    fun setInteractDim(context: Context, dim: Boolean) {
        prefs(context).edit { putBoolean("interact_dim", dim) }
    }

    /** Id of the shader color scheme (see ShaderPalettes). */
    fun getShaderColor(context: Context): String =
        prefs(context).string("shader_color", "aurora")

    fun setShaderColor(context: Context, id: String) {
        prefs(context).edit { putString("shader_color", id) }
    }

    /** Id of the animated shader style (see ShaderStyles). */
    fun getShaderStyle(context: Context): String =
        prefs(context).string("shader_style", "aurora")

    fun setShaderStyle(context: Context, id: String) {
        prefs(context).edit { putString("shader_style", id) }
    }

    /** Whether the shader's colors slowly shift through the whole color wheel. */
    fun getShaderCycle(context: Context): Boolean =
        prefs(context).boolean("shader_cycle", false)

    fun setShaderCycle(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean("shader_cycle", on) }
    }

    /** How fast the video plays and the shader moves: 1.0 is normal. */
    fun getSpeed(context: Context): Float =
        prefs(context).float("speed", 1f)

    fun setSpeed(context: Context, speed: Float) {
        prefs(context).edit { putFloat("speed", speed) }
    }

    /** How loud the home screen's video is: a [com.portal.tv.settings.VideoSound] level, off until chosen. */
    fun getSound(context: Context): Int = prefs(context).int("sound", VideoSound.OFF)

    fun setSound(context: Context, level: Int) {
        prefs(context).edit { putInt("sound", level) }
    }

    /** File name (in assets/backgrounds) of the video used by the Video background. */
    fun getVideo(context: Context): String =
        prefs(context).string("video_asset", com.portal.tv.background.BackgroundRepository.DEFAULT_VIDEO)

    fun setVideo(context: Context, asset: String) {
        prefs(context).edit { putString("video_asset", asset) }
    }

    /** Whether holiday videos (assets/backgrounds/special) replace the chosen video around holidays. */
    fun getHolidayVideos(context: Context): Boolean =
        prefs(context).boolean("holiday_videos", true)

    fun setHolidayVideos(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean("holiday_videos", enabled) }
    }

    /** Whether the weather page shows a weather video or animated scene behind its panels. */
    fun getWeatherBackground(context: Context): Boolean =
        prefs(context).boolean("weather_background", true)

    fun setWeatherBackground(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean("weather_background", enabled) }
    }

    /** The holiday video this profile picked before holidays could have several (kept to carry that choice over). */
    fun getHolidayVideo(context: Context, holiday: String): String? =
        prefs(context).string("holiday_video_$holiday", "").takeIf { it.isNotEmpty() }

    /** This profile's holidays as saved text (see Holidays.toJson), or null if it has never changed them. */
    fun getHolidaysJson(context: Context): String? =
        prefs(context).string("holidays_json", "").takeIf { it.isNotEmpty() }

    fun setHolidaysJson(context: Context, json: String) {
        prefs(context).edit { putString("holidays_json", json) }
    }

    /** The birthday video this profile picked (a file name); empty means Portal switches between them. */
    fun getBirthdayVideo(context: Context): String =
        prefs(context).string("birthday_video", "")

    fun setBirthdayVideo(context: Context, file: String) {
        prefs(context).edit { putString("birthday_video", file) }
    }

    /**
     * Gives a newly made profile the standard look: the Aurora shader in the Aurora color, with color
     * cycling off and normal speed. Saved in the profile's own file, so it does not take on whatever
     * the TV's earlier shared choices were.
     */
    fun setDefaultsForNewProfile(context: Context, profileId: Int) {
        ProfilePrefs.write(context, PREFS, profileId) {
            putString("mode", "shader")
            putString("shader_style", "aurora")
            putString("shader_color", "aurora")
            putBoolean("shader_cycle", false)
            putFloat("speed", 1f)
        }
    }

    /**
     * Whether a parent has let a kid profile pick its own everyday video. It is kept in the kid's own
     * file but read and written for whichever profile is asked about, so a parent can set it.
     */
    fun kidCanPickVideo(context: Context, profileId: Int): Boolean =
        context.getSharedPreferences("$PREFS.profile_$profileId", Context.MODE_PRIVATE)
            .getBoolean("kid_can_pick_video", false)

    fun setKidCanPickVideo(context: Context, profileId: Int, allowed: Boolean) {
        ProfilePrefs.write(context, PREFS, profileId) { putBoolean("kid_can_pick_video", allowed) }
    }

    /** Forgets this profile's background choices, e.g. when it is deleted. */
    fun clearForProfile(context: Context, profileId: Int) = ProfilePrefs.clear(context, PREFS, profileId)
}
