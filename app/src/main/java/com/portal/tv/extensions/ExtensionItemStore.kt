package com.portal.tv.extensions

import android.content.Context

/** What each extension has changed about its home screen items while running. Kept apart from what was allowed. */
object ExtensionItemStore {

    private const val PREFS = "portal_extension_items"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, packageName: String): List<ItemOverride> =
        ItemOverrides.decode(prefs(context).getString(packageName, null))

    fun put(context: Context, packageName: String, list: List<ItemOverride>) {
        prefs(context).edit().apply { if (list.isEmpty()) remove(packageName) else putString(packageName, ItemOverrides.encode(list)) }.apply()
    }

    fun remove(context: Context, packageName: String) = prefs(context).edit().remove(packageName).apply()

    /** Forgets the changes made in [area] only, for when that area is turned off for the extension. */
    fun clearArea(context: Context, packageName: String, area: ItemArea) =
        put(context, packageName, get(context, packageName).filter { it.area != area })

    fun packages(context: Context): Set<String> = prefs(context).all.keys
}
