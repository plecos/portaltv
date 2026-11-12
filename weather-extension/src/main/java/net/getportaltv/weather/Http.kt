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
