package com.portal.tv.extensions

import android.content.Context
import com.portal.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Fetches the catalog and keeps the last good copy on the TV, so Browse works offline and opens at once. The request
 * sends nothing about this TV: no account, no id, no list of what is installed (only what any web request carries,
 * such as an address). Every failure is an [IOException] whose message can be shown to the person.
 */
object CatalogClient {

    const val URL = "https://getportaltv.net/extensions/catalog.json"

    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"
    private const val PREFS = "extension_catalog"
    private const val FILE = "extension_catalog.json"

    /** The catalog and when it was fetched. */
    class Copy(val catalog: Catalog, val fetchedAtMs: Long)

    /** Debug builds can read a catalog from a test server, through a preference, to try Browse without the real one. */
    fun url(context: Context): String {
        if (!BuildConfig.DEBUG) return URL
        return context.getSharedPreferences("catalog_debug", Context.MODE_PRIVATE).getString("url", null) ?: URL
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var memo: Triple<Long, Long, Catalog?>? = null

    /**
     * The catalog from the copy kept on the TV, parsed once per version of the file so asking is cheap. No network.
     * Null if there is none.
     */
    @Synchronized
    fun cachedCatalog(context: Context): Catalog? {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) { memo = null; return null }
        val key = file.lastModified() to file.length()
        memo?.let { if (it.first == key.first && it.second == key.second) return it.third }
        val catalog = cached(context)?.catalog
        memo = Triple(key.first, key.second, catalog)
        return catalog
    }

    /** When a fetch was last tried, good or not, so being offline doesn't mean trying every time. */
    fun attemptedAt(context: Context): Long = prefs(context).getLong("attempted_at", 0L)

    /** The copy kept from the last time, or null if there is none or it can't be read. No network. */
    fun cached(context: Context): Copy? {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val ok = CatalogParser.parse(text, BuildConfig.DEBUG) as? CatalogParser.Result.Ok ?: return null
        return Copy(ok.catalog, prefs(context).getLong("fetched_at", 0L))
    }

    /** Fetches the catalog now and keeps it. Throws if it can't be reached or isn't a usable catalog; the old copy stays. */
    suspend fun fetch(context: Context): Copy = withContext(Dispatchers.IO) {
        val address = url(context)
        prefs(context).edit().putLong("attempted_at", System.currentTimeMillis()).apply()
        if (!address.startsWith("https://") && !(BuildConfig.DEBUG && address.startsWith("http://"))) {
            throw IOException("The catalog isn't offered over a secure connection")
        }
        val c = URL(address).openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IOException("The catalog isn't online yet")
                else -> throw IOException("The catalog answered $code")
            }
            readCapped(c.inputStream, CatalogParser.MAX_FILE_CHARS * 4)
        } catch (e: UnknownHostException) {
            throw IOException("No connection")
        } catch (e: java.net.ConnectException) {
            throw IOException("Couldn't reach the catalog")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The catalog took too long to answer")
        } finally {
            c.disconnect()
        }
        when (val r = CatalogParser.parse(text, BuildConfig.DEBUG)) {
            is CatalogParser.Result.Invalid -> throw IOException(r.reason)
            is CatalogParser.Result.NeedsNewerPortal -> throw IOException("The catalog needs a newer Portal")
            is CatalogParser.Result.Ok -> {
                val now = System.currentTimeMillis()
                File(context.filesDir, FILE).writeText(text)
                prefs(context).edit().putLong("fetched_at", now).apply()
                Copy(r.catalog, now)
            }
        }
    }

    /** All of [input] as text, or an [IOException] if it is longer than [maxBytes]. */
    internal fun readCapped(input: InputStream, maxBytes: Int): String = readBytesCapped(input, maxBytes).toString(Charsets.UTF_8)

    internal fun readBytesCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) throw IOException("The catalog is too big")
            }
        }
        return out.toByteArray()
    }
}
