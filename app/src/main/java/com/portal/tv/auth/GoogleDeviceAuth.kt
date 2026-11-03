package com.portal.tv.auth

import android.content.Context
import com.portal.tv.search.SecretStore
import com.portal.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google sign-in for TVs: OAuth 2.0 device authorization grant.
 *
 * The TV shows a short code and a URL; the user approves on their phone. Only
 * the profile info (name, email, photo) is requested, and no tokens are kept.
 *
 * Needs an OAuth client of type "TVs and Limited Input devices" from Google
 * Cloud. A private build can bake it in as GOOGLE_TV_CLIENT_ID / GOOGLE_TV_CLIENT_SECRET in
 * local.properties; public builds carry none, and the user enters their own (kept encrypted, see
 * [save]).
 */
object GoogleDeviceAuth {

    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    private const val SECRET_ID = "google_client_id"
    private const val SECRET_SECRET = "google_client_secret"

    @Volatile private var clientId = BuildConfig.GOOGLE_TV_CLIENT_ID
    @Volatile private var clientSecret = BuildConfig.GOOGLE_TV_CLIENT_SECRET

    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** True when the credentials were built into the app, so there is nothing for the user to enter. */
    val isBuiltIn: Boolean
        get() = BuildConfig.GOOGLE_TV_CLIENT_ID.isNotBlank() && BuildConfig.GOOGLE_TV_CLIENT_SECRET.isNotBlank()

    /** Picks the credentials: the built-in ones if there are any, else what the user entered. */
    fun load(context: Context) {
        if (isBuiltIn) return
        clientId = SecretStore.get(context, SECRET_ID).orEmpty()
        clientSecret = SecretStore.get(context, SECRET_SECRET).orEmpty()
    }

    /** Stores the user's own OAuth client, encrypted. */
    fun save(context: Context, id: String, secret: String) {
        SecretStore.put(context, SECRET_ID, id.trim())
        SecretStore.put(context, SECRET_SECRET, secret.trim())
        load(context)
    }

    /** What to show the user while they approve on another device. */
    data class Challenge(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int
    )

    data class GoogleProfile(
        val name: String,
        val email: String,
        val photo: ByteArray?
    )

    class AuthException(message: String) : Exception(message)

    suspend fun start(): Challenge = withContext(Dispatchers.IO) {
        val json = post(
            DEVICE_CODE_URL,
            "client_id" to clientId,
            "scope" to "openid email profile"
        )
        Challenge(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.getString("verification_url"),
            intervalSeconds = json.optInt("interval", 5),
            expiresInSeconds = json.optInt("expires_in", 1800)
        )
    }

    /**
     * Polls until the user approves, then returns their profile. Throws
     * [AuthException] if the code expires or the user denies; cancel the
     * coroutine to abort.
     */
    suspend fun awaitProfile(challenge: Challenge): GoogleProfile {
        var interval = challenge.intervalSeconds
        val deadline = System.currentTimeMillis() + challenge.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val json = withContext(Dispatchers.IO) {
                post(
                    TOKEN_URL,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "device_code" to challenge.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    allowErrors = true
                )
            }
            when (json.optString("error")) {
                "" -> return fetchProfile(json.getString("access_token"))
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw AuthException("Sign-in was declined")
                "expired_token" -> throw AuthException("The code expired")
                else -> throw AuthException(json.optString("error_description", "Sign-in failed"))
            }
        }
        throw AuthException("The code expired")
    }

    private suspend fun fetchProfile(accessToken: String): GoogleProfile =
        withContext(Dispatchers.IO) {
            val info = JSONObject(get(USERINFO_URL, accessToken).decodeToString())
            val photo = info.optString("picture").takeIf { it.isNotEmpty() }?.let {
                runCatching { get(it, null) }.getOrNull()
            }
            GoogleProfile(
                name = info.optString("name"),
                email = info.getString("email"),
                photo = photo
            )
        }

    private fun post(
        url: String,
        vararg params: Pair<String, String>,
        allowErrors: Boolean = false
    ): JSONObject {
        val body = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (conn.responseCode !in 200..299 && !allowErrors) {
                throw AuthException("Google returned ${conn.responseCode}")
            }
            return JSONObject(text.ifEmpty { "{}" })
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, bearer: String?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (conn.responseCode !in 200..299) throw AuthException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
