package com.portal.tv.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Makes a fragmented MP4 into an ordinary one. Small streaming-style downloads (the kind marked "iso5" or "iso6")
 * have no seek table and no stated length, so a player can't jump back to the start to loop them, and instead sees
 * the end again straight away, hundreds of times a second. Copying the same pictures and sound into a normal MP4,
 * without re-encoding them, gives the file a length and a seek table.
 */
object Mp4Fixer {

    private const val TAG = "Portal"

    /** The video's length in milliseconds as its header states it, or 0 if it doesn't (or can't be read). */
    fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** True for a file that can't say how long it is, which is how a fragmented MP4 shows up. */
    fun needsFixing(file: File) = durationMs(file) <= 0

    /**
     * Copies [src] into [dst] as a normal MP4. Returns true if [dst] now has a length and can seek; if not, [dst] is
     * deleted and [src] is left as it was.
     */
    fun fix(src: File, dst: File): Boolean {
        dst.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(src.path)
            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val tracks = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    tracks[i] = muxer.addTrack(format)
                }
            }
            if (tracks.isEmpty()) return false
            muxer.setOrientationHint(rotation(src))
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks.getValue(track), buffer, info)
                extractor.advance()
            }
            muxer.stop()
            started = false
            val length = durationMs(dst)
            Log.d(TAG, "Repaired ${src.name}: now ${length} ms long")
            return length > 0
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't repair ${src.name}: ${e.message}")
            return false
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            // Whatever went wrong, don't leave half a file behind.
            if (dst.exists() && durationMs(dst) <= 0) dst.delete()
        }
    }

    private fun rotation(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}
