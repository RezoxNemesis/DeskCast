package com.rezox.deskcast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper

/**
 * Multi-finger touch injection on one display, built on accessibility gestures.
 *
 * Fingers stay pressed between gestures (`willContinue`), so a held key, a joystick being steered
 * and a tap can all happen at once: every state change is sent as one gesture that continues the
 * fingers already down, adds new ones and lifts released ones.
 */
class TouchInjector(private val service: AccessibilityService, val displayId: Int) {

    private class Finger(var last: PointF, var target: PointF) {
        var stroke: GestureDescription.StrokeDescription? = null
        var release = false
        var tapOnly = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val fingers = LinkedHashMap<String, Finger>()
    private var inFlight = false
    private var dirty = false

    /** Called after the system cancels our touches (e.g. the user touched the screen). */
    var onReset: (() -> Unit)? = null

    val isIdle get() = fingers.isEmpty() && !inFlight

    /** Make exactly these fingers be down at these points. [starts] gives where new fingers land first. */
    fun sync(desired: Map<String, PointF>, starts: Map<String, PointF> = emptyMap()) {
        for ((id, p) in desired) {
            val f = fingers[id]
            if (f == null) {
                fingers[id] = Finger(PointF((starts[id] ?: p).x, (starts[id] ?: p).y), PointF(p.x, p.y))
            } else if (!f.release) {
                f.target = PointF(p.x, p.y)
            }
        }
        for ((id, f) in fingers) if (id !in desired) f.release = true
        dirty = true
        pump()
    }

    fun press(id: String, p: PointF) {
        val f = fingers[id]
        if (f == null) fingers[id] = Finger(PointF(p.x, p.y), PointF(p.x, p.y))
        else { f.target = PointF(p.x, p.y); f.release = false }
        dirty = true; pump()
    }

    fun move(id: String, p: PointF) {
        val f = fingers[id] ?: return
        f.target = PointF(p.x, p.y)
        dirty = true; pump()
    }

    fun release(id: String) {
        fingers[id]?.release = true
        dirty = true; pump()
    }

    /** A quick tap that doesn't disturb any held fingers. */
    fun tap(p: PointF) {
        val id = "tap" + System.nanoTime()
        fingers[id] = Finger(PointF(p.x, p.y), PointF(p.x, p.y)).apply { release = true; tapOnly = true }
        dirty = true; pump()
    }

    /** One-off swipe (scrolling). Skipped while fingers are held so it can't break a drag. */
    fun swipe(from: PointF, to: PointF, durationMs: Long) {
        if (fingers.isNotEmpty() || inFlight) return
        val path = Path().apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .setDisplayId(displayId)
            .build()
        inFlight = true
        val ok = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { inFlight = false; if (dirty) pump() }
            override fun onCancelled(gestureDescription: GestureDescription?) { inFlight = false; if (dirty) pump() }
        }, main)
        if (!ok) inFlight = false
    }

    fun releaseAll() {
        if (fingers.isEmpty()) return
        fingers.values.forEach { it.release = true }
        dirty = true; pump()
    }

    private fun pump() {
        if (inFlight || !dirty) return
        dirty = false
        if (fingers.isEmpty()) return

        val max = GestureDescription.getMaxStrokeCount()
        val builder = GestureDescription.Builder().setDisplayId(displayId)
        val sent = ArrayList<Pair<String, GestureDescription.StrokeDescription>>()
        var count = 0
        for ((id, f) in fingers) {
            if (count >= max) { dirty = true; break }
            val prev = f.stroke
            val keepDown = !f.release
            val s: GestureDescription.StrokeDescription = if (prev == null) {
                if (!keepDown && f.tapOnly) {
                    GestureDescription.StrokeDescription(point(f.target), 0, 40, false)
                } else if (!keepDown) {
                    // Pressed and released before it was ever sent: deliver as a tap.
                    GestureDescription.StrokeDescription(point(f.target), 0, 40, false)
                } else {
                    val moving = f.last.x != f.target.x || f.last.y != f.target.y
                    val path = if (moving) line(f.last, f.target) else point(f.target)
                    GestureDescription.StrokeDescription(path, 0, if (moving) 30 else 1, true)
                }
            } else {
                val moving = f.last.x != f.target.x || f.last.y != f.target.y
                val path = if (moving) line(f.last, f.target) else point(f.last)
                try {
                    prev.continueStroke(path, 0, if (moving) 12 else 1, keepDown)
                } catch (e: Exception) {
                    GestureDescription.StrokeDescription(point(f.target), 0, 1, keepDown)
                }
            }
            builder.addStroke(s)
            sent += id to s
            count++
        }
        if (sent.isEmpty()) return

        val gesture = try { builder.build() } catch (e: Exception) { resetAll(); return }
        inFlight = true
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                for ((id, s) in sent) {
                    val f = fingers[id] ?: continue
                    if (f.release) fingers.remove(id) else { f.stroke = s; f.last = PointF(f.target.x, f.target.y) }
                }
                // Fingers whose target changed while this gesture was in flight still need a move.
                if (fingers.values.any { it.stroke == null || it.release || it.last.x != it.target.x || it.last.y != it.target.y }) dirty = true
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                inFlight = false
                resetAll()
            }
        }, main)
        if (!ok) { inFlight = false; resetAll() }
    }

    private fun resetAll() {
        fingers.clear()
        dirty = false
        onReset?.invoke()
    }

    private fun point(p: PointF) = Path().apply { moveTo(p.x, p.y) }
    private fun line(a: PointF, b: PointF) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }
}
