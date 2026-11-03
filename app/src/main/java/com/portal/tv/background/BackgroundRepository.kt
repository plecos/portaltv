package com.portal.tv.background

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages the bundled background video.
 *
 * A bundled video (such as space2.mp4, or birthday.mp4) in assets/backgrounds/ is extracted
 * to internal storage on first launch. No scanning, no rotation.
 */
class BackgroundRepository(
    private val context: Context,
    private val assetName: String = DEFAULT_VIDEO
) {

    companion object {
        private const val TAG = "Portal"
        /** The video used when none is chosen, or the chosen one is gone. */
        const val DEFAULT_VIDEO = "space2.mp4"
        const val BIRTHDAY_VIDEO = "birthday.mp4"

        /** Saved as the video choice to mean "a different video each day". */
        const val RANDOM = "random"

        /** A bundled video the user can pick as the background. */
        data class VideoChoice(val asset: String, val label: String)

        /**
         * Every mp4 in assets/backgrounds except the birthday video (reserved for birthdays), the
         * default first and the rest in natural order (space2 before space10). Dropping a file
         * into the folder is all it takes to offer a new video.
         */
        fun availableVideos(context: Context): List<VideoChoice> {
            val files = (context.assets.list("backgrounds") ?: emptyArray()).toList()
            val bundled = orderVideoNames(files).map { VideoChoice(it, labelFor(it)) }
                .ifEmpty { listOf(VideoChoice(DEFAULT_VIDEO, "Default")) }
            // Videos the user downloaded follow the bundled ones.
            val downloaded = com.portal.tv.video.DownloadedVideos.withUse(context, com.portal.tv.video.DownloadedVideos.USE_BACKGROUND)
                .map { VideoChoice(it.file, it.title) }
            return bundled + downloaded
        }

        /** The offered videos from a folder listing: mp4s only, no birthday video, default first. */
        internal fun orderVideoNames(files: List<String>): List<String> =
            files.filter { it.endsWith(".mp4", ignoreCase = true) && it != BIRTHDAY_VIDEO }
                .sortedWith(
                    compareBy<String> { it != DEFAULT_VIDEO }.thenComparator { x, y -> naturalCompare(x, y) }
                )

        /** "space_1.mp4" -> "Space 1", "northernLights.mp4" -> "Northern Lights". */
        internal fun labelFor(asset: String): String =
            asset.substringBeforeLast('.')
                .replace(Regex("[_-]+"), " ")
                .replace(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])"), " ")
                .trim()
                .split(Regex("\\s+"))
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        internal fun naturalCompare(a: String, b: String): Int {
            val parts = Regex("\\d+|\\D+")
            val x = parts.findAll(a.lowercase()).map { it.value }.toList()
            val y = parts.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toLongOrNull()
                val ny = y[i].toLongOrNull()
                val c = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i])
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }

        /** Below this average brightness (0 to 1) a video swallows the normal dark panels. */
        private const val DARK_THRESHOLD = 0.30f

        /**
         * True when the video is dark enough that the home panels need lightening to stay
         * visible. Measured once by sampling a few frames, then remembered per file.
         */
        suspend fun isDark(context: Context, asset: String): Boolean = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val length = runCatching { app.assets.openFd("backgrounds/$asset").use { it.length } }.getOrNull()
                ?: File(app.filesDir, "backgrounds/$asset").length()
            val prefs = app.getSharedPreferences("video_brightness", Context.MODE_PRIVATE)
            val key = "$asset:$length"
            if (prefs.contains(key)) return@withContext prefs.getFloat(key, 1f) < DARK_THRESHOLD
            val brightness = measureBrightness(app, asset) ?: return@withContext false
            Log.d(TAG, "Brightness of $asset: $brightness")
            prefs.edit().putFloat(key, brightness).apply()
            brightness < DARK_THRESHOLD
        }

        private fun measureBrightness(context: Context, asset: String): Float? {
            val retriever = MediaMetadataRetriever()
            return try {
                val bundled = runCatching { context.assets.openFd("backgrounds/$asset") }.getOrNull()
                if (bundled != null) {
                    bundled.use { retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                } else {
                    // A downloaded video is a plain file.
                    retriever.setDataSource(File(context.filesDir, "backgrounds/$asset").path)
                }
                val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000
                var total = 0f
                var frames = 0
                for (fraction in listOf(0.1f, 0.4f, 0.7f)) {
                    val frame = retriever.getScaledFrameAtTime(
                        (durationUs * fraction).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 32, 18
                    ) ?: continue
                    val pixels = IntArray(frame.width * frame.height)
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    total += pixels.map {
                        (0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it)) / 255f
                    }.average().toFloat()
                    frames++
                }
                if (frames == 0) null else total / frames
            } catch (e: Exception) {
                Log.w(TAG, "Brightness of $asset failed: ${e.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        /**
         * The videos for birthdays: the bundled one, then any downloaded for birthdays. When there is more
         * than one, a profile can pick which plays; otherwise they take turns by day.
         */
        fun birthdayVideos(context: Context): List<VideoChoice> {
            val bundled = (context.assets.list("backgrounds") ?: emptyArray()).contains(BIRTHDAY_VIDEO)
            return listOfNotNull(if (bundled) VideoChoice(BIRTHDAY_VIDEO, "Birthday") else null) +
                com.portal.tv.video.DownloadedVideos.withUse(context, com.portal.tv.video.DownloadedVideos.USE_BIRTHDAY)
                    .map { VideoChoice(it.file, it.title) }
        }

        /** The video to play on a birthday: the one this profile picked, else one chosen by day. */
        fun birthdayVideo(context: Context, today: java.time.LocalDate = java.time.LocalDate.now()): String {
            val pool = birthdayVideos(context).map { it.asset }
            if (pool.isEmpty()) return BIRTHDAY_VIDEO
            val chosen = com.portal.tv.settings.BackgroundSettings.getBirthdayVideo(context)
            return if (chosen in pool) chosen else pool[today.dayOfYear % pool.size]
        }

        /**
         * Deletes extracted copies of videos that are no longer bundled or downloaded, such as ones
         * dropped from the app in an update, which would otherwise keep using the TV's storage.
         */
        fun removeStaleCopies(context: Context) {
            val keep = com.portal.tv.video.DownloadedVideos.all(context).map { it.file }.toSet()
            for (folder in listOf("", "special")) {
                val assetFolder = if (folder.isEmpty()) "backgrounds" else "backgrounds/$folder"
                val bundled = (context.assets.list(assetFolder) ?: emptyArray()).toSet()
                val dir = File(context.filesDir, assetFolder)
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".mp4", ignoreCase = true) && f.name !in bundled && f.name !in keep) {
                        Log.d(TAG, "Removing the old copy of ${f.name}")
                        f.delete()
                    }
                }
            }
        }

        /** The saved video, or the default if it is no longer bundled. */
        fun selectedVideo(context: Context): String {
            val saved = com.portal.tv.settings.BackgroundSettings.getVideo(context)
            val available = availableVideos(context)
            if (saved == RANDOM) {
                return DailyPick.pick(available.map { it.asset }, java.time.LocalDate.now()) ?: DEFAULT_VIDEO
            }
            return available.firstOrNull { it.asset == saved }?.asset ?: DEFAULT_VIDEO
        }

        /** True when the saved choice is the daily random one. */
        fun isRandom(context: Context): Boolean =
            com.portal.tv.settings.BackgroundSettings.getVideo(context) == RANDOM
    }

    /**
     * The single background video file in internal storage.
     */
    fun getVideoFile(): File =
        File(context.filesDir, "backgrounds/$assetName")

    /**
     * Extracts the bundled video from assets into internal storage.
     * Skips if the file already exists and is non-empty.
     * Returns true if the video file is ready to play.
     */
    fun extractBundledVideo(): Boolean {
        val dest = getVideoFile()
        dest.parentFile?.mkdirs()

        // Re-extract when the bundled file has been swapped for a different one.
        val assetLength = runCatching {
            context.assets.openFd("backgrounds/$assetName").use { it.length }
        }.getOrNull()
        if (dest.exists() && dest.length() > 0 && (assetLength == null || assetLength == dest.length())) {
            Log.d(TAG, "extractBundledVideo: $assetName already exists (${dest.length()} bytes)")
            return true
        }

        try {
            Log.d(TAG, "extractBundledVideo: extracting assets/$assetName -> ${dest.path}")
            context.assets.open("backgrounds/$assetName").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "extractBundledVideo: extracted (${dest.length()} bytes)")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "extractBundledVideo failed: ${e.message}", e)
            return false
        }
    }
}
