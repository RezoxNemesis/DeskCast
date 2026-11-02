package com.rezox.deskcast

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import java.util.concurrent.Executors

/** Per-app "how should this open on the big screen" settings. */
class AppProfilesActivity : Activity() {

    companion object { const val EXTRA_PKG = "pkg" }

    data class Item(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var list: LinearLayout
    private var items: List<Item> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = Ui.column(c, 20).apply { setBackgroundColor(Ui.BG) }
        root.addView(Ui.title(c, "App window profiles"))
        root.addView(Ui.text(c, "Choose how each app opens on the big screen. Games start full screen by default; everything else as a window.", 14f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 12))
        })
        val search = EditText(c).apply {
            hint = "Search apps"; setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); isSingleLine = true
            background = Ui.rounded(Ui.CARD_HI, Ui.dp(c, 12).toFloat())
            setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty().lowercase(); renderList() }
            })
        }
        root.addView(search)
        list = Ui.column(c).apply { setPadding(0, Ui.dp(c, 10), 0, 0) }
        val scroll = ScrollView(c).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Executors.newSingleThreadExecutor().execute {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val all = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { Item(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            runOnUiThread {
                items = all
                renderList()
                intent.getStringExtra(EXTRA_PKG)?.let { p -> all.firstOrNull { it.pkg == p }?.let { edit(it) } }
            }
        }
    }

    private fun renderList() {
        val c = this
        list.removeAllViews()
        items.filter { query.isEmpty() || it.label.lowercase().contains(query) }.forEach { item ->
            val p = Profiles.get(c, item.pkg)
            val row = Ui.row(c).apply {
                setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10))
                background = Ui.rounded(Ui.CARD, Ui.dp(c, 14).toFloat())
                setOnClickListener { edit(item) }
            }
            row.addView(ImageView(c).apply { setImageDrawable(item.icon) }, LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)).apply { rightMargin = Ui.dp(c, 12) })
            val col = Ui.column(c)
            col.addView(Ui.text(c, item.label, 15f))
            val detail = buildString {
                append(p.mode.label)
                if (p.mode == WindowMode.WINDOW) append(" · ${p.widthPct}% × ${p.heightPct}%")
                if (Profiles.effectiveBindings(c, item.pkg).isNotEmpty()) append(if (p.keymapOn) " · game keys" else " · keys off")
                if (!Profiles.has(c, item.pkg)) append(" (default)")
            }
            col.addView(Ui.text(c, detail, 13f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(c, 8) })
        }
    }

    private fun edit(item: Item) {
        val c = this
        val p = Profiles.get(c, item.pkg)
        val box = Ui.column(c, 20)

        box.addView(Ui.text(c, "Opens as", 13f, Ui.MUTED))
        val modes = Ui.column(c)
        fun drawModes() {
            modes.removeAllViews()
            WindowMode.values().forEach { m ->
                modes.addView(TextView(c).apply {
                    text = (if (m == p.mode) "●  " else "○  ") + m.label
                    textSize = 15f; setTextColor(Ui.TEXT)
                    setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
                    setOnClickListener { p.mode = m; drawModes() }
                })
            }
        }
        drawModes()
        box.addView(modes)

        fun slider(label: String, value: Int, set: (Int) -> Unit): View {
            val col = Ui.column(c).apply { setPadding(0, Ui.dp(c, 10), 0, 0) }
            val t = Ui.text(c, "$label: $value%", 13f, Ui.MUTED)
            col.addView(t)
            col.addView(SeekBar(c).apply {
                max = 70; progress = value - 30
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { set(v + 30); t.text = "$label: ${v + 30}%" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
            return col
        }
        box.addView(Ui.text(c, "Window size (for Window mode)", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 12), 0, 0) })
        box.addView(slider("Width", p.widthPct) { p.widthPct = it })
        box.addView(slider("Height", p.heightPct) { p.heightPct = it })

        box.addView(Ui.text(c, "Mouse", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(c, 14), 0, 0) })
        val mice = Ui.column(c)
        fun drawMice() {
            mice.removeAllViews()
            MouseMode.values().forEach { m ->
                mice.addView(TextView(c).apply {
                    text = (if (m == p.mouse) "●  " else "○  ") + m.label
                    textSize = 15f; setTextColor(Ui.TEXT)
                    setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8))
                    setOnClickListener { p.mouse = m; drawMice() }
                })
            }
        }
        drawMice()
        box.addView(mice)

        box.addView(Switch(c).apply {
            text = "Game keys (keyboard → touch)"
            setTextColor(Ui.TEXT); isChecked = p.keymapOn
            setOnCheckedChangeListener { _, v -> p.keymapOn = v }
            setPadding(0, Ui.dp(c, 14), 0, 0)
        })

        val scroll = ScrollView(c).apply { addView(box) }
        AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(item.label)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                Profiles.save(c, p); ControlService.instance?.invalidateKeymaps(); renderList()
            }
            .setNeutralButton("Edit keys") { _, _ ->
                Profiles.save(c, p)
                startActivity(Intent(c, KeymapEditorActivity::class.java).putExtra(KeymapEditorActivity.EXTRA_PKG, item.pkg))
            }
            .setNegativeButton("Reset") { _, _ -> Profiles.reset(c, item.pkg); ControlService.instance?.invalidateKeymaps(); renderList() }
            .show()
    }
}
