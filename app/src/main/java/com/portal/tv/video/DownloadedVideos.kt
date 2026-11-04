package com.portal.tv.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The videos the user has downloaded. The files sit in the backgrounds folder beside the bundled
 * videos, so they play and appear in the lists the same way; this keeps their titles and credits.
 */
object DownloadedVideos {

    private const val TAG = "Portal"
    private const val PREFS = "downloaded_videos"
    private const val KEY = "items"
    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"

    /** Bigger than this is refused: it would fill a TV's storage and take too long to fetch. */
    const val MAX_BYTES = 400_000_000L

    /** What a downloaded video is for. Holiday uses are "holiday:" followed by the holiday's id, as in [com.portal.tv.background.Holidays]. */
    const val USE_BACKGROUND = "background"
    const val USE_BIRTHDAY = "birthday"
    const val USE_HOLIDAY = "holiday:"

    /** The uses to choose from, each with the words shown for it: ordinary days, birthdays, then each of the profile's holidays. */
    fun uses(context: Context): List<Pair<String, String>> =
        listOf(USE_BACKGROUND to "Regular background", USE_BIRTHDAY to "Birthdays") +
            com.portal.tv.background.HolidayStore.load(context).map { "$USE_HOLIDAY${it.id}" to it.name }

    fun useLabel(context: Context, use: String): String =
        if (use == USE_BIRTHDAY) "Birthdays"
        else if (use.startsWith(USE_HOLIDAY)) com.portal.tv.background.HolidayStore.nameOf(context, use.removePrefix(USE_HOLIDAY)) ?: "Regular background"
        else "Regular background"

    data class Item(
        val file: String,
        val title: String,
        val creator: String,
        val creatorUrl: String,
        val pageUrl: String,
        val provider: String,
        val use: String = USE_BACKGROUND
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(context: Context) = File(context.filesDir, "backgrounds").also { it.mkdirs() }

    /** True when [asset] is a downloaded video rather than one bundled in the app. */
    fun isDownloaded(asset: String) = asset.startsWith("dl_")

    /** The downloads whose files are still there, oldest first. */
    fun all(context: Context): List<Item> {
        val array = runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Item(
                o.optString("file"), o.optString("title"), o.optString("creator"),
                o.optString("creatorUrl"), o.optString("pageUrl"), o.optString("provider"),
                o.optString("use").ifEmpty { USE_BACKGROUND }
            ).takeIf { it.file.isNotEmpty() && File(dir(context), it.file).length() > 0 }
        }
    }

    fun find(context: Context, asset: String): Item? = all(context).firstOrNull { it.file == asset }

    /** The downloads made for [use], such as [USE_BIRTHDAY]. */
    fun withUse(context: Context, use: String): List<Item> = all(context).filter { it.use == use }

    /** The id of the holiday a download is for (such as "halloween"), or null. */
    fun holidayOf(item: Item): String? = item.use.takeIf { it.startsWith(USE_HOLIDAY) }?.removePrefix(USE_HOLIDAY)

    /** Changes what a downloaded video is for. */
    fun setUse(context: Context, asset: String, use: String) {
        save(context, all(context).map { if (it.file == asset) it.copy(use = use) else it })
    }

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("file", it.file).put("title", it.title).put("creator", it.creator)
                .put("creatorUrl", it.creatorUrl).put("pageUrl", it.pageUrl).put("provider", it.provider)
                .put("use", it.use))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /**
     * Repairs downloads made before this was done on arrival: any that can't say how long they are get rewritten
     * as ordinary MP4s, so they loop properly. Quick when there is nothing to do; call it off the main thread.
     */
    fun repairAll(context: Context) {
        val folder = dir(context.applicationContext)
        for (item in all(context)) {
            val file = File(folder, item.file)
            if (!file.exists() || !Mp4Fixer.needsFixing(file)) continue
            Log.d(TAG, "${item.file} can't say how long it is; repairing it")
            val fixed = File(file.path + ".fixed")
            if (Mp4Fixer.fix(file, fixed)) {
                if (!file.delete() || !fixed.renameTo(file)) fixed.delete()
            } else {
                fixed.delete()
            }
        }
    }

    /** Forgets a download and deletes its file. */
    fun remove(context: Context, asset: String) {
        File(dir(context), asset).delete()
        save(context, all(context).filter { it.file != asset })
    }

    /** The size the file will be, from the server, or 0 if it won't say. */
    suspend fun sizeOf(url: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "HEAD"
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                if (c.responseCode == 200) c.contentLengthLong.coerceAtLeast(0) else 0L
            } finally {
                c.disconnect()
            }
        }.getOrDefault(0L)
    }

    /** A problem that is usually brief (a dropped connection, a busy server), so the download is worth trying again. */
    private class Retryable(message: String) : IOException(message)

    private fun retryable(e: Exception) =
        e is Retryable || e is java.net.SocketTimeoutException || e is java.net.ConnectException ||
            e is java.net.SocketException || e is java.io.EOFException || e is javax.net.ssl.SSLException

    private const val TRIES = 3
    private const val RETRY_DELAY_MS = 2_000L

    /**
     * Downloads [hit] into the backgrounds folder and records it. [onProgress] gets bytes so far and
     * the total (0 if unknown). Throws if the download fails, is too big, or isn't a playable video;
     * cancelling the calling coroutine stops it and leaves nothing behind. A brief problem is tried
     * again twice before giving up.
     */
    suspend fun download(context: Context, hit: VideoCatalog.Hit, use: String, onProgress: (Long, Long) -> Unit) {
        val app = context.applicationContext
        val dest = File(dir(app), hit.fileName)
        val part = File(dir(app), hit.fileName + ".part")
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    fetch(hit, part, onProgress)
                    if (!playable(part)) {
                        Log.w(TAG, "${hit.fileName}: ${part.length()} bytes that can't be read as a video, starting with: ${head(part)}")
                        val why = "The downloaded file can't be played (${part.length()} bytes)"
                        // A tiny file is most likely an error page, which another try may fix. A whole video that
                        // still can't be read is a format this TV can't handle, and fetching it again won't help.
                        throw if (part.length() < 1_000_000L) Retryable(why) else IOException(why)
                    }
                    // A fragmented MP4 can't loop, so make it an ordinary one before it is kept.
                    if (Mp4Fixer.needsFixing(part)) {
                        val fixed = File(part.path + ".fixed")
                        if (!Mp4Fixer.fix(part, fixed)) {
                            throw IOException("This video's format can't be played on a loop on this TV")
                        }
                        part.delete()
                        fixed.renameTo(part)
                    }
                    break
                } catch (e: Exception) {
                    part.delete()
                    File(part.path + ".fixed").delete()
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Log.w(TAG, "Download of ${hit.fileName} failed (try $attempt of $TRIES): ${e.message}")
                    if (attempt >= TRIES || !retryable(e)) throw e
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                }
            }
            try {
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("Couldn't save the video")
                save(app, all(app).filter { it.file != hit.fileName } + Item(
                    hit.fileName, hit.title, hit.creator, hit.creatorUrl, hit.pageUrl, hit.provider.label, use
                ))
            } catch (e: Exception) {
                part.delete()
                Log.w(TAG, "Saving ${hit.fileName} failed: ${e.message}")
                throw e
            }
        }
    }

    /** One try at getting the file into [part], checking that what arrives is a whole video. */
    private suspend fun fetch(hit: VideoCatalog.Hit, part: File, onProgress: (Long, Long) -> Unit) {
        val c = URL(hit.fileUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code != 200) {
                val why = "The server answered $code"
                throw if (code == 429 || code >= 500) Retryable(why) else IOException(why)
            }
            val type = c.contentType.orEmpty().lowercase()
            val total = c.contentLengthLong.coerceAtLeast(0)
            Log.d(TAG, "Downloading ${hit.fileName}: type=$type, size=$total")
            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) {
                throw Retryable("The server sent a web page, not a video ($type)")
            }
            if (total > MAX_BYTES) throw IOException("That video is too big (${VideoCatalog.size(total)})")
            var done = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done > MAX_BYTES) throw IOException("That video is too big")
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) { lastReport = now; onProgress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw Retryable("The download was cut short ($done of $total bytes)")
            onProgress(done, total.takeIf { it > 0 } ?: done)
            Log.d(TAG, "Downloaded ${hit.fileName}: $done bytes")
        } finally {
            c.disconnect()
        }
    }

    /** The first bytes of a file as text, for the log when something that isn't a video arrives. */
    private fun head(file: File): String = runCatching {
        file.inputStream().use { input ->
            val bytes = ByteArray(80)
            val n = input.read(bytes).coerceAtLeast(0)
            String(bytes, 0, n, Charsets.ISO_8859_1).map { if (it in ' '..'~') it else '.' }.joinToString("")
        }
    }.getOrDefault("?")

    /**
     * Whether [file] holds a video this TV can read. A duration over zero is not required: fragmented MP4s (the
     * kind marked "iso5" or "iso6", common for small downloads) often say 0 in their header and play fine, so a
     * readable video track counts too.
     */
    private fun playable(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        val byRetriever = try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            Log.d(TAG, "${file.name}: duration=$duration hasVideo=$hasVideo width=$width")
            duration > 0 || hasVideo || width > 0
        } catch (e: Exception) {
            Log.d(TAG, "${file.name}: the metadata reader said: ${e.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
        if (byRetriever) return true
        // A second opinion from the media extractor, which reads the file's tracks directly.
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }.also { Log.d(TAG, "${file.name}: the track reader found a video track = $it") }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}
