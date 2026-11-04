package com.portal.tv.background

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preview frames for the background videos, kept in memory and in the cache folder so they appear at once next time. */
object VideoThumbnails {

    private const val TAG = "Portal"
    private val cache = HashMap<String, Bitmap>()

    /** The picture already loaded for [asset], if there is one. */
    fun peek(asset: String): Bitmap? = synchronized(cache) { cache[asset] }

    /** Keeps [frame] as the picture for [asset], for a video that couldn't give one up front (see [ScreensaverVideosFragment]). */
    suspend fun store(context: Context, asset: String, frame: Bitmap) {
        synchronized(cache) { cache[asset] = frame }
        withContext(Dispatchers.IO) { save(context.applicationContext, asset, frame) }
    }

    private fun file(context: Context, asset: String) =
        java.io.File(java.io.File(context.cacheDir, "thumbs").also { it.mkdirs() }, "$asset.jpg")

    private fun save(context: Context, asset: String, frame: Bitmap) {
        runCatching {
            file(context, asset).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }

    /** A small frame from the video, or null if it can't be read. */
    suspend fun load(context: Context, asset: String): Bitmap? {
        synchronized(cache) { cache[asset]?.let { return it } }
        val app = context.applicationContext
        val frame = withContext(Dispatchers.IO) {
            // A picture kept from an earlier run, or else read from the video itself.
            runCatching { file(app, asset).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
                ?: grab(app, asset)?.also { save(app, asset, it) }
        }
        if (frame != null) synchronized(cache) { cache[asset] = frame }
        return frame
    }

    /** Points [retriever] at the video for [asset], bundled or downloaded. False if it can't be opened. */
    private fun openSource(retriever: MediaMetadataRetriever, context: Context, asset: String): Boolean {
        val fd = runCatching { context.assets.openFd("backgrounds/$asset") }.getOrNull()
        if (fd != null) {
            fd.use { retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        } else {
            // Compressed assets can't be opened in place, so use the extracted copy.
            val repo = BackgroundRepository(context, asset)
            if (!repo.extractBundledVideo()) return false
            retriever.setDataSource(repo.getVideoFile().path)
        }
        return true
    }

    private val audio = HashMap<String, Boolean>()

    /** The answer already known for [asset], or null if it hasn't been checked yet. */
    fun peekAudio(asset: String): Boolean? = synchronized(audio) { audio[asset] }

    private fun audioFile(context: Context, asset: String) =
        java.io.File(java.io.File(context.cacheDir, "thumbs").also { it.mkdirs() }, "$asset.audio")

    /** Whether the video has a sound track. Remembered in the cache folder; false if it can't be read. */
    suspend fun hasAudio(context: Context, asset: String): Boolean {
        peekAudio(asset)?.let { return it }
        val app = context.applicationContext
        val answer = withContext(Dispatchers.IO) {
            val saved = runCatching { audioFile(app, asset).takeIf { it.exists() }?.readText() }.getOrNull()
            if (saved == "1" || saved == "0") return@withContext saved == "1"
            val retriever = MediaMetadataRetriever()
            try {
                if (!openSource(retriever, app, asset)) return@withContext null
                val has = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                runCatching { audioFile(app, asset).writeText(if (has) "1" else "0") }
                has
            } catch (e: Exception) {
                Log.w(TAG, "Audio check for $asset failed: ${e.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (answer != null) synchronized(audio) { audio[asset] = answer }
        return answer ?: false
    }

    private fun grab(context: Context, asset: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (!openSource(retriever, context, asset)) return null
            // A tenth of the way in, so a video that opens on black still shows a picture.
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val at = maxOf(2_000_000L, durationUs / 10).coerceAtMost(durationUs.coerceAtLeast(0))
            // Fragmented MP4s (common for small downloads) have no seek table, so the usual "nearest key frame" call can
            // return nothing for them. Try the other ways of asking, and note which one worked.
            val ways = listOf<Pair<String, () -> Bitmap?>>(
                "nearest key frame" to { retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) },
                "start" to { retriever.getFrameAtTime(0) },
                "exact time" to { retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST) },
                "exact start" to { retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) },
                "first frame" to { retriever.getFrameAtIndex(0) },
                "any frame" to { retriever.getFrameAtTime(-1) }
            )
            for ((name, way) in ways) {
                val frame = runCatching { way() }.getOrNull() ?: continue
                Log.d(TAG, "Thumbnail for $asset: used the $name")
                return Bitmap.createScaledBitmap(frame, 320, 180, true)
            }
            Log.w(TAG, "Thumbnail for $asset: no way of reading a frame worked (duration ${durationUs / 1000} ms)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail for $asset failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
