package com.portal.tv.repo

import android.content.Context
import com.portal.tv.data.Profile
import com.portal.tv.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages Portal profiles.
 *
 * Android TV runs Portal as a single OS user, so profiles are app-level and
 * stored in SharedPreferences as a JSON array. Each profile keeps its own
 * usage data and settings ([UsageTracker], [AppSettings] scope themselves by
 * the active profile's id). A profile is either a plain Portal profile or
 * linked to a Google account (see [Profile.Type]).
 */
object ProfileManager {

    private const val PREFS = "portal_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_NEXT_ID = "next_id"
    private const val PHOTO_DIR = "profile_photos"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Querying ----------

    /** All profiles, in display order. */
    fun getAll(context: Context): List<Profile> {
        val json = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(
                    id = o.getInt("id"),
                    name = o.getString("name"),
                    colorIndex = o.optInt("color", 0),
                    type = if (o.optString("type") == "google") Profile.Type.GOOGLE
                    else Profile.Type.APP,
                    email = o.optString("email").takeIf { it.isNotEmpty() },
                    photoFile = o.optString("photo").takeIf { it.isNotEmpty() },
                    avatarId = o.optString("avatar").takeIf { it.isNotEmpty() },
                    isKids = o.optBoolean("kids", false),
                    allowedApps = o.optJSONArray("allowed")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    } ?: emptySet()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getById(context: Context, id: Int): Profile? =
        getAll(context).firstOrNull { it.id == id }

    /** The currently selected profile, or null if none is set. */
    fun getActive(context: Context): Profile? {
        val id = prefs(context).getInt(KEY_ACTIVE_ID, -1)
        if (id == -1) return null
        return getById(context, id)
    }

    fun getActiveId(context: Context): Int? =
        prefs(context).getInt(KEY_ACTIVE_ID, -1).takeIf { it != -1 }

    /** True when the active profile is a kid profile. */
    fun isActiveKids(context: Context): Boolean = getActive(context)?.isKids == true

    fun findByEmail(context: Context, email: String): Profile? =
        getAll(context).firstOrNull { it.email.equals(email, ignoreCase = true) }

    // ---------- Mutations ----------

    fun addProfile(
        context: Context,
        name: String,
        colorIndex: Int = 0,
        isKids: Boolean = false
    ): Profile = add(
        context,
        Profile(id = 0, name = name.trim(), colorIndex = colorIndex, isKids = isKids)
    )

    /** Adds a profile linked to a Google account. [photo] is the avatar image bytes, if any. */
    fun addGoogleProfile(
        context: Context,
        name: String,
        email: String,
        photo: ByteArray?,
        isKids: Boolean = false
    ): Profile {
        val added = add(
            context,
            Profile(
                id = 0,
                name = name.trim().ifEmpty { email },
                colorIndex = getAll(context).size,
                type = Profile.Type.GOOGLE,
                email = email,
                isKids = isKids
            )
        )
        if (photo == null) return added
        val file = "${added.id}.img"
        photoDir(context).also { it.mkdirs() }.resolve(file).writeBytes(photo)
        val withPhoto = added.copy(photoFile = file)
        updateProfile(context, withPhoto)
        return withPhoto
    }

    private fun add(context: Context, template: Profile): Profile {
        val all = getAll(context).toMutableList()
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        val profile = template.copy(id = id)
        all.add(profile)
        persist(context, all, nextId = id + 1)
        com.portal.tv.settings.BackgroundSettings.setDefaultsForNewProfile(context, id)
        return profile
    }

    fun updateProfile(context: Context, profile: Profile) {
        val all = getAll(context).toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile
        persist(context, all)
    }

    /**
     * Changes one profile from its saved state, so a screen that has held on to an older copy (for
     * example through a chain of dialogs) can't write that copy back and undo a change made since,
     * such as a newly chosen avatar. Returns the saved profile, or null if it no longer exists.
     */
    fun modify(context: Context, id: Int, change: (Profile) -> Profile): Profile? {
        val current = getById(context, id) ?: return null
        val changed = change(current)
        updateProfile(context, changed)
        return changed
    }

    /** Deletes a profile along with its usage data, settings and cached photo. */
    fun deleteProfile(context: Context, id: Int) {
        val profile = getById(context, id)
        val all = getAll(context).toMutableList()
        all.removeAll { it.id == id }
        persist(context, all)
        UsageTracker.clearForProfile(context, id)
        AppSettings.clearForProfile(context, id)
        com.portal.tv.settings.BackgroundSettings.clearForProfile(context, id)
        com.portal.tv.screensaver.ScreensaverSettings.clearForProfile(context, id)
        com.portal.tv.search.SearchSettings.clearForProfile(context, id)
        ProfileCode.clear(context, id)
        com.portal.tv.reminders.ReminderStore.clearForProfile(context, id)
        profile?.photoFile?.let { photoDir(context).resolve(it).delete() }
        val p = prefs(context)
        if (p.getInt(KEY_ACTIVE_ID, -1) == id) {
            p.edit().putInt(KEY_ACTIVE_ID, -1).apply()
        }
    }

    /** Set the profile that owns the current home-screen state. */
    fun setActive(context: Context, id: Int) {
        // A parent's "skip bedtime tonight" belongs to the profile that was loaded, so it ends with it.
        KidsSchedule.endBedtimeSkips(context)
        prefs(context).edit().putInt(KEY_ACTIVE_ID, id).apply()
    }

    // ---------- Photos ----------

    fun photoPath(context: Context, profile: Profile): File? =
        profile.photoFile?.let { photoDir(context).resolve(it) }?.takeIf { it.exists() }

    private fun photoDir(context: Context) = File(context.filesDir, PHOTO_DIR)

    // ---------- Persistence ----------

    private fun persist(context: Context, profiles: List<Profile>, nextId: Int? = null) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("color", p.colorIndex)
                    .put("type", if (p.isGoogle) "google" else "app")
                    .put("email", p.email ?: "")
                    .put("photo", p.photoFile ?: "")
                    .put("avatar", p.avatarId ?: "")
                    .put("kids", p.isKids)
                    .put("allowed", JSONArray(p.allowedApps.toList()))
            )
        }
        val editor = prefs(context).edit().putString(KEY_PROFILES, arr.toString())
        if (nextId != null) editor.putInt(KEY_NEXT_ID, nextId)
        editor.apply()
    }
}
