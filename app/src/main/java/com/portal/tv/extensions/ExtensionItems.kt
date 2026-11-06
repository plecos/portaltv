package com.portal.tv.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** One item to draw on the home screen, with whose it is. */
data class ShownItem(val packageName: String, val extensionName: String, val item: ExtensionItem)

/** The buttons and text extensions put in the top bar and under the clock, and how a button opens its screen. */
object ExtensionItems {

    /** Most items shown in each area, over all extensions, so the header can't overflow. */
    fun limit(area: ItemArea): Int = when (area) {
        ItemArea.BAR -> 4
        ItemArea.CLOCK -> 3
    }

    /**
     * The items to draw in [area]: those of extensions allowed that area, in extension name order and then the order each
     * lists them, up to [limit]. The rest are not shown.
     */
    fun pick(extensions: List<Extension>, area: ItemArea): List<ShownItem> =
        extensions
            .filter { area.capability in it.granted }
            .flatMap { ext -> ext.items.filter { it.area == area }.map { ShownItem(ext.packageName, ext.name, it) } }
            .take(limit(area))

    /** What to draw in [area] now. Runs a scan, so not on the main thread. */
    fun forArea(context: Context, area: ItemArea): List<ShownItem> = pick(ExtensionRegistry.scan(context), area)

    /**
     * Where the button [itemId] of [packageName] opens, checked again now: the extension must still be there, still
     * signed by who was allowed, and still allowed that area. Null if any of that no longer holds.
     */
    fun resolve(context: Context, packageName: String, area: ItemArea, itemId: String): Intent? {
        val ext = ExtensionRegistry.find(context, packageName) ?: return null
        if (area.capability !in ext.granted) return null
        val screen = ext.items.firstOrNull { it.area == area && it.id == itemId }?.activity ?: return null
        return Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(packageName, screen))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The button's own icon, or null if it has none or it can't be read. */
    fun iconOf(context: Context, shown: ShownItem): Drawable? {
        if (shown.item.iconRes == 0) return null
        return runCatching {
            context.packageManager.getResourcesForApplication(shown.packageName).getDrawable(shown.item.iconRes, null)
        }.getOrNull()
    }
}
