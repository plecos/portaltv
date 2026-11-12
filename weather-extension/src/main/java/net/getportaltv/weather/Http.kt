package net.getportaltv.weather

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** What a source got back: the value, or the short reason it could not be had (such as "No connection"). */
sealed class Fetched<out T> {
    data class Ok<T>(val value: T) : Fetched<T>()
    data class Failed(val reason: String) : Fetched<Nothing>()
}

/** A plain GET for the weather services. Never throws: a failure is a [code] of -1 and a short [failure]. */
internal object Http {

    private const val TAG = "PortalWeather"

    class Response(val code: Int, val body: String?, val failure: String?) {
        val ok get() = code == 200 && body != null
    }

    /** The bytes at [url] (a picture), at most [max] of them, or null if it couldn't be had or was bigger. */
    fun bytes(url: String, max: Int = 2_000_000): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Portal TV Weather (https://github.com/plecos/portaltv)")
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > max) return@use null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "HTTP GET (picture) $url failed: ${e.message}")
        null
    }

    fun get(url: String, userAgent: String = "PortalWeather/1.0", accept: String? = null): Response = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", userAgent)
        accept?.let { conn.setRequestProperty("Accept", it) }
        try {
            val code = conn.responseCode
            if (code == 200) Response(code, conn.inputStream.bufferedReader().readText(), null)
            else Response(code, null, "Server error")
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "HTTP GET $url failed: ${e.message}")
        Response(-1, null, if (e is java.net.SocketTimeoutException) "Timed out" else "No connection")
    }
}
