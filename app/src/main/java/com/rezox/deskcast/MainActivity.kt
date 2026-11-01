package com.rezox.deskcast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val REQ_MIRROR = 11
    private val REQ_STREAM = 12

    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var pendingMirror = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        content = Ui.column(this, 20)
        scroll.addView(content)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        ProjectionService.onStateChanged = {
            if (pendingMirror && ProjectionService.instance?.mode == ProjectionService.MODE_MIRROR) {
                pendingMirror = false
                launchMirror()
            }
            render()
        }
        render()
    }

    override fun onPause() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onPause()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = render()
        override fun onDisplayRemoved(displayId: Int) = render()
        override fun onDisplayChanged(displayId: Int) {}
    }

    // ---------- UI ----------

    private fun render() {
        val c = this
        content.removeAllViews()

        content.addView(Ui.title(c, "DeskCast"))
        content.addView(Ui.text(c, "Your phone as a desktop on any screen.", 15f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 4), 0, 0) })

        // ---- external screen ----
        content.addView(Ui.section(c, "Big screen"))
        val screen = Ui.card(c)
        val ext = Displays.external(c)
        if (ext.isEmpty()) {
            screen.addView(Ui.statusRow(c, false, "No external screen detected",
                "Connect a USB‑C → HDMI/DisplayPort adapter or dock (the phone must support video out over USB‑C), or cast to a wireless display. On phones without video out, use “Stream to a PC” below."))
        } else {
            ext.forEach { screen.addView(Ui.statusRow(c, true, "Connected", Displays.describe(c, it))) }
            screen.addView(Ui.button(c, "Open desktop on big screen", primary = true) {
                val d = Displays.primaryExternal(c) ?: return@button
                Displays.launchOn(c, Intent(c, DesktopActivity::class.java), d.displayId, Displays.bounds(c, d))
            })
            screen.addView(Ui.button(c, "Mirror phone screen (games, any app)") { requestCapture(REQ_MIRROR) })
        }
        content.addView(screen)

        // ---- capture status ----
        val ps = ProjectionService.instance
        if (ps?.mode != null) {
            val live = Ui.card(c)
            if (ps.mode == ProjectionService.MODE_MIRROR) {
                live.addView(Ui.statusRow(c, true, "Mirroring to the big screen"))
            } else {
                val n = ps.streamClients
                live.addView(Ui.statusRow(c, n > 0, if (n > 0) "Streaming to $n viewer(s)" else "Stream ready — waiting for the PC", ps.streamInfo))
                live.addView(Ui.text(c, streamHelp(), 13f, Ui.MUTED).apply { setTextIsSelectable(true); setPadding(0, Ui.dp(c, 6), 0, 0) })
                live.addView(Ui.button(c, "Copy PC command") { copy(pcCommand()) })
            }
            live.addView(Ui.button(c, "Stop") { ProjectionService.stop(c) })
            content.addView(live)
        }

        // ---- stream ----
        content.addView(Ui.section(c, "Stream to a PC over USB"))
        val stream = Ui.card(c)
        stream.addView(Ui.text(c, "Hardware H.264, no B‑frames, realtime priority — typically 30–60 ms over a USB cable. Works on every phone, video out or not.", 14f, Ui.MUTED))
        stream.addView(choiceRow("Quality", listOf("720p" to 1280, "1080p" to 1920, "1440p" to 2560, "Native" to 0), Settings2.streamMaxEdge(c)) { Settings2.setStreamMaxEdge(c, it) })
        stream.addView(choiceRow("Bitrate", listOf("8 Mbps" to 8, "16 Mbps" to 16, "30 Mbps" to 30, "50 Mbps" to 50), Settings2.streamMbps(c)) { Settings2.setStreamMbps(c, it) })
        stream.addView(choiceRow("Frame rate", listOf("30" to 30, "60" to 60, "90" to 90, "120" to 120), Settings2.streamFps(c)) { Settings2.setStreamFps(c, it) })
        stream.addView(Ui.button(c, if (ps?.mode == ProjectionService.MODE_STREAM) "Restart stream" else "Start stream", primary = ext.isEmpty()) {
            requestCapture(REQ_STREAM)
        })
        content.addView(stream)

        // ---- control ----
        content.addView(Ui.section(c, "Mouse, keyboard & games"))
        val ctl = Ui.card(c)
        ctl.addView(Ui.text(c, "A USB/Bluetooth mouse and keyboard work out of the box. DeskCast adds Win‑key start menu, Alt+Tab, Alt+F4, a phone touchpad, and keyboard → touch mapping for games.", 14f, Ui.MUTED))
        ctl.addView(Ui.button(c, "Use phone as touchpad", primary = ext.isNotEmpty()) {
            if (ControlService.instance == null) openAccessibility() else startActivity(Intent(c, TouchpadActivity::class.java))
        })
        ctl.addView(Ui.button(c, "App window profiles") { startActivity(Intent(c, AppProfilesActivity::class.java)) })
        ctl.addView(Ui.button(c, "Game key mapping") { startActivity(Intent(c, KeymapEditorActivity::class.java)) })
        content.addView(ctl)

        // ---- setup ----
        content.addView(Ui.section(c, "Setup"))
        val setup = Ui.card(c)
        val accOn = ControlService.isEnabled(c)
        setup.addView(Ui.statusRow(c, accOn, "DeskCast control (Accessibility)",
            if (accOn) "On — cursor, touchpad, shortcuts and key mapping are ready" else "Needed for the cursor, touchpad, shortcuts and key mapping"))
        if (!accOn) setup.addView(Ui.button(c, "Turn on DeskCast control") { openAccessibility() })

        val canWrite = DesktopFlags.canWrite(c)
        val flagsOn = DesktopFlags.allOn(c)
        setup.addView(Ui.statusRow(c, if (flagsOn) true else if (canWrite) null else false, "Desktop windows on the big screen",
            when {
                flagsOn -> "On — apps open as movable, resizable windows. If you just turned it on, restart the phone once."
                canWrite -> "Permission granted — tap Enable, then restart the phone once."
                else -> "One-time step from a computer (USB debugging on):\n" + DesktopFlags.adbGrantCommand(c)
            }))
        if (!flagsOn && canWrite) setup.addView(Ui.button(c, "Enable desktop windows", primary = true) {
            val ok = DesktopFlags.setAll(c, true)
            Toast.makeText(c, if (ok) "Enabled. Restart your phone once to finish." else "Some switches could not be set.", Toast.LENGTH_LONG).show()
            render()
        })
        if (flagsOn && canWrite) setup.addView(Ui.button(c, "Turn desktop windows off") { DesktopFlags.setAll(c, false); render() })
        if (!canWrite) {
            setup.addView(Ui.button(c, "Copy ADB command") { copy(DesktopFlags.adbGrantCommand(c)) })
            setup.addView(Ui.button(c, "Open Developer options") {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {}
            })
        }

        val auto = Switch(c).apply {
            text = "Open the desktop automatically when a screen is connected"
            setTextColor(Ui.TEXT); textSize = 15f
            isChecked = Settings2.autoDesktop(c)
            setOnCheckedChangeListener { _, v -> Settings2.setAutoDesktop(c, v) }
            setPadding(0, Ui.dp(c, 10), 0, 0)
        }
        setup.addView(auto)
        content.addView(setup)

        // ---- shortcuts ----
        content.addView(Ui.section(c, "Shortcuts"))
        val keys = Ui.card(c)
        listOf(
            "Win" to "Start menu",
            "Win + D" to "Show desktop",
            "Win + N" to "Notifications",
            "Alt + Tab" to "Switch apps",
            "Alt + F4" to "Back / close",
            "F12 or Ctrl + Shift + K" to "Pause game key mapping (to type in chat)",
            "Type on the desktop" to "Search apps",
        ).forEach { (k, v) ->
            val r = Ui.row(c).apply { setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4)) }
            r.addView(Ui.text(c, k, 14f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Ui.text(c, v, 14f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            keys.addView(r)
        }
        content.addView(keys)
        content.addView(View(c), LinearLayout.LayoutParams(1, Ui.dp(c, 30)))
    }

    private fun choiceRow(label: String, options: List<Pair<String, Int>>, current: Int, onPick: (Int) -> Unit): View {
        val c = this
        val col = Ui.column(c).apply { setPadding(0, Ui.dp(c, 12), 0, 0) }
        col.addView(Ui.text(c, label, 13f, Ui.MUTED))
        val row = Ui.row(c).apply { setPadding(0, Ui.dp(c, 6), 0, 0) }
        options.forEach { (name, value) ->
            val selected = value == current
            row.addView(TextView(c).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.TEXT)
                background = Ui.rounded(if (selected) Ui.ACCENT else Ui.CARD_HI, Ui.dp(c, 10).toFloat())
                setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9))
                setOnClickListener { onPick(value); render() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(c, 6) })
        }
        col.addView(row)
        return col
    }

    // ---------- capture ----------

    private fun requestCapture(req: Int) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_MIRROR || requestCode == REQ_STREAM) Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_SHORT).show()
            return
        }
        when (requestCode) {
            REQ_MIRROR -> {
                pendingMirror = true
                ProjectionService.start(this, ProjectionService.MODE_MIRROR, resultCode, data)
            }
            REQ_STREAM -> ProjectionService.start(this, ProjectionService.MODE_STREAM, resultCode, data)
        }
    }

    private fun launchMirror() {
        val d = Displays.primaryExternal(this) ?: return
        Displays.launchOn(this, Intent(this, MirrorActivity::class.java), d.displayId, Displays.bounds(this, d))
    }

    // ---------- helpers ----------

    private fun ipAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    } catch (_: Exception) { emptyList() }

    private fun pcCommand() =
        "adb forward tcp:${ProjectionService.PORT} tcp:${ProjectionService.PORT} && " +
            "ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -f h264 tcp://127.0.0.1:${ProjectionService.PORT}"

    private fun streamHelp(): String {
        val ips = ipAddresses()
        val sb = StringBuilder()
        sb.append("USB cable (fastest): on the PC run\n").append(pcCommand()).append("\n\n")
        sb.append("Or use pc/deskcast-view (.bat / .sh) from the DeskCast GitHub repo.")
        if (ips.isNotEmpty()) {
            sb.append("\n\nUSB tethering / Wi‑Fi: replace 127.0.0.1 with one of: ").append(ips.joinToString(", "))
        }
        return sb.toString()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DeskCast", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibility() {
        Toast.makeText(this, "Find “DeskCast control” and turn it on.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
