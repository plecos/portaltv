package com.portal.tv.extensions

import android.content.Context
import org.json.JSONObject

/** What an extension has put on one of its tiles while running: a short badge on the icon and a line under the name. */
data class TileDecor(val badge: String? = null, val subtitle: String? = null) {
    val isEmpty: Boolean get() = badge == null && subtitle == null
}

/** The rules for changing a tile's badge and subtitle, and how they are saved. */
object TileDecorations {

    const val MAX_BADGE = 6
    const val MAX_SUBTITLE = 24

    sealed class Change {
        data class Ok(val decorations: Map<String, TileDecor>) : Change()
        data class Refused(val error: String) : Change()
    }

    /**
     * Sets [badge] and/or [subtitle] of the tile [id]. A null leaves that part as it was and an empty text clears it. [declared]
     * are the ids of the tiles the extension declared: no other tile can be decorated.
     */
    fun update(declared: Set<String>, current: Map<String, TileDecor>, id: String, badge: Any?, subtitle: Any?, hasBadge: Boolean, hasSubtitle: Boolean): Change {
        if (id !in declared) return Change.Refused(HostApi.Error.NOT_FOUND)
        if (!hasBadge && !hasSubtitle) return Change.Refused(HostApi.Error.BAD_ARGUMENT)
        val old = current[id] ?: TileDecor()
        val newBadge = if (hasBadge) part(badge, MAX_BADGE) ?: return Change.Refused(HostApi.Error.BAD_ARGUMENT) else Part(old.badge)
        val newSubtitle = if (hasSubtitle) part(subtitle, MAX_SUBTITLE) ?: return Change.Refused(HostApi.Error.BAD_ARGUMENT) else Part(old.subtitle)
        val next = TileDecor(newBadge.text, newSubtitle.text)
        return Change.Ok(if (next.isEmpty) current - id else current + (id to next))
    }

    /** A part's new text, where null text means cleared. Wrapped so "wrong" (null result) differs from "cleared". */
    private class Part(val text: String?)

    private fun part(value: Any?, max: Int): Part? = when {
        value !is String -> null
        value.isEmpty() -> Part(null)
        HostChecks.text(value, max) != null -> null
        else -> Part(value.trim())
    }

    fun encode(map: Map<String, TileDecor>): String {
        val o = JSONObject()
        map.forEach { (id, d) ->
            o.put(id, JSONObject().apply { d.badge?.let { put("badge", it) }; d.subtitle?.let { put("subtitle", it) } })
        }
        return o.toString()
    }

    fun decode(json: String?): Map<String, TileDecor> {
        if (json == null) return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().filter { ManifestParser.isValidId(it) }.associateWith { id ->
                val d = o.getJSONObject(id)
                TileDecor(d.optString("badge").ifEmpty { null }?.take(MAX_BADGE), d.optString("subtitle").ifEmpty { null }?.take(MAX_SUBTITLE))
            }.filterValues { !it.isEmpty }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** What each extension has put on its tiles. Kept apart from what was allowed. */
object ExtensionTileStore {

    private const val PREFS = "portal_extension_tiles"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, packageName: String): Map<String, TileDecor> =
        TileDecorations.decode(prefs(context).getString(packageName, null))

    fun put(context: Context, packageName: String, map: Map<String, TileDecor>) {
        prefs(context).edit().apply { if (map.isEmpty()) remove(packageName) else putString(packageName, TileDecorations.encode(map)) }.apply()
    }

    fun remove(context: Context, packageName: String) = prefs(context).edit().remove(packageName).apply()

    fun packages(context: Context): Set<String> = prefs(context).all.keys
}
