package com.portal.tv.adb

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Finishes setting up this TV over Portal's own connection to its debug service, doing what scripts/install.sh
 * does from a computer: turn off the TV's own home screen, make Portal the home app, allow alerts over apps and
 * the microphone, and make Portal the TV's screen saver in place of its ambient mode. The TV asks once to allow the
 * connection.
 *
 * Every function blocks; call them off the main thread.
 */
object SelfSetup {
    private const val TAG = "Portal"
    private const val PORT = 5555
    private const val PACKAGE = "com.portal.tv"
    private const val PREFS = "portal_selfsetup"
    private const val KEY_DISABLED = "disabled_launchers"

    /** Portal's screen saver service, written the way the TV stores it. */
    internal const val SAVER = "com.portal.tv/com.portal.tv.screensaver.ScreensaverService"

    // What the TV had before Portal became its screen saver, so Restore can put it back.
    private const val KEY_SAVER_SAVED = "saver_saved"
    private const val KEY_SAVER_COMPONENTS = "saver_components"
    private const val KEY_SAVER_ON_SLEEP = "saver_on_sleep"

    /** Launchers that must stay on: Portal, and the Settings app's fallback home. */
    private val KEEP = setOf(PACKAGE, "com.android.tv.settings", "android")

    /** Where the stock launchers have been seen, in case the TV doesn't list them as home apps. */
    private val KNOWN_LAUNCHERS = listOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher"
    )

    sealed class Result {
        data object Ok : Result()
        data class Failed(val why: String) : Result()
    }

    /**
     * Connect to this TV's debug service (the TV asks the person to allow it the first time). A connection from
     * earlier may have been dropped by the TV without telling Portal, so a reused one is tried before it is trusted.
     */
    fun connect(context: Context): Result {
        val adb = PortalAdb.get(context)
        if (adb.isConnected) {
            val alive = try { run(adb, "echo ok", 5).contains("ok") } catch (e: Exception) { false }
            if (alive) return Result.Ok
            Log.d(TAG, "selfsetup: the old connection is dead, connecting again")
            try { adb.disconnect() } catch (e: Exception) { /* already gone */ }
        }
        return try {
            adb.setTimeout(45, TimeUnit.SECONDS)
            if (adb.connect("127.0.0.1", PORT)) Result.Ok
            else Result.Failed("The TV didn't accept the connection.")
        } catch (e: Exception) {
            Log.d(TAG, "selfsetup: connect failed", e)
            Result.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The TV didn't accept the connection.")
        }
    }

    /** Run the setup commands. Call after [connect] returned [Result.Ok]. */
    fun apply(context: Context): Result {
        val adb = PortalAdb.get(context)
        return try {
            val off = turnOffStockLaunchers(adb)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_DISABLED, off.toSet()).apply()
            run(adb, "cmd role add-role-holder --user 0 android.app.role.HOME $PACKAGE")
            run(adb, "appops set $PACKAGE SYSTEM_ALERT_WINDOW allow")
            run(adb, "pm grant $PACKAGE android.permission.RECORD_AUDIO")
            // The TV's own ambient mode would draw over Portal's screen saver, so Portal takes its place. The system
            // then starts it over any app that has been idle for the TV's screen saver time.
            setPortalAsScreensaver(context, adb)
            Result.Ok
        } catch (e: Exception) {
            Log.d(TAG, "selfsetup: apply failed", e)
            Result.Failed(e.message ?: "Something went wrong while setting up.")
        }
    }

    /** The launchers that [apply] turned off, so they can be turned back on. */
    fun disabledLaunchers(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()

    /** Turn the TV's own home screen and screen saver back on. */
    fun restore(context: Context): Result {
        val adb = PortalAdb.get(context)
        return try {
            for (name in disabledLaunchers(context).ifEmpty { KNOWN_LAUNCHERS.toSet() }) {
                run(adb, "pm enable --user 0 $name")
            }
            putBackScreensaver(context, adb)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DISABLED).apply()
            Result.Ok
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Something went wrong.")
        }
    }

    /** Whether Portal is the TV's screen saver, so the system starts it over other apps. Readable without adb. */
    fun isSystemScreensaver(context: Context): Boolean {
        val resolver = context.contentResolver
        return isPortalSaver(
            android.provider.Settings.Secure.getString(resolver, "screensaver_components"),
            android.provider.Settings.Secure.getInt(resolver, "screensaver_enabled", 0) == 1
        )
    }

    /** Make Portal the TV's screen saver. Call after [connect] returned [Result.Ok]. */
    fun enableSystemScreensaver(context: Context): Result = try {
        setPortalAsScreensaver(context, PortalAdb.get(context))
        Result.Ok
    } catch (e: Exception) {
        Log.d(TAG, "selfsetup: enabling the screen saver failed", e)
        Result.Failed(e.message ?: "Something went wrong.")
    }

    /** Give the TV's own screen saver back. Call after [connect] returned [Result.Ok]. */
    fun disableSystemScreensaver(context: Context): Result = try {
        putBackScreensaver(context, PortalAdb.get(context))
        Result.Ok
    } catch (e: Exception) {
        Log.d(TAG, "selfsetup: restoring the screen saver failed", e)
        Result.Failed(e.message ?: "Something went wrong.")
    }

    private fun setPortalAsScreensaver(context: Context, adb: PortalAdb) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Remember the TV's own choice once, and never overwrite it with Portal's.
        if (!prefs.getBoolean(KEY_SAVER_SAVED, false) && !isSystemScreensaver(context)) {
            val resolver = context.contentResolver
            prefs.edit()
                .putBoolean(KEY_SAVER_SAVED, true)
                .putString(KEY_SAVER_COMPONENTS, android.provider.Settings.Secure.getString(resolver, "screensaver_components"))
                .putString(KEY_SAVER_ON_SLEEP, android.provider.Settings.Secure.getString(resolver, "screensaver_activate_on_sleep"))
                .apply()
        }
        run(adb, "settings put secure screensaver_components $SAVER")
        run(adb, "settings put secure screensaver_enabled 1")
        // A TV has no "sleep" apart from idling, so this is what lets it start after the screen saver time.
        run(adb, "settings put secure screensaver_activate_on_sleep 1")
    }

    private fun putBackScreensaver(context: Context, adb: PortalAdb) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (command in restoreScreensaverCommands(
            saved = prefs.getBoolean(KEY_SAVER_SAVED, false),
            components = prefs.getString(KEY_SAVER_COMPONENTS, null),
            onSleep = prefs.getString(KEY_SAVER_ON_SLEEP, null)
        )) run(adb, command)
        prefs.edit().remove(KEY_SAVER_SAVED).remove(KEY_SAVER_COMPONENTS).remove(KEY_SAVER_ON_SLEEP).apply()
    }

    /** Whether the TV's screen saver setting names Portal, however the component is spelled. */
    internal fun isPortalSaver(components: String?, enabled: Boolean): Boolean =
        enabled && components.orEmpty().split(',').any { expand(it.trim()) == expand(SAVER) }

    /** "pkg/.Class" and "pkg/pkg.Class" name the same component. */
    private fun expand(component: String): String {
        val slash = component.indexOf('/')
        if (slash < 0) return component
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    /**
     * The commands that give the TV its own screen saver back: what it had before Portal took over, if that was
     * saved, and the screen saver turned on either way (setup used to turn it off, so older setups have none saved).
     */
    internal fun restoreScreensaverCommands(saved: Boolean, components: String?, onSleep: String?): List<String> {
        val commands = mutableListOf<String>()
        commands += if (components != null) "settings put secure screensaver_components '$components'"
        else "settings delete secure screensaver_components"
        if (saved) {
            commands += if (onSleep != null) "settings put secure screensaver_activate_on_sleep $onSleep"
            else "settings delete secure screensaver_activate_on_sleep"
        }
        commands += "settings put secure screensaver_enabled 1"
        return commands
    }

    /**
     * Turn off every home app except Portal and the Settings fallback. Turning one off can reveal another that
     * also answers Home, so go round until none are left. Returns what was turned off.
     */
    private fun turnOffStockLaunchers(adb: PortalAdb): List<String> {
        val off = mutableListOf<String>()
        repeat(5) {
            val found = run(adb, "cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lines().mapNotNull { Regex("^\\s+([A-Za-z0-9_.]+)/").find(it)?.groupValues?.get(1) }
                .toSet() - KEEP
            if (found.isEmpty()) return off
            for (name in found) { run(adb, "pm disable-user --user 0 $name"); off += name }
        }
        // Some TVs don't list their own launcher as a home app; turn off the known ones that are still on.
        val enabled = run(adb, "pm list packages -e")
        for (name in KNOWN_LAUNCHERS) {
            if ("package:$name" in enabled.lines().map { it.trim() } && name !in off) {
                run(adb, "pm disable-user --user 0 $name"); off += name
            }
        }
        return off
    }

    /**
     * Run one shell command and return what it printed. A fast command can finish and close its stream before the
     * output is read, and the library then throws "Stream closed" instead of ending the read; treat that as the end.
     * If the TV stops answering, the connection is dropped after [seconds] so nothing waits forever.
     */
    private fun run(adb: PortalAdb, command: String, seconds: Long = 20): String {
        Log.d(TAG, "selfsetup: $command")
        val job = workers.submit<String> {
            val stream = adb.openStream("shell:$command")
            val out = java.io.ByteArrayOutputStream()
            try {
                val input = stream.openInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = try { input.read(buffer) } catch (e: java.io.IOException) { -1 }
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            } finally {
                try { stream.close() } catch (e: java.io.IOException) { /* already closed */ }
            }
            out.toString(Charsets.UTF_8.name())
        }
        return try {
            job.get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.d(TAG, "selfsetup: no answer to '$command', dropping the connection")
            try { adb.disconnect() } catch (x: Exception) { /* already gone */ }
            job.cancel(true)
            throw java.io.IOException("The TV stopped answering.")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private val workers = java.util.concurrent.Executors.newCachedThreadPool()
}
