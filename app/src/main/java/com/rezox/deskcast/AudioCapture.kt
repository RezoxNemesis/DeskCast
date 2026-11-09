package com.rezox.deskcast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/**
 * Captures what apps are playing (media, games, unknown) through the screen-capture permission,
 * as 48 kHz stereo 16-bit PCM in 10 ms chunks. Apps that opt out of capture (many DRM streaming
 * apps, calls) stay silent; that's an Android rule, not something DeskCast can change.
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val sink: (data: ByteArray, len: Int, ptsUs: Long) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_BYTES = SAMPLE_RATE / 100 * CHANNELS * 2 // 10 ms
        private const val CHUNK_US = 10_000L
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    /** Why capture failed, or null. */
    var error: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, CHUNK_BYTES * 8))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                error = "Android refused to start sound capture."
                return false
            }
            r.startRecording()
            record = r
            running = true
            worker = thread(name = "deskcast-audio", isDaemon = true) { loop(r) }
            error = null
            return true
        } catch (e: SecurityException) {
            error = "Audio permission not allowed."
        } catch (e: Exception) {
            error = "Sound capture failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return false
    }

    private fun loop(r: AudioRecord) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
            var got = 0
            while (got < CHUNK_BYTES && running) {
                val n = r.read(buf, got, CHUNK_BYTES - got, AudioRecord.READ_BLOCKING)
                if (n < 0) { running = false; break }
                got += n
            }
            if (got > 0 && running) {
                // Timestamp = when the first sample of this chunk was captured (approximately).
                sink(buf, got, LinkProto.nowUs() - CHUNK_US)
            }
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { worker?.join(300) } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        worker = null
    }
}
