package com.rezox.deskcast

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/** How a given app should open on the big screen. */
enum class WindowMode(val label: String) {
    WINDOW("Window"),
    MAXIMIZED("Maximized (keeps taskbar)"),
    FULLSCREEN("Full screen (games, video)"),
    PHONE("Phone-shaped window"),
}

data class AppProfile(
    val pkg: String,
    var mode: WindowMode = WindowMode.WINDOW,
    var widthPct: Int = 60,
    var heightPct: Int = 70,
    var keymapOn: Boolean = true,
)

enum class BindType { TAP, JOYSTICK }

/**
 * A key → touch binding. Positions are fractions (0..1) of the app's own window, so a binding
 * keeps working when the window is moved, resized or shown on a different screen.
 */
data class KeyBinding(
    val type: BindType,
    val keyCode: Int,
    val x: Float,
    val y: Float,
    val radius: Float = 0.12f, // joystick only: fraction of window width
    val up: Int = 0, val down: Int = 0, val left: Int = 0, val right: Int = 0, // joystick keys
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name); put("key", keyCode); put("x", x.toDouble()); put("y", y.toDouble())
        put("r", radius.toDouble()); put("u", up); put("d", down); put("l", left); put("rt", right)
    }

    fun keys(): List<Int> = if (type == BindType.JOYSTICK) listOf(up, down, left, right) else listOf(keyCode)

    companion object {
        fun fromJson(o: JSONObject) = KeyBinding(
            BindType.valueOf(o.optString("type", "TAP")),
            o.optInt("key"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
            o.optDouble("r", 0.12).toFloat(), o.optInt("u"), o.optInt("d"), o.optInt("l"), o.optInt("rt"),
        )
    }
}

object Profiles {
    private const val PREFS = "profiles"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, pkg: String): AppProfile {
        val raw = prefs(c).getString("p:$pkg", null) ?: return defaultFor(c, pkg)
        return try {
            val o = JSONObject(raw)
            AppProfile(
                pkg,
                WindowMode.valueOf(o.optString("mode", WindowMode.WINDOW.name)),
                o.optInt("w", 60), o.optInt("h", 70), o.optBoolean("km", true),
            )
        } catch (_: Exception) {
            defaultFor(c, pkg)
        }
    }

    fun has(c: Context, pkg: String) = prefs(c).contains("p:$pkg")

    fun save(c: Context, p: AppProfile) {
        val o = JSONObject().put("mode", p.mode.name).put("w", p.widthPct).put("h", p.heightPct).put("km", p.keymapOn)
        prefs(c).edit().putString("p:${p.pkg}", o.toString()).apply()
    }

    fun reset(c: Context, pkg: String) {
        prefs(c).edit().remove("p:$pkg").remove("k:$pkg").apply()
    }

    /** Games start full screen; everything else as a desktop window. */
    private fun defaultFor(c: Context, pkg: String): AppProfile {
        val isGame = try {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
        } catch (_: Exception) {
            false
        }
        return AppProfile(pkg, if (isGame) WindowMode.FULLSCREEN else WindowMode.WINDOW)
    }

    fun bindings(c: Context, pkg: String): List<KeyBinding> {
        val raw = prefs(c).getString("k:$pkg", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { KeyBinding.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveBindings(c: Context, pkg: String, list: List<KeyBinding>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("k:$pkg", a.toString()).apply()
        ControlService.instance?.invalidateKeymaps()
    }

    fun pkgsWithBindings(c: Context): List<String> =
        prefs(c).all.keys.filter { it.startsWith("k:") }.map { it.removePrefix("k:") }

    /** Window rectangle for launching [p] on a screen of [screen] size with a [taskbarPx] bar. */
    fun boundsFor(p: AppProfile, screen: Rect, taskbarPx: Int, cascade: Int): Rect? {
        val w = screen.width()
        val h = screen.height() - taskbarPx
        return when (p.mode) {
            WindowMode.FULLSCREEN -> Rect(screen)
            WindowMode.MAXIMIZED -> Rect(screen.left, screen.top, screen.right, screen.top + h)
            WindowMode.WINDOW -> {
                val ww = (w * p.widthPct / 100).coerceIn(320, w)
                val wh = (h * p.heightPct / 100).coerceIn(240, h)
                centered(screen, ww, wh, h, cascade)
            }
            WindowMode.PHONE -> {
                val wh = (h * 0.9f).toInt()
                val ww = (wh * 9 / 19.5f).toInt()
                centered(screen, ww, wh, h, cascade)
            }
        }
    }

    private fun centered(screen: Rect, ww: Int, wh: Int, usableH: Int, cascade: Int): Rect {
        val step = 36 * (cascade % 6)
        val l = (screen.left + (screen.width() - ww) / 2 + step).coerceIn(screen.left, screen.right - ww)
        val t = (screen.top + (usableH - wh) / 2 + step).coerceIn(screen.top, screen.top + usableH - wh)
        return Rect(l, t, l + ww, t + wh)
    }
}

/** App-wide settings. */
object Settings2 {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun autoDesktop(c: Context) = p(c).getBoolean("autoDesktop", true)
    fun setAutoDesktop(c: Context, v: Boolean) = p(c).edit().putBoolean("autoDesktop", v).apply()

    /** 0 = native, otherwise max long edge in px. */
    fun streamMaxEdge(c: Context) = p(c).getInt("streamEdge", 1920)
    fun setStreamMaxEdge(c: Context, v: Int) = p(c).edit().putInt("streamEdge", v).apply()

    fun streamMbps(c: Context) = p(c).getInt("streamMbps", 16)
    fun setStreamMbps(c: Context, v: Int) = p(c).edit().putInt("streamMbps", v).apply()

    fun streamFps(c: Context) = p(c).getInt("streamFps", 60)
    fun setStreamFps(c: Context, v: Int) = p(c).edit().putInt("streamFps", v).apply()

    fun pinned(c: Context): List<String> =
        p(c).getString("pinned", "")!!.split(',').filter { it.isNotBlank() }

    fun setPinned(c: Context, list: List<String>) = p(c).edit().putString("pinned", list.joinToString(",")).apply()

    fun cursorSpeed(c: Context) = p(c).getFloat("cursorSpeed", 1.6f)
    fun setCursorSpeed(c: Context, v: Float) = p(c).edit().putFloat("cursorSpeed", v).apply()
}
