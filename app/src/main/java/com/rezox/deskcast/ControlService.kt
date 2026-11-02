package com.rezox.deskcast

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The control brain: Win-key start menu and desktop shortcuts, keyboard → touch for games,
 * mouse → finger in games (Android 14+), phone-as-touchpad cursor, and auto-starting the desktop.
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

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, main)
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, main)
        Displays.primaryExternal(this)?.let { maybeAutoDesktop(it.displayId) }
        main.post { onFocusMaybeChanged() }
    }

    override fun onDestroy() {
        instance = null
        try { dm.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener) } catch (_: Exception) {}
        overlay?.dismiss(); overlay = null
        hideCursor()
        injectors.values.forEach { it.releaseAll() }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != "com.android.systemui") lastStatePkg = it }
        }
        targetDirty = true
        main.removeCallbacks(focusCheck)
        main.postDelayed(focusCheck, 120)
    }

    private val focusCheck = Runnable { onFocusMaybeChanged() }

    private var lastFocusPkg: String? = null

    private fun onFocusMaybeChanged() {
        val t = focusedTarget()
        if (t?.pkg != lastFocusPkg) {
            // Switching apps: lift every finger we hold so nothing gets stuck.
            if (lastFocusPkg != null) { releaseKeymap(); exitAim(false); releaseMouseFingers() }
            lastFocusPkg = t?.pkg
        }
        updateMouseInterception()
    }

    // =========================================================================================
    // external display / input device tracking
    // =========================================================================================

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { main.postDelayed({ maybeAutoDesktop(displayId) }, 900) }
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

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { main.postDelayed({ maybeAutoPhoneDesktop(deviceId) }, 600) }
        override fun onInputDeviceRemoved(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    private fun maybeAutoPhoneDesktop(deviceId: Int) {
        if (!Settings2.autoPhoneDesktop(this)) return
        if (Displays.external(this).isNotEmpty()) return
        if (DesktopActivity.phoneCurrent != null) return
        val dev = InputDevice.getDevice(deviceId) ?: return
        if (dev.isVirtual || !dev.isExternal) return
        val mouse = dev.supportsSource(InputDevice.SOURCE_MOUSE)
        val keyboard = dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        if (mouse || keyboard) DesktopActivity.startPhoneDesktop(this)
    }

    // =========================================================================================
    // injectors per display
    // =========================================================================================

    private val injectors = HashMap<Int, TouchInjector>()

    private fun injector(displayId: Int): TouchInjector = injectors.getOrPut(displayId) {
        TouchInjector(this, displayId).also { inj -> inj.onReset = { main.postDelayed({ resyncKeymap() }, 150) } }
    }

    private fun displaySize(displayId: Int): Rect {
        val d = dm.getDisplay(displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
        return try { Displays.bounds(this, d) } catch (_: Exception) { Rect(0, 0, 1080, 1920) }
    }

    // =========================================================================================
    // focused app tracking
    // =========================================================================================

    data class Target(val pkg: String, val displayId: Int, val bounds: Rect)

    @Volatile private var targetDirty = true
    private var target: Target? = null
    private var lastStatePkg: String? = null

    fun focusedTarget(): Target? {
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
                // Some game windows expose no node tree; fall back to the last app that came forward.
                val pkg = try { w.root?.packageName?.toString() } catch (_: Exception) { null } ?: lastStatePkg ?: continue
                val r = Rect(); w.getBoundsInScreen(r)
                return Target(pkg, displayId, r)
            }
        }
        return null
    }

    fun windowOf(pkg: String): Target? {
        val all = try { windowsOnAllDisplays } catch (_: Exception) { return null }
        for (i in 0 until all.size()) {
            for (w in all.valueAt(i)) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val p = try { w.root?.packageName?.toString() } catch (_: Exception) { null } ?: continue
                if (p != pkg) continue
                val r = Rect(); w.getBoundsInScreen(r)
                return Target(pkg, all.keyAt(i), r)
            }
        }
        return null
    }

    // =========================================================================================
    // keyboard
    // =========================================================================================

    private var metaSolo = false
    private var keymapPaused = false
    private var mousePaused = false
    private val heldKeys = HashSet<Int>()
    private val keymapCache = HashMap<String, List<KeyBinding>>()
    private var keymapTarget: Target? = null

    fun invalidateKeymaps() { main.post { keymapCache.clear(); targetDirty = true; onFocusMaybeChanged() } }

    private fun bindingsFor(pkg: String): List<KeyBinding> = keymapCache.getOrPut(pkg) {
        if (pkg == packageName || !Profiles.get(this, pkg).keymapOn) emptyList() else Profiles.effectiveBindings(this, pkg)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        overlay?.let { if (it.onKey(e)) return true }

        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0

        // Win key on its own → start menu (works with or without a desktop already open).
        if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (first) metaSolo = true
            else if (!down && metaSolo) { metaSolo = false; main.post { toggleStartMenu() } }
            return true
        }
        if (down) metaSolo = false
        if (code == KeyEvent.KEYCODE_ALL_APPS) { if (first) main.post { toggleStartMenu() }; return true }

        if (e.isMetaPressed) {
            if (first) when (code) {
                KeyEvent.KEYCODE_D -> main.post { showDesktop() }
                KeyEvent.KEYCODE_N -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                KeyEvent.KEYCODE_A -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                KeyEvent.KEYCODE_TAB -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_L -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                KeyEvent.KEYCODE_S -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_H -> performGlobalAction(GLOBAL_ACTION_HOME)
                else -> return false
            }
            return true
        }
        if (e.isAltPressed && code == KeyEvent.KEYCODE_TAB) { if (first) performGlobalAction(GLOBAL_ACTION_RECENTS); return true }
        if (e.isAltPressed && code == KeyEvent.KEYCODE_F4) { if (first) performGlobalAction(GLOBAL_ACTION_BACK); return true }

        // F10 / Ctrl+Shift+M → key editor on top of the current app.
        if (code == KeyEvent.KEYCODE_F10 || (code == KeyEvent.KEYCODE_M && e.isCtrlPressed && e.isShiftPressed)) {
            if (first) main.post { openEditorOnFocused() }
            return true
        }
        // F12 / Ctrl+Shift+K → pause keys (to type in a game's chat).
        if (code == KeyEvent.KEYCODE_F12 || (code == KeyEvent.KEYCODE_K && e.isCtrlPressed && e.isShiftPressed)) {
            if (first) {
                keymapPaused = !keymapPaused
                if (keymapPaused) { releaseKeymap(); exitAim(false) }
                toast(if (keymapPaused) "Game keys paused — type normally. F12 to resume." else "Game keys on")
            }
            return true
        }
        // Ctrl+Alt+M → normal mouse everywhere (escape hatch).
        if (code == KeyEvent.KEYCODE_M && e.isCtrlPressed && e.isAltPressed) {
            if (first) {
                mousePaused = !mousePaused
                toast(if (mousePaused) "Normal mouse everywhere" else "Touch mouse in games")
                updateMouseInterception()
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
        val code = e.keyCode
        val hits = binds.filter { code in it.keys() }
        if (hits.isEmpty()) return false
        if (e.repeatCount > 0) return true
        val down = e.action == KeyEvent.ACTION_DOWN

        // One-shot actions.
        hits.firstOrNull { it.type == BindType.AIM }?.let { if (down) main.post { toggleAim(t, it) }; return true }
        val swipes = hits.filter { it.type == BindType.SWIPE }
        if (swipes.isNotEmpty()) {
            if (down) main.post { swipes.forEach { doSwipe(t, it) } }
            if (hits.all { it.type == BindType.SWIPE }) return true
        }

        if (down) heldKeys += code else heldKeys -= code
        if (keymapTarget != null && (keymapTarget!!.displayId != t.displayId || keymapTarget!!.pkg != t.pkg)) {
            injectors[keymapTarget!!.displayId]?.releaseGroup("k:")
        }
        keymapTarget = t
        main.post { applyKeymap(t, binds) }
        return true
    }

    private fun at(b: Rect, fx: Float, fy: Float) = PointF(b.left + fx * b.width(), b.top + fy * b.height())

    private fun doSwipe(t: Target, k: KeyBinding) {
        val b = t.bounds
        val from = at(b, k.x, k.y)
        val to = PointF(from.x + k.dx * b.width(), from.y + k.dy * b.height())
        injector(t.displayId).swipe(from, to, 70)
    }

    private fun resyncKeymap() {
        val t = keymapTarget ?: return
        if (heldKeys.isEmpty()) return
        applyKeymap(t, bindingsFor(t.pkg))
    }

    private fun releaseKeymap() {
        heldKeys.clear()
        keymapTarget?.let { injectors[it.displayId]?.releaseGroup("k:") }
    }

    private fun applyKeymap(t: Target, binds: List<KeyBinding>) {
        val b = t.bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val desired = HashMap<String, PointF>()
        val starts = HashMap<String, PointF>()
        binds.forEachIndexed { i, k ->
            when (k.type) {
                BindType.TAP -> if (k.keyCode > 0 && k.keyCode in heldKeys) desired["k:t$i"] = at(b, k.x, k.y)
                BindType.JOYSTICK -> {
                    var dx = 0f; var dy = 0f
                    if (k.left in heldKeys) dx -= 1f
                    if (k.right in heldKeys) dx += 1f
                    if (k.up in heldKeys) dy -= 1f
                    if (k.down in heldKeys) dy += 1f
                    if (dx != 0f || dy != 0f) {
                        val len = hypot(dx, dy)
                        val r = k.radius * b.width()
                        val c = at(b, k.x, k.y)
                        desired["k:j$i"] = PointF(c.x + dx / len * r, c.y + dy / len * r)
                        starts["k:j$i"] = c
                    }
                }
                else -> {}
            }
        }
        injector(t.displayId).syncGroup("k:", desired, starts)
    }

    // =========================================================================================
    // mouse → finger (Android 14+): click = tap, hold = drag, wheel = swipe, aim mode
    // =========================================================================================

    var intercepting = false
        private set
    private var mouseButtons = 0
    private val lastMouse = PointF()
    private var rightDownAt = 0L
    private var aim: KeyBinding? = null
    private var aimTarget: Target? = null
    private var aimSeq = 0
    private val aimPos = PointF()
    private var wheelAccY = 0f
    private var wheelAccX = 0f
    private var wheelAt = PointF()
    private var wheelPosted = false

    val mouseSupported get() = Build.VERSION.SDK_INT >= 34

    private fun updateMouseInterception() {
        if (Build.VERSION.SDK_INT < 34) return
        val t = focusedTarget()
        val want = overlay == null && !mousePaused && t != null && t.pkg != packageName && Profiles.touchMouse(this, t.pkg)
        if (want == intercepting) return
        try {
            val info = serviceInfo ?: return
            info.motionEventSources = if (want) InputDevice.SOURCE_MOUSE else 0
            serviceInfo = info
            intercepting = want
        } catch (_: Throwable) {
            intercepting = false
        }
        if (!want) { exitAim(false); releaseMouseFingers() }
    }

    private fun releaseMouseFingers() {
        mouseButtons = 0
        injectors.values.forEach { it.releaseGroup("m:") }
    }

    private val displayIdMethod by lazy {
        try { InputEvent::class.java.getMethod("getDisplayId") } catch (_: Throwable) { null }
    }

    private fun eventDisplay(e: MotionEvent): Int? = try { displayIdMethod?.invoke(e) as? Int } catch (_: Throwable) { null }

    override fun onMotionEvent(e: MotionEvent) {
        val t = focusedTarget()
        val disp = eventDisplay(e)?.takeIf { it >= 0 } ?: t?.displayId ?: Display.DEFAULT_DISPLAY
        val inj = injector(disp)
        val p = PointF(e.x, e.y)
        val binds = t?.let { bindingsFor(it.pkg) } ?: emptyList()

        val now = e.buttonState
        val changed = now xor mouseButtons
        if (changed != 0) {
            for (bit in intArrayOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
                MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD)) {
                if (changed and bit == 0) continue
                if (now and bit != 0) buttonDown(bit, p, inj, t, binds) else buttonUp(bit, p, inj, t, binds)
            }
            mouseButtons = now
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> wheel(e, p, inj, t, binds)
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var rx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                var ry = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (rx == 0f && ry == 0f) { rx = p.x - lastMouse.x; ry = p.y - lastMouse.y }
                if (aim != null) aimMove(rx, ry)
                else {
                    if (mouseButtons and MotionEvent.BUTTON_PRIMARY != 0) inj.move("m:l", p)
                    if (mouseButtons and MotionEvent.BUTTON_SECONDARY != 0) inj.move("m:r", p)
                }
            }
        }
        lastMouse.set(p.x, p.y)
    }

    private fun pointOf(t: Target?, b: KeyBinding?): PointF? {
        if (t == null || b == null) return null
        return at(t.bounds, b.x, b.y)
    }

    private fun buttonDown(bit: Int, p: PointF, inj: TouchInjector, t: Target?, binds: List<KeyBinding>) {
        when (bit) {
            MotionEvent.BUTTON_PRIMARY -> {
                val fire = if (aim != null) pointOf(t, binds.firstOrNull { it.type == BindType.TAP && it.keyCode == Keys.MOUSE_LEFT }) else null
                inj.press("m:l", fire ?: p)
            }
            MotionEvent.BUTTON_SECONDARY -> {
                val bound = pointOf(t, binds.firstOrNull { it.type == BindType.TAP && it.keyCode == Keys.MOUSE_RIGHT })
                rightDownAt = SystemClock.uptimeMillis()
                inj.press("m:r", bound ?: p) // unbound right click = long-press (context menu)
            }
            MotionEvent.BUTTON_TERTIARY -> {
                val bound = binds.firstOrNull { it.keyCode == Keys.MOUSE_MIDDLE }
                val aimB = binds.firstOrNull { it.type == BindType.AIM }
                when {
                    bound != null && bound.type == BindType.TAP -> pointOf(t, bound)?.let { inj.tap(it) }
                    aimB != null && t != null -> toggleAim(t, aimB)
                    else -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            MotionEvent.BUTTON_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            MotionEvent.BUTTON_FORWARD -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun buttonUp(bit: Int, p: PointF, inj: TouchInjector, t: Target?, binds: List<KeyBinding>) {
        when (bit) {
            MotionEvent.BUTTON_PRIMARY -> inj.release("m:l")
            MotionEvent.BUTTON_SECONDARY -> {
                val bound = binds.any { it.type == BindType.TAP && it.keyCode == Keys.MOUSE_RIGHT }
                val wait = if (bound) 0L else (rightDownAt + 600 - SystemClock.uptimeMillis()).coerceAtLeast(0)
                if (wait == 0L) inj.release("m:r") else main.postDelayed({ inj.release("m:r") }, wait)
            }
        }
    }

    private fun wheel(e: MotionEvent, p: PointF, inj: TouchInjector, t: Target?, binds: List<KeyBinding>) {
        val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val key = if (v > 0) Keys.WHEEL_UP else if (v < 0) Keys.WHEEL_DOWN else 0
        val bound = binds.firstOrNull { it.keyCode == key && key != 0 }
        if (bound != null && t != null) {
            when (bound.type) {
                BindType.TAP -> pointOf(t, bound)?.let { inj.tap(it) }
                BindType.SWIPE -> doSwipe(t, bound)
                else -> {}
            }
            return
        }
        // Unbound wheel: swipe under the pointer (notches arriving together become one longer swipe).
        wheelAccY += v; wheelAccX += h
        wheelAt = PointF(p.x, p.y)
        if (wheelPosted) return
        wheelPosted = true
        main.postDelayed({
            wheelPosted = false
            val ay = wheelAccY; val ax = wheelAccX
            wheelAccY = 0f; wheelAccX = 0f
            if (ay == 0f && ax == 0f) return@postDelayed
            val size = displaySize(inj.displayId)
            val step = size.height() * 0.12f
            val from = wheelAt
            // Wheel up shows earlier content = finger moves down.
            val dy = (ay * step).coerceIn(-size.height() * 0.45f, size.height() * 0.45f)
            val dx = (-ax * step).coerceIn(-size.width() * 0.45f, size.width() * 0.45f)
            val to = PointF(
                (from.x + dx).coerceIn(size.left + 2f, size.right - 2f),
                (from.y + dy).coerceIn(size.top + 2f, size.bottom - 2f),
            )
            inj.swipe(from, to, 90)
        }, 35)
    }

    private fun toggleAim(t: Target, b: KeyBinding) {
        if (aim != null) exitAim(true) else enterAim(t, b)
    }

    private fun enterAim(t: Target, b: KeyBinding) {
        aim = b; aimTarget = t
        val c = at(t.bounds, b.x, b.y)
        aimPos.set(c.x, c.y)
        injector(t.displayId).press("a:${aimSeq}", c)
        toast(if (intercepting) "Mouse aim ON — move the mouse to look, click to fire. ` to get the cursor back."
        else "Mouse aim needs Android 14+. Use the keys instead.")
    }

    private fun exitAim(announce: Boolean) {
        val t = aimTarget ?: run { aim = null; return }
        injector(t.displayId).release("a:${aimSeq}")
        aimSeq++
        aim = null; aimTarget = null
        if (announce) toast("Cursor back")
    }

    private fun aimMove(rx: Float, ry: Float) {
        val b = aim ?: return
        val t = aimTarget ?: return
        val inj = injector(t.displayId)
        val sens = if (b.dx > 0f) b.dx else 1f
        aimPos.x += rx * sens; aimPos.y += ry * sens
        val c = at(t.bounds, b.x, b.y)
        val range = b.radius * t.bounds.width()
        if (hypot(aimPos.x - c.x, aimPos.y - c.y) > range || abs(rx) + abs(ry) > range) {
            // Reached the edge of the drag area: lift and put the finger back in the middle.
            inj.release("a:${aimSeq}")
            aimSeq++
            aimPos.set(c.x, c.y)
            inj.press("a:${aimSeq}", c)
        } else {
            inj.move("a:${aimSeq}", PointF(aimPos.x, aimPos.y))
        }
    }

    // =========================================================================================
    // key editor overlay
    // =========================================================================================

    private var overlay: KeymapOverlay? = null

    private fun openEditorOnFocused() {
        if (overlay != null) return
        val t = focusedTarget()
        if (t == null || t.pkg == packageName) { toast("Open a game first, then press F10."); return }
        openEditor(t)
    }

    private fun openEditor(t: Target) {
        releaseKeymap(); exitAim(false); releaseMouseFingers()
        try {
            val o = KeymapOverlay(this, t) { overlay = null; invalidateKeymaps() }
            overlay = o
            updateMouseInterception()
            o.show()
        } catch (e: Exception) {
            overlay = null
            toast("Could not open the key editor: ${e.message}")
        }
    }

    /** Opens the editor once [pkg]'s window shows up (after launching it from DeskCast). */
    fun openEditorWhenReady(pkg: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        fun poll() {
            if (overlay != null) return
            targetDirty = true
            val t = windowOf(pkg)
            if (t != null && t.bounds.width() > 0) { main.postDelayed({ openEditor(windowOf(pkg) ?: t) }, 1500); return }
            if (SystemClock.uptimeMillis() < deadline) main.postDelayed({ poll() }, 500)
        }
        main.postDelayed({ poll() }, 800)
    }

    // =========================================================================================
    // phone-as-touchpad cursor
    // =========================================================================================

    private var cursorView: CursorView? = null
    private var cursorWm: WindowManager? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    var cursorDisplayId = -1
        private set
    private val cursor = PointF(0f, 0f)
    private var cursorBounds = Rect()
    private val hideRunnable = Runnable { cursorView?.visibility = View.GONE }

    fun ensureCursor(): Boolean {
        val tgt = Displays.primaryExternal(this) ?: dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        if (cursorView != null && cursorDisplayId == tgt.displayId) return true
        hideCursor()
        return try {
            val wc = createDisplayContext(tgt).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = wc.getSystemService(WindowManager::class.java)
            cursorBounds = Rect(wm.maximumWindowMetrics.bounds)
            val size = Ui.dp(wc, 26)
            val lp = WindowManager.LayoutParams(
                size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (cursor.x == 0f && cursor.y == 0f) cursor.set(cursorBounds.exactCenterX(), cursorBounds.exactCenterY())
            clampCursor()
            lp.x = cursor.x.toInt(); lp.y = cursor.y.toInt()
            val v = CursorView(wc)
            wm.addView(v, lp)
            cursorView = v; cursorWm = wm; cursorLp = lp; cursorDisplayId = tgt.displayId
            true
        } catch (e: Exception) {
            toast("Cursor unavailable: ${e.message}")
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
        if (dragging) injector(cursorDisplayId).move("p:drag", PointF(cursor.x, cursor.y))
    }

    fun click() {
        if (!ensureCursor()) return
        showCursorBriefly(); cursorView?.pulse()
        injector(cursorDisplayId).tap(PointF(cursor.x, cursor.y))
    }

    fun secondaryClick() { performGlobalAction(GLOBAL_ACTION_BACK) }

    private var padScrollX = 0f
    private var padScrollY = 0f
    private var padScrollPosted = false

    fun scroll(dx: Float, dy: Float) {
        if (!ensureCursor()) return
        padScrollX += dx; padScrollY += dy
        if (padScrollPosted) return
        padScrollPosted = true
        main.postDelayed({
            padScrollPosted = false
            val sx = padScrollX; val sy = padScrollY
            padScrollX = 0f; padScrollY = 0f
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
        injector(cursorDisplayId).press("p:drag", PointF(cursor.x, cursor.y))
    }
    fun dragEnd() {
        if (!dragging) return
        dragging = false
        injector(cursorDisplayId).release("p:drag")
    }

    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // =========================================================================================
    // desktop helpers
    // =========================================================================================

    /** Win key: start menu on the open desktop, or open a desktop (monitor if connected, else phone). */
    private fun toggleStartMenu() {
        val d = DesktopActivity.active()
        when {
            d != null -> d.toggleStartMenu()
            Displays.primaryExternal(this) != null -> openDesktop(Displays.primaryExternal(this)!!)
            else -> DesktopActivity.startPhoneDesktop(this, openStart = true)
        }
    }

    private fun showDesktop() {
        val ext = DesktopActivity.current?.displayIdOrNull()
        if (ext == null) {
            val disp = Displays.primaryExternal(this)
            if (disp != null) openDesktop(disp) else DesktopActivity.startPhoneDesktop(this)
            return
        }
        dm.getDisplay(ext)?.let { openDesktop(it) }
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    // =========================================================================================
    // cursor drawing
    // =========================================================================================

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
