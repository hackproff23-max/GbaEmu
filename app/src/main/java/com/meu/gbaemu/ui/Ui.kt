package com.meu.gbaemu.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Tema preto e dourado + helpers de UI (sem dependências externas). */
object T {
    const val BG = 0xFF000000.toInt(); const val CARD = 0xFF101010.toInt(); const val LINE = 0xFF3B3112.toInt()
    const val GOLD = 0xFFD4AF37.toInt(); const val GOLD2 = 0xFFF5D77A.toInt()
    const val TX = 0xFFF3EAD0.toInt(); const val MUT = 0xFF9A8A5A.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun box(c: Context, fill: Int, stroke: Int, r: Int = 12) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(c, 1), stroke); cornerRadius = dp(c, r).toFloat()
    }

    fun text(c: Context, s: String, sp: Float = 15f, col: Int = TX, bold: Boolean = false) =
        TextView(c).apply { text = s; textSize = sp; setTextColor(col); if (bold) setTypeface(typeface, Typeface.BOLD) }

    fun chip(c: Context, s: String, on: Boolean, click: () -> Unit) =
        text(c, s, 13f, if (on) BG else MUT, on).apply {
            setPadding(dp(c, 13), dp(c, 8), dp(c, 13), dp(c, 8))
            background = box(c, if (on) GOLD else CARD, if (on) GOLD else LINE, 20)
            setOnClickListener { click() }
        }

    fun chips(c: Context, opts: List<String>, cur: String, pick: (String) -> Unit): View {
        val row = LinearLayout(c).apply { setPadding(0, dp(c, 6), 0, dp(c, 6)) }
        opts.forEach { o ->
            val ch = chip(c, o, o == cur) { pick(o) }
            row.addView(ch, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(c, 8) })
        }
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    fun h(c: Context, s: String) = text(c, s, 16f, GOLD2, true).apply { setPadding(0, dp(c, 14), 0, dp(c, 4)) }

    fun note(c: Context, s: String) = text(c, s, 12f, MUT).apply { setPadding(0, dp(c, 4), 0, dp(c, 4)) }

    fun switchRow(c: Context, label: String, on: Boolean, change: (Boolean) -> Unit): View {
        val sw = Switch(c).apply {
            isChecked = on
            thumbTintList = ColorStateList.valueOf(GOLD); trackTintList = ColorStateList.valueOf(LINE)
            setOnCheckedChangeListener { _, v -> change(v) }
        }
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(c, 10), 0, dp(c, 10))
            addView(text(c, label), LinearLayout.LayoutParams(0, -2, 1f)); addView(sw)
        }
    }

    fun seek(c: Context, label: String, lo: Int, hi: Int, cur: Int, on: (Int) -> Unit): View {
        val lb = text(c, "$label: $cur%", 13f, MUT)
        val sb = SeekBar(c).apply {
            max = hi - lo; progress = cur - lo
            progressTintList = ColorStateList.valueOf(GOLD); thumbTintList = ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { lb.text = "$label: ${p + lo}%"; on(p + lo) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 8), 0, dp(c, 8)); addView(lb); addView(sb) }
    }

    fun header(c: Context, title: TextView, right: View?): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        val logo = text(c, "GBA", 13f, BG, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(GOLD2, GOLD, 0xFF8A6D17.toInt())).apply { cornerRadius = dp(c, 10).toFloat() }
        }
        addView(logo, LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)))
        addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(c, 10) })
        right?.let { addView(it, LinearLayout.LayoutParams(dp(c, 42), dp(c, 42))) }
    }
}
