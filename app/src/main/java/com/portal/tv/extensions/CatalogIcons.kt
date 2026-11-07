package com.portal.tv.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Catalog icons: small PNGs next to catalog.json, fetched when first shown and kept in the cache folder. They come from
 * the catalog's own host. The size of the file, and of the picture, is capped, so a bad one can't use up memory.
 */
object CatalogIcons {

    const val MAX_FILE_BYTES = 200 * 1024
    const val MAX_SIDE = 256

    /** One load at a time for each address, so cards asking for the same icon share one download and one file. */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** The icon at [url] (null if it can't be had), from the cache when it was fetched before. Never throws but for cancellation. */
    suspend fun load(context: Context, url: String, allowHttp: Boolean): Bitmap? {
        if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) return null
        return locks.getOrPut(url) { kotlinx.coroutines.sync.Mutex() }.withLock {
            withContext(Dispatchers.IO) { fetchAndDecode(context, url) }
        }
    }

    private fun fetchAndDecode(context: Context, url: String): Bitmap? = try {
        val dir = File(context.cacheDir, "catalog_icons").apply { mkdirs() }
        val file = File(dir, hash(url) + ".png")
        if (!file.isFile) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode != 200) throw IOException("icon answered ${c.responseCode}")
                val bytes = CatalogClient.readBytesCapped(c.inputStream, MAX_FILE_BYTES)
                // Beside the real name and moved into place, so nobody reads half a picture.
                val temp = File(dir, file.name + ".tmp")
                temp.writeBytes(bytes)
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally {
                c.disconnect()
            }
        }
        decode(file) ?: run { file.delete(); null }
    } catch (e: Exception) {
        null
    }

    /** The picture in [file], shrunk if it is large; null if it isn't a picture. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
    }

    /** The power of two to shrink by so neither side is much over [MAX_SIDE]. */
    internal fun sampleSize(width: Int, height: Int): Int {
        var n = 1
        while (maxOf(width, height) / (n * 2) >= MAX_SIDE) n *= 2
        return n
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
