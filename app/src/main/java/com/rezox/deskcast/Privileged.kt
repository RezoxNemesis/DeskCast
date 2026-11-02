package com.rezox.deskcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * One-time unlock and automatic setup.
 *
 * Unlock = DeskCast gets WRITE_SECURE_SETTINGS, either from Shizuku (no computer: Shizuku starts
 * itself through Wireless debugging) or from one ADB command on a PC. After that DeskCast turns on
 * its own control service and the desktop-window switches — no hidden menus, no toggles.
 */
object Privileged {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQ_SHIZUKU = 4711

    fun unlocked(c: Context) = DesktopFlags.canWrite(c)

    // ---------- Shizuku ----------

    fun shizukuInstalled(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun shizukuAllowed(): Boolean = try {
        shizukuRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun askShizuku() {
        try { Shizuku.requestPermission(REQ_SHIZUKU) } catch (_: Throwable) {}
    }

    fun getShizuku(c: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(market) } catch (_: Exception) {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openShizuku(c: Context) {
        c.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Runs a shell command as the ADB shell user through Shizuku. */
    private fun sh(cmd: String): Boolean = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        p.waitFor() == 0
    } catch (_: Throwable) { false }

    /** Grants DeskCast everything it needs, using Shizuku. Returns true when unlocked. */
    fun unlockWithShizuku(c: Context): Boolean {
        val p = c.packageName
        sh("pm grant $p android.permission.WRITE_SECURE_SETTINGS")
        sh("appops set $p ACCESS_RESTRICTED_SETTINGS allow")
        sh("appops set $p PROJECT_MEDIA allow")
        sh("appops set $p SYSTEM_ALERT_WINDOW allow")
        sh("dumpsys deviceidle whitelist +$p")
        return unlocked(c)
    }

    // ---------- automatic setup ----------

    fun controlOn(c: Context) = ControlService.isEnabled(c)

    /** Switches DeskCast control on by itself (needs unlock). */
    fun enableControl(c: Context): Boolean {
        if (!unlocked(c)) return false
        return try {
            val cr = c.contentResolver
            val me = ComponentName(c, ControlService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = cur.split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName + "/") } + me
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) { false }
    }

    /** Everything after unlock, in one go. Safe to call repeatedly. */
    fun configureAll(c: Context) {
        if (!unlocked(c)) return
        if (!controlOn(c)) enableControl(c)
        if (Settings2.windowsWanted(c) && !DesktopFlags.allOn(c)) {
            DesktopFlags.setAll(c, true)
            Settings2.setFlagsAt(c, System.currentTimeMillis())
        }
    }

    /** The desktop switches were turned on after the phone last started. */
    fun restartNeeded(c: Context): Boolean {
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return DesktopFlags.allOn(c) && Settings2.flagsAt(c) > bootAt
    }

    // ---------- manual fallback ----------

    fun openAppInfo(c: Context) {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAccessibility(c: Context) {
        c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
