package com.portal.tv.extensions

import org.json.JSONArray
import org.json.JSONObject

/**
 * A change an extension made, while running, to its home screen items. For an item it declared, [text] replaces its
 * words (null leaves them) and [removed] takes it away; for an id it didn't declare, it is a line of text it added.
 */
data class ItemOverride(val area: ItemArea, val id: String, val text: String?, val removed: Boolean = false)

/** Works out which items to show once an extension's changes are applied, and whether a new change is allowed. */
object ItemOverrides {

    sealed class Change {
        data class Ok(val overrides: List<ItemOverride>) : Change()
        data class Refused(val error: String) : Change()
    }

    /** The items to show: the declared ones with changes applied, then lines of text the extension added. */
    fun apply(declared: List<ExtensionItem>, overrides: List<ItemOverride>): List<ExtensionItem> {
        val shown = declared.mapNotNull { item ->
            val o = overrides.firstOrNull { it.area == item.area && it.id == item.id }
            when {
                o == null -> item
                o.removed -> null
                o.text != null -> item.copy(text = o.text)
                else -> item
            }
        }
        val added = overrides.filter { o -> !o.removed && o.text != null && declared.none { it.area == o.area && it.id == o.id } }
            .map { ExtensionItem(it.id, it.area, it.text!!, null, 0) }
        return shown + added
    }

    /** Sets the words of item [id] in [area]: changes a declared one, or adds a line of text. */
    fun set(declared: List<ExtensionItem>, overrides: List<ItemOverride>, area: ItemArea, id: String, text: String?): Change {
        if (!ManifestParser.isValidId(id)) return Change.Refused(HostApi.Error.BAD_ARGUMENT)
        val item = declared.firstOrNull { it.area == area && it.id == id }
        val max = if (item?.isButton == true) ManifestParser.MAX_BUTTON_TEXT else ManifestParser.MAX_LINE_TEXT
        HostChecks.text(text, max)?.let { return Change.Refused(it) }
        val without = overrides.filterNot { it.area == area && it.id == id }
        // A new line of text counts against the extension's two items in that area.
        if (item == null && apply(declared, without).count { it.area == area } >= ManifestParser.MAX_ITEMS_PER_AREA) return Change.Refused(HostApi.Error.LIMIT)
        val next = without + ItemOverride(area, id, text!!.trim())
        if (next.size > HostLimits.MAX_ITEM_CHANGES) return Change.Refused(HostApi.Error.LIMIT)
        return Change.Ok(next)
    }

    /** Takes item [id] off the screen: hides a declared one (until it is set again) or drops one that was added. */
    fun remove(declared: List<ExtensionItem>, overrides: List<ItemOverride>, area: ItemArea, id: String): Change {
        val item = declared.firstOrNull { it.area == area && it.id == id }
        val added = overrides.any { it.area == area && it.id == id && it.text != null && !it.removed }
        if (item == null && !added) return Change.Refused(HostApi.Error.NOT_FOUND)
        val without = overrides.filterNot { it.area == area && it.id == id }
        return Change.Ok(if (item != null) without + ItemOverride(area, id, null, removed = true) else without)
    }

    fun encode(list: List<ItemOverride>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("area", it.area.key).put("id", it.id).put("removed", it.removed).apply { it.text?.let { t -> put("text", t) } }) }
        return arr.toString()
    }

    fun decode(json: String?): List<ItemOverride> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val area = ItemArea.values().firstOrNull { it.key == o.optString("area") } ?: return@mapNotNull null
                val id = o.optString("id").takeIf { ManifestParser.isValidId(it) } ?: return@mapNotNull null
                ItemOverride(area, id, o.optString("text").ifEmpty { null }, o.optBoolean("removed", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
