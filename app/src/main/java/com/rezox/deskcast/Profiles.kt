package com.rezox.deskcast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** How a given app should open on the desktop. */
enum class WindowMode(val label: String) {
    WINDOW("Window"),
    MAXIMIZED("Maximized (keeps taskbar)"),
    FULLSCREEN("Full screen (games, video)"),
    PHONE("Phone-shaped window"),
}

/** What the mouse does inside an app. */
enum class MouseMode(val label: String) {
    AUTO("Automatic (touch mouse in games)"),
    NATIVE("Normal mouse"),
    TOUCH("Touch mouse (click = tap, wheel = swipe)"),
}

data class AppProfile(
    val pkg: String,
    var mode: WindowMode = WindowMode.WINDOW,
    var widthPct: Int = 60,
    var heightPct: Int = 70,
    var keymapOn: Boolean = true,
    var mouse: MouseMode = MouseMode.AUTO,
)

/**
 * TAP: hold key = hold finger. JOYSTICK: 4 keys steer a finger around a centre.
 * SWIPE: key press = one swipe (runner games). AIM: mouse movement drags the camera; key toggles it.
 */
enum class BindType { TAP, JOYSTICK, SWIPE, AIM }

/** Mouse buttons and wheel can be bound like keys. */
object Keys {
    const val MOUSE_LEFT = -1
    const val MOUSE_RIGHT = -2
    const val MOUSE_MIDDLE = -3
    const val WHEEL_UP = -4
    const val WHEEL_DOWN = -5

    fun name(code: Int): String = when (code) {
        MOUSE_LEFT -> "L‑Click"
        MOUSE_RIGHT -> "R‑Click"
        MOUSE_MIDDLE -> "M‑Click"
        WHEEL_UP -> "Wheel↑"
        WHEEL_DOWN -> "Wheel↓"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
        KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
        KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
}

/** Positions are fractions (0..1) of the app's window, so layouts survive moving/resizing. */
data class KeyBinding(
    val type: BindType,
    val keyCode: Int,
    var x: Float,
    var y: Float,
    var radius: Float = 0.08f, // joystick throw / aim range, fraction of window width
    val up: Int = 0, val down: Int = 0, val left: Int = 0, val right: Int = 0,
    val dx: Float = 0f, val dy: Float = 0f, // swipe direction (fractions); for AIM dx = sensitivity
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name); put("key", keyCode); put("x", x.toDouble()); put("y", y.toDouble())
        put("r", radius.toDouble()); put("u", up); put("d", down); put("l", left); put("rt", right)
        put("dx", dx.toDouble()); put("dy", dy.toDouble())
    }

    fun keys(): List<Int> = if (type == BindType.JOYSTICK) listOf(up, down, left, right) else listOf(keyCode)

    fun label(): String = when (type) {
        BindType.JOYSTICK -> if (up == KeyEvent.KEYCODE_W) "WASD" else "Arrows"
        BindType.AIM -> "Aim " + Keys.name(keyCode)
        BindType.SWIPE -> Keys.name(keyCode) + " " + when {
            dy < 0 -> "⇡"; dy > 0 -> "⇣"; dx < 0 -> "⇠"; else -> "⇢"
        }
        BindType.TAP -> Keys.name(keyCode)
    }

    companion object {
        fun fromJson(o: JSONObject) = KeyBinding(
            BindType.valueOf(o.optString("type", "TAP")),
            o.optInt("key"), o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
            o.optDouble("r", 0.08).toFloat(), o.optInt("u"), o.optInt("d"), o.optInt("l"), o.optInt("rt"),
            o.optDouble("dx", 0.0).toFloat(), o.optDouble("dy", 0.0).toFloat(),
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
                MouseMode.valueOf(o.optString("mouse", MouseMode.AUTO.name)),
            )
        } catch (_: Exception) {
            defaultFor(c, pkg)
        }
    }

    fun has(c: Context, pkg: String) = prefs(c).contains("p:$pkg")

    fun save(c: Context, p: AppProfile) {
        val o = JSONObject().put("mode", p.mode.name).put("w", p.widthPct).put("h", p.heightPct)
            .put("km", p.keymapOn).put("mouse", p.mouse.name)
        prefs(c).edit().putString("p:${p.pkg}", o.toString()).apply()
        ControlService.instance?.invalidateKeymaps()
    }

    fun reset(c: Context, pkg: String) {
        prefs(c).edit().remove("p:$pkg").remove("k:$pkg").apply()
        ControlService.instance?.invalidateKeymaps()
    }

    private val knownGames = setOf(
        "com.dts.freefireth", "com.dts.freefiremax", "com.kiloo.subwaysurf", "com.tencent.ig", "com.pubg.imobile",
        "com.activision.callofduty.shooter", "com.garena.game.codm", "com.mobile.legends", "com.supercell.clashofclans",
        "com.supercell.brawlstars", "com.mojang.minecraftpe", "com.roblox.client", "com.imangi.templerun2",
        "com.miHoYo.GenshinImpact", "com.pubg.krmobile", "com.ea.gp.fifamobile",
    )

    fun isGame(c: Context, pkg: String): Boolean {
        if (pkg in knownGames || Presets.forPackage(pkg) != null) return true
        return try {
            val ai = c.packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** Games start full screen; everything else as a desktop window. */
    private fun defaultFor(c: Context, pkg: String): AppProfile =
        AppProfile(pkg, if (isGame(c, pkg)) WindowMode.FULLSCREEN else WindowMode.WINDOW)

    /** The user's own layout, if they saved one (an empty saved layout means "no keys"). */
    fun savedBindings(c: Context, pkg: String): List<KeyBinding>? {
        val raw = prefs(c).getString("k:$pkg", null) ?: return null
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { KeyBinding.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            null
        }
    }

    /** Saved layout → built-in preset for this game → ready-made layout for any game → nothing. */
    fun effectiveBindings(c: Context, pkg: String): List<KeyBinding> =
        savedBindings(c, pkg) ?: Presets.forPackage(pkg) ?: if (isGame(c, pkg)) Presets.anyGame() else emptyList()

    fun saveBindings(c: Context, pkg: String, list: List<KeyBinding>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("k:$pkg", a.toString()).apply()
        ControlService.instance?.invalidateKeymaps()
    }

    fun pkgsWithBindings(c: Context): List<String> =
        prefs(c).all.keys.filter { it.startsWith("k:") }.map { it.removePrefix("k:") }

    /** True when the mouse should act like a finger in this app. */
    fun touchMouse(c: Context, pkg: String): Boolean = when (get(c, pkg).mouse) {
        MouseMode.NATIVE -> false
        MouseMode.TOUCH -> true
        MouseMode.AUTO -> Settings2.touchMouseEverywhere(c) || isGame(c, pkg)
    }

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

    /** Start phone desktop mode when a mouse or keyboard is plugged in and no monitor is connected. */
    fun autoPhoneDesktop(c: Context) = p(c).getBoolean("autoPhoneDesktop", true)
    fun setAutoPhoneDesktop(c: Context, v: Boolean) = p(c).edit().putBoolean("autoPhoneDesktop", v).apply()

    /** Touch mouse (wheel = swipe) in every app, not only games. */
    fun touchMouseEverywhere(c: Context) = p(c).getBoolean("touchMouseAll", false)
    fun setTouchMouseEverywhere(c: Context, v: Boolean) = p(c).edit().putBoolean("touchMouseAll", v).apply()

    /** User wants desktop windows on (so DeskCast re-applies them automatically). */
    fun windowsWanted(c: Context) = p(c).getBoolean("windowsWanted", true)
    fun setWindowsWanted(c: Context, v: Boolean) = p(c).edit().putBoolean("windowsWanted", v).apply()

    /** When the desktop switches were last turned on (to know if a restart is still needed). */
    fun flagsAt(c: Context) = p(c).getLong("flagsAt", 0L)
    fun setFlagsAt(c: Context, v: Long) = p(c).edit().putLong("flagsAt", v).apply()

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
