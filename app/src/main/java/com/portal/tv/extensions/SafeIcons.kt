package com.portal.tv.extensions

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser
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
            if (!sizeOk(res, id, 0, Budget())) return null
            val drawable = res.getDrawable(id, null) ?: return null
            // A vector can say any size; the size it reports is checked too.
            if (drawable.intrinsicWidth > MAX_SIDE || drawable.intrinsicHeight > MAX_SIDE) null else drawable
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Whether every picture that drawing [id] would decode is small enough, found by reading sizes only, never decoding a
     * big one. A picture file is checked by its size. A drawable made of XML (a `<bitmap>`, an adaptive icon, a layer list)
     * is followed into the pictures it names, because such a wrapper can point at a huge one or at hundreds (all of them together are
     * held to a small budget); an XML kind not known here is
     * refused. Colors are fine.
     */
    private fun sizeOk(res: Resources, id: Int, depth: Int, budget: Budget): Boolean {
        if (id == 0 || depth > MAX_DEPTH) return false
        val value = TypedValue()
        res.getValue(id, value, true)
        val file = value.string?.toString().orEmpty().lowercase()
        if (file.isEmpty()) return value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        if (RASTER.any { file.endsWith(it) }) {
            // Only the size is read first; a big picture is never decoded.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            res.openRawResource(id, value).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (!allowed(bounds.outWidth, bounds.outHeight)) return false
            // Counted at the size it will be decoded: a picture made for a low-density screen is scaled up on this one.
            val scale = if (value.density == 0 || value.density == TypedValue.DENSITY_NONE) 1.0 else res.displayMetrics.densityDpi.toDouble() / value.density
            budget.pixels += bounds.outWidth.toDouble() * bounds.outHeight * scale * scale
            budget.pictures++
            return budget.pixels <= MAX_PIXELS && budget.pictures <= MAX_PICTURES
        }
        if (!file.endsWith(".xml")) return false
        val parser = res.getXml(id)
        try {
            var first = true
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (first) {
                    if (parser.name !in XML_KINDS) return false
                    first = false
                }
                for (attribute in REFERENCES) {
                    val target = parser.getAttributeResourceValue(ANDROID, attribute, 0)
                    if (target != 0 && !sizeOk(res, target, depth + 1, budget)) return false
                }
            }
            return !first
        } finally {
            parser.close()
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

    /** All the pictures one icon may name together (a layer list of hundreds is refused), counted by size once decoded. */
    private const val MAX_PIXELS = 1_048_576.0
    private const val MAX_PICTURES = 4
    private class Budget(var pixels: Double = 0.0, var pictures: Int = 0)
    private const val MAX_DEPTH = 4
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private val XML_KINDS = setOf("vector", "adaptive-icon", "layer-list", "inset", "bitmap", "rotate", "scale", "clip")
    private val REFERENCES = listOf("drawable", "src")
    private val RASTER = listOf(".png", ".webp", ".jpg", ".jpeg", ".gif", ".bmp")
}
