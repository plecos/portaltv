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

    /** The page the catalog is shown on, for a phone. Shown as a QR code. */
    const val WEB_PAGE = "https://getportaltv.net/extensions"

    private const val USER_AGENT = "Portal TV (https://github.com/plecos/portaltv)"
    private const val PREFS = "extension_catalog"
    private const val FILE = "extension_catalog.json"

    /** The catalog and when it was fetched. */
    class Copy(val catalog: Catalog, val fetchedAtMs: Long)

    /** Debug builds can read a catalog from a test server, through a preference, to try Browse without the real one. */
    private fun url(context: Context): String {
        if (!BuildConfig.DEBUG) return URL
        return context.getSharedPreferences("catalog_debug", Context.MODE_PRIVATE).getString("url", null) ?: URL
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        if (!address.startsWith("https://") && !(BuildConfig.DEBUG && address.startsWith("http://"))) {
            throw IOException("The catalog isn't offered over a secure connection")
        }
        val c = URL(address).openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != 200) throw IOException("The catalog answered ${c.responseCode}")
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
    internal fun readCapped(input: InputStream, maxBytes: Int): String {
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
        return out.toString(Charsets.UTF_8.name())
    }
}
