package com.portal.tv.ui

import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.portal.tv.R
import com.portal.tv.background.BackgroundPlayer
import com.portal.tv.background.WeatherBackground
import com.portal.tv.repo.MoonPhase
import com.portal.tv.repo.WeatherRepository
import com.portal.tv.settings.BackgroundSettings
import com.portal.tv.settings.AppSettings
import com.portal.tv.weather.WeatherAlert
import com.portal.tv.weather.WeatherReport
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Weather page, opened from the time and weather panel on the home screen:
 * current conditions, the next 12 hours, a 7-day forecast, details such as UV and air quality,
 * and the moon. It sits on a weather video, or a drawn scene when there is no video. Units follow
 * the profile's weather setting. Back returns to the home screen.
 */
class WeatherFragment : Fragment() {

    private var player: BackgroundPlayer? = null
    private var playerVideo: String? = null
    private var backgroundKey: String? = null

    /** Reloads the forecast, for after the place has changed. */
    private var refreshWeather: (() -> Unit)? = null

    private val metric get() = AppSettings.isMetric(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_weather, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val status = view.findViewById<TextView>(R.id.weather_status)
        val scroll = view.findViewById<ScrollView>(R.id.weather_scroll)
        val retry = view.findViewById<TextView>(R.id.weather_retry)
        val updated = view.findViewById<TextView>(R.id.weather_updated)
        var showing = false

        fun show(outcome: WeatherRepository.Outcome) {
            val report = outcome.report ?: return
            status.visibility = View.GONE
            retry.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            if (!showing) scroll.requestFocus()
            showing = true
            bind(view, report)
            applyBackground(view, report)
            loadAlerts(view)
            updated.visibility = View.VISIBLE
            updated.text = "Updated ${ago(outcome.updatedAt)}" +
                (outcome.error?.let { " · couldn't refresh: ${it.lowercase()}" } ?: "")
        }

        fun refresh() {
            retry.visibility = View.GONE
            if (!showing) {
                WeatherRepository.cached(requireContext())?.let { show(it) }
            }
            if (!showing) {
                status.visibility = View.VISIBLE
                status.text = "Loading…"
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = WeatherRepository.load(requireContext())
                if (outcome.report != null) {
                    show(outcome)
                } else {
                    status.visibility = View.VISIBLE
                    status.text = "Couldn't load the weather: ${outcome.error?.lowercase() ?: "error"}"
                    retry.visibility = View.VISIBLE
                    retry.requestFocus()
                }
            }
        }

        retry.setOnClickListener { refresh() }
        refreshWeather = ::refresh
        refresh()
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            time == 0L || minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} hr ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    private fun bind(view: View, r: WeatherReport) {
        view.findViewById<TextView>(R.id.weather_title).text =
            if (r.city != null) "Weather · ${r.city}" else "Weather"

        view.findViewById<FrameLayout>(R.id.weather_current_icon).apply {
            removeAllViews()
            addView(WeatherIcons.layered(context, 120, r.code, r.isDay))
        }
        view.findViewById<TextView>(R.id.weather_temp).text = temp(r.tempC)
        view.findViewById<TextView>(R.id.weather_condition).text = WeatherIcons.describe(r.code)
        view.findViewById<TextView>(R.id.weather_detail_feels).text = "Feels like ${temp(r.feelsLikeC)}"
        view.findViewById<TextView>(R.id.weather_detail_humidity).text = "Humidity ${r.humidity}%"
        view.findViewById<TextView>(R.id.weather_detail_wind).text =
            "Wind ${wind(r.windKmh)} ${compass(r.windDirection)}"
        r.days.firstOrNull()?.let {
            view.findViewById<TextView>(R.id.weather_detail_range).text =
                "High ${temp(it.highC)}  ·  Low ${temp(it.lowC)}"
        }

        val hourly = view.findViewById<LinearLayout>(R.id.weather_hourly)
        hourly.removeAllViews()
        r.hours.forEachIndexed { i, h ->
            hourly.addView(column(
                top = if (i == 0) "Now" else hourLabel(h.hour24),
                icon = WeatherIcons.layered(requireContext(), 44, h.code, h.isDay),
                bottom = temp(h.tempC),
                rain = h.rainChance
            ))
        }

        buildDetails(view, r)
        buildMoon(view, r)

        val radarSection = view.findViewById<View>(R.id.weather_radar_section)
        // Prefer the saved or detected place to the forecast's, which is rounded to its own grid.
        val place = WeatherRepository.knownLocation(requireContext()) ?: (r.latitude to r.longitude)
        if (place.first == 0.0 && place.second == 0.0) {
            radarSection.visibility = View.GONE
        } else {
            radarSection.visibility = View.VISIBLE
            val radar = view.findViewById<RadarView>(R.id.weather_radar)
            radar.show(
                place.first, place.second, ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds),
                startZoom = if (radar.isLocated) radar.zoom else null
            )
            radar.bindControls(
                view.findViewById(R.id.radar_zoom_in),
                view.findViewById(R.id.radar_zoom_out),
                view.findViewById(R.id.radar_play),
                view.findViewById(R.id.radar_layers),
                view.findViewById(R.id.radar_move),
                view.findViewById(R.id.radar_pin)
            ) { lat, lon ->
                WeatherRepository.setCustomSpot(requireContext(), lat, lon)
                refreshWeather?.invoke()
            }
            view.findViewById<View>(R.id.radar_fullscreen).setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.container,
                        RadarFragment.of(radar.home.first, radar.home.second, r.utcOffsetSeconds, radar.zoom,
                            radar.centerLat, radar.centerLon)
                    )
                    .addToBackStack("radar")
                    .commit()
            }
        }

        val daily = view.findViewById<LinearLayout>(R.id.weather_daily)
        daily.removeAllViews()
        r.days.forEachIndexed { i, d ->
            daily.addView(dayRow(
                name = if (i == 0) "Today"
                else d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                code = d.code,
                summary = com.portal.tv.repo.DayNote.precipitation(d, WeatherIcons.describe(d.code).lowercase(), metric)
                    ?: WeatherIcons.describe(d.code),
                note = com.portal.tv.repo.DayNote.of(d, metric),
                low = temp(d.lowC),
                high = temp(d.highC)
            ))
        }
    }


    // ---------- Alerts ----------

    /** Shows the active National Weather Service alerts at the top of the page, if the profile wants them. */
    private fun loadAlerts(view: View) {
        val context = requireContext()
        val box = view.findViewById<LinearLayout>(R.id.weather_alerts)
        if (!AppSettings.showWeatherAlerts(context)) {
            box.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val alerts = com.portal.tv.repo.WeatherAlerts.load(context)
            if (!isAdded) return@launch
            box.removeAllViews()
            for (alert in alerts) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = AlertStyle.background(alert, dp(14).toFloat())
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, hasFocus ->
                        v.animate().scaleX(if (hasFocus) 1.01f else 1f).scaleY(if (hasFocus) 1.01f else 1f).setDuration(120).start()
                        v.alpha = if (hasFocus) 1f else 0.92f
                    }
                    setOnClickListener { showAlertDetails(alert) }
                }
                val text = AlertStyle.textColor(alert)
                row.addView(label(alert.event, 22f).apply { setTextColor(text); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                val sub = listOf(AlertStyle.until(context, alert), alert.area).filter { it.isNotEmpty() }.joinToString("  ·  ")
                row.addView(label(sub, 16f).apply { setTextColor(text); alpha = 0.85f })
                box.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
            box.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** The full text of an alert, with what to do about it. */
    private fun showAlertDetails(alert: WeatherAlert) {
        val body = buildString {
            val until = AlertStyle.until(requireContext(), alert)
            if (until.isNotEmpty()) append(until.replaceFirstChar { it.uppercase() }).append("\n\n")
            append(alert.description.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ").trim())
            if (alert.instruction.isNotBlank()) {
                append("\n\nWhat to do: ").append(alert.instruction.replace(Regex("(?<!\\n)\\n(?!\\n)"), " ").trim())
            }
            if (alert.area.isNotBlank()) append("\n\nArea: ").append(alert.area)
        }
        PortalDialog.Builder(requireContext())
            .setTitle(alert.event)
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Background ----------

    /** Plays the weather video for the current conditions, or draws a scene when there is none. */
    private fun applyBackground(view: View, r: WeatherReport) {
        val context = requireContext()
        val texture = view.findViewById<TextureView>(R.id.weather_bg_video)
        val effect = view.findViewById<WeatherEffectView>(R.id.weather_bg_effect)
        val dim = view.findViewById<View>(R.id.weather_bg_dim)
        if (!BackgroundSettings.getWeatherBackground(context)) {
            stopBackground()
            backgroundKey = null
            texture.visibility = View.GONE
            effect.visibility = View.GONE
            dim.visibility = View.GONE
            return
        }
        val kind = WeatherBackground.kindFor(r.code)
        val video = WeatherBackground.videoFor(context, kind, r.isDay)
        val key = "$kind/$video/${r.isDay}"
        if (key == backgroundKey) return
        backgroundKey = key
        stopBackground()
        dim.visibility = View.VISIBLE
        if (video != null) {
            effect.visibility = View.GONE
            texture.visibility = View.VISIBLE
            playerVideo = video
            player = BackgroundPlayer(context, texture, dim, video, mayHaveSound = false).also { it.start(viewLifecycleOwner.lifecycleScope) }
        } else {
            texture.visibility = View.GONE
            effect.visibility = View.VISIBLE
            effect.show(kind, r.isDay)
        }
    }

    private fun stopBackground() {
        player?.stop()
        player = null
        playerVideo = null
    }

    override fun onPause() {
        super.onPause()
        player?.stop()
    }

    override fun onResume() {
        super.onResume()
        // Back from another app: the video was stopped in onPause, so start it again.
        if (playerVideo != null) player?.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        stopBackground()
        backgroundKey = null
        super.onDestroyView()
    }

    // ---------- Details ----------

    private fun buildDetails(view: View, r: WeatherReport) {
        val box = view.findViewById<LinearLayout>(R.id.weather_details)
        box.removeAllViews()
        val today = r.days.firstOrNull()
        val tiles = buildList<Pair<String, String>> {
            today?.sunrise?.let { add("Sunrise" to clock(it)) }
            today?.sunset?.let { add("Sunset" to clock(it)) }
            r.uvIndex?.let { uv ->
                val peak = today?.uvMax?.let { "  ·  peak ${Math.round(it)}" } ?: ""
                add("UV index" to "${Math.round(uv)} ${uvLabel(uv)}$peak")
            }
            r.gustKmh?.let { add("Wind gusts" to wind(it)) }
            r.cloudCover?.let { add("Cloud cover" to "$it%") }
            r.pressureHpa?.let { add("Pressure" to pressure(it)) }
            r.visibilityKm?.let { add("Visibility" to distance(it)) }
            today?.precipMm?.let { add("Rain today" to precip(it)) }
            r.aqi?.let { add("Air quality" to "$it ${aqiLabel(it)}") }
        }
        tiles.chunked(3).forEach { rowTiles ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val tile = rowTiles.getOrNull(i)
                row.addView(
                    LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        if (tile != null) {
                            addView(label(tile.first, 15f, secondary = true))
                            addView(label(tile.second, 22f))
                        }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            box.addView(row)
        }
        box.visibility = if (tiles.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- Moon ----------

    private fun buildMoon(view: View, r: WeatherReport) {
        val box = view.findViewById<LinearLayout>(R.id.weather_moon)
        box.removeAllViews()
        val ctx = requireContext()
        val now = System.currentTimeMillis()
        val moon = MoonPhase.stateAt(now)
        val offset = ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds)
        val zone = if (r.latitude == 0.0 && r.longitude == 0.0) java.time.ZoneId.systemDefault() else offset
        val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        fun at(t: Long) = Instant.ofEpochMilli(t).atZone(zone)

        box.addView(
            androidx.appcompat.widget.AppCompatImageView(ctx).apply {
                setImageDrawable(MoonPhotoDrawable.now(ctx))
            },
            LinearLayout.LayoutParams(dp(110), dp(110)).apply { marginEnd = dp(28) }
        )

        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(moon.name, 24f))
        text.addView(label(
            "${Math.round(moon.illumination * 100)}% lit  ·  ${if (moon.waxing) "waxing" else "waning"}  ·  " +
                "day ${Math.round(moon.ageDays)} of 29", 18f, secondary = true
        ).apply { setPadding(0, dp(2), 0, dp(10)) })

        val nextFull = MoonPhase.nextPhase(now, full = true)
        val nextNew = MoonPhase.nextPhase(now, full = false)
        fun upcoming(name: String, t: Long): String {
            val days = MoonPhase.daysUntil(now, t)
            val when_ = when (days) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $days days" }
            return "Next $name: ${at(t).format(dateFmt)} ($when_)"
        }
        // The nearer of the two comes first.
        for ((name, t) in listOf("full moon" to nextFull, "new moon" to nextNew).sortedBy { it.second }) {
            text.addView(label(upcoming(name, t), 18f))
        }

        if (r.latitude != 0.0 || r.longitude != 0.0) {
            val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val rs = MoonPhase.riseSet(dayStart, r.latitude, r.longitude)
            val parts = listOfNotNull(
                rs.rise?.let { "Moonrise ${at(it).format(timeFmt)}" },
                rs.set?.let { "Moonset ${at(it).format(timeFmt)}" }
            )
            if (parts.isNotEmpty()) {
                text.addView(label(parts.joinToString("  ·  "), 18f, secondary = true)
                    .apply { setPadding(0, dp(10), 0, 0) })
            }
        }
        box.addView(text)
    }

    // ---------- Detail wording ----------

    private fun clock(iso: String): String = try {
        LocalDateTime.parse(iso).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    } catch (e: Exception) {
        iso.takeLast(5)
    }

    private fun uvLabel(uv: Double) = when {
        uv < 3 -> "Low"
        uv < 6 -> "Moderate"
        uv < 8 -> "High"
        uv < 11 -> "Very high"
        else -> "Extreme"
    }

    private fun aqiLabel(aqi: Int) = when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for sensitive groups"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very unhealthy"
        else -> "Hazardous"
    }

    private fun pressure(hPa: Double) =
        if (metric) "${Math.round(hPa)} hPa" else "%.2f inHg".format(Locale.US, hPa * 0.02953)

    private fun distance(km: Double) =
        if (metric) "${Math.round(km)} km" else "${Math.round(km * 0.621371)} mi"

    private fun precip(mm: Double) =
        if (metric) "%.1f mm".format(Locale.US, mm) else "%.2f in".format(Locale.US, mm / 25.4)

    // ---------- Rows ----------

    private fun column(top: String, icon: View, bottom: String, rain: Int? = null): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(66)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(label(top, 16f, secondary = true).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
            addView(label(bottom, 20f).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(label(if (rain != null && rain >= 10) "$rain%" else " ", 14f, secondary = true)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun dayRow(name: String, code: Int, summary: String, note: String?, low: String, high: String): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label(name, 20f).apply { width = dp(170) })
            addView(WeatherIcons.layered(ctx, 40, code, true),
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            // The words take whatever room is left, so the temperatures are never pushed off the row. A
            // wet day's sentence ("70% chance of light rain, about 0.25 in") may run to a second line.
            addView(label(summary, 18f, secondary = true).apply {
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(label(note ?: "", 18f).apply {
                width = dp(150); gravity = Gravity.END
                setPadding(0, 0, dp(16), 0)
            })
            addView(label(low, 20f, secondary = true).apply { width = dp(64); gravity = Gravity.END })
            addView(label(high, 20f).apply { width = dp(64); gravity = Gravity.END })
        }
    }

    private fun label(text: String, sp: Float, secondary: Boolean = false) = TextView(requireContext()).apply {
        this.text = text
        isSingleLine = true
        textSize = sp
        typeface = resources.getFont(R.font.inter)
        setTextColor(resources.getColor(if (secondary) R.color.text_secondary else R.color.text_primary, null))
    }

    // ---------- Formatting ----------

    private fun temp(c: Double): String =
        if (metric) "${Math.round(c)}°" else "${Math.round(c * 9.0 / 5.0 + 32.0)}°"

    private fun wind(kmh: Double): String =
        if (metric) "${Math.round(kmh)} km/h" else "${Math.round(kmh * 0.621371)} mph"

    private fun compass(degrees: Int): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[Math.floorMod(Math.round(degrees / 45.0).toInt(), 8)]
    }

    private fun hourLabel(h: Int): String {
        val hour12 = if (h % 12 == 0) 12 else h % 12
        return "$hour12 ${if (h < 12) "AM" else "PM"}"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
