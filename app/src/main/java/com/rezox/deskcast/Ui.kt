package com.rezox.deskcast

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny programmatic-UI kit so the app needs no extra libraries. */
object Ui {
    const val BG = 0xFF0B0E14.toInt()
    const val CARD = 0xFF151A24.toInt()
    const val CARD_HI = 0xFF1E2533.toInt()
    const val TEXT = 0xFFEEF1F7.toInt()
    const val MUTED = 0xFF9AA3B5.toInt()
    const val ACCENT = 0xFF5B8CFF.toInt()
    const val OK = 0xFF3DDC97.toInt()
    const val WARN = 0xFFFFB547.toInt()
    const val BAD = 0xFFFF5C6C.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun column(c: Context, padDp: Int = 0): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, padDp)
        setPadding(p, p, p, p)
    }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(c: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
        }

    fun title(c: Context, s: CharSequence): TextView = text(c, s, 26f, TEXT, true).apply {
        letterSpacing = -0.01f
    }

    fun section(c: Context, s: CharSequence): TextView = text(c, s.toString().uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(c, 22), 0, dp(c, 8))
    }

    fun card(c: Context): LinearLayout = column(c, 16).apply {
        background = rounded(CARD, dp(c, 16).toFloat())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(c, 10)
        }
    }

    fun button(c: Context, label: String, primary: Boolean = false, onClick: (View) -> Unit): Button =
        Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) Color.WHITE else TEXT)
            background = rounded(if (primary) ACCENT else CARD_HI, dp(c, 12).toFloat())
            stateListAnimator = null
            minHeight = dp(c, 48)
            setPadding(dp(c, 16), 0, dp(c, 16), 0)
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(c, 8)
            }
        }

    fun dot(c: Context, color: Int): View = View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)).apply { rightMargin = dp(c, 10) }
    }

    fun statusRow(c: Context, ok: Boolean?, label: String, detail: String? = null): LinearLayout = row(c).apply {
        setPadding(0, dp(c, 6), 0, dp(c, 6))
        addView(dot(c, when (ok) { true -> OK; false -> BAD; null -> WARN }))
        val col = column(c)
        col.addView(text(c, label, 15f))
        if (detail != null) col.addView(text(c, detail, 13f, MUTED))
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
