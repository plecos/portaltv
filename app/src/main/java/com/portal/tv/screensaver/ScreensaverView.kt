package com.portal.tv.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.portal.tv.R
import com.portal.tv.background.BackgroundRepository
import com.portal.tv.repo.WeatherRepository
import com.portal.tv.settings.AppSettings
import com.portal.tv.settings.VideoSound
import com.portal.tv.ui.ShaderBackgroundView
import com.portal.tv.ui.ShaderPalettes
import com.portal.tv.ui.ShaderStyles
import com.portal.tv.settings.BirthdayStore
import com.portal.tv.ui.WeatherIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The screen saver: the videos in shuffled order, fading through black between them (or, when the
 * background is a shader, that shader), with only the time and weather on top. That block bounces around the screen, off
 * every edge, like the logo on an old DVD player, so no pixel stays lit. Overnight it can dim or go fully black (see [ScreensaverSettings]).
 *
 * Used by both the in-app idle timer ([ScreensaverFragment]) and the system screen saver
 * ([ScreensaverService]); it plays while attached to a window and stops when detached.
 */
class ScreensaverView(context: Context) : FrameLayout(context) {

    private val ui = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    private val textureView = TextureView(context)
    private val fade = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 1f }
    private val nightShade = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

    private val timeText = text(72f, bold = true)
    private val weatherIcon = FrameLayout(context)
    private val weatherText = text(26f)
    private val weatherRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val greetingText = text(24f, bold = true).apply { visibility = View.GONE }
    private val clockBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var input: FileInputStream? = null
    private var playlist: List<String> = emptyList()
    private var nextIndex = 0
    private var failures = 0
    private var playing = false
    private var blackout = false
    private var playJob: Job? = null

    /** The shader being shown instead of videos, when the profile's background is a shader. */
    private var shader: ShaderBackgroundView? = null
    /** The extension's picture, when the screen saver is one an extension draws. */
    private var extensionView: com.portal.tv.ui.ExtensionBackgroundView? = null

    private val tick = object : Runnable {
        override fun run() {
            timeText.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 1000)
        }
    }
    /** Once a minute, checks whether it has become (or stopped being) night. */
    private val minuteTick = object : Runnable {
        override fun run() {
            applyNight()
            ui.postDelayed(this, MINUTE_MS)
        }
    }

    // The clock's place on the screen, in pixels from the top left, and its speed in each direction.
    private var bounceX = 0f
    private var bounceY = 0f
    private var speedX = 0f
    private var speedY = 0f
    private var placed = false
    private var lastFrameNanos = 0L
    private val bounce = object : Runnable {
        override fun run() {
            stepBounce()
            if (isAttachedToWindow && !blackout) postOnAnimation(this)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        weatherRow.addView(weatherIcon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        weatherRow.addView(weatherText)
        clockBlock.addView(timeText)
        clockBlock.addView(weatherRow)
        clockBlock.addView(greetingText, LinearLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(nightShade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The clock sits at the top left and is moved from there by [stepBounce].
        addView(clockBlock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        othersWerePlaying = VideoSound.othersPlaying(context)
        startShaderIfWanted()
        buildPlaylist()
        if (shader != null) ui.postDelayed({ if (!blackout) fade.animate().alpha(0f).setDuration(FADE_MS).start() }, 300)
        showGreeting()
        tick.run()
        ui.postDelayed(minuteTick, MINUTE_MS)
        postOnAnimation(bounce)
        applyNight()
        refreshWeatherLoop()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                startPlaylist()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                stopPlaylist()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (textureView.isAvailable) {
            surface = Surface(textureView.surfaceTexture)
            startPlaylist()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ui.removeCallbacksAndMessages(null)
        removeCallbacks(bounce)
        stopShader()
        stopExtension()
        stopPlaylist()
        scope?.cancel()
        scope = null
    }

    // ---------- Shader ----------

    /** With the shader look, the screen saver is a slowly moving shader in the colors and style chosen for it. */
    private fun startShaderIfWanted() {
        stopShader()
        stopExtension()
        val look = ScreensaverSettings.look(context)
        val chosen = ScreensaverSettings.extension(context)
        if (look == ScreensaverSettings.LOOK_EXTENSION && chosen != null) { startExtension(chosen); return }
        if (look != ScreensaverSettings.LOOK_SHADER) return
        startShader()
    }

    /**
     * An extension draws the screen saver in its own process and Portal only shows the result, as it does for the home
     * screen's background. The screen stays black until its first picture arrives and then fades in; if it can't be used
     * (not answering, no longer allowed) the screen saver turns into the shader instead, so it is never left black.
     */
    private fun startExtension(packageName: String) {
        val scope = scope ?: return
        val bg = com.portal.tv.ui.ExtensionBackgroundView(context)
        bg.onReady = { if (!blackout) fade.animate().alpha(0f).setDuration(FADE_MS).start() }
        bg.onFailed = { reason ->
            android.util.Log.w("Portal", "Extension screen saver $packageName not used: $reason")
            stopExtension()
            startShader()
            if (!blackout) fade.animate().alpha(0f).setDuration(FADE_MS).start()
        }
        addView(bg, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        extensionView = bg
        textureView.visibility = View.GONE
        bg.start(packageName, scope)
    }

    private fun stopExtension() {
        extensionView?.let {
            it.stop()
            removeView(it)
        }
        extensionView = null
    }

    private fun startShader() {
        val view = ShaderBackgroundView(
            context,
            ShaderPalettes.find(ScreensaverSettings.shaderColor(context)),
            ShaderStyles.find(ScreensaverSettings.shaderStyle(context)),
            1f,
            ScreensaverSettings.shaderCycle(context)
        )
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shader = view
        textureView.visibility = View.GONE
        view.onResume()
    }

    private fun stopShader() {
        shader?.let {
            it.onPause()
            removeView(it)
        }
        shader = null
        textureView.visibility = View.VISIBLE
    }

    // ---------- Video ----------

    private fun buildPlaylist() {
        if (shader != null || extensionView != null) {
            playlist = emptyList()
            return
        }
        val videos = ScreensaverSettings.enabledVideos(context).toMutableList()
        if (BirthdayStore.on(context).isNotEmpty()) videos.add(BackgroundRepository.birthdayVideo(context))
        playlist = videos.shuffled()
        nextIndex = 0
    }

    private fun startPlaylist() {
        if (playing || blackout || surface == null || playlist.isEmpty()) return
        playing = true
        failures = 0
        playNext()
    }

    private fun stopPlaylist() {
        playing = false
        playJob?.cancel()
        releasePlayer()
    }

    private fun playNext() {
        val s = scope ?: return
        playJob?.cancel()
        playJob = s.launch {
            val asset = playlist[nextIndex % playlist.size]
            nextIndex++
            val file = withContext(Dispatchers.IO) {
                val repo = BackgroundRepository(context, asset)
                if (repo.extractBundledVideo()) repo.getVideoFile() else null
            }
            if (!playing) return@launch
            if (file == null) { onFailure(); return@launch }

            fadeTo(1f)
            releasePlayer()
            try {
                val fis = FileInputStream(file)
                val mp = MediaPlayer()
                mp.setSurface(surface)
                mp.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                val volume = volume()
                mp.setVolume(volume, volume)
                mp.isLooping = playlist.size == 1
                mp.setDataSource(fis.fd, 0, file.length())
                mp.setOnPreparedListener {
                    failures = 0
                    // A video that can't seek back can't loop; ending it lets the playlist start it again.
                    if (it.duration <= 0) it.isLooping = false
                    it.start()
                    if (!blackout) fade.animate().alpha(0f).setDuration(FADE_MS).start()
                }
                mp.setOnCompletionListener { if (playing) playNext() }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Screensaver video error $what/$extra")
                    onFailure()
                    true
                }
                input = fis
                player = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "Screensaver video failed: ${e.message}")
                onFailure()
            }
        }
    }

    /** Skip a broken video, but give up if several fail in a row so it doesn't spin. */
    private fun onFailure() {
        if (++failures >= 3) {
            playing = false
            return
        }
        ui.postDelayed({ if (playing) playNext() }, 500)
    }

    private fun releasePlayer() {
        try {
            player?.stop()
            player?.release()
        } catch (e: IllegalStateException) {
            // already released
        }
        player = null
        try { input?.close() } catch (e: Exception) { }
        input = null
    }

    private suspend fun fadeTo(alpha: Float) {
        if (fade.alpha == alpha) return
        fade.animate().alpha(alpha).setDuration(FADE_MS).start()
        delay(FADE_MS + 50)
    }

    // ---------- Night ----------

    private fun isNight(): Boolean {
        return ScreensaverSettings.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    /** Another app was playing sound when the screen saver opened, so the saver stays quiet over it. */
    private var othersWerePlaying = false

    /**
     * The volume for the screen saver's videos: the profile's choice, but silent while the screen is black, silent
     * overnight unless the night setting is Normal, and silent over another app's sound.
     */
    private fun volume(): Float {
        if (blackout || othersWerePlaying) return 0f
        if (isNight() && ScreensaverSettings.night(context) != ScreensaverSettings.NIGHT_NORMAL) return 0f
        return VideoSound.volume(ScreensaverSettings.sound(context))
    }

    private fun applyVolume() {
        val v = volume()
        runCatching { player?.setVolume(v, v) }
    }

    private fun applyNight() {
        val mode = if (isNight()) ScreensaverSettings.night(context) else ScreensaverSettings.NIGHT_NORMAL
        nightShade.animate().alpha(if (mode == ScreensaverSettings.NIGHT_DIM) NIGHT_DIM_ALPHA else 0f)
            .setDuration(FADE_MS).start()
        val black = mode == ScreensaverSettings.NIGHT_BLACK
        if (black == blackout) { applyVolume(); return }
        blackout = black
        applyVolume()
        clockBlock.visibility = if (black) View.INVISIBLE else View.VISIBLE
        if (black) removeCallbacks(bounce) else { lastFrameNanos = 0L; postOnAnimation(bounce) }
        if (black) {
            fade.alpha = 1f
            stopPlaylist()
            shader?.onPause()
        } else {
            startPlaylist()
            shader?.let {
                it.onResume()
                fade.animate().alpha(0f).setDuration(FADE_MS).start()
            }
        }
    }

    // ---------- Clock and weather ----------

    /**
     * Moves the clock a little, at a steady speed, turning back when it reaches an edge: the way the logo bounced round
     * the screen of an old DVD player. The distance covered depends on the time since the last frame, so the speed is
     * the same however fast frames come.
     */
    private fun stepBounce() {
        val w = width
        val h = height
        val blockW = clockBlock.width
        val blockH = clockBlock.height
        if (w == 0 || h == 0 || blockW == 0 || blockH == 0) { lastFrameNanos = 0L; return }
        // Kept a little in from the edges, since some TVs hide a strip around the picture.
        val margin = dp(40).toFloat()
        val maxX = (w - blockW - margin).coerceAtLeast(margin)
        val maxY = (h - blockH - margin).coerceAtLeast(margin)
        if (!placed) {
            placed = true
            bounceX = margin + Math.random().toFloat() * (maxX - margin)
            bounceY = margin + Math.random().toFloat() * (maxY - margin)
            val pace = dp(PACE_DP).toFloat()
            speedX = if (Math.random() < 0.5) pace else -pace
            speedY = if (Math.random() < 0.5) pace * 0.7f else -pace * 0.7f
        }
        val now = System.nanoTime()
        val seconds = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
        lastFrameNanos = now
        bounceX += speedX * seconds
        bounceY += speedY * seconds
        if (bounceX < margin) { bounceX = margin; speedX = Math.abs(speedX) }
        if (bounceX > maxX) { bounceX = maxX; speedX = -Math.abs(speedX) }
        if (bounceY < margin) { bounceY = margin; speedY = Math.abs(speedY) }
        if (bounceY > maxY) { bounceY = maxY; speedY = -Math.abs(speedY) }
        clockBlock.translationX = bounceX
        clockBlock.translationY = bounceY
    }

    private fun refreshWeatherLoop() {
        scope?.launch {
            while (true) {
                WeatherRepository.cached(context)?.report?.let { bindWeather(it) }
                WeatherRepository.load(context).report?.let { bindWeather(it) }
                delay(WEATHER_REFRESH_MS)
            }
        }
    }

    private fun bindWeather(r: WeatherRepository.Report) {
        val metric = AppSettings.isMetric(context)
        val temp = if (metric) "${Math.round(r.tempC)}°C" else "${Math.round(r.tempC * 9.0 / 5.0 + 32.0)}°F"
        weatherIcon.removeAllViews()
        weatherIcon.addView(WeatherIcons.layered(context, 44, r.code, r.isDay))
        weatherText.text = "$temp  ·  ${WeatherIcons.describe(r.code)}"
        weatherRow.visibility = View.VISIBLE
    }

    private fun showGreeting() {
        val names = BirthdayStore.on(context).map { it.name }
        if (names.isEmpty()) {
            greetingText.visibility = View.GONE
            return
        }
        val who = if (names.size == 1) names[0]
        else names.dropLast(1).joinToString(", ") + " & " + names.last()
        greetingText.text = "Happy Birthday, $who!"
        greetingText.visibility = View.VISIBLE
    }

    // ---------- Helpers ----------

    private fun text(sp: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (bold) android.graphics.Typeface.create(resources.getFont(R.font.inter), android.graphics.Typeface.BOLD)
        else resources.getFont(R.font.inter)
        setTextColor(if (secondary) Color.argb(200, 255, 255, 255) else Color.WHITE)
        // A soft shadow keeps the text readable over bright parts of a video.
        setShadowLayer(18f, 0f, 2f, Color.argb(230, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "Portal"
        const val FADE_MS = 700L
        const val MINUTE_MS = 60_000L
        /** How fast the clock moves sideways, in dp a second; up and down it is 70% of that, so it doesn't just trace the diagonal. */
        const val PACE_DP = 70
        const val WEATHER_REFRESH_MS = 15 * 60 * 1000L
        const val NIGHT_DIM_ALPHA = 0.55f
    }
}
