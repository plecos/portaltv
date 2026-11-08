package com.portal.tv.screensaver

import android.content.Context
import android.content.SharedPreferences
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.repo.ProfileManager
import com.portal.tv.settings.BackgroundSettings

/**
 * The screen saver's settings. They belong to the TV, not to a profile: whoever is signed in, the screen saver starts
 * after the same time, looks the same and sounds the same, and it no longer follows a profile's background.
 *
 * Before this, each profile kept its own. The first time they are read, the choices of the TV's first adult profile
 * (and, if that profile's background was a shader, its shader look) become the TV's, so nothing changes for the person
 * who set it up.
 */
object ScreensaverSettings {

    /** The name the per-profile settings were kept under; only read when moving to the TV's own, and cleared on delete. */
    const val PREFS = "screensaver_settings"

    private const val DEVICE = "screensaver_device"

    const val NIGHT_NORMAL = "normal"
    const val NIGHT_DIM = "dim"
    const val NIGHT_BLACK = "black"

    /** The time (and weather) on the screen saver moves round the screen like a DVD logo, steps from corner to corner, or isn't shown. */
    const val CLOCK_BOUNCE = "bounce"
    const val CLOCK_CORNERS = "corners"
    const val CLOCK_OFF = "off"

    /** The screen saver plays videos, or shows a slowly moving shader of colors. */
    const val LOOK_VIDEO = "video"
    const val LOOK_SHADER = "shader"
    /** The screen saver shows a picture an allowed extension draws (see [extension]). */
    const val LOOK_EXTENSION = "extension"

    /** Night runs from [NIGHT_START] to [NIGHT_END] (hours, 24h clock). */
    const val NIGHT_START = 22
    const val NIGHT_END = 6

    /** True for an [hour] (0 to 23) inside the overnight window. */
    fun isNight(hour: Int): Boolean = hour >= NIGHT_START || hour < NIGHT_END

    /** The profile whose choices become the TV's: the first one that isn't a kid's, or the first there is. */
    internal fun ownerOf(profiles: List<Pair<Int, Boolean>>): Int? =
        (profiles.firstOrNull { !it.second } ?: profiles.firstOrNull())?.first

    /** What the look picker shows as chosen: "extension:<package>" for an extension's screen saver, else the look itself. */
    internal fun choiceOf(look: String, extension: String?): String =
        if (look == LOOK_EXTENSION && extension != null) "$LOOK_EXTENSION:$extension" else look

    /** The look and extension a picker choice made by [choiceOf] stands for. */
    internal fun parseChoice(choice: String): Pair<String, String?> =
        if (choice.startsWith("$LOOK_EXTENSION:")) LOOK_EXTENSION to choice.removePrefix("$LOOK_EXTENSION:") else choice to null

    /** The look that matches an old profile's background: a shader background gave a shader screen saver. */
    internal fun lookFor(backgroundMode: String?): String = if (backgroundMode == "shader") LOOK_SHADER else LOOK_VIDEO

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        val device = context.applicationContext.getSharedPreferences(DEVICE, Context.MODE_PRIVATE)
        if (!device.getBoolean("migrated", false)) migrate(context.applicationContext, device)
        return device
    }

    /** Takes the first adult profile's choices, read the way that profile read them: its own file, then the TV's older shared one. */
    private fun migrate(context: Context, device: SharedPreferences) {
        val owner = ownerOf(ProfileManager.getAll(context).map { it.id to it.isKids })
        fun files(base: String): List<SharedPreferences> = listOfNotNull(
            owner?.let { context.getSharedPreferences("$base.profile_$it", Context.MODE_PRIVATE) },
            context.getSharedPreferences(base, Context.MODE_PRIVATE)
        )
        val saver = files(PREFS)
        val background = files(BackgroundSettings.PREFS)
        fun from(files: List<SharedPreferences>, key: String) = files.firstOrNull { it.contains(key) }

        val edit = device.edit()
        from(saver, "idle_minutes")?.let { edit.putInt("idle_minutes", it.getInt("idle_minutes", 10)) }
        from(saver, "sound")?.let { edit.putInt("sound", it.getInt("sound", com.portal.tv.settings.VideoSound.OFF)) }
        from(saver, "night")?.let { edit.putString("night", it.getString("night", NIGHT_DIM)) }
        from(saver, "videos")?.let { f -> f.getStringSet("videos", null)?.let { edit.putStringSet("videos", it) } }
        if (lookFor(from(background, "mode")?.getString("mode", null)) == LOOK_SHADER) {
            edit.putString("look", LOOK_SHADER)
            from(background, "shader_color")?.let { edit.putString("shader_color", it.getString("shader_color", "aurora")) }
            from(background, "shader_style")?.let { edit.putString("shader_style", it.getString("shader_style", "aurora")) }
            from(background, "shader_cycle")?.let { edit.putBoolean("shader_cycle", it.getBoolean("shader_cycle", false)) }
        }
        edit.putBoolean("migrated", true).apply()
    }

    /** Minutes of inactivity on the home screen before the screen saver starts; 0 = never. */
    fun idleMinutes(context: Context): Int = prefs(context).getInt("idle_minutes", 10)

    fun setIdleMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("idle_minutes", minutes).apply()
    }

    /** How loud the screen saver's videos are: a [com.portal.tv.settings.VideoSound] level, off until chosen. */
    fun sound(context: Context): Int = prefs(context).getInt("sound", com.portal.tv.settings.VideoSound.OFF)

    fun setSound(context: Context, level: Int) {
        prefs(context).edit().putInt("sound", level).apply()
    }

    /** What the screen saver does overnight: play as usual, play dimmed, or go black. */
    fun night(context: Context): String = prefs(context).getString("night", NIGHT_DIM) ?: NIGHT_DIM

    fun setNight(context: Context, mode: String) {
        prefs(context).edit().putString("night", mode).apply()
    }

    /** The extension that draws the screen saver when the look is [LOOK_EXTENSION]; null until one is chosen. */
    fun extension(context: Context): String? = prefs(context).getString("extension", null)

    fun setExtension(context: Context, packageName: String?) {
        prefs(context).edit().apply { if (packageName == null) remove("extension") else putString("extension", packageName) }.apply()
    }

    /** How the time shows on the screen saver: [CLOCK_BOUNCE] (the default), [CLOCK_CORNERS] or [CLOCK_OFF]. */
    fun clockMode(context: Context): String =
        prefs(context).getString("clock_mode", CLOCK_BOUNCE)?.takeIf { it == CLOCK_BOUNCE || it == CLOCK_CORNERS || it == CLOCK_OFF } ?: CLOCK_BOUNCE

    fun setClockMode(context: Context, mode: String) {
        prefs(context).edit().putString("clock_mode", mode).apply()
    }

    /** Whether the weather shows under the time (it never does when the time is off). */
    fun showWeather(context: Context): Boolean = prefs(context).getBoolean("show_weather", true)

    fun setShowWeather(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("show_weather", on).apply()
    }

    /** [LOOK_VIDEO], [LOOK_SHADER] or [LOOK_EXTENSION]. */
    fun look(context: Context): String = prefs(context).getString("look", LOOK_VIDEO) ?: LOOK_VIDEO

    fun setLook(context: Context, look: String) {
        prefs(context).edit().putString("look", look).apply()
    }

    /** The shader's color scheme (see ShaderPalettes), animated style (see ShaderStyles), and whether the colors cycle. */
    fun shaderColor(context: Context): String = prefs(context).getString("shader_color", "aurora") ?: "aurora"
    fun shaderStyle(context: Context): String = prefs(context).getString("shader_style", "aurora") ?: "aurora"
    fun shaderCycle(context: Context): Boolean = prefs(context).getBoolean("shader_cycle", false)

    fun setShaderColor(context: Context, id: String) {
        prefs(context).edit().putString("shader_color", id).apply()
    }

    fun setShaderStyle(context: Context, id: String) {
        prefs(context).edit().putString("shader_style", id).apply()
    }

    fun setShaderCycle(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("shader_cycle", on).apply()
    }

    /** Bundled videos the screen saver may play; every available video until the user narrows it. */
    fun enabledVideos(context: Context): List<String> {
        val available = BackgroundRepository.availableVideos(context).map { it.asset }
        val saved = prefs(context).getStringSet("videos", null) ?: return available
        return available.filter { it in saved }.ifEmpty { available }
    }

    /** Turns [asset] on or off; the last enabled video can't be turned off. */
    fun setVideoEnabled(context: Context, asset: String, enabled: Boolean) {
        val now = enabledVideos(context).toMutableSet()
        if (enabled) now.add(asset) else if (now.size > 1) now.remove(asset)
        prefs(context).edit().putStringSet("videos", now).apply()
    }

    /** Forgets what a deleted profile kept here before the screen saver became the TV's (nothing reads it any more). */
    fun clearForProfile(context: Context, profileId: Int) =
        com.portal.tv.settings.ProfilePrefs.clear(context, PREFS, profileId)
}
