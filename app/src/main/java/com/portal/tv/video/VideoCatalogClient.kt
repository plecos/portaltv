package com.portal.tv.video

import android.content.Context
import com.portal.tv.BuildConfig
import com.portal.tv.search.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Searches Pexels or Pixabay with the user's own free key. */
object VideoCatalogClient {

    const val PER_PAGE = 24
    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"

    fun key(context: Context, provider: VideoCatalog.Provider): String? =
        if (provider.needsKey) SecretStore.get(context, provider.keyName) else ""
    /** True when the service can be searched: it has no key, or the user has added theirs. */
    fun hasKey(context: Context, provider: VideoCatalog.Provider) =
        !provider.needsKey || SecretStore.has(context, provider.keyName)
    fun setKey(context: Context, provider: VideoCatalog.Provider, value: String?) =
        SecretStore.put(context, provider.keyName, value?.trim())

    /** Debug builds can point a service at a test server, through a preference, to try the screen without a key. */
    private fun base(context: Context, provider: VideoCatalog.Provider, default: String): String {
        if (!BuildConfig.DEBUG) return default
        val override = context.getSharedPreferences("video_catalog_debug", Context.MODE_PRIVATE)
            .getString("base_${provider.name}", null)?.trimEnd('/')
        VideoCatalog.allowCleartext = override != null
        return override ?: default
    }

    /** A page of results, or throws an [IOException] whose message can be shown to the user. */
    suspend fun search(context: Context, provider: VideoCatalog.Provider, query: String, page: Int): List<VideoCatalog.Hit> {
        val key = key(context, provider) ?: throw IOException("Add your ${provider.label} key first")
        return withContext(Dispatchers.IO) {
            when (provider) {
                VideoCatalog.Provider.PEXELS -> {
                    // The versioned address is the current one; the older one is tried if it isn't found.
                    val root = base(context, provider, "https://api.pexels.com")
                    val json = try {
                        get(VideoCatalog.pexelsUrl("$root/v1", query, page, PER_PAGE), authorization = key)
                    } catch (e: IOException) {
                        if (e.message?.contains("404") != true) throw e
                        get(VideoCatalog.pexelsUrl(root, query, page, PER_PAGE), authorization = key)
                    }
                    VideoCatalog.parsePexels(json)
                }
                VideoCatalog.Provider.NASA -> {
                    val url = VideoCatalog.nasaUrl(base(context, provider, "https://images-api.nasa.gov"), query, page, PER_PAGE)
                    VideoCatalog.parseNasa(get(url, authorization = null))
                }
                VideoCatalog.Provider.PIXABAY -> {
                    val url = VideoCatalog.pixabayUrl(base(context, provider, "https://pixabay.com"), key, query, page, PER_PAGE)
                    VideoCatalog.parsePixabay(get(url, authorization = null))
                }
            }
        }
    }

    /**
     * Fills in the file to download for a result that doesn't have one yet (NASA's), choosing the best
     * size the app will accept. Throws an [IOException] if there isn't one.
     */
    suspend fun resolve(hit: VideoCatalog.Hit): VideoCatalog.Hit {
        if (hit.fileUrl.isNotEmpty()) return hit
        val files = withContext(Dispatchers.IO) { VideoCatalog.nasaFiles(get(hit.manifestUrl, authorization = null)) }
        if (files.isEmpty()) throw IOException("This video has no file that can be downloaded")
        for (file in files) {
            val bytes = DownloadedVideos.sizeOf(file)
            if (bytes in 1..DownloadedVideos.MAX_BYTES) return hit.copy(fileUrl = file, bytes = bytes)
        }
        throw IOException("This video is too big to download")
    }

    private fun get(address: String, authorization: String?): String {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (authorization != null) c.setRequestProperty("Authorization", authorization)
            when (val code = c.responseCode) {
                200 -> return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                401, 403 -> throw IOException("The key was refused. Check it and try again")
                429 -> throw IOException("Too many searches just now. Try again in a little while")
                400 -> throw IOException("The service didn't like that search (it may have refused the key)")
                else -> throw IOException("The service answered $code")
            }
        } catch (e: IOException) {
            throw if (e.message?.contains("Unable to resolve") == true || e is java.net.UnknownHostException)
                IOException("No connection") else e
        } finally {
            c.disconnect()
        }
    }
}
