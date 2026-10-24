package com.meu.gbaemu.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Uma linha de menu: ícone (nome em IconDrawable), texto, subtexto, marca de ligado e ação. */
class Row(val icon: String?, val label: String, val sub: String? = null, val checked: Boolean? = null, val click: () -> Unit)

/** Peças de diálogo no tema do app (cores RGB e fonte importada). */
object Dlg {
    fun rowView(c: Context, icon: String?, label: String, sub: String?, checked: Boolean?, click: () -> Unit): View {
        val row = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = T.box(c, T.CARD, if (checked == true) T.GOLD else T.LINE, 12)
            setPadding(T.dp(c, 14), T.dp(c, 12), T.dp(c, 14), T.dp(c, 12))
            setOnClickListener { click() }
        }
        if (icon != null) row.addView(ImageView(c).apply { setImageDrawable(IconDrawable(icon)) },
            LinearLayout.LayoutParams(T.dp(c, 24), T.dp(c, 24)).apply { rightMargin = T.dp(c, 14) })
        val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(T.text(c, label, 15f))
        if (sub != null) txt.addView(T.text(c, sub, 12f, T.MUT))
        row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        if (checked == true) row.addView(ImageView(c).apply { setImageDrawable(IconDrawable("check")) }, LinearLayout.LayoutParams(T.dp(c, 22), T.dp(c, 22)))
        return row
    }

    fun add(c: Context, parent: LinearLayout, v: View) =
        parent.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(c, 8) })

    fun frame(c: Context, title: String, body: View): View {
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = T.box(c, T.BG, T.GOLD, 18)
            setPadding(T.dp(c, 16), T.dp(c, 16), T.dp(c, 16), T.dp(c, 16))
        }
        col.addView(T.text(c, title, 18f, T.GOLD2, true))
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(c, 4) })
        return ScrollView(c).apply { addView(col) }
    }

    fun content(c: Context, title: String, rows: List<Row>, dismiss: () -> Unit): View {
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { r -> add(c, body, rowView(c, r.icon, r.label, r.sub, r.checked) { dismiss(); r.click() }) }
        return frame(c, title, body)
    }

    fun button(c: Context, text: String, solid: Boolean, click: () -> Unit): View =
        T.text(c, text, 15f, if (solid) T.BG else T.GOLD, true).apply {
            gravity = Gravity.CENTER
            background = T.box(c, if (solid) T.GOLD else T.CARD, T.GOLD)
            setPadding(0, T.dp(c, 12), 0, T.dp(c, 12))
            setOnClickListener { click() }
        }
}
