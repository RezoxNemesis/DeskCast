package com.rezox.deskcast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    companion object { const val EXTRA_MIRROR = "mirror" }

    private val REQ_MIRROR = 11
    private val REQ_STREAM = 12
    private val REQ_AUDIO = 13

    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var pendingMirror = false
    private var showManual = false

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == Privileged.REQ_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) main.post { unlockNow() } else main.post { render() }
    }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { main.post { render() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { main.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        content = Ui.column(this, 20)
        scroll.addView(content)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        try {
            Shizuku.addRequestPermissionResultListener(shizukuResult)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
            Shizuku.addBinderDeadListener(shizukuDead)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuResult)
            Shizuku.removeBinderReceivedListener(shizukuBinder)
            Shizuku.removeBinderDeadListener(shizukuDead)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Already unlocked (Shizuku or PC)? Then switch everything on silently.
        Privileged.configureAll(this)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        ProjectionService.onStateChanged = {
            if (pendingMirror && ProjectionService.instance?.mode == ProjectionService.MODE_MIRROR) {
                pendingMirror = false
                launchMirror()
            }
            render()
        }
        render()
        if (intent?.getBooleanExtra(EXTRA_MIRROR, false) == true) { intent.removeExtra(EXTRA_MIRROR); requestCapture(REQ_MIRROR) }
        // The control service takes a moment to start after being switched on.
        main.postDelayed({ render() }, 1200)
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

    private fun unlockNow() {
        val ok = Privileged.unlockWithShizuku(this)
        if (ok) {
            Privileged.configureAll(this)
            Toast.makeText(this, "Unlocked. DeskCast is setting itself up…", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Shizuku didn't allow it. Open Shizuku, make sure it says “running”, then try again.", Toast.LENGTH_LONG).show()
        }
        main.postDelayed({ render() }, 800)
    }

    // =========================================================================================
    // UI
    // =========================================================================================

    private fun render() {
        val c = this
        content.removeAllViews()
        content.addView(Ui.title(c, "DeskCast"))
        content.addView(Ui.text(c, "Your phone as a desktop — on a monitor or on its own screen.", 15f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 4), 0, 0) })

        renderSetup()
        renderStart()
        renderMirrorPc()
        renderGames()
        renderStream()
        renderShortcuts()
        content.addView(View(c), LinearLayout.LayoutParams(1, Ui.dp(c, 30)))
    }

    private fun stepRow(n: Int, done: Boolean, title: String, detail: String): View {
        val c = this
        val r = Ui.row(c).apply { setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 4)) }
        r.addView(TextView(c).apply {
            text = if (done) "✓" else n.toString()
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (done) 0xFF0B0E14.toInt() else Ui.TEXT)
            background = Ui.rounded(if (done) Ui.OK else Ui.CARD_HI, Ui.dp(c, 14).toFloat())
        }, LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)).apply { rightMargin = Ui.dp(c, 12) })
        val col = Ui.column(c)
        col.addView(Ui.text(c, title, 16f, Ui.TEXT, bold = true))
        col.addView(Ui.text(c, detail, 13f, Ui.MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun renderSetup() {
        val c = this
        val unlocked = Privileged.unlocked(c)
        val control = Privileged.controlOn(c)
        val windows = DesktopFlags.allOn(c)
        val restart = Privileged.restartNeeded(c)
        val allDone = unlocked && control && windows && !restart

        content.addView(Ui.section(c, "Setup"))
        val card = Ui.card(c)
        if (allDone) {
            card.addView(Ui.statusRow(c, true, "Everything is set up", "Mouse, keyboard, game keys and desktop windows are ready."))
            content.addView(card)
            return
        }

        // ---- Step 1: unlock ----
        card.addView(stepRow(1, unlocked, "Unlock DeskCast (one time)",
            if (unlocked) "Done." else "Lets DeskCast switch on desktop windows and its own controls for you. No computer needed."))
        if (!unlocked) {
            val installed = Privileged.shizukuInstalled(c)
            val running = Privileged.shizukuRunning()
            when {
                !installed -> {
                    card.addView(Ui.text(c, "a) Install the free app Shizuku.\nb) Open it and start it with “Wireless debugging” — it shows each step (needs Wi‑Fi on; Developer options: tap Build number 7 times in About phone).\nc) Come back here and tap Unlock.", 13f, Ui.MUTED).apply { setPadding(Ui.dp(c, 40), Ui.dp(c, 4), 0, 0) })
                    card.addView(Ui.button(c, "Get Shizuku", primary = true) { Privileged.getShizuku(c) })
                }
                !running -> {
                    card.addView(Ui.text(c, "Shizuku is installed but not started. Open it → “Start via Wireless debugging” → follow its steps, then come back.", 13f, Ui.WARN).apply { setPadding(Ui.dp(c, 40), Ui.dp(c, 4), 0, 0) })
                    card.addView(Ui.button(c, "Open Shizuku", primary = true) { Privileged.openShizuku(c) })
                }
                else -> {
                    card.addView(Ui.text(c, "Shizuku is running. Tap Unlock and press Allow.", 13f, Ui.OK).apply { setPadding(Ui.dp(c, 40), Ui.dp(c, 4), 0, 0) })
                    card.addView(Ui.button(c, "Unlock now", primary = true) {
                        if (Privileged.shizukuAllowed()) unlockNow() else Privileged.askShizuku()
                    })
                }
            }
            card.addView(Ui.button(c, "I have a computer instead") { pcDialog() })
        }

        // ---- Step 2: control ----
        card.addView(stepRow(2, control, "DeskCast control",
            when {
                control -> "On — Win key, mouse in games, game keys and touchpad work."
                unlocked -> "Turning on… if it stays off, tap below."
                else -> "Turns on by itself after step 1."
            }))
        if (!control && unlocked) card.addView(Ui.button(c, "Turn on now") { Privileged.enableControl(c); main.postDelayed({ render() }, 1000) })
        if (!control && !unlocked) {
            val manual = TextView(c).apply {
                text = if (showManual) "Hide manual way" else "Or turn it on by hand"
                textSize = 13f; setTextColor(Ui.ACCENT); setPadding(Ui.dp(c, 40), Ui.dp(c, 6), 0, Ui.dp(c, 6))
                setOnClickListener { showManual = !showManual; render() }
            }
            card.addView(manual)
            if (showManual) {
                card.addView(Ui.text(c,
                    "1. Tap “Open App info” → tap ⋮ (top‑right) → “Allow restricted settings” (only on Android 13+; if you don't see it, skip).\n" +
                        "2. Tap “Open Accessibility” → Installed apps / Downloaded apps → DeskCast control → On.",
                    13f, Ui.MUTED).apply { setPadding(Ui.dp(c, 40), 0, 0, 0) })
                card.addView(Ui.button(c, "Open App info") { Privileged.openAppInfo(c) })
                card.addView(Ui.button(c, "Open Accessibility") { Privileged.openAccessibility(c) })
            }
        }

        // ---- Step 3: windows ----
        card.addView(stepRow(3, windows && !restart, "Desktop windows",
            when {
                restart -> "Almost done — restart your phone once to finish."
                windows -> "On — apps open as movable, resizable windows."
                unlocked -> "Turning on…"
                else -> "Turns on by itself after step 1."
            }))
        content.addView(card)
    }

    private fun renderStart() {
        val c = this
        val ext = Displays.external(c)
        content.addView(Ui.section(c, "Start"))
        val card = Ui.card(c)
        if (ext.isNotEmpty()) {
            ext.forEach { card.addView(Ui.statusRow(c, true, "Monitor connected", Displays.describe(c, it))) }
            card.addView(Ui.button(c, "Connect", primary = true) {
                val d = Displays.primaryExternal(c) ?: return@button
                Displays.launchOn(c, Intent(c, DesktopActivity::class.java), d.displayId, Displays.bounds(c, d))
            })
            card.addView(Ui.button(c, "Mirror phone screen on the monitor") { requestCapture(REQ_MIRROR) })
        } else {
            card.addView(Ui.statusRow(c, null, "No monitor connected",
                "Plug in your USB‑C → HDMI adapter and tap Connect — DeskCast finds the monitor or tells you exactly what's wrong."))
            card.addView(Ui.button(c, "Connect a monitor", primary = true) { startActivity(Intent(c, ConnectActivity::class.java)) })
        }
        card.addView(Ui.button(c, "HDMI detector, screen test & videos", primary = true) { startActivity(Intent(c, HdmiVideoActivity::class.java)) })
        card.addView(Ui.button(c, if (DesktopActivity.phoneCurrent != null) "Return to phone desktop" else "Phone desktop (no monitor)") {
            DesktopActivity.startPhoneDesktop(c)
        })
        card.addView(Ui.button(c, "Use phone as touchpad") {
            if (ControlService.instance == null) Toast.makeText(c, "Finish Setup first.", Toast.LENGTH_LONG).show()
            else startActivity(Intent(c, TouchpadActivity::class.java))
        })
        card.addView(toggle("Open the desktop when a monitor is connected", Settings2.autoDesktop(c)) { Settings2.setAutoDesktop(c, it) })
        card.addView(toggle("Open phone desktop when a mouse or keyboard is connected", Settings2.autoPhoneDesktop(c)) { Settings2.setAutoPhoneDesktop(c, it) })
        content.addView(card)
    }

    private fun renderMirrorPc() {
        val c = this
        val ps = ProjectionService.instance
        content.addView(Ui.section(c, "Mirror to PC (Wi‑Fi)"))
        val card = Ui.card(c)
        if (ps != null && ps.mode == ProjectionService.MODE_STREAM && ps.linkRunning) {
            val pcs = ps.linkPcs
            card.addView(Ui.statusRow(c, if (pcs.isNotEmpty()) true else null,
                if (pcs.isNotEmpty()) "Mirroring to ${pcs.joinToString(", ")}" else "Ready — open DeskCast Mirror on the PC",
                if (pcs.isNotEmpty()) ps.streamInfo else "Pick this phone (${Build.MODEL}) on the PC. A paired PC connects by itself."))
            card.addView(Ui.text(c, "Pairing code (type it on the PC once)", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
            card.addView(TextView(c).apply {
                text = ps.linkCode.chunked(3).joinToString(" ")
                textSize = 46f; letterSpacing = 0.12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Ui.ACCENT); gravity = Gravity.CENTER
                setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4))
                setTextIsSelectable(true)
            })
            val ips = ipAddresses()
            card.addView(Ui.text(c,
                if (ips.isEmpty()) "No network found — connect the phone and the PC to the same Wi‑Fi."
                else "Phone address: ${ips.joinToString(", ")} · port ${LinkProto.PORT}",
                13f, Ui.MUTED).apply { setTextIsSelectable(true) })
            card.addView(Ui.statusRow(c, if (ps.audioOn) true else null, ps.audioStatus.ifBlank { "Sound: starting…" }))
            if (!ps.audioOn && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                card.addView(Ui.button(c, "Allow sound") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO) })
            }
            card.addView(Ui.button(c, "Stop mirroring") { ProjectionService.stop(c) })
        } else {
            card.addView(Ui.text(c,
                "See and hear the phone on your computer over Wi‑Fi — videos, games, the whole screen, portrait or landscape exactly as the phone is held.\n\n" +
                    "On the PC install DeskCast Mirror (the .deb from the DeskCast GitHub release), open it and pick this phone. The first time, type the 6‑digit code shown here.",
                14f, Ui.MUTED))
            ps?.linkError?.let { card.addView(Ui.text(c, it, 13f, Ui.BAD).apply { setPadding(0, Ui.dp(c, 6), 0, 0) }) }
            if (ps?.mode == ProjectionService.MODE_MIRROR) {
                card.addView(Ui.text(c, "Starting this stops mirroring to the monitor.", 13f, Ui.WARN).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
            }
            card.addView(Ui.button(c, "Mirror to PC", primary = true) { startMirrorToPc() })
        }
        val paired = PairedPcs.count(c)
        if (paired > 0) {
            card.addView(Ui.text(c, "Paired PCs: ${PairedPcs.names(c).joinToString(", ")}", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
            card.addView(Ui.button(c, "Forget paired PCs") {
                AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Forget paired PCs?")
                    .setMessage("Every PC will have to type the pairing code again next time.")
                    .setPositiveButton("Forget") { _, _ -> PairedPcs.forget(c); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
        content.addView(card)
    }

    private fun startMirrorToPc() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Ask for sound first; the screen capture prompt follows either way.
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        } else requestCapture(REQ_STREAM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val ps = ProjectionService.instance
        if (ps != null && ps.mode == ProjectionService.MODE_STREAM) {
            if (granted) ps.startAudio()
            render()
            return
        }
        if (!granted) Toast.makeText(this, "No sound permission: the PC gets video only, sound stays on the phone.", Toast.LENGTH_LONG).show()
        requestCapture(REQ_STREAM)
    }

    private fun renderGames() {
        val c = this
        content.addView(Ui.section(c, "Games, mouse & keyboard"))
        val card = Ui.card(c)
        card.addView(Ui.text(c,
            "Games work with ready-made keys: WASD moves, arrows swipe (Subway Surfers), Space jumps. Free Fire has its own layout: mouse click fires, ` turns on mouse aim.\n\n" +
                "Press F10 inside any game to drag the keys onto its buttons.",
            14f, Ui.MUTED))
        val svc = ControlService.instance
        val mouseLine = when {
            Build.VERSION.SDK_INT < 34 -> "Your Android version can't turn mouse clicks into touches inside games — use keys there. The mouse works normally in apps."
            svc == null -> "Mouse in games turns on after Setup."
            else -> "In games the mouse acts like a finger: click = tap, hold = drag, wheel = swipe, right click = long press."
        }
        card.addView(Ui.text(c, mouseLine, 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
        card.addView(Ui.button(c, "Edit keys for a game", primary = svc != null) { startActivity(Intent(c, KeymapEditorActivity::class.java)) })
        card.addView(Ui.button(c, "How each app opens (window / full screen / mouse)") { startActivity(Intent(c, AppProfilesActivity::class.java)) })
        if (Build.VERSION.SDK_INT >= 34) {
            card.addView(toggle("Touch mouse in all apps (wheel scrolls everywhere)", Settings2.touchMouseEverywhere(c)) {
                Settings2.setTouchMouseEverywhere(c, it); ControlService.instance?.invalidateKeymaps()
            })
        }
        content.addView(card)
    }

    private fun renderStream() {
        val c = this
        val ps = ProjectionService.instance
        content.addView(Ui.section(c, "Stream to a PC over USB"))
        val card = Ui.card(c)
        if (ps?.mode != null) {
            if (ps.mode == ProjectionService.MODE_MIRROR) card.addView(Ui.statusRow(c, true, "Mirroring to the monitor"))
            else {
                val n = ps.streamClients
                card.addView(Ui.statusRow(c, n > 0, if (n > 0) "Streaming to $n viewer(s)" else "Stream ready — waiting for the PC", ps.streamInfo))
                card.addView(Ui.text(c, streamHelp(), 13f, Ui.MUTED).apply { setTextIsSelectable(true); setPadding(0, Ui.dp(c, 6), 0, 0) })
                card.addView(Ui.button(c, "Copy PC command") { copy(pcCommand()) })
            }
            card.addView(Ui.button(c, "Stop") { ProjectionService.stop(c) })
        } else {
            card.addView(Ui.text(c, "For phones without video out: watch the phone on a computer through the USB cable (about 30–60 ms delay).", 14f, Ui.MUTED))
        }
        card.addView(choiceRow("Quality", listOf("720p" to 1280, "1080p" to 1920, "1440p" to 2560, "Native" to 0), Settings2.streamMaxEdge(c)) { Settings2.setStreamMaxEdge(c, it) })
        card.addView(choiceRow("Bitrate", listOf("8 Mbps" to 8, "16 Mbps" to 16, "30 Mbps" to 30, "50 Mbps" to 50), Settings2.streamMbps(c)) { Settings2.setStreamMbps(c, it) })
        card.addView(choiceRow("Frame rate", listOf("30" to 30, "60" to 60, "90" to 90, "120" to 120), Settings2.streamFps(c)) { Settings2.setStreamFps(c, it) })
        card.addView(Ui.button(c, if (ps?.mode == ProjectionService.MODE_STREAM) "Restart stream" else "Start stream") { requestCapture(REQ_STREAM) })
        content.addView(card)
    }

    private fun renderShortcuts() {
        val c = this
        content.addView(Ui.section(c, "Shortcuts"))
        val keys = Ui.card(c)
        listOf(
            "Win" to "Start menu (opens the desktop if needed)",
            "Win + D" to "Show desktop",
            "Win + N / Win + A" to "Notifications / Quick settings",
            "Alt + Tab" to "Switch apps",
            "Alt + F4 / Win + Esc" to "Back / close",
            "F10" to "Edit game keys",
            "F12" to "Pause game keys (type in chat)",
            "` or middle click" to "Mouse aim on/off (shooters)",
            "Ctrl + Alt + M" to "Normal mouse everywhere on/off",
        ).forEach { (k, v) ->
            val r = Ui.row(c).apply { setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4)) }
            r.addView(Ui.text(c, k, 14f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Ui.text(c, v, 14f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            keys.addView(r)
        }
        content.addView(keys)
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; setTextColor(Ui.TEXT); textSize = 14f
        isChecked = value
        setOnCheckedChangeListener { _, v -> onChange(v) }
        setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
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

    private fun pcDialog() {
        val cmd = DesktopFlags.adbGrantCommand(this)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Unlock with a computer")
            .setMessage("1. On the phone: Developer options → USB debugging → On.\n2. Connect the phone to the computer (with Android platform‑tools installed).\n3. Run:\n\n$cmd\n\nThen open DeskCast again — it sets up the rest by itself.")
            .setPositiveButton("Copy command") { _, _ -> copy(cmd) }
            .setNegativeButton("Close", null)
            .show()
    }

    // =========================================================================================
    // capture
    // =========================================================================================

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
            REQ_MIRROR -> { pendingMirror = true; ProjectionService.start(this, ProjectionService.MODE_MIRROR, resultCode, data) }
            REQ_STREAM -> ProjectionService.start(this, ProjectionService.MODE_STREAM, resultCode, data)
        }
    }

    private fun launchMirror() {
        val d = Displays.primaryExternal(this) ?: return
        Displays.launchOn(this, Intent(this, MirrorActivity::class.java), d.displayId, Displays.bounds(this, d))
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

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
        val sb = StringBuilder("USB cable (fastest): on the PC run\n").append(pcCommand())
            .append("\n\nOr use pc/deskcast-view (.bat / .sh) from the DeskCast GitHub repo.")
        if (ips.isNotEmpty()) sb.append("\n\nUSB tethering / Wi‑Fi: replace 127.0.0.1 with one of: ").append(ips.joinToString(", "))
        return sb.toString()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DeskCast", s))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
