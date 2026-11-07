package com.portal.tv.extensions

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.portal.tv.R

/**
 * Pictures that come from another app's resources (an extension's tile icon, button icon, or its own icon). They are drawn
 * in Portal's process, so one that is huge could run Portal out of memory, on the home screen, on every start. A picture
 * that is too big, or can't be read, is replaced by a generic one; nothing here throws.
 */
object SafeIcons {

    /** The longest side a picture from another app may have, in pixels. Tiles show icons far smaller than this. */
    const val MAX_SIDE = 512

    /** Whether a picture of this size may be loaded. */
    fun allowed(width: Int, height: Int): Boolean = width in 1..MAX_SIDE && height in 1..MAX_SIDE

    /** The drawable [id] in [res], or null if there is none, it is too big, or loading it fails (even by running out of memory). */
    fun load(res: Resources, id: Int): Drawable? {
        if (id == 0) return null
        return try {
            val value = TypedValue()
            res.getValue(id, value, true)
            val file = value.string?.toString().orEmpty().lowercase()
            if (RASTER.any { file.endsWith(it) }) {
                // Only the size is read first; a big picture is never decoded.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                res.openRawResource(id, value).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (!allowed(bounds.outWidth, bounds.outHeight)) return null
            }
            val drawable = res.getDrawable(id, null) ?: return null
            // A drawable made from several pieces (an adaptive icon) can hide a big one: its own size says.
            if (drawable.intrinsicWidth > MAX_SIDE * 8 || drawable.intrinsicHeight > MAX_SIDE * 8) null else drawable
        } catch (t: Throwable) {
            null
        }
    }

    /** The drawable [id] of the app [packageName], or null. */
    fun of(context: Context, packageName: String, id: Int): Drawable? = try {
        load(context.packageManager.getResourcesForApplication(packageName), id)
    } catch (t: Throwable) {
        null
    }

    /** An app's own icon, kept to the same limits, or Portal's generic extension icon. */
    fun appIconOrGeneric(context: Context, packageName: String): Drawable {
        val id = try { context.packageManager.getApplicationInfo(packageName, 0).icon } catch (t: Throwable) { 0 }
        return of(context, packageName, id) ?: generic(context)
    }

    fun generic(context: Context): Drawable = context.getDrawable(R.drawable.ic_cat_extensions)!!

    private val RASTER = listOf(".png", ".webp", ".jpg", ".jpeg", ".gif", ".bmp")
}
