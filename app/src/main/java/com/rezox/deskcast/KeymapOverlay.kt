package com.rezox.deskcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Key layout editor drawn on top of the running game (press F10 in any game).
 * Drag a button to move it, tap it to change or delete it, use the bar to add new ones.
 */
class KeymapOverlay(
    private val svc: ControlService,
    private val target: ControlService.Target,
    private val onClosed: () -> Unit,
) {
    private enum class Placing { KEY, JOYSTICK, SWIPE, AIM }

    private val ctx: Context
    private val wm: WindowManager
    private val root: FrameLayout
    private val board: Board
    private val bar: LinearLayout
    private val prompt: LinearLayout
    private val promptText: TextView
    private val promptChoices: LinearLayout
    private val bindings = ArrayList(Profiles.effectiveBindings(svc, target.pkg).map { it.copy() })
    private var placing: Placing? = null
    private var capture: ((Int) -> Unit)? = null
    private var barTop = true

    init {
        val d = svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(target.displayId)
            ?: throw IllegalStateException("display gone")
        ctx = svc.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        wm = ctx.getSystemService(WindowManager::class.java)

        root = FrameLayout(ctx)
        board = Board(ctx)
        root.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = Ui.row(ctx).apply {
            background = Ui.rounded(0xEE151A24.toInt(), Ui.dp(ctx, 14).toFloat())
            setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4))
        }
        fun barBtn(label: String, color: Int = Ui.TEXT, f: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 13f; setTextColor(color); gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
            setOnClickListener { f() }
        }
        bar.addView(barBtn("+ Key") { startPlacing(Placing.KEY) })
        bar.addView(barBtn("+ Joystick") { startPlacing(Placing.JOYSTICK) })
        bar.addView(barBtn("+ Swipe") { startPlacing(Placing.SWIPE) })
        bar.addView(barBtn("+ Mouse aim") { startPlacing(Placing.AIM) })
        bar.addView(barBtn("Layouts") { pickLayout() })
        bar.addView(barBtn("Clear") { bindings.clear(); board.invalidate(); say("All keys removed. Add new ones or pick a layout.") })
        bar.addView(barBtn("⇅") { moveBar() })
        bar.addView(barBtn("Cancel", Ui.MUTED) { close(false) })
        bar.addView(barBtn("Save", Ui.ACCENT) { close(true) })
        val barScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
        root.addView(barScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Ui.dp(ctx, 10); leftMargin = Ui.dp(ctx, 8); rightMargin = Ui.dp(ctx, 8)
        })

        prompt = Ui.column(ctx, 12).apply {
            background = Ui.rounded(0xEE151A24.toInt(), Ui.dp(ctx, 14).toFloat())
        }
        promptText = Ui.text(ctx, "", 14f, Ui.TEXT).apply { gravity = Gravity.CENTER }
        promptChoices = Ui.row(ctx).apply { gravity = Gravity.CENTER }
        prompt.addView(promptText)
        prompt.addView(promptChoices)
        root.addView(prompt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(ctx, 16); leftMargin = Ui.dp(ctx, 12); rightMargin = Ui.dp(ctx, 12)
        })
        say("Drag buttons onto your game's controls. Tap one to change it. Save when done.")
    }

    fun show() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
    }

    fun dismiss() {
        try { wm.removeView(root) } catch (_: Exception) {}
    }

    private fun close(save: Boolean) {
        if (save) {
            Profiles.saveBindings(svc, target.pkg, bindings)
            val p = Profiles.get(svc, target.pkg); p.keymapOn = true; Profiles.save(svc, p)
            Toast.makeText(svc, "Keys saved (${bindings.size}). F10 edits again, F12 pauses keys.", Toast.LENGTH_LONG).show()
        }
        dismiss()
        onClosed()
    }

    private fun moveBar() {
        barTop = !barTop
        val sv = bar.parent as View
        val lp = sv.layoutParams as FrameLayout.LayoutParams
        lp.gravity = (if (barTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        lp.topMargin = if (barTop) Ui.dp(ctx, 10) else 0
        lp.bottomMargin = if (barTop) 0 else Ui.dp(ctx, 90)
        sv.layoutParams = lp
    }

    // ---------- keys from the accessibility service ----------

    /** All keys go here while the editor is open. Returns true to swallow them. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_META_LEFT || e.keyCode == KeyEvent.KEYCODE_META_RIGHT) return false
        if (e.action != KeyEvent.ACTION_UP) return true
        val cb = capture
        when {
            e.keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                if (cb != null || placing != null) { capture = null; placing = null; say("Cancelled.") } else close(false)
            }
            e.keyCode == KeyEvent.KEYCODE_F10 -> close(true)
            cb != null -> { capture = null; cb(e.keyCode) }
        }
        return true
    }

    // ---------- prompts ----------

    private fun say(text: String, choices: List<Pair<String, () -> Unit>> = emptyList()) {
        promptText.text = text
        promptChoices.removeAllViews()
        for ((label, f) in choices) {
            promptChoices.addView(TextView(ctx).apply {
                this.text = label; textSize = 13f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
                background = Ui.rounded(Ui.CARD_HI, Ui.dp(ctx, 10).toFloat())
                setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
                setOnClickListener { f() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 8); rightMargin = Ui.dp(ctx, 6)
            })
        }
    }

    private fun startPlacing(p: Placing) {
        placing = p; capture = null
        say(when (p) {
            Placing.KEY -> "Tap the game button you want a key for."
            Placing.JOYSTICK -> "Tap the centre of the game's movement joystick."
            Placing.SWIPE -> "Tap where the swipe should start (usually the middle of the screen)."
            Placing.AIM -> "Tap the middle of the area you drag to look around / aim."
        })
    }

    /** Ask for a key or mouse button, then call [done]. */
    private fun askKey(what: String, done: (Int) -> Unit) {
        capture = done
        val mouse = listOf(
            "Left click" to Keys.MOUSE_LEFT, "Right click" to Keys.MOUSE_RIGHT, "Middle click" to Keys.MOUSE_MIDDLE,
            "Wheel up" to Keys.WHEEL_UP, "Wheel down" to Keys.WHEEL_DOWN,
        ).map { (n, k) -> n to { capture = null; done(k) } }
        say("Press a keyboard key for $what — or pick a mouse button:", mouse)
    }

    private fun removeKeys(keys: List<Int>) {
        bindings.removeAll { b -> b.keys().any { it in keys && it != 0 } }
    }

    private fun place(fx: Float, fy: Float) {
        val p = placing ?: return
        placing = null
        when (p) {
            Placing.KEY -> askKey("this button") { k ->
                removeKeys(listOf(k))
                bindings += KeyBinding(BindType.TAP, k, fx, fy)
                board.invalidate(); say("${Keys.name(k)} added. Drag to adjust, or add more.")
            }
            Placing.JOYSTICK -> say("Which keys move?", listOf(
                "W A S D" to { addStick(fx, fy, true) },
                "Arrow keys" to { addStick(fx, fy, false) },
            ))
            Placing.SWIPE -> say("Swipe direction?", listOf(
                "↑ Up" to { swipeKey(fx, fy, 0f, -0.25f) }, "↓ Down" to { swipeKey(fx, fy, 0f, 0.25f) },
                "← Left" to { swipeKey(fx, fy, -0.2f, 0f) }, "→ Right" to { swipeKey(fx, fy, 0.2f, 0f) },
            ))
            Placing.AIM -> {
                bindings.removeAll { it.type == BindType.AIM }
                bindings += KeyBinding(BindType.AIM, KeyEvent.KEYCODE_GRAVE, fx, fy, 0.22f, dx = 1f)
                board.invalidate()
                say("Mouse aim added. In the game press ` (key left of 1) or the middle mouse button to aim with the mouse; press again for the cursor. Bind Left click to your fire button.")
            }
        }
    }

    private fun addStick(fx: Float, fy: Float, wasd: Boolean) {
        val k = if (wasd) KeyBinding(BindType.JOYSTICK, 0, fx, fy, 0.075f, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
        else KeyBinding(BindType.JOYSTICK, 0, fx, fy, 0.075f, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        removeKeys(k.keys())
        bindings += k
        board.invalidate(); say("Joystick added. Tap it to make it bigger or smaller.")
    }

    private fun swipeKey(fx: Float, fy: Float, dx: Float, dy: Float) {
        askKey("this swipe") { k ->
            removeKeys(listOf(k))
            bindings += KeyBinding(BindType.SWIPE, k, fx, fy, dx = dx, dy = dy)
            board.invalidate(); say("${Keys.name(k)} swipes now. Add more or Save.")
        }
    }

    private fun edit(b: KeyBinding) {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (b.type == BindType.TAP || b.type == BindType.SWIPE || b.type == BindType.AIM) {
            choices += "Change key" to {
                askKey("this button") { k ->
                    val i = bindings.indexOfFirst { it === b }
                    if (i >= 0) {
                        val nb = b.copy(keyCode = k)
                        bindings[i] = nb
                        bindings.removeAll { it !== nb && k in it.keys() }
                    }
                    board.invalidate(); say("Changed to ${Keys.name(k)}.")
                }
            }
        }
        if (b.type == BindType.JOYSTICK || b.type == BindType.AIM) {
            choices += "Bigger" to { b.radius = (b.radius * 1.2f).coerceAtMost(0.4f); board.invalidate() }
            choices += "Smaller" to { b.radius = (b.radius / 1.2f).coerceAtLeast(0.03f); board.invalidate() }
        }
        if (b.type == BindType.AIM) {
            choices += "Faster aim" to { replace(b, b.copy(dx = (b.dx * 1.25f).coerceAtMost(6f))); say("Aim speed ×%.1f".format(b.dx * 1.25f)) }
            choices += "Slower aim" to { replace(b, b.copy(dx = (b.dx / 1.25f).coerceAtLeast(0.2f))); say("Aim speed ×%.1f".format(b.dx / 1.25f)) }
        }
        choices += "Delete" to { bindings.remove(b); board.invalidate(); say("Deleted.") }
        choices += "Done" to { say("Drag buttons to adjust. Save when done.") }
        say(b.label(), choices)
    }

    private fun replace(old: KeyBinding, new: KeyBinding) {
        val i = bindings.indexOfFirst { it === old }
        if (i >= 0) bindings[i] = new
        board.invalidate()
        edit(new)
    }

    private fun pickLayout() {
        val names = Presets.all.map { it.first }
        val ownPreset = Presets.forPackage(target.pkg)
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (ownPreset != null) choices += "This game's default" to { bindings.clear(); bindings.addAll(ownPreset); board.invalidate(); say("Default layout loaded. Drag to match your HUD.") }
        names.forEachIndexed { i, n ->
            choices += n to { bindings.clear(); bindings.addAll(Presets.all[i].second()); board.invalidate(); say("$n loaded. Drag to match your game.") }
        }
        say("Load a ready-made layout:", choices)
    }

    // ---------- drawing + dragging ----------

    private inner class Board(c: Context) : View(c) {
        private val tint = Paint().apply { color = 0x66000000 }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x885B8CFF.toInt(); style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD5B8CFF.toInt() }
        private val fillSwipe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD3DDC97.toInt() }
        private val fillAim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFB547 }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = Ui.dp(c, 12).toFloat(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val r = Ui.dp(c, 20).toFloat()

        private var drag: KeyBinding? = null
        private var downX = 0f; private var downY = 0f
        private var moved = false
        private val loc = IntArray(2)

        /** Game window in this view's coordinates. */
        private fun win(): RectF {
            getLocationOnScreen(loc)
            val b = target.bounds
            return RectF((b.left - loc[0]).toFloat(), (b.top - loc[1]).toFloat(), (b.right - loc[0]).toFloat(), (b.bottom - loc[1]).toFloat())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
            val w = win()
            canvas.drawRect(w, frame)
            for (b in bindings) {
                val x = w.left + b.x * w.width(); val y = w.top + b.y * w.height()
                when (b.type) {
                    BindType.JOYSTICK -> {
                        canvas.drawCircle(x, y, b.radius * w.width(), ring)
                        canvas.drawCircle(x, y, r * 1.3f, fill)
                    }
                    BindType.AIM -> {
                        canvas.drawCircle(x, y, b.radius * w.width(), fillAim)
                        canvas.drawCircle(x, y, b.radius * w.width(), ring)
                        canvas.drawCircle(x, y, r, fill)
                    }
                    BindType.SWIPE -> {
                        val ex = x + b.dx * w.width(); val ey = y + b.dy * w.height()
                        canvas.drawLine(x, y, ex, ey, ring)
                        canvas.drawCircle(ex, ey, r * 0.9f, fillSwipe)
                        canvas.drawText(b.label(), ex, ey + label.textSize / 3, label)
                        continue
                    }
                    BindType.TAP -> { canvas.drawCircle(x, y, r, fill); canvas.drawCircle(x, y, r, ring) }
                }
                canvas.drawText(b.label(), x, y + label.textSize / 3, label)
            }
        }

        private fun hit(px: Float, py: Float): KeyBinding? {
            val w = win()
            return bindings.lastOrNull { b ->
                val (x, y) = if (b.type == BindType.SWIPE) (w.left + (b.x + b.dx) * w.width()) to (w.top + (b.y + b.dy) * w.height())
                else (w.left + b.x * w.width()) to (w.top + b.y * w.height())
                hypot(x - px, y - py) < r * 1.5f
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = win()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    drag = if (placing == null) hit(e.x, e.y) else null
                }
                MotionEvent.ACTION_MOVE -> {
                    val b = drag ?: return true
                    if (!moved && hypot(e.x - downX, e.y - downY) > r / 2) moved = true
                    if (moved && w.width() > 0 && w.height() > 0) {
                        val ox = if (b.type == BindType.SWIPE) b.dx else 0f
                        val oy = if (b.type == BindType.SWIPE) b.dy else 0f
                        b.x = ((e.x - w.left) / w.width() - ox).coerceIn(0f, 1f)
                        b.y = ((e.y - w.top) / w.height() - oy).coerceIn(0f, 1f)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val b = drag
                    drag = null
                    if (placing != null) {
                        if (w.contains(e.x, e.y)) place((e.x - w.left) / w.width(), (e.y - w.top) / w.height())
                    } else if (b != null && !moved) edit(b)
                }
            }
            return true
        }
    }
}
