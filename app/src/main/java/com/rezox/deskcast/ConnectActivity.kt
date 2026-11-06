package com.rezox.deskcast

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** What is plugged into the USB-C port, and why a monitor does or doesn't show up. */
object MonitorProbe {
    private const val VENDOR_DISPLAYLINK = 0x17E9
    private const val CLASS_BILLBOARD = 0x11

    data class Result(
        val display: Display?,
        val devices: List<UsbDevice>,
        /** The adapter reports "the phone didn't accept video mode" (USB Billboard device). */
        val billboard: Boolean,
        val displayLink: Boolean,
        /** Phone's USB-C port lists DisplayPort video: true / false / null = can't tell. */
        val phoneHasDp: Boolean?,
    )

    fun scan(c: Context): Result {
        val usb = c.getSystemService(UsbManager::class.java)
        val devices = try { usb.deviceList.values.toList() } catch (_: Exception) { emptyList() }
        val billboard = devices.any { d ->
            d.deviceClass == CLASS_BILLBOARD || (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == CLASS_BILLBOARD }
        }
        val displayLink = devices.any { it.vendorId == VENDOR_DISPLAYLINK }
        return Result(Displays.primaryExternal(c), devices, billboard, displayLink, phoneSupportsDp())
    }

    /** Reads the USB-C port's alternate modes (0xff01 = DisplayPort) when the system lets apps see them. */
    private fun phoneSupportsDp(): Boolean? = try {
        val root = File("/sys/class/typec")
        val ports = root.listFiles()?.filter { it.name.matches(Regex("port\\d+")) } ?: emptyList()
        if (ports.isEmpty()) null
        else {
            var sawAny = false
            var dp = false
            for (p in ports) {
                p.listFiles()?.filter { it.name.startsWith(p.name + ".") }?.forEach { alt ->
                    val svid = File(alt, "svid").takeIf { it.canRead() }?.readText()?.trim()?.lowercase()
                    if (svid != null) { sawAny = true; if (svid == "ff01") dp = true }
                }
            }
            if (dp) true else if (sawAny) false else null
        }
    } catch (_: Throwable) { null }

    fun deviceName(d: UsbDevice): String =
        listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device %04x:%04x".format(d.vendorId, d.productId) }

    fun hubOrAdapter(d: UsbDevice) = d.deviceClass == UsbConstants.USB_CLASS_HUB || d.deviceClass == CLASS_BILLBOARD ||
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass.let { k -> k == UsbConstants.USB_CLASS_HUB || k == CLASS_BILLBOARD } }

    /** Monitor mode to ask for: sharpest picture that still runs at ~60 Hz or more; else the sharpest. */
    fun bestMode(d: Display): Display.Mode {
        val modes = d.supportedModes.toList()
        val smooth = modes.filter { it.refreshRate >= 59f }
        val pool = smooth.ifEmpty { modes }
        return pool.maxWith(compareBy<Display.Mode>({ it.physicalWidth.toLong() * it.physicalHeight }, { it.refreshRate }))
    }

    /** Applies the best mode to a window shown on the monitor. */
    fun applyBestMode(w: Window, d: Display?) {
        if (d == null || d.displayId == Display.DEFAULT_DISPLAY) return
        try {
            val m = bestMode(d)
            val lp = w.attributes
            if (lp.preferredDisplayModeId != m.modeId) { lp.preferredDisplayModeId = m.modeId; w.attributes = lp }
        } catch (_: Exception) {}
    }

    fun modeText(m: Display.Mode) = "${m.physicalWidth}×${m.physicalHeight} @ ${m.refreshRate.toInt()} Hz"
}

/** One button: Connect. Finds the monitor and opens the desktop on it, or explains exactly what's wrong. */
class ConnectActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var connectBtn: TextView
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val scroll = ScrollView(c).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val root = Ui.column(c, 20)
        root.addView(Ui.title(c, "Connect a monitor"))
        root.addView(Ui.text(c, "Plug the USB‑C → HDMI adapter into the phone, the HDMI cable into the monitor, turn the monitor on and pick its HDMI input. Then tap Connect.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 16))
        })
        connectBtn = TextView(c).apply {
            text = "Connect"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(Ui.ACCENT, Ui.dp(c, 80).toFloat())
            setOnClickListener { connect() }
        }
        val holder = LinearLayout(c).apply { gravity = Gravity.CENTER }
        holder.addView(connectBtn, LinearLayout.LayoutParams(Ui.dp(c, 160), Ui.dp(c, 160)))
        root.addView(holder)
        body = Ui.column(c).apply { setPadding(0, Ui.dp(c, 18), 0, 0) }
        root.addView(body)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        val f = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbRx, f, RECEIVER_NOT_EXPORTED) else registerReceiver(usbRx, f)
        show(MonitorProbe.scan(this))
    }

    override fun onPause() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        try { unregisterReceiver(usbRx) } catch (_: Exception) {}
        super.onPause()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { main.postDelayed({ show(MonitorProbe.scan(this@ConnectActivity)) }, 500) }
        override fun onDisplayRemoved(displayId: Int) { show(MonitorProbe.scan(this@ConnectActivity)) }
        override fun onDisplayChanged(displayId: Int) {}
    }

    private val usbRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { main.postDelayed({ show(MonitorProbe.scan(this@ConnectActivity)) }, 1200) }
    }

    /** The button: if a monitor is there, open the desktop on it; otherwise look for ~4 s, then explain. */
    private fun connect() {
        if (scanning) return
        val r = MonitorProbe.scan(this)
        if (r.display != null) { openDesktop(r.display); return }
        scanning = true
        connectBtn.text = "Looking…"
        var tries = 0
        fun again() {
            val s = MonitorProbe.scan(this)
            if (s.display != null) { scanning = false; connectBtn.text = "Connect"; openDesktop(s.display); return }
            if (++tries < 8) { main.postDelayed({ again() }, 500); return }
            scanning = false; connectBtn.text = "Connect"; show(s)
        }
        again()
    }

    private fun openDesktop(d: Display) {
        Displays.launchOn(this, Intent(this, DesktopActivity::class.java), d.displayId, Displays.bounds(this, d))
        show(MonitorProbe.scan(this))
    }

    // ---------- results ----------

    private fun show(r: MonitorProbe.Result) {
        val c = this
        body.removeAllViews()
        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

        val card = Ui.card(c)
        val d = r.display
        when {
            d != null -> {
                val best = MonitorProbe.bestMode(d)
                card.addView(Ui.statusRow(c, true, "Monitor found", "${d.name} · best mode ${MonitorProbe.modeText(best)}"))
                card.addView(Ui.text(c, "DeskCast uses the sharpest mode that still runs at 60 Hz or more, straight from the phone's graphics chip — no compression, no delay.", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })
                card.addView(Ui.button(c, "Open desktop on the monitor", primary = true) { openDesktop(d) })
                card.addView(Ui.button(c, "Mirror the phone screen instead (games)") {
                    startActivity(Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_MIRROR, true))
                })
                connectBtn.text = "Connect"
            }
            r.displayLink -> {
                card.addView(Ui.statusRow(c, null, "DisplayLink adapter found",
                    "This kind of adapter needs the free “DisplayLink Presenter” app once. After you allow it, the monitor shows up here and Connect opens the desktop."))
                card.addView(Ui.button(c, "Get DisplayLink Presenter", primary = true) { openStore("com.displaylink.presenter") })
            }
            r.billboard || r.phoneHasDp == false -> {
                card.addView(Ui.statusRow(c, false, "Your phone doesn't send video through USB‑C",
                    "The adapter is connected and asked the phone for video, but $model has no video output on its USB‑C port (no “DisplayPort Alt Mode”). That is a chip inside the phone — no app can switch it on."))
                card.addView(Ui.text(c, "You can still use a big screen — see the options below.", 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
            }
            r.devices.isNotEmpty() -> {
                card.addView(Ui.statusRow(c, null, "Adapter connected, but no picture yet",
                    r.devices.joinToString("\n") { "• " + MonitorProbe.deviceName(it) }))
                card.addView(Ui.text(c,
                    "1. Turn the monitor on and choose the right HDMI input.\n" +
                        "2. Unplug the adapter from the phone, wait 3 s, plug it back in.\n" +
                        "3. Tap Connect again.\n\n" +
                        "If it still doesn't appear, $model most likely has no video output over USB‑C" +
                        (if (r.phoneHasDp == true) " — although its port lists DisplayPort, so also try another adapter or cable." else ".") ,
                    13f, Ui.TEXT).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
            }
            else -> {
                card.addView(Ui.statusRow(c, false, "Nothing detected on the USB‑C port",
                    if (r.phoneHasDp == false) "$model has no video output over USB‑C." else "No adapter or monitor answered."))
                card.addView(Ui.text(c,
                    "• Push the adapter fully into the phone.\n• Make sure it is a USB‑C → HDMI video adapter (charging cables and plain OTG adapters carry no video).\n• Turn the monitor on, select HDMI, then tap Connect.",
                    13f, Ui.TEXT).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
            }
        }
        if (r.phoneHasDp == true && d == null) card.addView(Ui.text(c, "Good sign: this phone's USB‑C port supports video.", 13f, Ui.OK).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
        card.addView(Ui.button(c, "Open HDMI video player & test") { startActivity(Intent(c, HdmiVideoActivity::class.java)) })
        body.addView(card)

        if (d == null) {
            body.addView(Ui.section(c, "Other ways to use a big screen"))
            val alt = Ui.card(c)
            alt.addView(Ui.statusRow(c, null, "Smart TV / wireless display",
                "Cast the phone to a Smart TV or Miracast/Chromecast receiver. When Android sees it as a screen, DeskCast opens the desktop there by itself."))
            alt.addView(Ui.button(c, "Open Cast / Screen mirroring") { openCast() })
            alt.addView(Ui.statusRow(c, null, "DisplayLink USB adapter",
                "Works on phones without video out (any Android). Look for “DisplayLink” on the box, then use Connect."))
            alt.addView(Ui.statusRow(c, null, "Stream to a computer",
                "Watch and play the phone on a PC through the USB cable."))
            alt.addView(Ui.button(c, "Set up the PC stream") { startActivity(Intent(c, MainActivity::class.java)) })
            body.addView(alt)
        }

        // What DeskCast can see, for troubleshooting.
        body.addView(Ui.text(c, "Phone: $model · Android ${Build.VERSION.RELEASE} · USB devices: ${r.devices.size}" +
            (if (r.billboard) " · adapter says “no video mode”" else "") +
            when (r.phoneHasDp) { true -> " · port: DisplayPort yes"; false -> " · port: no DisplayPort"; null -> "" },
            11f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 14), 0, 0) })
    }

    private fun openStore(pkg: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    private fun openCast() {
        val tries = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("com.samsung.android.smartmirroring.CAPTURE"),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }
}
