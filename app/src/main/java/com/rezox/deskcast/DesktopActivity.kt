package com.rezox.deskcast

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors

/** The phone's own screen turned into a desktop (no monitor needed). */
class PhoneDesktopActivity : DesktopActivity() {
    override val phoneMode = true
}

/**
 * The desktop: wallpaper, pinned apps, taskbar and start menu. Runs on the external screen, or —
 * as [PhoneDesktopActivity] — on the phone itself in landscape.
 */
open class DesktopActivity : Activity() {

    companion object {
        /** Desktop on the external screen. */
        var current: DesktopActivity? = null
            private set
        /** Desktop on the phone's own screen. */
        var phoneCurrent: DesktopActivity? = null
            private set
        const val EXTRA_OPEN_START = "open_start"

        /** The desktop the Win key should act on. */
        fun active(): DesktopActivity? = current ?: phoneCurrent

        fun startPhoneDesktop(c: Context, openStart: Boolean = false) {
            PhoneHome.setEnabled(c, true)
            val i = Intent(c, PhoneDesktopActivity::class.java).putExtra(EXTRA_OPEN_START, openStart)
            Displays.launchOn(c, i, Display.DEFAULT_DISPLAY, null, Displays.WINDOWING_FULLSCREEN)
        }
    }

    open val phoneMode = false

    data class App(val pkg: String, val label: String, val ri: ResolveInfo)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val iconCache = HashMap<String, Drawable>()

    private var allApps: List<App> = emptyList()
    private val recent = ArrayList<String>()
    private var cascade = 0
    private var resumedTop = false

    private lateinit var root: FrameLayout
    private lateinit var desktopGrid: GridView
    private lateinit var emptyHint: TextView
    private lateinit var taskbar: LinearLayout
    private lateinit var taskApps: LinearLayout
    private lateinit var clock: TextView
    private lateinit var battery: TextView
    private lateinit var startMenu: LinearLayout
    private lateinit var search: EditText
    private lateinit var menuGrid: GridView
    private lateinit var modeChip: TextView

    private val taskbarH get() = Ui.dp(this, if (phoneMode) 48 else 56)

    fun displayIdOrNull(): Int? = try { display?.displayId } catch (_: Exception) { null }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (phoneMode) {
            phoneCurrent = this
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            current = this
        }
        buildUi()
        if (phoneMode) hideSystemBars()
        loadApps()
        if (intent?.getBooleanExtra(EXTRA_OPEN_START, false) == true) main.post { showStart(true) }
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        tick()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_START, false)) showStart(true)
    }

    override fun onResume() {
        super.onResume()
        resumedTop = true
        if (phoneMode) hideSystemBars()
        refreshModeChip()
        refreshTaskbar()
    }

    override fun onPause() {
        resumedTop = false
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (phoneCurrent === this) phoneCurrent = null
        try { unregisterReceiver(batteryRx) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (startMenu.visibility == View.VISIBLE) showStart(false)
        // The desktop never closes itself on Back.
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Works even before DeskCast control is switched on.
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            showStart(startMenu.visibility != View.VISIBLE); return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) return true
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && startMenu.visibility == View.VISIBLE) { showStart(false); return true }
        // Start typing anywhere on the desktop to search apps.
        if (startMenu.visibility != View.VISIBLE && event.isPrintingKey && !event.isCtrlPressed && !event.isAltPressed) {
            showStart(true)
            search.dispatchKeyEvent(event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Called by the Win key. Brings the desktop forward first if apps cover it. */
    fun toggleStartMenu() {
        if (!resumedTop) {
            val id = displayIdOrNull() ?: return
            val i = Intent(this, javaClass).putExtra(EXTRA_OPEN_START, true)
            Displays.launchOn(this, i, id, null, if (phoneMode) Displays.WINDOWING_FULLSCREEN else null)
            return
        }
        showStart(startMenu.visibility != View.VISIBLE)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Leaves phone desktop mode and returns to the normal phone launcher. */
    fun exitPhoneDesktop() {
        PhoneHome.setEnabled(this, false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        finish()
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun windowsReady() =
        if (phoneMode) DesktopFlags.phoneFreeformReady(this) else DesktopFlags.freeformReady(this)

    // ---------- UI ----------

    private fun hoverBg(radiusDp: Int): StateListDrawable = StateListDrawable().apply {
        val hi = Ui.rounded(0x33FFFFFF, Ui.dp(this@DesktopActivity, radiusDp).toFloat())
        addState(intArrayOf(android.R.attr.state_pressed), hi)
        addState(intArrayOf(android.R.attr.state_hovered), hi)
        addState(intArrayOf(android.R.attr.state_focused), hi)
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0E1630.toInt(), 0xFF1B2B5C.toInt(), 0xFF2B1A4F.toInt()),
            )
        }

        // Desktop icons (pinned apps)
        desktopGrid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(this@DesktopActivity, 96)
            stretchMode = GridView.NO_STRETCH
            verticalSpacing = Ui.dp(this@DesktopActivity, 6)
            horizontalSpacing = Ui.dp(this@DesktopActivity, 6)
            setPadding(Ui.dp(this@DesktopActivity, 16), Ui.dp(this@DesktopActivity, 16), 0, 0)
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            isVerticalScrollBarEnabled = false
        }
        root.addView(desktopGrid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            bottomMargin = taskbarH
        })

        emptyHint = Ui.text(this, "Press the ⊞ Win key or click Start to open apps.\nRight-click any app to pin it here or choose how it opens.", 16f, 0xCCFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
        }
        root.addView(emptyHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        buildStartMenu()
        buildTaskbar()
        setContentView(root)
    }

    private fun buildTaskbar() {
        val c = this
        taskbar = Ui.row(c).apply {
            background = GradientDrawable().apply { setColor(0xE60B0E14.toInt()) }
            setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0)
        }

        val start = TextView(c).apply {
            text = "⊞"
            textSize = 24f
            setTextColor(Ui.ACCENT)
            gravity = Gravity.CENTER
            background = hoverBg(10)
            setOnClickListener { showStart(startMenu.visibility != View.VISIBLE) }
        }
        taskbar.addView(start, LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 44)))

        fun navBtn(label: String, onClick: () -> Unit) = TextView(c).apply {
            text = label; textSize = 18f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
            background = hoverBg(10)
            setOnClickListener { onClick() }
        }
        taskbar.addView(navBtn("◁") { ControlService.instance?.globalBack() }, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)))
        taskbar.addView(navBtn("▢") { ControlService.instance?.globalRecents() }, LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)))

        val sep = View(c).apply { setBackgroundColor(0x33FFFFFF) }
        taskbar.addView(sep, LinearLayout.LayoutParams(Ui.dp(c, 1), Ui.dp(c, 28)).apply { leftMargin = Ui.dp(c, 6); rightMargin = Ui.dp(c, 6) })

        taskApps = Ui.row(c)
        val scroller = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(taskApps) }
        taskbar.addView(scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        modeChip = Ui.text(c, "", 12f, Ui.MUTED).apply {
            setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4))
            background = hoverBg(8)
            setOnClickListener { openOnPhone(Intent(c, MainActivity::class.java)) }
        }
        taskbar.addView(modeChip)

        battery = Ui.text(c, "", 13f, Ui.TEXT).apply { setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 6), 0) }
        taskbar.addView(battery)

        clock = Ui.text(c, "", 13f, Ui.TEXT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0)
            background = hoverBg(8)
            setOnClickListener { ControlService.instance?.globalNotifications() }
        }
        taskbar.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 44)))

        root.addView(taskbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, taskbarH, Gravity.BOTTOM))
    }

    private fun buildStartMenu() {
        val c = this
        startMenu = Ui.column(c, 16).apply {
            background = Ui.rounded(0xF2151A24.toInt(), Ui.dp(c, 18).toFloat(), 0x22FFFFFF, Ui.dp(c, 1))
            visibility = View.GONE
            elevation = Ui.dp(c, 12).toFloat()
            isClickable = true
        }
        search = EditText(c).apply {
            hint = "Search apps"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = Ui.rounded(Ui.CARD_HI, Ui.dp(c, 12).toFloat())
            setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { filterMenu(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { _, _, _ ->
                (menuGrid.adapter as? AppAdapter)?.items?.firstOrNull()?.let { launch(it.pkg); showStart(false) }
                true
            }
        }
        startMenu.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        menuGrid = GridView(c).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(c, 92)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = Ui.dp(c, 4)
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        }
        startMenu.addView(menuGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(c, 12) })

        val footer = Ui.row(c).apply { setPadding(0, Ui.dp(c, 8), 0, 0) }
        fun footBtn(label: String, onClick: () -> Unit) = Ui.text(c, label, 13f, Ui.TEXT).apply {
            setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8))
            background = hoverBg(10)
            setOnClickListener { onClick() }
        }
        footer.addView(footBtn("⚙  DeskCast settings") { showStart(false); openOnPhone(Intent(c, MainActivity::class.java)) })
        footer.addView(footBtn("Android settings") {
            showStart(false); launchIntent(Intent(android.provider.Settings.ACTION_SETTINGS), null)
        })
        footer.addView(footBtn("Files") {
            showStart(false)
            val i = Intent(Intent.ACTION_VIEW).setType("resource/folder")
            if (i.resolveActivity(packageManager) != null) launchIntent(i, null)
            else launchIntent(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS), null)
        })
        if (phoneMode) {
            footer.addView(footBtn("⏻  Exit desktop") { exitPhoneDesktop() })
        }
        startMenu.addView(footer)

        val dm = resources.displayMetrics
        val longEdge = maxOf(dm.widthPixels, dm.heightPixels)
        val menuW = minOf(Ui.dp(c, 620), (longEdge * 0.62f).toInt())
        root.addView(startMenu, FrameLayout.LayoutParams(menuW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.dp(c, 12); bottomMargin = taskbarH + Ui.dp(c, 10); topMargin = Ui.dp(c, if (phoneMode) 16 else 60)
        })

        root.setOnClickListener { if (startMenu.visibility == View.VISIBLE) showStart(false) }
    }

    private fun showStart(show: Boolean) {
        if (show) {
            startMenu.visibility = View.VISIBLE
            startMenu.alpha = 0f; startMenu.translationY = Ui.dp(this, 24).toFloat()
            startMenu.animate().alpha(1f).translationY(0f).setDuration(140).start()
            search.setText("")
            search.requestFocus()
        } else {
            startMenu.visibility = View.GONE
        }
    }

    // ---------- apps ----------

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            main.post {
                allApps = list
                menuGrid.adapter = AppAdapter(list, big = false)
                refreshDesktop()
            }
        }
    }

    private fun filterMenu(q: String) {
        val qq = q.trim().lowercase()
        val items = if (qq.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(qq) || it.pkg.contains(qq) }
        menuGrid.adapter = AppAdapter(items, big = false)
    }

    private fun refreshDesktop() {
        val pinned = Settings2.pinned(this)
        val items = pinned.mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }
        desktopGrid.adapter = AppAdapter(items, big = true)
        emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshTaskbar() {
        if (!::taskApps.isInitialized) return
        taskApps.removeAllViews()
        val c = this
        for (pkg in recent.take(12)) {
            val app = allApps.firstOrNull { it.pkg == pkg } ?: continue
            val iv = ImageView(c).apply {
                setImageDrawable(icon(app))
                setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8))
                background = hoverBg(10)
                contentDescription = app.label
                tooltipText = app.label
                setOnClickListener { launch(app.pkg) }
                setOnLongClickListener { appMenu(this, app); true }
                setOnContextClickListener { appMenu(this, app); true }
            }
            taskApps.addView(iv, LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)))
        }
    }

    private fun refreshModeChip() {
        if (!::modeChip.isInitialized) return
        modeChip.text = when {
            windowsReady() -> if (phoneMode) "Phone desktop · windows on" else "Desktop windows on"
            else -> "Full-screen apps · tap to enable windows"
        }
    }

    private fun icon(app: App): Drawable =
        iconCache.getOrPut(app.pkg) { app.ri.loadIcon(packageManager) }

    fun launch(pkg: String, modeOverride: WindowMode? = null) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val profile = Profiles.get(this, pkg).let { if (modeOverride != null) it.copy(mode = modeOverride) else it }
        if (windowsReady()) {
            val d = display ?: return
            val bounds = Profiles.boundsFor(profile, Displays.bounds(this, d), taskbarH, cascade++)
            launchIntent(intent, bounds, Displays.windowingFor(profile))
        } else {
            launchIntent(intent, null)
        }
        recent.remove(pkg); recent.add(0, pkg)
        refreshTaskbar()
    }

    private fun launchIntent(intent: Intent, bounds: Rect?, windowing: Int? = null) {
        val id = displayIdOrNull() ?: return
        // Plain launches (settings, files) open as a window too when windows are available.
        val mode = windowing ?: if (windowsReady() && bounds == null) Displays.WINDOWING_FREEFORM else null
        val b = bounds ?: if (mode == Displays.WINDOWING_FREEFORM) {
            display?.let { Profiles.boundsFor(AppProfile(""), Displays.bounds(this, it), taskbarH, cascade++) }
        } else null
        Displays.launchOn(this, intent, id, b, mode)
    }

    private fun openOnPhone(intent: Intent) {
        Displays.launchOn(this, intent, Display.DEFAULT_DISPLAY, null)
    }

    private fun appMenu(anchor: View, app: App) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        val pinned = Settings2.pinned(this)
        m.add(0, 1, 0, "Open")
        m.add(0, 2, 1, "Open as window")
        m.add(0, 3, 2, "Open maximized")
        m.add(0, 4, 3, "Open full screen")
        m.add(0, 5, 4, "Open phone-shaped")
        m.add(0, 6, 5, if (app.pkg in pinned) "Unpin from desktop" else "Pin to desktop")
        m.add(0, 7, 6, "Always open as… (profile)")
        m.add(0, 8, 7, "Edit game keys (F10)")
        m.add(0, 9, 8, "App info")
        pm.setOnMenuItemClickListener { item ->
            showStart(false)
            when (item.itemId) {
                1 -> launch(app.pkg)
                2 -> launch(app.pkg, WindowMode.WINDOW)
                3 -> launch(app.pkg, WindowMode.MAXIMIZED)
                4 -> launch(app.pkg, WindowMode.FULLSCREEN)
                5 -> launch(app.pkg, WindowMode.PHONE)
                6 -> {
                    val list = pinned.toMutableList()
                    if (app.pkg in list) list.remove(app.pkg) else list.add(app.pkg)
                    Settings2.setPinned(this, list); refreshDesktop()
                }
                7 -> openOnPhone(Intent(this, AppProfilesActivity::class.java).putExtra(AppProfilesActivity.EXTRA_PKG, app.pkg))
                8 -> { launch(app.pkg); ControlService.instance?.openEditorWhenReady(app.pkg) }
                9 -> launchIntent(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)), null)
            }
            true
        }
        pm.show()
    }

    private inner class AppAdapter(val items: List<App>, val big: Boolean) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = this@DesktopActivity
            val app = items[position]
            val v = (convertView as? LinearLayout) ?: Ui.column(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 8))
                background = hoverBg(12)
                addView(ImageView(c), LinearLayout.LayoutParams(Ui.dp(c, if (big) 52 else 44), Ui.dp(c, if (big) 52 else 44)))
                addView(Ui.text(c, "", 12f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (big) setShadowLayer(4f, 0f, 1f, Color.BLACK)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 6) })
            }
            (v.getChildAt(0) as ImageView).setImageDrawable(icon(app))
            (v.getChildAt(1) as TextView).text = app.label
            v.setOnClickListener { showStart(false); launch(app.pkg) }
            v.setOnLongClickListener { appMenu(v, app); true }
            v.setOnContextClickListener { appMenu(v, app); true }
            return v
        }
    }

    // ---------- tray ----------

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0) battery.text = (if (plugged) "⚡" else "🔋") + " ${level * 100 / scale}%"
        }
    }

    private fun tick() {
        val now = Date()
        clock.text = DateFormat.getTimeFormat(this).format(now) + "\n" + DateFormat.getMediumDateFormat(this).format(now)
        main.postDelayed({ tick() }, 15_000)
    }
}
