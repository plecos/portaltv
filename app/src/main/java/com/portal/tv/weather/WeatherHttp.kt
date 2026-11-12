package com.portal.tv.weather

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** A plain GET for the weather services. Never throws: a failure is a [code] of -1 and a short [failure]. */
internal object WeatherHttp {

    private const val TAG = "Portal"

    class Response(val code: Int, val body: String?, val failure: String?) {
        val ok get() = code == 200 && body != null
    }

    fun get(url: String, userAgent: String = "Portal/1.0", accept: String? = null): Response = try {
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
