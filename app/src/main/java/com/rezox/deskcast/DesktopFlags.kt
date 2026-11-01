package com.rezox.deskcast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android's hidden desktop switches. They live in Settings.Global and can be written by an app
 * once it has WRITE_SECURE_SETTINGS (granted one time over ADB, see README).
 */
object DesktopFlags {
    const val FREEFORM = "enable_freeform_support"
    const val RESIZABLE = "force_resizable_activities"
    const val DESKTOP_EXTERNAL = "force_desktop_mode_on_external_displays"
    const val NON_RESIZABLE_MULTIWINDOW = "enable_non_resizable_multi_window"

    val ALL = listOf(FREEFORM, RESIZABLE, DESKTOP_EXTERNAL, NON_RESIZABLE_MULTIWINDOW)

    fun adbGrantCommand(c: Context) =
        "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun canWrite(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(c: Context, key: String): Boolean =
        Settings.Global.getInt(c.contentResolver, key, 0) == 1

    fun allOn(c: Context): Boolean = ALL.all { isOn(c, it) }

    fun freeformReady(c: Context): Boolean = isOn(c, FREEFORM) && isOn(c, DESKTOP_EXTERNAL)

    /** Returns true when every switch was written. A reboot makes the desktop-mode switch take effect. */
    fun setAll(c: Context, on: Boolean): Boolean {
        if (!canWrite(c)) return false
        var ok = true
        for (k in ALL) {
            ok = try {
                Settings.Global.putInt(c.contentResolver, k, if (on) 1 else 0) && ok
            } catch (_: SecurityException) {
                false
            }
        }
        // Lets DeskCast pick "window" vs "full screen" per app launch (same approach as Taskbar/DeX-style launchers).
        try {
            Settings.Global.putString(c.contentResolver, HIDDEN_API, if (on) "1" else null)
        } catch (_: Exception) {
        }
        return ok
    }

    private const val HIDDEN_API = "hidden_api_policy"

    /** Freeform windows on the phone's own screen only need the freeform switch. */
    fun phoneFreeformReady(c: Context): Boolean = isOn(c, FREEFORM)
}
