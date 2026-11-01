package com.rezox.deskcast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.Surface
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Hardware H.264 encoder tuned for latency (no B-frames, realtime priority, low-latency mode),
 * sending a raw Annex‑B stream to every TCP client. Any player that reads raw H.264 works,
 * e.g. `ffplay -f h264 tcp://127.0.0.1:27183` after `adb forward tcp:27183 tcp:27183`.
 */
class StreamEncoder(
    private val context: Context,
    private val port: Int,
    private val maxEdge: Int,
    private val bitrate: Int,
    private val fps: Int,
    private val onClientsChanged: () -> Unit,
) {
    private class Client(val socket: Socket, val out: OutputStream)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var running = false
    @Volatile private var configData: ByteArray? = null
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private var drainThread: Thread? = null
    private var codecName = ""

    var size: Size = Size(1280, 720)
        private set

    val clientCount get() = clients.size

    fun describe() = "${size.width}×${size.height} · ${fps}fps · ${bitrate / 1_000_000} Mbps · $codecName"

    /** Starts the TCP server and the encoder; returns the surface to draw into. */
    fun start(source: Size): Surface {
        running = true
        startServer()
        return startCodec(source)
    }

    /** Re-creates the encoder for a new orientation. Clients stay connected and get new SPS/PPS. */
    fun restart(source: Size): Surface? {
        stopCodec()
        return try { startCodec(source) } catch (e: Exception) { null }
    }

    fun stop() {
        running = false
        stopCodec()
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
    }

    // ---------- encoder ----------

    private fun targetSize(source: Size): Size {
        var w = source.width.toFloat(); var h = source.height.toFloat()
        val longEdge = maxOf(w, h)
        if (maxEdge > 0 && longEdge > maxEdge) { val k = maxEdge / longEdge; w *= k; h *= k }
        // Encoders want multiples of 16 (some of 2); round down to be safe.
        return Size((w.toInt() / 16) * 16, (h.toInt() / 16) * 16)
    }

    private fun pickEncoder(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        return list.firstOrNull { it.isHardwareAccelerated } ?: list.firstOrNull()
    }

    private fun startCodec(source: Size): Surface {
        val info = pickEncoder()
        var s = targetSize(source)
        val caps = info?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        if (caps != null) {
            // Shrink until the hardware accepts the size.
            var guard = 0
            while (!caps.isSizeSupported(s.width, s.height) && guard++ < 12) {
                s = Size(((s.width * 0.85f).toInt() / 16) * 16, ((s.height * 0.85f).toInt() / 16) * 16)
            }
        }
        size = s

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, s.width, s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            // Resend the last frame when the screen is static so players never stall.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        val c = if (info != null) MediaCodec.createByCodecName(info.name) else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codecName = c.name
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject the latency keys; retry with the basics only.
            fmt.removeKey(MediaFormat.KEY_LATENCY)
            fmt.removeKey(MediaFormat.KEY_MAX_B_FRAMES)
            fmt.removeKey(MediaFormat.KEY_PRIORITY)
            c.reset()
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = c.createInputSurface()
        c.start()
        codec = c
        configData = null
        drainThread = thread(name = "deskcast-encoder", priority = Thread.MAX_PRIORITY) { drain(c) }
        return surface
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        try { c.signalEndOfInputStream() } catch (_: Exception) {}
        try { drainThread?.join(500) } catch (_: Exception) {}
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        drainThread = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var scratch = ByteArray(512 * 1024)
        while (running && codec === c) {
            val idx = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: IllegalStateException) { break }
            if (idx < 0) continue
            val buf = try { c.getOutputBuffer(idx) } catch (e: IllegalStateException) { null }
            if (buf != null && info.size > 0) {
                if (scratch.size < info.size) scratch = ByteArray(info.size * 2)
                buf.position(info.offset); buf.limit(info.offset + info.size)
                buf.get(scratch, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    configData = scratch.copyOf(info.size)
                }
                broadcast(scratch, info.size)
            }
            try { c.releaseOutputBuffer(idx, false) } catch (_: IllegalStateException) { break }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun broadcast(data: ByteArray, len: Int) {
        for (cl in clients) {
            try {
                cl.out.write(data, 0, len)
                cl.out.flush()
            } catch (e: Exception) {
                drop(cl)
            }
        }
    }

    private fun drop(cl: Client) {
        if (clients.remove(cl)) {
            try { cl.socket.close() } catch (_: Exception) {}
            onClientsChanged()
        }
    }

    // ---------- network ----------

    private fun startServer() {
        val ss = ServerSocket(port).apply { reuseAddress = true }
        server = ss
        thread(name = "deskcast-server", isDaemon = true) {
            while (running) {
                val sock = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                try {
                    sock.tcpNoDelay = true
                    sock.sendBufferSize = 1 shl 20
                    val out = sock.getOutputStream()
                    configData?.let { out.write(it); out.flush() }
                    clients += Client(sock, out)
                    // New viewer: ask for a keyframe right away so the picture appears instantly.
                    codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    onClientsChanged()
                } catch (e: Exception) {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        }
    }
}
