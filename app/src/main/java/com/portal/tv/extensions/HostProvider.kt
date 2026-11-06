package com.portal.tv.extensions

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.portal.tv.BuildConfig
import com.portal.tv.reminders.Reminder
import com.portal.tv.reminders.ReminderStore
import com.portal.tv.reminders.ReminderService
import com.portal.tv.reminders.ReminderTones
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
        val context = context ?: return fail(HostApi.Error.UNAVAILABLE)
        val caller = verifiedCaller(context) ?: return fail(HostApi.Error.NOT_ALLOWED)
        return try {
            dispatch(context, caller, method, extras ?: Bundle.EMPTY)
        } catch (e: Exception) {
            android.util.Log.w("Portal", "Host call $method from $caller failed: ${e.message}")
            fail(HostApi.Error.UNAVAILABLE)
        }
    }

    /** The package Android says is calling, only if that package really owns the caller's uid. */
    private fun verifiedCaller(context: Context): String? {
        val pkg = callingPackage ?: return null
        val owners = context.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return null
        return pkg.takeIf { it in owners }
    }

    private fun dispatch(context: Context, caller: String, method: String, args: Bundle): Bundle {
        val ext = ExtensionRegistry.find(context, caller) ?: return fail(HostApi.Error.NOT_ALLOWED)
        if (method == HostApi.INFO) return info(ext)
        val needs = when (method) {
            HostApi.REMINDERS_ADD, HostApi.REMINDERS_LIST, HostApi.REMINDERS_UPDATE, HostApi.REMINDERS_DELETE -> Capability.REMINDERS
            HostApi.CARD_SHOW -> Capability.CARDS
            HostApi.ITEMS_SET, HostApi.ITEMS_REMOVE, HostApi.ITEMS_LIST -> itemCapability(args) ?: return fail(HostApi.Error.BAD_ARGUMENT)
            else -> return fail(HostApi.Error.NO_SUCH_METHOD)
        }
        if (needs !in ext.granted) return fail(HostApi.Error.NOT_ALLOWED)
        val profile = ProfileManager.getActive(context) ?: return fail(HostApi.Error.UNAVAILABLE)
        if (profile.isKids) return fail(HostApi.Error.KID_PROFILE)
        val changes = method != HostApi.REMINDERS_LIST && method != HostApi.ITEMS_LIST
        if (changes && !writes.allow(caller, SystemClock.elapsedRealtime())) return fail(HostApi.Error.RATE_LIMITED)
        return when (method) {
            HostApi.REMINDERS_ADD -> addReminder(context, ext, profile.id, args)
            HostApi.REMINDERS_LIST -> listReminders(context, ext)
            HostApi.REMINDERS_UPDATE -> updateReminder(context, ext, args)
            HostApi.REMINDERS_DELETE -> deleteReminder(context, ext, args)
            HostApi.CARD_SHOW -> showCard(context, ext, args)
            else -> items(context, ext, method, args)
        }
    }

    // ---------- host.info ----------

    private fun info(ext: Extension): Bundle = ok().apply {
        putInt("contract", ManifestParser.CONTRACT_VERSION)
        putInt("portal_version_code", BuildConfig.VERSION_CODE)
        putStringArrayList("granted", ArrayList(ext.granted))
        putInt("max_reminders", HostLimits.MAX_REMINDERS)
    }

    // ---------- reminders ----------

    private fun addReminder(context: Context, ext: Extension, profileId: Int, args: Bundle): Bundle {
        val title = args.getString("title")
        HostChecks.text(title, HostLimits.MAX_REMINDER_TITLE)?.let { return fail(it) }
        val at = args.getLong("at_millis", 0L)
        HostChecks.reminderTime(at, System.currentTimeMillis())?.let { return fail(it) }
        val like = reminderFields(args, Reminder(0, profileId, "", at, source = ext.packageName)) ?: return fail(HostApi.Error.BAD_ARGUMENT)
        if (ReminderStore.forSource(context, ext.packageName).size >= HostLimits.MAX_REMINDERS) return fail(HostApi.Error.LIMIT)
        val made = ReminderStore.add(context, profileId, title!!, at, like.repeat, like = like)
        return ok().apply { putInt("id", made.id) }
    }

    /** [base] with the optional fields in [args] applied, or null if one is wrong. */
    private fun reminderFields(args: Bundle, base: Reminder): Reminder? {
        var r = base
        if (args.containsKey("repeat")) r = r.copy(repeat = HostChecks.repeat(args.getString("repeat")) ?: return null)
        if (args.containsKey("tone")) {
            val tone = args.getString("tone")
            if (!HostChecks.tone(tone)) return null
            r = r.copy(tone = tone ?: ReminderTones.DEFAULT)
        }
        if (args.containsKey("manual")) r = r.copy(manual = args.getBoolean("manual"))
        if (args.containsKey("snooze_minutes")) {
            val minutes = args.getInt("snooze_minutes")
            if (!HostChecks.snooze(minutes)) return null
            r = r.copy(snoozeMinutes = minutes)
        }
        return r
    }

    private fun listReminders(context: Context, ext: Extension): Bundle = ok().apply {
        putParcelableArrayList("reminders", ArrayList(ReminderStore.forSource(context, ext.packageName).map { r ->
            Bundle().apply {
                putInt("id", r.id); putString("title", r.title); putLong("at_millis", r.atMillis)
                putString("repeat", r.repeat.name.lowercase()); putString("tone", r.tone)
                putBoolean("manual", r.manual); putInt("snooze_minutes", r.snoozeMinutes)
            }
        }))
    }

    private fun updateReminder(context: Context, ext: Extension, args: Bundle): Bundle {
        val id = args.getInt("id", -1)
        val own = ReminderStore.forSource(context, ext.packageName).firstOrNull { it.id == id } ?: return fail(HostApi.Error.NOT_FOUND)
        var r = reminderFields(args, own) ?: return fail(HostApi.Error.BAD_ARGUMENT)
        if (args.containsKey("title")) {
            HostChecks.text(args.getString("title"), HostLimits.MAX_REMINDER_TITLE)?.let { return fail(it) }
            r = r.copy(title = args.getString("title")!!.trim())
        }
        if (args.containsKey("at_millis")) {
            val at = args.getLong("at_millis")
            HostChecks.reminderTime(at, System.currentTimeMillis())?.let { return fail(it) }
            r = r.copy(atMillis = at)
        }
        ReminderStore.update(context, r)
        return ok()
    }

    private fun deleteReminder(context: Context, ext: Extension, args: Bundle): Bundle {
        val id = args.getInt("id", -1)
        if (ReminderStore.forSource(context, ext.packageName).none { it.id == id }) return fail(HostApi.Error.NOT_FOUND)
        ReminderStore.remove(context, id)
        return ok()
    }

    // ---------- cards ----------

    private fun showCard(context: Context, ext: Extension, args: Bundle): Bundle {
        val title = args.getString("title")
        HostChecks.text(title, HostLimits.MAX_CARD_TITLE)?.let { return fail(it) }
        val text = args.getString("text").orEmpty()
        if (text.isNotEmpty()) HostChecks.text(text, HostLimits.MAX_CARD_TEXT)?.let { return fail(it) }
        if (!cards.allow(ext.packageName, SystemClock.elapsedRealtime())) return fail(HostApi.Error.RATE_LIMITED)
        val shown = ReminderService.showCard(context, ext.name, title!!.trim(), text.trim(), HostChecks.cardSeconds(args.getInt("seconds", 0)))
        return if (shown) ok() else fail(HostApi.Error.UNAVAILABLE)
    }

    // ---------- home screen items ----------

    private fun itemCapability(args: Bundle): String? =
        ItemArea.values().firstOrNull { it.key == args.getString("area") }?.capability

    private fun items(context: Context, ext: Extension, method: String, args: Bundle): Bundle {
        val area = ItemArea.values().first { it.key == args.getString("area") }
        val declared = ext.manifest?.items.orEmpty()
        val saved = ExtensionItemStore.get(context, ext.packageName)
        if (method == HostApi.ITEMS_LIST) return ok().apply {
            putParcelableArrayList("items", ArrayList(ItemOverrides.apply(declared, saved).filter { it.area == area }.map { item ->
                Bundle().apply { putString("id", item.id); putString("text", item.text); putBoolean("button", item.isButton) }
            }))
        }
        val id = args.getString("id") ?: return fail(HostApi.Error.BAD_ARGUMENT)
        val change = if (method == HostApi.ITEMS_SET) ItemOverrides.set(declared, saved, area, id, args.getString("text"))
        else ItemOverrides.remove(declared, saved, area, id)
        return when (change) {
            is ItemOverrides.Change.Refused -> fail(change.error)
            is ItemOverrides.Change.Ok -> {
                ExtensionItemStore.put(context, ext.packageName, change.overrides)
                HostEvents.itemsChanged()
                ok()
            }
        }
    }

    // ---------- nothing else is offered ----------

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun ok() = Bundle().apply { putBoolean("ok", true) }
    private fun fail(error: String) = Bundle().apply { putBoolean("ok", false); putString("error", error) }

    private companion object {
        val writes = RateLimiter(HostLimits.WRITES_PER_MINUTE, 60_000L)
        val cards = RateLimiter(1, HostLimits.CARD_INTERVAL_MS)
    }
}
