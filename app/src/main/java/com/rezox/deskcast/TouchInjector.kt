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
 * Fingers stay pressed between gestures (`willContinue`), so a held key, a joystick, the mouse
 * and a swipe can all happen at once. Every change is sent as one gesture that continues the
 * fingers already down, adds new ones and lifts released ones. Finger ids use a group prefix
 * ("k:" keyboard, "m:" mouse, "a:" aim) so each input source only touches its own fingers.
 */
class TouchInjector(private val service: AccessibilityService, val displayId: Int) {

    private class Finger(var last: PointF, var target: PointF, var moveMs: Long = 14) {
        var stroke: GestureDescription.StrokeDescription? = null
        var release = false
        /** Pressed again while still lifting: put the finger back down here once the lift is sent. */
        var repressAt: PointF? = null
        var repressFrom: PointF? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val fingers = LinkedHashMap<String, Finger>()
    private var inFlight = false
    private var dirty = false
    private var seq = 0

    /** Called after the system cancels our touches (e.g. the user touched the real screen). */
    var onReset: (() -> Unit)? = null

    fun isDown(id: String) = fingers[id]?.let { !it.release } ?: false

    /** Make exactly [desired] be down among fingers whose id starts with [group]. */
    fun syncGroup(group: String, desired: Map<String, PointF>, starts: Map<String, PointF> = emptyMap()) {
        for ((id, p) in desired) {
            val f = fingers[id]
            if (f == null) {
                val s = starts[id] ?: p
                fingers[id] = Finger(PointF(s.x, s.y), PointF(p.x, p.y))
            } else if (f.release) {
                f.repressAt = PointF(p.x, p.y); f.repressFrom = starts[id]
            } else {
                f.target = PointF(p.x, p.y)
            }
        }
        for ((id, f) in fingers) if (id.startsWith(group) && id !in desired) { f.release = true; f.repressAt = null }
        kick()
    }

    fun press(id: String, p: PointF) {
        val f = fingers[id]
        if (f == null) fingers[id] = Finger(PointF(p.x, p.y), PointF(p.x, p.y))
        else if (f.release) f.repressAt = PointF(p.x, p.y)
        else f.target = PointF(p.x, p.y)
        kick()
    }

    fun move(id: String, p: PointF) {
        val f = fingers[id] ?: return
        if (f.release) return
        f.target = PointF(p.x, p.y)
        kick()
    }

    fun release(id: String) {
        fingers[id]?.let { it.release = true; it.repressAt = null }
        kick()
    }

    /** A quick tap that doesn't disturb held fingers. */
    fun tap(p: PointF) {
        fingers["t:" + (seq++)] = Finger(PointF(p.x, p.y), PointF(p.x, p.y)).apply { release = true }
        kick()
    }

    /** A complete swipe as its own finger; works while other fingers are held. */
    fun swipe(from: PointF, to: PointF, durationMs: Long) {
        fingers["s:" + (seq++)] = Finger(PointF(from.x, from.y), PointF(to.x, to.y), durationMs.coerceIn(10, 600)).apply { release = true }
        kick()
    }

    fun releaseGroup(group: String) {
        var any = false
        for ((id, f) in fingers) if (id.startsWith(group)) { f.release = true; any = true }
        if (any) kick()
    }

    fun releaseAll() {
        if (fingers.isEmpty()) return
        fingers.values.forEach { it.release = true }
        kick()
    }

    private fun kick() {
        dirty = true
        pump()
    }

    private fun pump() {
        if (inFlight || !dirty) return
        dirty = false
        if (fingers.isEmpty()) return

        val max = GestureDescription.getMaxStrokeCount()
        val builder = GestureDescription.Builder().setDisplayId(displayId)
        val sent = ArrayList<Pair<String, GestureDescription.StrokeDescription>>()
        for ((id, f) in fingers) {
            if (sent.size >= max) { dirty = true; break }
            val prev = f.stroke
            val keepDown = !f.release
            val moving = f.last.x != f.target.x || f.last.y != f.target.y
            val s = try {
                if (prev == null) {
                    when {
                        !keepDown && moving -> GestureDescription.StrokeDescription(line(f.last, f.target), 0, f.moveMs, false)
                        !keepDown -> GestureDescription.StrokeDescription(point(f.target), 0, 45, false)
                        moving -> GestureDescription.StrokeDescription(line(f.last, f.target), 0, 25, true)
                        else -> GestureDescription.StrokeDescription(point(f.target), 0, 1, true)
                    }
                } else {
                    val path = if (moving) line(f.last, f.target) else point(f.last)
                    prev.continueStroke(path, 0, if (moving) f.moveMs else 1, keepDown)
                }
            } catch (e: Exception) {
                null
            } ?: continue
            builder.addStroke(s)
            sent += id to s
        }
        if (sent.isEmpty()) { fingers.clear(); return }

        val gesture = try { builder.build() } catch (e: Exception) { resetAll(); return }
        inFlight = true
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                for ((id, s) in sent) {
                    val f = fingers[id] ?: continue
                    if (f.release) {
                        fingers.remove(id)
                        val again = f.repressAt
                        if (again != null) {
                            val from = f.repressFrom ?: again
                            fingers[id] = Finger(PointF(from.x, from.y), PointF(again.x, again.y))
                        }
                    } else { f.stroke = s; f.last = PointF(f.target.x, f.target.y) }
                }
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

    private fun clampPoint(p: PointF) = PointF(maxOf(0f, p.x), maxOf(0f, p.y))
    private fun point(p: PointF) = Path().apply { val q = clampPoint(p); moveTo(q.x, q.y) }
    private fun line(a: PointF, b: PointF) = Path().apply {
        val p = clampPoint(a); val q = clampPoint(b); moveTo(p.x, p.y); lineTo(q.x, q.y)
    }
}
