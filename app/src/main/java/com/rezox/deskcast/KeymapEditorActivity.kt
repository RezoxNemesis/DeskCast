package com.rezox.deskcast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

/** Pick a game → it opens with the key editor on top. */
class KeymapEditorActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"

        /** Open [pkg] where the user is working (monitor desktop, phone desktop or normal phone). */
        fun openGame(c: Context, pkg: String) {
            val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
            val prof = Profiles.get(c, pkg)
            val ext = DesktopActivity.current?.displayIdOrNull()?.let { id -> Displays.external(c).firstOrNull { it.displayId == id } }
                ?: Displays.primaryExternal(c)
            when {
                ext != null -> {
                    val b = if (DesktopFlags.freeformReady(c)) Profiles.boundsFor(prof, Displays.bounds(c, ext), Ui.dp(c, 56), 0) else null
                    Displays.launchOn(c, i, ext.displayId, b, if (b != null) Displays.windowingFor(prof) else null)
                }
                DesktopActivity.phoneCurrent != null && DesktopFlags.phoneFreeformReady(c) -> {
                    val d = c.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    val b = Profiles.boundsFor(prof, Displays.bounds(c, d), Ui.dp(c, 48), 0)
                    Displays.launchOn(c, i, Display.DEFAULT_DISPLAY, b, Displays.windowingFor(prof))
                }
                else -> c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun openGameWithEditor(c: Context, pkg: String) {
            val svc = ControlService.instance
            if (svc == null) {
                Toast.makeText(c, "Finish Setup first (DeskCast control is off).", Toast.LENGTH_LONG).show()
                return
            }
            openGame(c, pkg)
            svc.openEditorWhenReady(pkg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_PKG)?.let { openGameWithEditor(this, it); finish(); return }

        val c = this
        val root = Ui.column(c, 20).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(c, "Game keys"))
        root.addView(Ui.text(c,
            "Games already work with ready-made keys (WASD to move, arrows to swipe, Space, mouse to aim in shooters). " +
                "To change them, pick a game: it opens with the key editor on top — drag buttons onto the game's controls and Save.\n\n" +
                "In any game: F10 edits keys · F12 pauses keys (for chat) · ` or middle click toggles mouse aim.",
            14f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12)) })
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
                    Triple(it, Profiles.isGame(c, p) || p in mapped, it.loadLabel(pm).toString())
                }
                .sortedWith(compareByDescending<Triple<android.content.pm.ResolveInfo, Boolean, String>> { it.second }.thenBy { it.third.lowercase() })
            runOnUiThread {
                var gamesShown = false
                var othersShown = false
                apps.forEach { (ri, game, label) ->
                    if (game && !gamesShown) { list.addView(Ui.section(c, "Games")); gamesShown = true }
                    if (!game && !othersShown) { list.addView(Ui.section(c, "Other apps")); othersShown = true }
                    val p = ri.activityInfo.packageName
                    val row = Ui.row(c).apply {
                        setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10))
                        background = Ui.rounded(Ui.CARD, Ui.dp(c, 14).toFloat())
                        setOnClickListener { openGameWithEditor(c, p) }
                    }
                    row.addView(ImageView(c).apply { setImageDrawable(ri.loadIcon(pm)) }, LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)).apply { rightMargin = Ui.dp(c, 12) })
                    val col = Ui.column(c)
                    col.addView(Ui.text(c, label, 15f))
                    val n = Profiles.effectiveBindings(c, p).size
                    val how = when {
                        Profiles.savedBindings(c, p) != null -> "Your layout · $n keys"
                        n > 0 -> "Ready-made layout · $n keys"
                        else -> "No keys"
                    }
                    col.addView(Ui.text(c, how, 12f, Ui.MUTED))
                    row.addView(col)
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8) })
                }
            }
        }
    }
}
