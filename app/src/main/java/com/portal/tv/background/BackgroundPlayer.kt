package com.portal.tv.background

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.portal.tv.settings.BackgroundSettings
import com.portal.tv.settings.VideoSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the looping video background using android.media.MediaPlayer.
 *
 * - Hardware-decoded (Pentonic 700 handles H.264/HEVC in silicon)
 * - Muted, looping, fill-crop
 * - Plays one video (the chosen one, or the birthday or holiday video when it is due)
 * - Dims further while user is interacting
 */
class BackgroundPlayer(
    private val context: Context,
    private val textureView: TextureView,
    private val dimOverlay: View,
    assetName: String = BackgroundRepository.DEFAULT_VIDEO,
    /** Whether this video may have sound at all, when the profile has turned sound on. The weather page's doesn't. */
    private val mayHaveSound: Boolean = true
) {
    private var player: MediaPlayer? = null
    private var currentFileInputStream: FileInputStream? = null
    private val repo = BackgroundRepository(context, assetName)
    private var videoFile: File? = null
    private var isPlaying = false
    private var baseBrightness: Float = 0.75f
    private var interactDimEnabled: Boolean = true
    private val playbackStarted = AtomicBoolean(false)

    /** Called on the main thread when the first video frame has been drawn. */
    var onFirstFrame: (() -> Unit)? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "surface AVAILABLE (${width}x${height})")
            startPlayback(Surface(surface))
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "surface DESTROYED")
            playbackStarted.set(false)
            releasePlayer()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    fun start(scope: CoroutineScope) {
        Log.d(TAG, "BackgroundPlayer.start() called")
        if (isPlaying) {
            Log.d(TAG, "BackgroundPlayer already playing, returning")
            return
        }
        // Asked before this player makes any sound of its own, so only other apps can be heard.
        othersWerePlaying = VideoSound.othersPlaying(context)
        isPlaying = true
        playbackStarted.set(false)

        baseBrightness = BackgroundSettings.getBrightness(context)
        interactDimEnabled = BackgroundSettings.getInteractDim(context)
        applyDim()

        textureView.surfaceTextureListener = surfaceListener

        scope.launch(Dispatchers.IO) {
            val ready = repo.extractBundledVideo()
            if (!ready) {
                Log.w(TAG, "BackgroundPlayer: no video available, aborting")
                return@launch
            }
            videoFile = repo.getVideoFile()
            Log.d(TAG, "BackgroundPlayer: video ready at ${videoFile?.path}")

            withContext(Dispatchers.Main) {
                if (textureView.isAvailable) {
                    val st = textureView.surfaceTexture
                    if (st != null) {
                        startPlayback(Surface(st))
                    }
                }
                // Otherwise, surfaceListener will call startPlayback
            }
        }
    }

    /** Whether another app was playing sound when this player was asked to start. */
    private var othersWerePlaying = false

    /** The surface the video is drawn on, kept so the video can be started again from the beginning. */
    private var lastSurface: Surface? = null

    /**
     * A video that can't seek back to its start (a fragmented MP4 that wasn't repaired) can't loop with the player's
     * own looping: it would see its end again at once, hundreds of times a second. Start it over instead.
     */
    private fun restartForLoop() {
        val surface = lastSurface ?: return
        if (!isPlaying) return
        Log.d(TAG, "startPlayback: the video can't seek, starting it over to loop")
        releasePlayer()
        playbackStarted.set(false)
        startPlayback(surface)
    }

    private fun startPlayback(surface: Surface) {
        if (!playbackStarted.compareAndSet(false, true)) {
            Log.d(TAG, "startPlayback: already started, skipping")
            return
        }
        lastSurface = surface

        val file = videoFile
        if (file == null || !file.exists()) {
            Log.w(TAG, "startPlayback: no video file, cannot play")
            playbackStarted.set(false)
            return
        }

        Log.d(TAG, "startPlayback: ${file.path} (${file.length()} bytes)")
        releasePlayer()

        try {
            val mp = MediaPlayer()
            mp.setSurface(surface)
            mp.setLooping(true)
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            // Silent unless the profile asked for sound, and then not over another app's music.
            val volume = if (!mayHaveSound || othersWerePlaying) 0f else VideoSound.volume(BackgroundSettings.getSound(context))
            mp.setVolume(volume, volume)
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) textureView.post { onFirstFrame?.invoke() }
                false
            }

            val fis = FileInputStream(file)
            mp.setDataSource(fis.fd, 0, file.length())
            currentFileInputStream = fis

            Log.d(TAG, "startPlayback: preparing...")
            mp.prepare()
            if (mp.duration <= 0) {
                mp.isLooping = false
                mp.setOnCompletionListener { textureView.post { restartForLoop() } }
            }
            mp.start()
            // Slower or faster than normal, if the profile asked for it and this TV's decoder allows.
            val speed = BackgroundSettings.getSpeed(context)
            if (speed != 1f) {
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
                    .onFailure { Log.w(TAG, "startPlayback: speed $speed not supported: ${it.message}") }
            }

            player = mp
            Log.d(TAG, "startPlayback: PLAYING ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play video: ${e.message}", e)
            closeFileInputStream()
            playbackStarted.set(false)
        }
    }

    fun onInteract() {
        if (!interactDimEnabled) return
        applyDim(extraDim = 0.3f)
    }

    fun onIdle() {
        applyDim()
    }

    private fun applyDim(extraDim: Float = 0f) {
        val dim = (1f - baseBrightness + extraDim).coerceIn(0f, 0.9f)
        dimOverlay.post { dimOverlay.alpha = dim }
    }

    fun stop() {
        Log.d(TAG, "BackgroundPlayer.stop()")
        isPlaying = false
        playbackStarted.set(false)
        releasePlayer()
    }

    private fun releasePlayer() {
        try {
            player?.stop()
            player?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "releasePlayer: ${e.message}")
        }
        player = null
        closeFileInputStream()
    }

    private fun closeFileInputStream() {
        try {
            currentFileInputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closeFileInputStream: ${e.message}")
        }
        currentFileInputStream = null
    }

    companion object {
        private const val TAG = "Portal"
    }
}
