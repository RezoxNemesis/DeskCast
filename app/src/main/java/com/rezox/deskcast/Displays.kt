package com.rezox.deskcast

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

/** Finding the external (HDMI / USB-C DisplayPort / wireless) screen and launching things onto it. */
object Displays {

    /** Real external screens. Our own capture surfaces are private and never show up here. */
    fun external(c: Context): List<Display> {
        val dm = c.getSystemService(DisplayManager::class.java)
        val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        val all = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        return (presentation + all)
            .distinctBy { it.displayId }
            .filter { it.displayId != Display.DEFAULT_DISPLAY && !it.name.startsWith("DeskCast") }
    }

    fun primaryExternal(c: Context): Display? = external(c).firstOrNull()

    fun bounds(c: Context, display: Display): Rect {
        val dc = c.createDisplayContext(display)
        val wm = dc.getSystemService(WindowManager::class.java)
        return Rect(wm.maximumWindowMetrics.bounds)
    }

    fun describe(c: Context, d: Display): String {
        val b = bounds(c, d)
        val hz = d.mode.refreshRate.toInt()
        return "${d.name} · ${b.width()}×${b.height()} @ ${hz}Hz"
    }

    /**
     * Launch [intent] on [displayId]. With freeform enabled, [launchBounds] becomes the window
     * rectangle; without it Android shows the app full screen on that display.
     */
    fun launchOn(c: Context, intent: Intent, displayId: Int, launchBounds: Rect?): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (launchBounds != null) opts.setLaunchBounds(launchBounds)
        return try {
            c.startActivity(intent, opts.toBundle())
            true
        } catch (e: Exception) {
            // Some apps refuse secondary displays; fall back to opening them normally.
            try {
                c.startActivity(intent)
            } catch (_: Exception) {
            }
            false
        }
    }
}
