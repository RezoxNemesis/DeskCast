package com.rezox.deskcast

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HDMI diagnostics + movie playback in DeskCast. It uses public Android display/USB APIs;
 * it cannot create DP Alt Mode or make a passive HDMI dongle output video.
 */
class HdmiVideoActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var currentVideo: TextView
    private lateinit var outputLabel: TextView
    private lateinit var playButton: TextView
    private lateinit var outputButton: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var phoneVideo: VideoView
    private lateinit var phoneMotion: MotionTestView
    private lateinit var phonePlaceholder: TextView

    private var remote: ScreenVideoPresentation? = null
    private var currentUri: Uri? = null
    private var motionMode = true
    private var playing = true
    private var preferExternal = true
    private var playbackPosition = 0
    private var chosenDisplayId: Int? = null
    private var receiverRegistered = false
    private var scrubbing = false
    private var videoTitle = "Offline HDMI motion test"

    private val ticker = object : Runnable {
        override fun run() {
            updatePlaybackLabels()
            ui.postDelayed(this, 500)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { ui.postDelayed({ refreshConnection() }, 350) }
        override fun onDisplayRemoved(displayId: Int) { refreshConnection() }
        override fun onDisplayChanged(displayId: Int) { refreshConnection() }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ui.postDelayed({ refreshConnection() }, 450)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildScreen()
        showMotionTest()
        refreshConnection()
        ui.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, ui)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        refreshConnection()
    }

    override fun onPause() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        closeRemote()
        phoneVideo.stopPlayback()
        phoneMotion.setRunning(false)
        super.onDestroy()
    }

    private fun buildScreen() {
        val c = this
        val scroll = ScrollView(c).apply { setBackgroundColor(Ui.BG); isFillViewport = true }
        val col = Ui.column(c, 18)
        col.addView(Ui.title(c, "HDMI Video"))
        col.addView(Ui.text(c, "Connection detector · display test · video player", 14f, Ui.MUTED))

        col.addView(Ui.section(c, "Live connection"))
        val status = Ui.card(c)
        statusLine = Ui.text(c, "Checking connected screens…", 19f, Ui.TEXT, true)
        statusDetail = Ui.text(c, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 8), 0, 0) }
        status.addView(statusLine)
        status.addView(statusDetail)
        status.addView(Ui.button(c, "Rescan HDMI / USB") { refreshConnection() })
        status.addView(Ui.button(c, "Choose output screen") { chooseOutputScreen() })
        status.addView(Ui.button(c, "Connection troubleshooting") {
            startActivity(Intent(c, ConnectActivity::class.java))
        })
        col.addView(status)

        col.addView(Ui.section(c, "Now playing"))
        val playerCard = Ui.card(c)
        currentVideo = Ui.text(c, "Offline HDMI motion test", 17f, Ui.TEXT, true)
        outputLabel = Ui.text(c, "On phone", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 10)) }
        playerCard.addView(currentVideo)
        playerCard.addView(outputLabel)

        val videoStage = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            background = Ui.rounded(0xFF050816.toInt(), Ui.dp(c, 12).toFloat())
        }
        phoneVideo = VideoView(c)
        phoneMotion = MotionTestView(c)
        phonePlaceholder = TextView(c).apply {
            text = "Output is playing on the external screen.\nUse the controls below."
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Ui.TEXT)
            setBackgroundColor(0xFF0B1023.toInt())
            visibility = View.GONE
        }
        listOf(phoneVideo, phoneMotion, phonePlaceholder).forEach {
            videoStage.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val stageHeight = (resources.displayMetrics.widthPixels - Ui.dp(c, 68)) * 9 / 16
        playerCard.addView(videoStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(Ui.dp(c, 164), stageHeight)))

        val buttons = Ui.row(c).apply { setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 6)) }
        playButton = Ui.button(c, "Pause") { togglePlayback() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { rightMargin = Ui.dp(c, 7) }
        }
        outputButton = Ui.button(c, "Use external screen") { toggleOutput() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1.6f)
        }
        buttons.addView(playButton)
        buttons.addView(outputButton)
        playerCard.addView(buttons)

        progressBar = SeekBar(c).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) { scrubbing = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    scrubbing = false
                    if (!motionMode) seekTo(progressBar.progress)
                }
            })
        }
        timeLabel = Ui.text(c, "Live animation", 12f, Ui.MUTED)
        playerCard.addView(progressBar)
        playerCard.addView(timeLabel)
        col.addView(playerCard)

        col.addView(Ui.section(c, "Videos to play"))
        val videos = Ui.card(c)
        videos.addView(Ui.text(c, "Built-in signal test works offline. Sample videos below require internet; your own videos play from phone storage.", 13f, Ui.MUTED))
        videos.addView(Ui.button(c, "▶ Offline motion & color test", primary = true) { showMotionTest() })
        videos.addView(Ui.button(c, "▶ Nature — flower (CC0 sample)") {
            loadVideo("Nature · flower", Uri.parse("https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"))
        })
        videos.addView(Ui.button(c, "▶ Big Buck Bunny — 10 sec clip") {
            loadVideo("Big Buck Bunny · 10 second sample", Uri.parse("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"))
        })
        videos.addView(Ui.button(c, "▶ Sintel — movie trailer") {
            loadVideo("Sintel · trailer", Uri.parse("https://media.w3.org/2010/05/sintel/trailer.mp4"))
        })
        videos.addView(Ui.button(c, "Choose a video from my phone") { pickLocalVideo() })
        videos.addView(Ui.button(c, "Play a direct video URL") { askForVideoUrl() })
        col.addView(videos)

        col.addView(Ui.section(c, "Other connection methods"))
        val links = Ui.card(c)
        links.addView(Ui.button(c, "Open screen casting settings") { openCastSettings() })
        links.addView(Ui.button(c, "DisplayLink adapter setup") { openDisplayLink() })
        links.addView(Ui.text(c,
            "A USB hub being detected does not mean HDMI video is active. An ordinary USB-C → HDMI dongle needs hardware video output (DP Alt Mode). For a USB 2.0 phone without video output, a genuine DisplayLink USB graphics adapter is a different option.",
            13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 8), 0, 0) })
        col.addView(links)
        col.addView(View(c), LinearLayout.LayoutParams(1, Ui.dp(c, 24)))
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun refreshConnection() {
        if (isFinishing || isDestroyed) return
        val scan = MonitorProbe.scan(this)
        val screens = Displays.external(this)
        val selected = selectedScreen()
        val usb = scan.devices.size
        val deviceDetail = when {
            scan.displayLink -> "DisplayLink USB adapter identified"
            scan.billboard -> "Adapter reports alternate-mode negotiation failed (USB Billboard)"
            usb > 0 -> "$usb USB device(s) attached; this does not prove HDMI output"
            else -> "No USB host accessories are visible"
        }
        when {
            screens.isNotEmpty() -> {
                statusLine.text = "External screen detected"
                statusLine.setTextColor(Ui.OK)
                statusDetail.text = "${selected?.name ?: screens.first().name} · ${screens.size} Android display(s) available.\n$deviceDetail"
            }
            scan.displayLink -> {
                statusLine.text = "DisplayLink adapter detected"
                statusLine.setTextColor(Ui.WARN)
                statusDetail.text = "The adapter is visible, but Android has not registered a screen. Enable it with DisplayLink Presenter."
            }
            scan.billboard -> {
                statusLine.text = "No HDMI video mode"
                statusLine.setTextColor(Ui.BAD)
                statusDetail.text = "$deviceDetail. A software app cannot provide a missing hardware video mode."
            }
            usb > 0 -> {
                statusLine.text = "USB connected · no video screen"
                statusLine.setTextColor(Ui.WARN)
                statusDetail.text = "$deviceDetail. Check the monitor input and adapter."
            }
            else -> {
                statusLine.text = "Waiting for display"
                statusLine.setTextColor(Ui.MUTED)
                statusDetail.text = "Connect an HDMI monitor with compatible hardware, or use your phone as the player."
            }
        }
        val desiredDisplayId = if (preferExternal) selected?.displayId else null
        val remoteDisplayId = remote?.display?.displayId
        if (desiredDisplayId != remoteDisplayId) {
            val oldPosition = currentPosition()
            startOutput(oldPosition)
        } else {
            updateOutputLabels()
        }
    }

    private fun selectedScreen(): Display? {
        val screens = Displays.external(this)
        return screens.firstOrNull { it.displayId == chosenDisplayId } ?: screens.firstOrNull()
    }

    private fun chooseOutputScreen() {
        val screens = Displays.external(this)
        if (screens.isEmpty()) {
            showError("No Android external display is registered yet. Connect a compatible adapter or use Cast settings.")
            return
        }
        val labels = screens.map { "${it.name} (display ${it.displayId})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose a connected screen").setItems(labels) { _, index ->
            val pos = currentPosition()
            chosenDisplayId = screens[index].displayId
            preferExternal = true
            startOutput(pos)
            refreshConnection()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun toggleOutput() {
        val screens = Displays.external(this)
        if (screens.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No external screen available")
                .setMessage("The phone has not registered a display. A connected HDMI cable or USB hub alone is not enough. Use Connect troubleshooting, or try a genuine DisplayLink adapter with its official app.")
                .setPositiveButton("Troubleshoot") { _, _ -> startActivity(Intent(this, ConnectActivity::class.java)) }
                .setNegativeButton("Close", null).show()
            return
        }
        val oldPosition = currentPosition()
        preferExternal = !preferExternal
        startOutput(oldPosition)
    }

    private fun showMotionTest() {
        currentUri = null
        motionMode = true
        videoTitle = "Offline HDMI motion test"
        playing = true
        playbackPosition = 0
        currentVideo.text = videoTitle
        startOutput(0)
    }

    private fun loadVideo(title: String, uri: Uri) {
        currentUri = uri
        motionMode = false
        playing = true
        videoTitle = title
        playbackPosition = 0
        currentVideo.text = title
        startOutput(0)
    }

    private fun startOutput(positionMs: Int) {
        playbackPosition = positionMs.coerceAtLeast(0)
        val display = if (preferExternal) selectedScreen() else null
        if (display == null) {
            closeRemote()
            phonePlaceholder.visibility = View.GONE
            if (motionMode) {
                phoneVideo.stopPlayback()
                phoneVideo.visibility = View.GONE
                phoneMotion.visibility = View.VISIBLE
                phoneMotion.setRunning(playing)
            } else {
                phoneMotion.setRunning(false)
                phoneMotion.visibility = View.GONE
                phoneVideo.visibility = View.VISIBLE
                currentUri?.let { loadIntoPhone(it, playbackPosition) }
            }
        } else {
            try {
                if (remote?.display?.displayId != display.displayId) {
                    closeRemote()
                    remote = ScreenVideoPresentation(this, display) { message ->
                        runOnUiThread { showError(message) }
                    }.also { it.show() }
                }
                phoneMotion.setRunning(false)
                phoneMotion.visibility = View.GONE
                phoneVideo.stopPlayback()
                phoneVideo.visibility = View.GONE
                phonePlaceholder.visibility = View.VISIBLE
                if (motionMode) remote?.showTest(playing)
                else currentUri?.let { remote?.showVideo(it, playbackPosition, playing) }
            } catch (e: Exception) {
                closeRemote()
                preferExternal = false
                showError("Android couldn't open the external screen: ${e.message ?: "unknown error"}")
                startOutput(playbackPosition)
                return
            }
        }
        updateOutputLabels()
        updatePlaybackLabels()
    }

    private fun loadIntoPhone(uri: Uri, positionMs: Int) {
        phoneVideo.setOnPreparedListener { player ->
            player.isLooping = false
            if (positionMs > 0) phoneVideo.seekTo(positionMs)
            if (playing) phoneVideo.start()
            updatePlaybackLabels()
        }
        phoneVideo.setOnErrorListener { _, what, extra ->
            showError("Video couldn't play (code $what/$extra). Check the network, direct video URL, or codec.")
            true
        }
        phoneVideo.setVideoURI(uri)
        phoneVideo.requestFocus()
    }

    private fun togglePlayback() {
        playing = !playing
        if (motionMode) {
            if (remote != null) remote?.setMotionRunning(playing) else phoneMotion.setRunning(playing)
        } else if (remote != null) {
            remote?.setVideoPlaying(playing)
        } else {
            if (playing) phoneVideo.start() else phoneVideo.pause()
        }
        updatePlaybackLabels()
    }

    private fun currentPosition(): Int = when {
        motionMode -> 0
        remote != null -> (remote?.videoPosition() ?: 0).takeIf { it > 0 } ?: playbackPosition
        else -> (phoneVideo.currentPosition).takeIf { it > 0 } ?: playbackPosition
    }

    private fun currentDuration(): Int = if (motionMode) 0 else if (remote != null) remote?.videoDuration() ?: 0 else phoneVideo.duration

    private fun seekTo(position: Int) {
        if (motionMode) return
        val duration = currentDuration()
        playbackPosition = position.coerceIn(0, max(0, duration))
        if (remote != null) remote?.seekVideo(playbackPosition) else phoneVideo.seekTo(playbackPosition)
        updatePlaybackLabels()
    }

    private fun updateOutputLabels() {
        val screen = remote?.display
        outputLabel.text = if (screen != null) "Playing on ${screen.name}" else "Playing on phone"
        outputButton.text = if (screen != null) "Use phone screen" else "Use external screen"
    }

    private fun updatePlaybackLabels() {
        playButton.text = if (playing) "Pause" else "Play"
        if (motionMode) {
            progressBar.isEnabled = false
            progressBar.progress = 0
            timeLabel.text = "Offline 30 FPS test scene · ${if (playing) "running" else "paused"}"
        } else {
            val duration = currentDuration().coerceAtLeast(0)
            val pos = currentPosition().coerceIn(0, max(0, duration))
            if (duration > 0) {
                playbackPosition = pos
                progressBar.isEnabled = true
                progressBar.max = duration
                if (!scrubbing) progressBar.progress = pos
                timeLabel.text = "${formatTime(pos)} / ${formatTime(duration)}"
            } else {
                progressBar.isEnabled = false
                timeLabel.text = "Opening video…"
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun pickLocalVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 501)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 501 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) {}
        loadVideo("Video from phone", uri)
    }

    private fun askForVideoUrl() {
        val field = EditText(this).apply {
            hint = "https://example.com/movie.mp4"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(Ui.dp(this@HdmiVideoActivity, 18), Ui.dp(this@HdmiVideoActivity, 12), Ui.dp(this@HdmiVideoActivity, 18), Ui.dp(this@HdmiVideoActivity, 12))
        }
        AlertDialog.Builder(this)
            .setTitle("Play a direct video link")
            .setMessage("Use a direct HTTPS MP4 video URL, not a webpage or DRM streaming link.")
            .setView(field)
            .setPositiveButton("Play") { _, _ ->
                val uri = Uri.parse(field.text.toString().trim())
                if (uri.scheme == "https" && !uri.host.isNullOrBlank()) loadVideo("Web video", uri)
                else showError("Enter a valid HTTPS video link.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openCastSettings() {
        try { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openDisplayLink() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.displaylink.presenter"))) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.displaylink.presenter"))) }
    }

    private fun closeRemote() {
        try { remote?.stop() } catch (_: Exception) {}
        try { remote?.dismiss() } catch (_: Exception) {}
        remote = null
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Video output status").setMessage(message).setPositiveButton("OK", null).show()
    }
}

/** The TV receives its own VideoView surface, not a fake USB/HDMI stream. */
private class ScreenVideoPresentation(
    context: Context,
    display: Display,
    private val onError: (String) -> Unit,
) : Presentation(context, display) {
    private lateinit var movie: VideoView
    private lateinit var scene: MotionTestView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.let { MonitorProbe.applyBestMode(it, display) }
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        movie = VideoView(context)
        scene = MotionTestView(context)
        frame.addView(movie, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
    }

    fun showTest(playing: Boolean) {
        movie.stopPlayback()
        movie.visibility = View.GONE
        scene.visibility = View.VISIBLE
        scene.setRunning(playing)
    }

    fun showVideo(uri: Uri, startMs: Int, shouldPlay: Boolean) {
        scene.setRunning(false)
        scene.visibility = View.GONE
        movie.visibility = View.VISIBLE
        movie.setOnPreparedListener { player ->
            player.isLooping = false
            if (startMs > 0) movie.seekTo(startMs)
            if (shouldPlay) movie.start()
        }
        movie.setOnErrorListener { _, code, extra ->
            onError("External screen couldn't play the video (code $code/$extra). Try another video or format.")
            true
        }
        movie.setVideoURI(uri)
        movie.requestFocus()
    }

    fun setMotionRunning(running: Boolean) { scene.setRunning(running) }
    fun setVideoPlaying(playing: Boolean) { if (playing) movie.start() else movie.pause() }
    fun seekVideo(ms: Int) { movie.seekTo(ms) }
    fun videoPosition() = movie.currentPosition
    fun videoDuration() = movie.duration
    fun stop() { scene.setRunning(false); movie.stopPlayback() }

    override fun onStop() {
        stop()
        super.onStop()
    }
}

/** Lightweight, entirely offline 30 FPS animated signal-check pattern. Not an encoded movie. */
private class MotionTestView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()
    private var running = false
    private var freezeAt = 0f

    fun setRunning(active: Boolean) {
        if (active == running) return
        if (active) {
            startOffset = (SystemClock.uptimeMillis() / 1000f) - freezeAt
        } else {
            freezeAt = timeSeconds()
        }
        running = active
        invalidate()
    }

    private var startOffset = start / 1000f
    private fun timeSeconds(): Float = if (running) SystemClock.uptimeMillis() / 1000f - startOffset else freezeAt

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val time = timeSeconds()
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(0xFF111A37.toInt(), 0xFF152349.toInt(), 0xFF080B17.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        for (i in 0..17) {
            val phase = i * 0.40f + time * 0.42f
            val x = w * (0.5f + 0.44f * sin(phase * 1.2f))
            val y = h * (0.48f + 0.34f * cos(phase * 0.76f))
            val radius = h * (0.035f + 0.009f * (i % 4))
            paint.color = if (i % 3 == 0) 0xBB54E4DE.toInt() else if (i % 3 == 1) 0x88A976FF.toInt() else 0xAAFF70AD.toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, w * 0.004f)
        for (j in 0..4) {
            val r = h * (0.15f + j * 0.115f)
            paint.color = if (j % 2 == 0) 0xAA5FE2D5.toInt() else 0x8856A8FF.toInt()
            canvas.drawCircle(w * 0.5f, h * 0.51f, r + 9f * sin(time * 1.2f + j), paint)
        }
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        paint.color = Color.WHITE
        paint.textSize = min(w * 0.067f, h * 0.10f)
        canvas.drawText("HDMI VIDEO TEST", w * 0.5f, h * 0.51f, paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.color = 0xFFB7C6E3.toInt()
        paint.textSize = min(w * 0.029f, h * 0.046f)
        canvas.drawText("MOTION • COLOR • DISPLAY", w * 0.5f, h * 0.61f, paint)
        paint.textSize = min(w * 0.022f, h * 0.033f)
        canvas.drawText("OFFLINE SIGNAL CHECK  /  30 FPS", w * 0.5f, h * 0.88f, paint)
        if (running) postInvalidateDelayed(33L)
    }
}
