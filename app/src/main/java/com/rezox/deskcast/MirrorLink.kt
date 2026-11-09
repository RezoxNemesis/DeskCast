package com.rezox.deskcast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Wire format of the "Mirror to PC" link (TCP [PORT]). Every packet:
 *
 *   type u8 | length u32 BE | ptsUs i64 BE | payload[length]
 *
 * VIDEO payloads start with one flags byte ([VF_CONFIG], [VF_KEY]) followed by an Annex‑B H.264
 * access unit. The SPS/PPS config always comes as its own VIDEO packet with [VF_CONFIG] set.
 * The desktop app (desktop/src/protocol.js) implements the other end.
 */
object LinkProto {
    const val PORT = 27184
    const val BEACON_PORT = 27185
    const val VERSION = 1
    const val HEADER = 13
    const val MAX_IN = 64 * 1024

    const val T_HELLO = 1        // phone → pc  JSON {app, proto, name, id}
    const val T_AUTH = 2         // pc → phone  JSON {code} or {token}, plus {pcName}
    const val T_AUTH_OK = 3      // phone → pc  JSON {name, id, token?}
    const val T_AUTH_FAIL = 4    // phone → pc  JSON {reason: "code" | "token" | "locked"}
    const val T_VIDEO_FORMAT = 16 // phone → pc JSON {codec, width, height, rotation, orientation}
    const val T_VIDEO = 17       // phone → pc  flags u8 + Annex‑B
    const val T_AUDIO_FORMAT = 18 // phone → pc JSON {codec:"pcm_s16le", sampleRate, channels}
    const val T_AUDIO = 19       // phone → pc  interleaved s16le PCM
    const val T_PING = 32        // pc → phone  (pts = pc clock, echoed back)
    const val T_PONG = 33        // phone → pc  JSON {now: phone clock µs}
    const val T_KEYFRAME = 34    // pc → phone  please send an IDR frame
    const val T_BYE = 35         // either way

    const val VF_CONFIG = 1
    const val VF_KEY = 2

    fun frame(type: Int, ptsUs: Long, payload: ByteArray, off: Int = 0, len: Int = payload.size, flags: Int = -1): ByteArray {
        val extra = if (flags >= 0) 1 else 0
        val total = len + extra
        val b = ByteArray(HEADER + total)
        b[0] = type.toByte()
        b[1] = (total ushr 24).toByte(); b[2] = (total ushr 16).toByte(); b[3] = (total ushr 8).toByte(); b[4] = total.toByte()
        for (i in 0 until 8) b[5 + i] = (ptsUs ushr (56 - 8 * i)).toByte()
        if (extra == 1) b[HEADER] = flags.toByte()
        System.arraycopy(payload, off, b, HEADER + extra, len)
        return b
    }

    fun json(type: Int, o: JSONObject, ptsUs: Long = 0L): ByteArray = frame(type, ptsUs, o.toString().toByteArray(Charsets.UTF_8))

    fun nowUs(): Long = System.nanoTime() / 1000
}

/** PCs that typed the pairing code once. Only SHA‑256 hashes of their tokens are stored. */
object PairedPcs {
    private fun p(c: Context) = c.getSharedPreferences("mirror_link", Context.MODE_PRIVATE)

    fun deviceId(c: Context): String {
        val prefs = p(c)
        prefs.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("id", id).apply()
        return id
    }

    private fun all(c: Context): JSONObject = try { JSONObject(p(c).getString("trusted", "{}")!!) } catch (_: Exception) { JSONObject() }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Name of the PC that owns [token], or null if it isn't paired. */
    @Synchronized fun lookup(c: Context, token: String): String? {
        val o = all(c).optJSONObject(hash(token)) ?: return null
        return o.optString("name", "PC")
    }

    @Synchronized fun add(c: Context, token: String, name: String) {
        val a = all(c)
        a.put(hash(token), JSONObject().put("name", name).put("at", System.currentTimeMillis()))
        p(c).edit().putString("trusted", a.toString()).apply()
    }

    @Synchronized fun touch(c: Context, token: String, name: String) {
        val a = all(c)
        val h = hash(token)
        if (!a.has(h)) return
        a.put(h, JSONObject().put("name", name).put("at", System.currentTimeMillis()))
        p(c).edit().putString("trusted", a.toString()).apply()
    }

    fun names(c: Context): List<String> {
        val a = all(c)
        return a.keys().asSequence().mapNotNull { a.optJSONObject(it)?.optString("name") }.toList()
    }

    fun count(c: Context) = all(c).length()

    @Synchronized fun forget(c: Context) = p(c).edit().remove("trusted").apply()
}

/**
 * "Mirror to PC" server: pairs with the DeskCast Mirror desktop app, then sends it the encoded
 * screen (from [StreamEncoder] via [PacketListener]) and the captured sound. Also broadcasts a
 * UDP beacon so the PC finds the phone by itself.
 */
class MirrorLink(
    private val context: Context,
    private val rotationDegrees: () -> Int,
    private val requestKeyframe: () -> Unit,
    private val onChanged: () -> Unit,
) : PacketListener {

    private companion object {
        const val K_CONTROL = 0
        const val K_CONFIG = 1
        const val K_VIDEO = 2
        const val K_AUDIO = 3
        const val MAX_CLIENTS = 4
        const val MAX_VIDEO_QUEUED = 24
        const val MAX_BYTES_QUEUED = 3L * 1024 * 1024
        const val MAX_AUDIO_QUEUED = 40
        const val MAX_FAILED_CODES = 5
    }

    private class Out(val bytes: ByteArray, val kind: Int, val key: Boolean = false)

    private inner class Peer(val socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val queue = LinkedBlockingDeque<Out>()
        val queuedBytes = AtomicLong()
        @Volatile var authed = false
        @Volatile var needKey = true
        @Volatile var closed = false
        @Volatile var pcName = "PC"

        fun offer(o: Out) {
            if (closed) return
            when (o.kind) {
                K_VIDEO -> {
                    if (needKey && !o.key) return
                    val videoQueued = queue.count { it.kind == K_VIDEO }
                    if (videoQueued >= MAX_VIDEO_QUEUED || queuedBytes.get() > MAX_BYTES_QUEUED) {
                        // Wi‑Fi can't keep up: throw away the backlog and restart from a fresh keyframe.
                        val iter = queue.iterator()
                        while (iter.hasNext()) { val x = iter.next(); if (x.kind == K_VIDEO) { iter.remove(); queuedBytes.addAndGet(-x.bytes.size.toLong()) } }
                        needKey = true
                        askKeyframe()
                        if (!o.key) return
                    }
                    if (o.key) needKey = false
                }
                K_AUDIO -> {
                    if (queue.count { it.kind == K_AUDIO } >= MAX_AUDIO_QUEUED) {
                        val iter = queue.iterator()
                        while (iter.hasNext()) { val x = iter.next(); if (x.kind == K_AUDIO) { iter.remove(); queuedBytes.addAndGet(-x.bytes.size.toLong()); break } }
                    }
                }
            }
            queuedBytes.addAndGet(o.bytes.size.toLong())
            queue.offer(o)
        }

        fun close() {
            if (closed) return
            closed = true
            queue.clear()
            queue.offer(Out(ByteArray(0), -1)) // wakes the writer
            try { socket.close() } catch (_: Exception) {}
            if (clients.remove(this)) onChanged()
        }

        fun runWriter() {
            try {
                while (!closed) {
                    val o = queue.take()
                    if (o.kind < 0) break
                    queuedBytes.addAndGet(-o.bytes.size.toLong())
                    out.write(o.bytes)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: Exception) {
            } finally { close() }
        }

        fun runReader() {
            try {
                socket.soTimeout = 15_000 // the PC pings every second
                val input = DataInputStream(socket.getInputStream())
                while (!closed) {
                    val type = input.readUnsignedByte()
                    val len = input.readInt()
                    val pts = input.readLong()
                    if (len < 0 || len > LinkProto.MAX_IN) break
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    if (!handle(this, type, pts, payload)) break
                }
            } catch (_: Exception) {
            } finally { close() }
        }
    }

    private val random = SecureRandom()
    private val lock = Any()
    private val clients = CopyOnWriteArrayList<Peer>()
    private val failedCodes = AtomicInteger()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var beacon: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastKeyRequest = 0L

    // Latest stream state, sent to each PC right after it authenticates.
    private var videoFormat: ByteArray? = null
    private var videoConfig: ByteArray? = null
    private var audioFormat: ByteArray? = null

    val deviceId: String = PairedPcs.deviceId(context)
    val deviceName: String = try {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** The 6‑digit code a new PC has to type. */
    @Volatile var code: String = newCode()
        private set

    val isRunning get() = running

    /** Names of the PCs that are watching right now. */
    val connectedPcs: List<String> get() = clients.filter { it.authed && !it.closed }.map { it.pcName }

    private fun newCode() = "%06d".format(random.nextInt(1_000_000))

    // ---------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(LinkProto.PORT))
        server = ss
        running = true
        thread(name = "deskcast-link", isDaemon = true) { acceptLoop(ss) }
        startBeacon()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { beacon?.close() } catch (_: Exception) {}
        beacon = null
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        for (p in clients) {
            try { p.offer(Out(LinkProto.json(LinkProto.T_BYE, JSONObject()), K_CONTROL)) } catch (_: Exception) {}
        }
        // Give writers a moment to flush BYE, then close.
        Thread.sleep(50)
        clients.toList().forEach { it.close() }
        clients.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
            if (clients.size >= MAX_CLIENTS) { try { sock.close() } catch (_: Exception) {}; continue }
            try {
                sock.tcpNoDelay = true
                sock.sendBufferSize = 1 shl 20
                val p = Peer(sock)
                clients += p
                p.offer(Out(LinkProto.json(LinkProto.T_HELLO, JSONObject()
                    .put("app", "deskcast").put("proto", LinkProto.VERSION)
                    .put("name", deviceName).put("id", deviceId)), K_CONTROL))
                thread(name = "deskcast-link-w", isDaemon = true, priority = Thread.MAX_PRIORITY) { p.runWriter() }
                thread(name = "deskcast-link-r", isDaemon = true) { p.runReader() }
            } catch (e: Exception) {
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // incoming
    // ---------------------------------------------------------------------------------------

    /** Returns false to close the connection. */
    private fun handle(p: Peer, type: Int, pts: Long, payload: ByteArray): Boolean {
        when (type) {
            LinkProto.T_AUTH -> return auth(p, payload)
            LinkProto.T_PING -> p.offer(Out(LinkProto.json(LinkProto.T_PONG, JSONObject().put("now", LinkProto.nowUs()), pts), K_CONTROL))
            LinkProto.T_KEYFRAME -> if (p.authed) { p.needKey = true; askKeyframe() }
            LinkProto.T_BYE -> return false
        }
        return true
    }

    private fun auth(p: Peer, payload: ByteArray): Boolean {
        if (p.authed) return true
        val o = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (_: Exception) { return false }
        val pcName = o.optString("pcName", "PC").take(64).ifBlank { "PC" }
        val token = o.optString("token", "")
        val typed = o.optString("code", "").filter { it.isDigit() }
        if (token.isNotEmpty() && typed.isEmpty()) {
            val known = PairedPcs.lookup(context, token)
            if (known == null) {
                // Unknown or forgotten PC: it may still pair with the code on this connection.
                p.offer(Out(LinkProto.json(LinkProto.T_AUTH_FAIL, JSONObject().put("reason", "token")), K_CONTROL))
                return true
            }
            PairedPcs.touch(context, token, pcName)
            accept(p, pcName, null)
            return true
        }
        if (typed.length == 6 && typed == code) {
            failedCodes.set(0)
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            val newToken = bytes.joinToString("") { "%02x".format(it) }
            PairedPcs.add(context, newToken, pcName)
            accept(p, pcName, newToken)
            return true
        }
        val locked = failedCodes.incrementAndGet() >= MAX_FAILED_CODES
        if (locked) { failedCodes.set(0); code = newCode(); onChanged() }
        p.offer(Out(LinkProto.json(LinkProto.T_AUTH_FAIL, JSONObject().put("reason", if (locked) "locked" else "code")), K_CONTROL))
        Thread.sleep(300) // let the writer send it before the socket closes
        return false
    }

    private fun accept(p: Peer, pcName: String, newToken: String?) {
        p.pcName = pcName
        val ok = JSONObject().put("name", deviceName).put("id", deviceId)
        if (newToken != null) ok.put("token", newToken)
        synchronized(lock) {
            p.offer(Out(LinkProto.json(LinkProto.T_AUTH_OK, ok), K_CONTROL))
            audioFormat?.let { p.offer(Out(it, K_CONTROL)) }
            videoFormat?.let { p.offer(Out(it, K_CONTROL)) }
            videoConfig?.let { p.offer(Out(it, K_CONFIG)) }
            p.needKey = true
            p.authed = true
        }
        askKeyframe(force = true)
        onChanged()
    }

    private fun askKeyframe(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyRequest < 250) return
        lastKeyRequest = now
        try { requestKeyframe() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------------------------
    // outgoing (called from the encoder / audio threads)
    // ---------------------------------------------------------------------------------------

    private fun toAuthed(o: Out) {
        for (p in clients) if (p.authed) p.offer(o)
    }

    override fun onFormat(width: Int, height: Int) {
        val rot = try { rotationDegrees() } catch (_: Exception) { 0 }
        val f = LinkProto.json(LinkProto.T_VIDEO_FORMAT, JSONObject()
            .put("codec", "h264").put("width", width).put("height", height)
            .put("rotation", rot).put("orientation", if (width >= height) "landscape" else "portrait"), LinkProto.nowUs())
        synchronized(lock) {
            videoFormat = f
            videoConfig = null
            for (p in clients) if (p.authed) { p.needKey = true; p.offer(Out(f, K_CONTROL)) }
        }
    }

    override fun onPacket(data: ByteArray, len: Int, ptsUs: Long, isConfig: Boolean, isKeyframe: Boolean) {
        if (!running || clients.isEmpty() && !isConfig) return
        if (isConfig) {
            val f = LinkProto.frame(LinkProto.T_VIDEO, ptsUs, data, 0, len, LinkProto.VF_CONFIG)
            synchronized(lock) {
                videoConfig = f
                toAuthed(Out(f, K_CONFIG))
            }
            return
        }
        if (clients.none { it.authed }) return
        val f = LinkProto.frame(LinkProto.T_VIDEO, ptsUs, data, 0, len, if (isKeyframe) LinkProto.VF_KEY else 0)
        toAuthed(Out(f, K_VIDEO, isKeyframe))
    }

    fun setAudioFormat(sampleRate: Int, channels: Int) {
        val f = LinkProto.json(LinkProto.T_AUDIO_FORMAT, JSONObject()
            .put("codec", "pcm_s16le").put("sampleRate", sampleRate).put("channels", channels))
        synchronized(lock) {
            audioFormat = f
            toAuthed(Out(f, K_CONTROL))
        }
    }

    fun onAudio(data: ByteArray, len: Int, ptsUs: Long) {
        if (clients.none { it.authed }) return
        toAuthed(Out(LinkProto.frame(LinkProto.T_AUDIO, ptsUs, data, 0, len), K_AUDIO))
    }

    // ---------------------------------------------------------------------------------------
    // discovery beacon
    // ---------------------------------------------------------------------------------------

    private fun startBeacon() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("deskcast-beacon").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        val sock = try { DatagramSocket().apply { broadcast = true } } catch (_: Exception) { return }
        beacon = sock
        thread(name = "deskcast-beacon", isDaemon = true) {
            val msg = JSONObject().put("app", "deskcast").put("proto", LinkProto.VERSION)
                .put("name", deviceName).put("id", deviceId).put("port", LinkProto.PORT)
                .toString().toByteArray(Charsets.UTF_8)
            var targets = broadcastTargets()
            var n = 0
            while (running && !sock.isClosed) {
                if (n++ % 10 == 0) targets = broadcastTargets()
                for (t in targets) {
                    try { sock.send(DatagramPacket(msg, msg.size, t, LinkProto.BEACON_PORT)) } catch (_: Exception) {}
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** 255.255.255.255 plus each interface's own broadcast address (some routers drop the first). */
    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        try { out += InetAddress.getByName("255.255.255.255") } catch (_: Exception) {}
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (ia.address is Inet4Address && b != null && out.none { it == b }) out += b
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
