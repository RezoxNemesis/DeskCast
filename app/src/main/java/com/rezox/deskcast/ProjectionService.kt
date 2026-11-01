package com.rezox.deskcast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.WindowManager

/**
 * Owns the screen capture. Two modes:
 *  - MIRROR: phone screen → a surface on the external display (GPU only, no encoding, near-zero lag)
 *  - STREAM: phone screen → hardware H.264 encoder → TCP (USB via `adb forward`, or Wi‑Fi / USB tethering)
 */
class ProjectionService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val MODE_MIRROR = "mirror"
        const val MODE_STREAM = "stream"
        const val ACTION_STOP = "com.rezox.deskcast.STOP"
        const val PORT = 27183
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7

        @Volatile var instance: ProjectionService? = null
            private set

        /** UI listeners (MainActivity) get notified when state changes. */
        var onStateChanged: (() -> Unit)? = null

        fun start(c: Context, mode: String, resultCode: Int, data: Intent) {
            val i = Intent(c, ProjectionService::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var encoder: StreamEncoder? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var dm: DisplayManager

    var mode: String? = null
        private set

    /** Size of the phone screen as it is right now (rotation-aware). */
    private var sourceSize = Size(1080, 2400)
    private var densityDpi = 420

    private var mirrorSurface: Surface? = null
    var onMirrorSizeChanged: ((Size) -> Unit)? = null

    val streamClients: Int get() = encoder?.clientCount ?: 0
    val streamInfo: String get() = encoder?.describe() ?: ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        dm = getSystemService(DisplayManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val m = intent?.getStringExtra(EXTRA_MODE)
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (m == null || data == null || code != Activity.RESULT_OK) {
            stopSelf(); return START_NOT_STICKY
        }

        // Must be foreground (type mediaProjection) before asking for the projection.
        startForeground(NOTIF_ID, notification(m), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        releaseCapture()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) { shutdown(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        }, main)
        projection = p
        mode = m
        readSourceSize()
        dm.registerDisplayListener(rotationListener, main)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskCast:capture").apply { acquire(6 * 60 * 60 * 1000L) }

        when (m) {
            MODE_MIRROR -> createVirtualDisplay(mirrorSurface)
            MODE_STREAM -> startStream()
        }
        notifyState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseCapture()
        instance = null
        notifyState()
        super.onDestroy()
    }

    private fun notifyState() = main.post { onStateChanged?.invoke() }

    // ---------- shared ----------

    private fun readSourceSize() {
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        // Use the real panel size including system bars.
        val mode = d?.mode
        var w = b.width(); var h = b.height()
        if (mode != null) {
            val landscape = w > h
            val pw = minOf(mode.physicalWidth, mode.physicalHeight)
            val ph = maxOf(mode.physicalWidth, mode.physicalHeight)
            if (pw > 0 && ph > 0) { if (landscape) { w = ph; h = pw } else { w = pw; h = ph } }
        }
        sourceSize = Size(w, h)
        densityDpi = resources.displayMetrics.densityDpi
    }

    private fun createVirtualDisplay(surface: Surface?) {
        val p = projection ?: return
        val size = if (mode == MODE_STREAM) encoder?.size ?: sourceSize else sourceSize
        vd = p.createVirtualDisplay(
            "DeskCast-capture", size.width, size.height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main,
        )
    }

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val old = sourceSize
            readSourceSize()
            if (old == sourceSize) return
            when (mode) {
                MODE_MIRROR -> {
                    vd?.resize(sourceSize.width, sourceSize.height, densityDpi)
                    onMirrorSizeChanged?.invoke(sourceSize)
                }
                MODE_STREAM -> restartEncoder()
            }
        }
    }

    // ---------- mirror ----------

    val mirrorSize: Size get() = sourceSize

    fun attachMirrorSurface(s: Surface?) {
        mirrorSurface = s
        if (mode != MODE_MIRROR) return
        val v = vd
        if (v == null) createVirtualDisplay(s) else v.surface = s
    }

    // ---------- stream ----------

    private fun startStream() {
        val enc = StreamEncoder(
            this, PORT,
            maxEdge = Settings2.streamMaxEdge(this),
            bitrate = Settings2.streamMbps(this) * 1_000_000,
            fps = Settings2.streamFps(this),
            onClientsChanged = { notifyState() },
        )
        encoder = enc
        val surface = enc.start(sourceSize)
        createVirtualDisplay(surface)
    }

    private fun restartEncoder() {
        val enc = encoder ?: return
        val surface = enc.restart(sourceSize) ?: return
        val size = enc.size
        vd?.resize(size.width, size.height, densityDpi)
        vd?.surface = surface
    }

    // ---------- teardown ----------

    private fun releaseCapture() {
        try { dm.unregisterDisplayListener(rotationListener) } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        encoder?.stop(); encoder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        mode = null
    }

    private fun shutdown() {
        releaseCapture()
        MirrorActivity.current?.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyState()
    }

    private fun notification(m: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (m == MODE_MIRROR) "Mirroring your phone to the external screen" else "Streaming on port $PORT"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("DeskCast")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
