package com.rezox.deskcast

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns the phone into a laptop touchpad for the external screen.
 *  1 finger move → cursor · tap → click · tap‑and‑hold then move → drag
 *  2 fingers move → scroll · 2‑finger tap → back (right click)
 * A physical mouse can also be captured here so it drives the big-screen cursor.
 */
class TouchpadActivity : Activity() {

    private lateinit var pad: PadView
    private var captureOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = this
        val root = Ui.column(c).apply { setBackgroundColor(Ui.BG) }

        val bar = Ui.row(c).apply { setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 4)) }
        fun chip(label: String, onClick: () -> Unit) = TextView(c).apply {
            text = label; textSize = 14f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
            background = Ui.rounded(Ui.CARD_HI, Ui.dp(c, 10).toFloat())
            setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(c, 6) }
        }
        bar.addView(chip("◁ Back") { ControlService.instance?.globalBack() })
        bar.addView(chip("⊞ Start") {
            val d = DesktopActivity.current
            if (d != null) d.toggleStartMenu() else Displays.primaryExternal(c)?.let { ControlService.instance?.openDesktop(it) }
        })
        bar.addView(chip("▢ Apps") { ControlService.instance?.globalRecents() })
        bar.addView(chip("🖱 Mouse") { toggleCapture() })
        root.addView(bar)

        pad = PadView(c)
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
        })

        val buttons = Ui.row(c).apply { setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 14)) }
        fun big(label: String, onClick: () -> Unit) = TextView(c).apply {
            text = label; textSize = 15f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
            background = Ui.rounded(Ui.CARD, Ui.dp(c, 14).toFloat())
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(c, 64), 1f).apply { rightMargin = Ui.dp(c, 8) }
        }
        buttons.addView(big("Left click") { ControlService.instance?.click() })
        buttons.addView(big("Right click") { ControlService.instance?.secondaryClick() })
        root.addView(buttons)

        setContentView(root)

        if (ControlService.instance == null) {
            Toast.makeText(c, "Turn on DeskCast control in Accessibility first.", Toast.LENGTH_LONG).show()
        } else {
            ControlService.instance?.ensureCursor()
        }
    }

    override fun onPause() {
        ControlService.instance?.dragEnd()
        super.onPause()
    }

    // ---------- physical mouse capture ----------

    private fun toggleCapture() {
        captureOn = !captureOn
        if (captureOn) {
            pad.isFocusable = true
            pad.isFocusableInTouchMode = true
            pad.requestFocus()
            pad.requestPointerCapture()
            Toast.makeText(this, "Mouse captured — it now moves the big-screen cursor. Press Esc to release.", Toast.LENGTH_LONG).show()
        } else {
            pad.releasePointerCapture()
        }
        pad.invalidate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && captureOn) pad.requestPointerCapture()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (captureOn && event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && event.action == android.view.KeyEvent.ACTION_UP) {
            toggleCapture(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class PadView(c: Context) : View(c) {
        private val speed = Settings2.cursorSpeed(c)
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = Ui.dp(c, 14).toFloat(); textAlign = Paint.Align.CENTER }
        private val bg = Ui.rounded(Ui.CARD, Ui.dp(c, 20).toFloat(), 0x22FFFFFF, Ui.dp(c, 1))

        private var lastX = 0f; private var lastY = 0f
        private var downX = 0f; private var downY = 0f
        private var downTime = 0L
        private var maxFingers = 0
        private var moved = false
        private var dragging = false
        private var lastTapUp = 0L
        private val slop = Ui.dp(c, 8).toFloat()

        init { background = bg }

        override fun onDraw(canvas: Canvas) {
            val lines = if (captureOn) listOf("Mouse captured", "Esc to release")
            else listOf("Touchpad", "Move · tap to click · double‑tap & hold to drag", "Two fingers: scroll · two‑finger tap: back")
            var y = height / 2f - (lines.size - 1) * hint.textSize
            lines.forEachIndexed { i, s ->
                hint.color = if (i == 0) Ui.TEXT else Ui.MUTED
                canvas.drawText(s, width / 2f, y, hint); y += hint.textSize * 1.8f
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val svc = ControlService.instance ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                    downTime = SystemClock.uptimeMillis(); maxFingers = 1; moved = false
                    // Second tap quickly after a tap = start a drag (like laptop touchpads).
                    if (downTime - lastTapUp < 280) { dragging = true; svc.dragStart() }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxFingers = maxOf(maxFingers, e.pointerCount)
                    lastX = avgX(e); lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val x = avgX(e); val y = avgY(e)
                        val dx = x - lastX; val dy = y - lastY
                        lastX = x; lastY = y
                        if (hypot(dx, dy) > 0.5f) { moved = true; svc.scroll(dx, dy) }
                    } else {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        lastX = e.x; lastY = e.y
                        if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                        if (moved) svc.moveCursorBy(accel(dx), accel(dy))
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = (0 until e.pointerCount).filter { it != e.actionIndex }
                    if (remaining.isNotEmpty()) { lastX = e.getX(remaining[0]); lastY = e.getY(remaining[0]) }
                }
                MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.uptimeMillis() - downTime < 250
                    if (dragging) {
                        dragging = false; svc.dragEnd()
                    } else if (!moved && quick) {
                        if (maxFingers >= 2) svc.secondaryClick()
                        else { svc.click(); lastTapUp = SystemClock.uptimeMillis() }
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
                MotionEvent.ACTION_CANCEL -> { if (dragging) { dragging = false; svc.dragEnd() } }
            }
            return true
        }

        override fun onCapturedPointerEvent(e: MotionEvent): Boolean {
            val svc = ControlService.instance ?: return false
            if (!e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) && !e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                    svc.moveCursorBy(e.x * speed, e.y * speed)
                }
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    when (e.actionButton) {
                        MotionEvent.BUTTON_PRIMARY -> { downTime = SystemClock.uptimeMillis(); svc.dragStart(); dragging = true }
                        MotionEvent.BUTTON_SECONDARY -> svc.secondaryClick()
                    }
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (e.actionButton == MotionEvent.BUTTON_PRIMARY && dragging) { dragging = false; svc.dragEnd() }
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    svc.scroll(-h * 60f, v * 60f)
                }
            }
            return true
        }

        private fun accel(d: Float): Float {
            val a = abs(d)
            val k = if (a > 18) 2.2f else if (a > 8) 1.6f else 1.0f
            return d * k * speed
        }

        private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
        private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }
    }
}
