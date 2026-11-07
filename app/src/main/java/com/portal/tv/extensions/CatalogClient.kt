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

    /**
     * The copy kept from the last time, or null if there is none, it can't be read, or it no longer checks out against the
     * keys this Portal trusts. No network. It is kept as the signature (one line) followed by the catalog, in one file, so
     * the two can never be out of step.
     */
    fun cached(context: Context): Copy? {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val (signature, catalog) = split(bytes) ?: return null
        if (CatalogTrust.verify(catalog, signature, CatalogTrust.keys(BuildConfig.DEBUG)) !is CatalogTrust.Result.Ok) return null
        val ok = CatalogParser.parse(catalog.toString(Charsets.UTF_8), BuildConfig.DEBUG) as? CatalogParser.Result.Ok ?: return null
        return Copy(ok.catalog, prefs(context).getLong("fetched_at", 0L))
    }

    /** The signature line and the catalog bytes of a kept file, or null if it has no signature line. */
    internal fun split(stored: ByteArray): Pair<String, ByteArray>? {
        val newline = stored.indexOf('\n'.code.toByte())
        if (newline <= 0 || newline > CatalogTrust.MAX_SIGNATURE_BYTES) return null
        return stored.copyOfRange(0, newline).toString(Charsets.UTF_8) to stored.copyOfRange(newline + 1, stored.size)
    }

    /** The signature file next to the catalog; null if it isn't there, so the catalog is then refused. */
    private fun fetchSignature(address: String): String? {
        val c = URL("$address.sig").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != 200) null else readCapped(c.inputStream, CatalogTrust.MAX_SIGNATURE_BYTES)
        } catch (e: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** The highest serial Portal has accepted; a catalog with a lower one is an old copy and is refused. */
    fun lastSerial(context: Context): Long = prefs(context).getLong("serial", 0L)

    /** When Portal may next fetch on its own, after the host said it was busy (0 if never). */
    fun retryUntil(context: Context): Long = prefs(context).getLong("retry_until", 0L)

    /** This TV's own extra wait before its daily check, rolled again after each good fetch so TVs drift apart. */
    fun jitterMs(context: Context): Long = prefs(context).getLong("jitter_ms", 0L)

    /**
     * Fetches the catalog now and keeps it. If the kept copy is current the host answers "not modified" and nothing is
     * downloaded. Throws if it can't be reached or isn't a usable catalog; the old copy stays.
     */
    suspend fun fetch(context: Context): Copy = withContext(Dispatchers.IO) {
        val address = url(context)
        val p = prefs(context)
        p.edit().putLong("attempted_at", System.currentTimeMillis()).apply()
        if (!address.startsWith("https://") && !(BuildConfig.DEBUG && address.startsWith("http://"))) {
            throw IOException("The catalog isn't offered over a secure connection")
        }
        val have = cached(context)
        val c = URL(address).openConnection() as HttpURLConnection
        var etag: String? = null
        var modified: String? = null
        val body: ByteArray? = try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            // Ask "has it changed?" when there is a copy to keep: nearly every check is then a few hundred bytes.
            if (have != null) {
                p.getString("etag", null)?.let { c.setRequestProperty("If-None-Match", it) }
                p.getString("last_modified", null)?.let { c.setRequestProperty("If-Modified-Since", it) }
            }
            when (val code = c.responseCode) {
                200 -> {
                    etag = c.getHeaderField("ETag")
                    modified = c.getHeaderField("Last-Modified")
                    readBytesCapped(c.inputStream, CatalogParser.MAX_FILE_CHARS * 4)
                }
                304 -> if (have != null) null else throw IOException("The catalog answered 304 with nothing kept")
                429, 503 -> {
                    // The host asked for time: stay away for as long as it says (an hour if it doesn't say).
                    val wait = CatalogHttp.retryAfterMs(c.getHeaderField("Retry-After"), System.currentTimeMillis())
                    p.edit().putLong("retry_until", System.currentTimeMillis() + wait).apply()
                    throw IOException("The catalog is busy just now")
                }
                404 -> throw IOException("The catalog isn't online yet")
                else -> throw IOException("The catalog answered $code")
            }
        } catch (e: UnknownHostException) {
            throw IOException("No connection")
        } catch (e: java.net.ConnectException) {
            throw IOException("Couldn't reach the catalog")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("The catalog took too long to answer")
        } finally {
            c.disconnect()
        }
        val now = System.currentTimeMillis()
        if (body == null) {
            // Not modified: the copy is as fresh as the host's.
            p.edit().putLong("fetched_at", now).putLong("jitter_ms", CatalogHttp.newJitterMs(kotlin.random.Random)).remove("retry_until").apply()
            return@withContext Copy(have!!.catalog, now)
        }
        // Nothing in the file is trusted until its signature checks out against a key built into this Portal.
        val signature = fetchSignature(address)
        when (val trust = CatalogTrust.verify(body, signature, CatalogTrust.keys(BuildConfig.DEBUG))) {
            is CatalogTrust.Result.Refused -> throw IOException(trust.reason + " It was not used.")
            is CatalogTrust.Result.Ok -> {}
        }
        when (val r = CatalogParser.parse(body.toString(Charsets.UTF_8), BuildConfig.DEBUG)) {
            is CatalogParser.Result.Invalid -> throw IOException(r.reason)
            is CatalogParser.Result.NeedsNewerPortal -> throw IOException("The catalog needs a newer Portal")
            is CatalogParser.Result.Ok -> {
                if (r.catalog.serial < 1) throw IOException("The catalog has no serial number, so it was not used.")
                // A signed catalog is still refused if it is older than one already used: that is how a withdrawal
                // would be undone by putting an old copy back.
                if (r.catalog.serial < lastSerial(context)) throw IOException("The catalog is older than one Portal already used, so it was not used.")
                // The signature line and the catalog go into one file, written beside the old one and moved into place,
                // so a power cut can't leave half a file (which would read as "no catalog" and stop withdrawals).
                val target = File(context.filesDir, FILE)
                val temp = File(context.filesDir, "$FILE.tmp")
                temp.writeBytes((signature!!.trim() + "\n").toByteArray(Charsets.UTF_8) + body)
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                p.edit().putLong("fetched_at", now).putLong("serial", r.catalog.serial).putString("etag", etag).putString("last_modified", modified)
                    .putLong("jitter_ms", CatalogHttp.newJitterMs(kotlin.random.Random)).remove("retry_until").apply()
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
