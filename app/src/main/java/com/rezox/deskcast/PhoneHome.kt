package com.rezox.deskcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * While phone desktop mode is on, DeskCast can act as the Home app so the Home button / gesture
 * returns to the desktop instead of the phone launcher. Off again when you exit desktop mode.
 */
object PhoneHome {
    private fun component(c: Context) = ComponentName(c, "com.rezox.deskcast.PhoneHome")

    fun setEnabled(c: Context, on: Boolean) {
        try {
            c.packageManager.setComponentEnabledSetting(
                component(c),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (_: Exception) {
        }
    }

    /** True when DeskCast is the chosen Home app. */
    fun isDefault(c: Context): Boolean {
        val ri = c.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return false
        return ri.activityInfo?.packageName == c.packageName
    }

    fun openHomeSettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
