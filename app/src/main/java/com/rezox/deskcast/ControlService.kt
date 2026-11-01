package com.rezox.deskcast

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlin.math.hypot

/**
 * The control brain: cursor on the external screen, phone-as-touchpad, desktop shortcuts,
 * keyboard → touch mapping for games, and auto-starting the desktop when a screen is plugged in.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        fun isEnabled(c: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.startsWith(c.packageName + "/") }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, main)
        Displays.primaryExternal(this)?.let { maybeAutoDesktop(it.displayId) }
    }

    override fun onDestroy() {
        instance = null
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        hideCursor()
        injectors.values.forEach { it.releaseAll() }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window layout changed: refresh which app has focus (and where its window is) lazily.
        targetDirty = true
    }

    // ---------- external display tracking ----------

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            main.postDelayed({ maybeAutoDesktop(displayId) }, 900)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (cursorDisplayId == displayId) hideCursor()
            injectors.remove(displayId)?.releaseAll()
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    private var lastAutoDisplay = -1

    private fun maybeAutoDesktop(displayId: Int) {
        if (!Settings2.autoDesktop(this)) return
        val d = dm.getDisplay(displayId) ?: return
        if (Displays.external(this).none { it.displayId == displayId }) return
        if (DesktopActivity.current?.displayIdOrNull() == displayId) return
        if (lastAutoDisplay == displayId && DesktopActivity.current != null) return
        lastAutoDisplay = displayId
        openDesktop(d)
    }

    fun openDesktop(d: Display) {
        val b = Displays.bounds(this, d)
        Displays.launchOn(this, Intent(this, DesktopActivity::class.java), d.displayId, b)
    }

    // ---------- injectors per display ----------

    private val injectors = HashMap<Int, TouchInjector>()

    private fun injector(displayId: Int): TouchInjector = injectors.getOrPut(displayId) {
        TouchInjector(this, displayId).also { inj ->
            inj.onReset = { main.postDelayed({ resyncKeymap() }, 150) }
        }
    }

    // ---------- cursor + touchpad ----------

    private var cursorView: CursorView? = null
    private var cursorWm: WindowManager? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    var cursorDisplayId = -1
        private set
    private val cursor = PointF(0f, 0f)
    private var cursorBounds = Rect()
    private val hideRunnable = Runnable { cursorView?.visibility = View.GONE }

    /** Put the cursor on the external screen (or the phone screen when nothing is plugged in). */
    fun ensureCursor(): Boolean {
        val target = Displays.primaryExternal(this) ?: dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        if (cursorView != null && cursorDisplayId == target.displayId) return true
        hideCursor()
        return try {
            val dc = createDisplayContext(target)
            val wc = dc.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = wc.getSystemService(WindowManager::class.java)
            cursorBounds = Rect(wm.maximumWindowMetrics.bounds)
            val size = Ui.dp(wc, 26)
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (cursor.x == 0f && cursor.y == 0f) {
                cursor.set(cursorBounds.exactCenterX(), cursorBounds.exactCenterY())
            }
            clampCursor()
            lp.x = cursor.x.toInt(); lp.y = cursor.y.toInt()
            val v = CursorView(wc)
            wm.addView(v, lp)
            cursorView = v; cursorWm = wm; cursorLp = lp; cursorDisplayId = target.displayId
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Cursor unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun hideCursor() {
        val v = cursorView ?: return
        try { cursorWm?.removeView(v) } catch (_: Exception) {}
        cursorView = null; cursorWm = null; cursorLp = null; cursorDisplayId = -1
    }

    private fun clampCursor() {
        cursor.x = cursor.x.coerceIn(cursorBounds.left.toFloat(), cursorBounds.right - 1f)
        cursor.y = cursor.y.coerceIn(cursorBounds.top.toFloat(), cursorBounds.bottom - 1f)
    }

    private fun showCursorBriefly() {
        val v = cursorView ?: return
        v.visibility = View.VISIBLE
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 4000)
    }

    fun moveCursorBy(dx: Float, dy: Float) {
        if (!ensureCursor()) return
        cursor.x += dx; cursor.y += dy
        clampCursor()
        val lp = cursorLp ?: return
        lp.x = cursor.x.toInt(); lp.y = cursor.y.toInt()
        try { cursorWm?.updateViewLayout(cursorView, lp) } catch (_: Exception) {}
        showCursorBriefly()
        if (dragging) injector(cursorDisplayId).move("drag", PointF(cursor.x, cursor.y))
    }

    fun click() {
        if (!ensureCursor()) return
        showCursorBriefly()
        cursorView?.pulse()
        injector(cursorDisplayId).tap(PointF(cursor.x, cursor.y))
    }

    /** Right click / two-finger tap: Back, like a desktop "close menu / go back". */
    fun secondaryClick() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var scrollPosted = false

    fun scroll(dx: Float, dy: Float) {
        if (!ensureCursor()) return
        scrollAccX += dx; scrollAccY += dy
        if (scrollPosted) return
        scrollPosted = true
        main.postDelayed({
            scrollPosted = false
            val sx = scrollAccX; val sy = scrollAccY
            scrollAccX = 0f; scrollAccY = 0f
            if (hypot(sx, sy) < 2f) return@postDelayed
            val from = PointF(cursor.x, cursor.y)
            val to = PointF(
                (cursor.x + sx * 2.5f).coerceIn(cursorBounds.left.toFloat(), cursorBounds.right - 1f),
                (cursor.y + sy * 2.5f).coerceIn(cursorBounds.top.toFloat(), cursorBounds.bottom - 1f),
            )
            injector(cursorDisplayId).swipe(from, to, 90)
        }, 60)
    }

    private var dragging = false

    fun dragStart() {
        if (!ensureCursor()) return
        dragging = true
        injector(cursorDisplayId).press("drag", PointF(cursor.x, cursor.y))
    }

    fun dragEnd() {
        if (!dragging) return
        dragging = false
        injector(cursorDisplayId).release("drag")
    }

    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---------- focused app tracking ----------

    data class Target(val pkg: String, val displayId: Int, val bounds: Rect)

    @Volatile private var targetDirty = true
    private var target: Target? = null

    private fun focusedTarget(): Target? {
        if (!targetDirty) return target
        targetDirty = false
        target = findTarget { it.isFocused } ?: findTarget { it.isActive }
        return target
    }

    private fun findTarget(pred: (AccessibilityWindowInfo) -> Boolean): Target? {
        val all = try { windowsOnAllDisplays } catch (_: Exception) { return null }
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            for (w in all.valueAt(i)) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !pred(w)) continue
                val pkg = w.root?.packageName?.toString() ?: continue
                val r = Rect(); w.getBoundsInScreen(r)
                return Target(pkg, displayId, r)
            }
        }
        return null
    }

    /** Where [pkg]'s window currently is, on any display (for the keymap editor's capture). */
    fun windowOf(pkg: String): Target? {
        val all = try { windowsOnAllDisplays } catch (_: Exception) { return null }
        for (i in 0 until all.size()) {
            for (w in all.valueAt(i)) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.root?.packageName?.toString() != pkg) continue
                val r = Rect(); w.getBoundsInScreen(r)
                return Target(pkg, all.keyAt(i), r)
            }
        }
        return null
    }

    // ---------- keyboard: shortcuts + game key mapping ----------

    private var metaSolo = false
    var keymapPaused = false
        private set
    private val heldKeys = HashSet<Int>()
    private val keymapCache = HashMap<String, List<KeyBinding>>()
    private var keymapTarget: Target? = null

    fun invalidateKeymaps() {
        main.post { keymapCache.clear() }
    }

    private fun bindingsFor(pkg: String): List<KeyBinding> = keymapCache.getOrPut(pkg) {
        if (Profiles.get(this, pkg).keymapOn) Profiles.bindings(this, pkg) else emptyList()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        // Win / Meta key alone → start menu. Meta combos are handled below.
        if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (down && e.repeatCount == 0) metaSolo = true
            else if (!down && metaSolo) { metaSolo = false; main.post { toggleStartMenu() } }
            return DesktopActivity.current != null
        }
        if (down) metaSolo = false

        if (e.isMetaPressed && down && e.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_D -> { main.post { showDesktop() }; return true }
                KeyEvent.KEYCODE_N -> { globalNotifications(); return true }
                KeyEvent.KEYCODE_TAB -> { globalRecents(); return true }
            }
        }
        if (e.isAltPressed && code == KeyEvent.KEYCODE_TAB) {
            if (down && e.repeatCount == 0) globalRecents()
            return true
        }
        if (e.isAltPressed && code == KeyEvent.KEYCODE_F4) {
            if (down && e.repeatCount == 0) globalBack()
            return true
        }
        // F12 or Ctrl+Shift+K pauses key mapping (to type in chat boxes inside games).
        if ((code == KeyEvent.KEYCODE_F12) || (code == KeyEvent.KEYCODE_K && e.isCtrlPressed && e.isShiftPressed)) {
            if (down && e.repeatCount == 0) {
                keymapPaused = !keymapPaused
                if (keymapPaused) releaseKeymap()
                main.post { Toast.makeText(this, if (keymapPaused) "Key mapping paused" else "Key mapping on", Toast.LENGTH_SHORT).show() }
            }
            return true
        }

        if (keymapPaused) return false
        return handleKeymap(e)
    }

    private fun handleKeymap(e: KeyEvent): Boolean {
        val t = focusedTarget() ?: return false
        if (t.pkg == packageName) return false
        val binds = bindingsFor(t.pkg)
        if (binds.isEmpty()) return false
        if (binds.none { e.keyCode in it.keys() }) return false

        if (e.repeatCount > 0) return true
        if (e.action == KeyEvent.ACTION_DOWN) heldKeys += e.keyCode else heldKeys -= e.keyCode
        if (keymapTarget != null && (keymapTarget!!.displayId != t.displayId || keymapTarget!!.pkg != t.pkg)) {
            injectors[keymapTarget!!.displayId]?.releaseAll()
        }
        keymapTarget = t
        main.post { applyKeymap(t, binds) }
        return true
    }

    private fun resyncKeymap() {
        val t = keymapTarget ?: return
        if (heldKeys.isEmpty()) return
        applyKeymap(t, bindingsFor(t.pkg))
    }

    private fun releaseKeymap() {
        heldKeys.clear()
        keymapTarget?.let { injectors[it.displayId]?.releaseAll() }
    }

    private fun applyKeymap(t: Target, binds: List<KeyBinding>) {
        val b = t.bounds
        if (b.width() <= 0 || b.height() <= 0) return
        fun at(fx: Float, fy: Float) = PointF(b.left + fx * b.width(), b.top + fy * b.height())

        val desired = HashMap<String, PointF>()
        val starts = HashMap<String, PointF>()
        binds.forEachIndexed { i, k ->
            when (k.type) {
                BindType.TAP -> if (k.keyCode in heldKeys) desired["k$i"] = at(k.x, k.y)
                BindType.JOYSTICK -> {
                    var dx = 0f; var dy = 0f
                    if (k.left in heldKeys) dx -= 1f
                    if (k.right in heldKeys) dx += 1f
                    if (k.up in heldKeys) dy -= 1f
                    if (k.down in heldKeys) dy += 1f
                    if (dx != 0f || dy != 0f) {
                        val len = hypot(dx, dy)
                        val r = k.radius * b.width()
                        val c = at(k.x, k.y)
                        desired["j$i"] = PointF(c.x + dx / len * r, c.y + dy / len * r)
                        starts["j$i"] = c
                    }
                }
            }
        }
        injector(t.displayId).sync(desired, starts)
    }

    // ---------- desktop helpers ----------

    private fun toggleStartMenu() {
        val d = DesktopActivity.current
        if (d != null) d.toggleStartMenu()
        else Displays.primaryExternal(this)?.let { openDesktop(it) }
    }

    private fun showDesktop() {
        val d = DesktopActivity.current?.displayIdOrNull()
        val disp = (if (d != null) dm.getDisplay(d) else null) ?: Displays.primaryExternal(this) ?: return
        openDesktop(disp)
    }

    // ---------- screenshots for the keymap editor ----------

    /** Captures [pkg]'s window (cropped) so the editor can show it. Calls back on the main thread. */
    fun captureWindow(pkg: String, cb: (Bitmap?, String) -> Unit) {
        val t = windowOf(pkg)
        if (t == null) { cb(null, "Open the app first (on the big screen or the phone), then tap Capture."); return }
        takeScreenshot(t.displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val full = try {
                    Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }
                try { result.hardwareBuffer.close() } catch (_: Exception) {}
                if (full == null) { cb(null, "Could not read the screenshot."); return }
                val r = Rect(t.bounds)
                r.intersect(0, 0, full.width, full.height)
                val crop = if (r.width() > 0 && r.height() > 0) Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height()) else full
                cb(crop, "")
            }

            override fun onFailure(errorCode: Int) {
                cb(null, "Screenshot failed (code $errorCode). Wait a second and try again.")
            }
        })
    }

    // ---------- cursor drawing ----------

    private class CursorView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 1.5f).toFloat(); strokeJoin = Paint.Join.ROUND
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat() }
        private var pulseUntil = 0L

        fun pulse() { pulseUntil = System.currentTimeMillis() + 160; invalidate(); postDelayed({ invalidate() }, 170) }

        override fun onDraw(canvas: Canvas) {
            val s = width / 26f
            val p = Path().apply {
                moveTo(2 * s, 2 * s); lineTo(2 * s, 20 * s); lineTo(7 * s, 15.5f * s); lineTo(10.5f * s, 23 * s)
                lineTo(13.5f * s, 21.7f * s); lineTo(10 * s, 14.5f * s); lineTo(16.5f * s, 14.5f * s); close()
            }
            if (System.currentTimeMillis() < pulseUntil) canvas.drawCircle(2 * s, 2 * s, 8 * s, ring)
            canvas.drawPath(p, fill)
            canvas.drawPath(p, stroke)
        }
    }
}
