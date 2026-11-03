package com.portal.tv.video

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading search results from the Pexels and Pixabay video services. Free of Android types so it
 * can be unit tested. Each result becomes a [Hit]: one video and the one file worth downloading.
 */
object VideoCatalog {

    /**
     * The services offered in the browser. Pexels is built but switched off ([ENABLED_PROVIDERS]) while it
     * isn't issuing new API keys; turn it back on by adding it there.
     */
    enum class Provider(val label: String, val keyName: String, val siteUrl: String, val getKeyUrl: String) {
        PEXELS("Pexels", "pexels_key", "https://www.pexels.com", "pexels.com/api"),
        PIXABAY("Pixabay", "pixabay_key", "https://pixabay.com", "pixabay.com/api/docs"),
        /** NASA's Image and Video Library: no key, and its media is generally public domain. */
        NASA("NASA", "", "https://images.nasa.gov", "");

        val needsKey get() = keyName.isNotEmpty()
    }

    /** The services shown to the user, in order. */
    val ENABLED_PROVIDERS = listOf(Provider.PIXABAY, Provider.NASA)

    /** One video from a search. [fileUrl] is the file to download; [bytes] is 0 when the service doesn't say. */
    data class Hit(
        val provider: Provider,
        val id: String,
        val title: String,
        val creator: String,
        val creatorUrl: String,
        val pageUrl: String,
        val thumbUrl: String,
        val seconds: Int,
        val width: Int,
        val height: Int,
        val fileUrl: String,
        val bytes: Long,
        /** For NASA, where the list of the video's files is; [fileUrl] is empty until that is read. */
        val manifestUrl: String = ""
    ) {
        /** The name the file is saved under in the backgrounds folder. */
        val fileName get() = "dl_${provider.name.lowercase()}_$id.mp4"
        val quality get() = if (height > 0) "${height}p" else ""

        /** A short line about the video: its length and resolution, where known. */
        val details get() = listOf(duration(seconds), quality).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** A file is offered at 1080p if there is one; otherwise the largest smaller one; 4K is never fetched. */
    private const val TARGET_WIDTH = 1920

    /** Only debug builds talking to a test server turn this on; the real services are always https. */
    @Volatile var allowCleartext = false

    private class Candidate(val url: String, val width: Int, val height: Int, val bytes: Long)

    /** The best of several files of one video: landscape, as close to [TARGET_WIDTH] wide without going over, else the smallest over. */
    private fun best(files: List<Candidate>): Candidate? {
        val usable = files.filter {
            (it.url.startsWith("https://") || (allowCleartext && it.url.startsWith("http://"))) &&
                it.width >= it.height && it.width > 0
        }
        return usable.filter { it.width <= TARGET_WIDTH }.maxByOrNull { it.width }
            ?: usable.minByOrNull { it.width }
    }

    // ---------- Pexels ----------

    fun pexelsUrl(base: String, query: String, page: Int, perPage: Int): String {
        val q = query.trim()
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        return if (q.isEmpty()) "$base/videos/popular?per_page=$perPage&page=$page"
        else "$base/videos/search?query=$enc&orientation=landscape&per_page=$perPage&page=$page"
    }

    fun parsePexels(json: String): List<Hit> = try {
        val videos = JSONObject(json).optJSONArray("videos") ?: JSONArray()
        (0 until videos.length()).mapNotNull { i ->
            val v = videos.optJSONObject(i) ?: return@mapNotNull null
            val files = v.optJSONArray("video_files") ?: return@mapNotNull null
            val candidates = (0 until files.length()).mapNotNull { j ->
                val f = files.optJSONObject(j) ?: return@mapNotNull null
                if (!f.optString("file_type", "video/mp4").contains("mp4")) return@mapNotNull null
                Candidate(f.optString("link"), f.optInt("width"), f.optInt("height"), 0L)
            }
            val file = best(candidates) ?: return@mapNotNull null
            val user = v.optJSONObject("user")
            val page = v.optString("url")
            Hit(
                provider = Provider.PEXELS,
                id = v.optLong("id").toString(),
                title = titleFromSlug(page).ifEmpty { "Pexels video" },
                creator = user?.optString("name").orEmpty().ifEmpty { "Unknown" },
                creatorUrl = user?.optString("url").orEmpty().ifEmpty { Provider.PEXELS.siteUrl },
                pageUrl = page.ifEmpty { Provider.PEXELS.siteUrl },
                thumbUrl = v.optString("image"),
                seconds = v.optInt("duration"),
                width = file.width, height = file.height, fileUrl = file.url, bytes = 0L
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** "https://www.pexels.com/video/aurora-over-a-lake-12345/" gives "Aurora over a lake". */
    internal fun titleFromSlug(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast('/').replace(Regex("-\\d+$"), "")
        return slug.replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
    }

    // ---------- Pixabay ----------

    fun pixabayUrl(base: String, key: String, query: String, page: Int, perPage: Int): String {
        val enc = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return "$base/api/videos/?key=$key&q=$enc&safesearch=true&per_page=$perPage&page=$page"
    }

    fun parsePixabay(json: String): List<Hit> = try {
        val hits = JSONObject(json).optJSONArray("hits") ?: JSONArray()
        (0 until hits.length()).mapNotNull { i ->
            val h = hits.optJSONObject(i) ?: return@mapNotNull null
            val sizes = h.optJSONObject("videos") ?: return@mapNotNull null
            val candidates = listOf("large", "medium", "small", "tiny").mapNotNull { name ->
                val s = sizes.optJSONObject(name) ?: return@mapNotNull null
                Candidate(s.optString("url"), s.optInt("width"), s.optInt("height"), s.optLong("size"))
            }
            val file = best(candidates) ?: return@mapNotNull null
            val thumb = listOf("medium", "small", "large", "tiny")
                .firstNotNullOfOrNull { sizes.optJSONObject(it)?.optString("thumbnail")?.takeIf { t -> t.isNotEmpty() } }
                .orEmpty()
            val user = h.optString("user")
            val tags = h.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            Hit(
                provider = Provider.PIXABAY,
                id = h.optLong("id").toString(),
                title = tags.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Pixabay video" },
                creator = user.ifEmpty { "Unknown" },
                creatorUrl = "${Provider.PIXABAY.siteUrl}/users/$user-${h.optLong("user_id")}/",
                pageUrl = h.optString("pageURL").ifEmpty { Provider.PIXABAY.siteUrl },
                thumbUrl = thumb,
                seconds = h.optInt("duration"),
                width = file.width, height = file.height, fileUrl = file.url, bytes = file.bytes
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- NASA ----------

    fun nasaUrl(base: String, query: String, page: Int, perPage: Int): String {
        val q = query.trim().ifEmpty { "earth from space" }
        return "$base/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&media_type=video&page_size=$perPage&page=$page"
    }

    fun parseNasa(json: String): List<Hit> = try {
        val items = JSONObject(json).optJSONObject("collection")?.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val data = item.optJSONArray("data")?.optJSONObject(0) ?: return@mapNotNull null
            if (data.optString("media_type") != "video") return@mapNotNull null
            val id = data.optString("nasa_id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val manifest = item.optString("href").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val links = item.optJSONArray("links")
            val thumb = (0 until (links?.length() ?: 0)).firstNotNullOfOrNull { j ->
                links!!.optJSONObject(j)?.takeIf { it.optString("rel") == "preview" }?.optString("href")
            }.orEmpty()
            val creator = listOf("photographer", "secondary_creator", "center")
                .firstNotNullOfOrNull { data.optString(it).takeIf { v -> v.isNotEmpty() } }
            val page = "${Provider.NASA.siteUrl}/details/${java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")}"
            Hit(
                provider = Provider.NASA,
                // The id is a file name in the end, so only safe characters are kept.
                id = id.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                title = data.optString("title").trim().ifEmpty { "NASA video" },
                creator = if (creator == null) "NASA" else if (creator.startsWith("NASA")) creator else "NASA / $creator",
                creatorUrl = page, pageUrl = page,
                thumbUrl = thumb, seconds = 0, width = 0, height = 0,
                fileUrl = "", bytes = 0L, manifestUrl = manifest
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The files of one NASA video, from its manifest (a JSON list of addresses), best size first. */
    fun nasaFiles(json: String): List<String> = try {
        val array = JSONArray(json)
        val urls = (0 until array.length()).map { array.optString(it) }
            .map { it.replace("http://", "https://") }
            .filter { it.startsWith("https://") && it.endsWith(".mp4", ignoreCase = true) }
        // Full HD ("large") first, then smaller; the original is skipped as it can be enormous.
        listOf("~large.", "~medium.", "~small.", "~mobile.")
            .mapNotNull { tag -> urls.firstOrNull { it.contains(tag, ignoreCase = true) } }
    } catch (e: Exception) {
        emptyList()
    }

    /** "0:12" for a clip's length, or nothing when it isn't known. */
    fun duration(seconds: Int) = if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)

    /** "42 MB" or "1.2 GB" for a download's size. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1_000_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        else -> "%.1f GB".format(bytes / 1e9)
    }
}
