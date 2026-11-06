package com.portal.tv.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import com.portal.tv.data.AppInfo

/** The tiles extensions add to the app grid, and how to open one. */
object ExtensionTiles {

    /** Where a tile goes when it is opened. */
    class Target(val packageName: String, val intent: Intent)

    /** A tile for each screen an allowed extension declares, ready for the grid. Runs a scan, so not on the main thread. */
    fun appInfos(context: Context): List<AppInfo> =
        ExtensionRegistry.scan(context)
            .filter { Capability.TILES in it.granted }
            .flatMap { ext ->
                ext.tiles.map { tile ->
                    AppInfo(
                        packageName = ExtensionTileKey.of(ext.packageName, tile.id),
                        label = tile.label,
                        icon = iconOf(context, ext.packageName, tile),
                        // Not an installed app of its own, so there is nothing to uninstall from the tile.
                        isSystem = true
                    )
                }
            }

    /**
     * Where the tile named [key] opens, checked again now: its extension must still be there, still signed by who was
     * allowed, and still allowed tiles. Null if any of that no longer holds.
     */
    fun resolve(context: Context, key: String): Target? {
        val (packageName, tileId) = ExtensionTileKey.parse(key) ?: return null
        val ext = ExtensionRegistry.find(context, packageName) ?: return null
        if (Capability.TILES !in ext.granted) return null
        val tile = ext.tiles.firstOrNull { it.id == tileId } ?: return null
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(packageName, tile.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Target(packageName, intent)
    }

    private fun iconOf(context: Context, packageName: String, tile: ExtensionTile): Drawable {
        val pm = context.packageManager
        if (tile.iconRes != 0) {
            runCatching { pm.getResourcesForApplication(packageName).getDrawable(tile.iconRes, null) }.getOrNull()?.let { return it }
        }
        return pm.getApplicationIcon(packageName)
    }
}
