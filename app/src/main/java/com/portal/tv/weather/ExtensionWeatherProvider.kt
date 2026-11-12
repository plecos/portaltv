package com.portal.tv.weather

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.portal.tv.extensions.Capability
import com.portal.tv.extensions.ExtensionRegistry
import com.portal.tv.extensions.TextSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Weather from an extension. Each time Portal wants weather it checks the extension is still installed, still signed by who
 * was allowed and still allowed to be a weather source, asks it, and keeps the answer only if [WeatherValidator] accepts it.
 * No other source stands in when it can't answer: Portal keeps showing what it had, marked as old.
 */
class ExtensionWeatherProvider(
    private val context: Context,
    private val packageName: String,
    override val name: String
) : WeatherProvider {

    private val app = context.applicationContext

    override suspend fun fetchReport(place: WeatherPlace): ProviderResult<WeatherReport> {
        val (answer, _) = ask(WeatherProtocol.MSG_REPORT, place)
        return when (answer) {
            is ProviderResult.Failed -> answer
            is ProviderResult.Ok -> WeatherValidator.report(answer.value, place)?.let { ProviderResult.Ok(it) }
                ?: ProviderResult.Failed(UNUSABLE)
        }
    }

    override suspend fun fetchAlerts(place: WeatherPlace): ProviderResult<List<WeatherAlert>> {
        val (answer, shownAs) = ask(WeatherProtocol.MSG_ALERTS, place)
        return when (answer) {
            is ProviderResult.Failed -> answer
            is ProviderResult.Ok -> WeatherValidator.alerts(answer.value, packageName, shownAs)?.let { ProviderResult.Ok(it) }
                ?: ProviderResult.Failed(UNUSABLE)
        }
    }

    override suspend fun searchCities(query: String): ProviderResult<List<WeatherCity>> {
        val (answer, _) = ask(WeatherProtocol.MSG_SEARCH_CITIES, null, query)
        return when (answer) {
            is ProviderResult.Failed -> answer
            is ProviderResult.Ok -> WeatherValidator.cities(answer.value)?.let { ProviderResult.Ok(it) }
                ?: ProviderResult.Failed(UNUSABLE)
        }
    }

    /** The extension's raw answer, or why there is none; and the name to show it under. */
    private suspend fun ask(what: Int, place: WeatherPlace?, query: String? = null): Pair<ProviderResult<String>, String> {
        // Checked again now: still installed, still signed by who was allowed, still allowed to supply the weather.
        val ext = withContext(Dispatchers.IO) { ExtensionRegistry.find(context, packageName) }
        val service = ext?.weatherService
        if (ext == null || service == null || Capability.WEATHER_SOURCE !in ext.granted) {
            return ProviderResult.Failed("It isn't available") to name
        }
        val result = try {
            withTimeout(WeatherProtocol.ANSWER_TIMEOUT_MS) { exchange(service, what, place, query) }
        } catch (e: TimeoutCancellationException) {
            ProviderResult.Failed("It didn't answer")
        }
        return result to ext.name
    }

    private suspend fun exchange(service: String, what: Int, place: WeatherPlace?, query: String?): ProviderResult<String> =
        suspendCancellableCoroutine { cont ->
            var connection: ServiceConnection? = null
            fun finish(result: ProviderResult<String>) {
                if (!cont.isActive) return
                connection?.let { runCatching { app.unbindService(it) } }
                cont.resume(result)
            }
            val replies = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what != WeatherProtocol.MSG_REPLY) return
                    // Only the two named keys are read; the rest of the message is never looked at.
                    val data = msg.data
                    val json = data?.getString(WeatherProtocol.KEY_JSON)
                    val error = data?.getString(WeatherProtocol.KEY_ERROR)
                    finish(
                        when {
                            json != null -> ProviderResult.Ok(json)
                            else -> ProviderResult.Failed(error?.trim()?.takeIf { it.isNotEmpty() && it.length <= WeatherProtocol.MAX_ERROR && TextSafety.isPlain(it) } ?: "It had no weather")
                        }
                    )
                }
            })
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val request = Message.obtain(null, what).apply {
                        replyTo = replies
                        data = Bundle().apply {
                            if (place != null) {
                                putDouble(WeatherProtocol.KEY_LAT, WeatherProtocol.rounded(place.lat))
                                putDouble(WeatherProtocol.KEY_LON, WeatherProtocol.rounded(place.lon))
                                place.city?.let { putString(WeatherProtocol.KEY_CITY, it) }
                            }
                            query?.let { putString(WeatherProtocol.KEY_QUERY, it) }
                        }
                    }
                    if (runCatching { Messenger(binder).send(request) }.isFailure) finish(ProviderResult.Failed("Portal couldn't reach it"))
                }
                override fun onServiceDisconnected(name: ComponentName) = finish(ProviderResult.Failed("It stopped"))
                override fun onBindingDied(name: ComponentName) = finish(ProviderResult.Failed("It stopped"))
                override fun onNullBinding(name: ComponentName) = finish(ProviderResult.Failed("It didn't offer weather"))
            }
            connection = conn
            cont.invokeOnCancellation { runCatching { app.unbindService(conn) } }
            val bound = runCatching {
                app.bindService(Intent(WeatherProtocol.ACTION).setComponent(ComponentName(packageName, service)), conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) finish(ProviderResult.Failed("Portal couldn't connect to it"))
        }

    private companion object {
        const val UNUSABLE = "Its weather couldn't be used"
    }
}
