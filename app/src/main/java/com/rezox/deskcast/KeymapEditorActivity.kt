package com.rezox.deskcast

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Map keyboard keys to touches for a game: capture the game's screen, tap where a button is,
 * press the key. WASD / arrow joysticks are supported.
 */
class KeymapEditorActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    private var pkg: String? = null
    private val bindings = ArrayList<KeyBinding>()
    private var shot: Bitmap? = null
    private var placing: BindType? = null
    private lateinit var canvasView: MapView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg == null) pickApp() else buildEditor()
    }

    // ---------- choose a game ----------

    private fun pickApp() {
        val c = this
        val root = Ui.column(c, 20).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(c, "Game key mapping"))
        root.addView(Ui.text(c, "Pick a game. Games and apps that already have keys mapped are listed first.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12))
        })
        val list = Ui.column(c)
        root.addView(ScrollView(c).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Executors.newSingleThreadExecutor().execute {
            val pm = packageManager
            val mapped = Profiles.pkgsWithBindings(c).toSet()
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    val p = it.activityInfo.packageName
                    val game = it.activityInfo.applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                    Triple(it, game || p in mapped, it.loadLabel(pm).toString())
                }
                .sortedWith(compareByDescending<Triple<android.content.pm.ResolveInfo, Boolean, String>> { it.second }.thenBy { it.third.lowercase() })
            runOnUiThread {
                apps.forEach { (ri, top, label) ->
                    val row = Ui.row(c).apply {
                        setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10))
                        background = Ui.rounded(if (top) Ui.CARD_HI else Ui.CARD, Ui.dp(c, 14).toFloat())
                        setOnClickListener {
                            startActivity(Intent(c, KeymapEditorActivity::class.java).putExtra(EXTRA_PKG, ri.activityInfo.packageName))
                        }
                    }
                    row.addView(ImageView(c).apply { setImageDrawable(ri.loadIcon(pm)) }, LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)).apply { rightMargin = Ui.dp(c, 12) })
                    row.addView(Ui.text(c, label, 15f))
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8) })
                }
            }
        }
    }

    // ---------- editor ----------

    private fun buildEditor() {
        val c = this
        val p = pkg!!
        bindings.clear(); bindings.addAll(Profiles.bindings(c, p))
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }

        val root = Ui.column(c, 16).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(c, label, 20f, Ui.TEXT, bold = true))
        status = Ui.text(c, "1. Open the game (on the big screen or this phone)  2. Tap Capture  3. Add keys", 13f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 8))
        }
        root.addView(status)

        canvasView = MapView(c)
        root.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val r1 = Ui.row(c)
        fun btn(text: String, primary: Boolean = false, f: () -> Unit) = Ui.button(c, text, primary) { f() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(c, 6); topMargin = Ui.dp(c, 8) }
        }
        r1.addView(btn("Open game") { openGame(p) })
        r1.addView(btn("Capture") { capture(p) })
        root.addView(r1)
        val r2 = Ui.row(c)
        r2.addView(btn("+ Key") { startPlacing(BindType.TAP) })
        r2.addView(btn("+ Joystick") { startPlacing(BindType.JOYSTICK) })
        r2.addView(btn("Save", primary = true) { save() })
        root.addView(r2)
        setContentView(root)
        if (ControlService.instance == null) status.text = "Turn on DeskCast control (Accessibility) to capture and use key mapping."
    }

    private fun openGame(p: String) {
        val i = packageManager.getLaunchIntentForPackage(p) ?: return
        val d = Displays.primaryExternal(this)
        if (d != null) {
            val prof = Profiles.get(this, p)
            val b = if (DesktopFlags.freeformReady(this)) Profiles.boundsFor(prof, Displays.bounds(this, d), Ui.dp(this, 56), 0) else null
            Displays.launchOn(this, i, d.displayId, b)
        } else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun capture(p: String) {
        val svc = ControlService.instance
        if (svc == null) { Toast.makeText(this, "Turn on DeskCast control first.", Toast.LENGTH_LONG).show(); return }
        status.text = "Capturing…"
        svc.captureWindow(p) { bmp, err ->
            if (bmp == null) status.text = err
            else { shot = bmp; status.text = "Tap + Key or + Joystick, then tap on the picture."; canvasView.invalidate() }
        }
    }

    private fun startPlacing(t: BindType) {
        placing = t
        status.text = if (t == BindType.TAP) "Tap where the button is on the picture." else "Tap the centre of the game's joystick."
    }

    private fun placeAt(fx: Float, fy: Float) {
        val t = placing ?: return
        placing = null
        if (t == BindType.JOYSTICK) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Joystick keys")
                .setItems(arrayOf("W A S D", "Arrow keys")) { _, which ->
                    val k = if (which == 0)
                        KeyBinding(BindType.JOYSTICK, 0, fx, fy, 0.10f, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
                    else
                        KeyBinding(BindType.JOYSTICK, 0, fx, fy, 0.10f, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
                    bindings.removeAll { b -> b.keys().any { it in k.keys() } }
                    bindings += k; canvasView.invalidate(); status.text = "Joystick added. Save when done."
                }.show()
            return
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Press a key")
            .setMessage("Press the keyboard key (or mouse button key) for this spot.")
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnKeyListener { d, keyCode, e ->
            if (e.action == KeyEvent.ACTION_UP && keyCode != KeyEvent.KEYCODE_BACK) {
                bindings.removeAll { b -> keyCode in b.keys() }
                bindings += KeyBinding(BindType.TAP, keyCode, fx, fy)
                canvasView.invalidate()
                status.text = "${keyName(keyCode)} added. Save when done."
                d.dismiss()
                true
            } else keyCode != KeyEvent.KEYCODE_BACK
        }
        dlg.show()
    }

    private fun save() {
        val p = pkg ?: return
        Profiles.saveBindings(this, p, bindings)
        val prof = Profiles.get(this, p); prof.keymapOn = true; Profiles.save(this, prof)
        Toast.makeText(this, "Saved ${bindings.size} binding(s). F12 pauses mapping while you type.", Toast.LENGTH_LONG).show()
    }

    private fun keyName(k: Int) = KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_").replace("DPAD_", "")

    // ---------- canvas ----------

    private inner class MapView(c: Context) : View(c) {
        private val frame = RectF()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC5B8CFF.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2).toFloat() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = Ui.dp(c, 12).toFloat(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val empty = Paint().apply { color = Ui.CARD }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = Ui.dp(c, 14).toFloat(); textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val b = shot
            val aw = b?.width?.toFloat() ?: 16f
            val ah = b?.height?.toFloat() ?: 9f
            val s = minOf(width / aw, height / ah)
            val w = aw * s; val h = ah * s
            frame.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            if (b != null) canvas.drawBitmap(b, null, frame, bmpPaint)
            else {
                canvas.drawRect(frame, empty)
                canvas.drawText("No capture yet", frame.centerX(), frame.centerY(), hint)
            }
            val r = Ui.dp(context, 16).toFloat()
            for (k in bindings) {
                val x = frame.left + k.x * frame.width(); val y = frame.top + k.y * frame.height()
                if (k.type == BindType.JOYSTICK) {
                    val jr = k.radius * frame.width()
                    canvas.drawCircle(x, y, jr, ring)
                    canvas.drawCircle(x, y, r, fill)
                    val names = if (k.up == KeyEvent.KEYCODE_W) "WASD" else "ARROWS"
                    canvas.drawText(names, x, y + label.textSize / 3, label)
                } else {
                    canvas.drawCircle(x, y, r, fill)
                    canvas.drawCircle(x, y, r, ring)
                    canvas.drawText(keyName(k.keyCode).take(5), x, y + label.textSize / 3, label)
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            if (!frame.contains(e.x, e.y)) return true
            val fx = (e.x - frame.left) / frame.width(); val fy = (e.y - frame.top) / frame.height()
            if (placing != null) { placeAt(fx, fy); return true }
            // Tap an existing marker to delete it.
            val hit = bindings.firstOrNull { hypot((it.x - fx) * frame.width(), (it.y - fy) * frame.height()) < Ui.dp(context, 22) }
            if (hit != null) {
                AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Remove this binding?")
                    .setPositiveButton("Remove") { _, _ -> bindings.remove(hit); invalidate() }
                    .setNegativeButton("Keep", null).show()
            }
            return true
        }
    }
}
