package com.meu.gbaemu.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    /** Quantas colunas cabem nas grades: 2 em pé, 3 deitado. */
    fun cols(c: Context): Int = if (c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    /** Capa quadrada com cantos arredondados; sem capa, mostra as 2 primeiras letras do jogo. */
    fun coverBox(c: Context, title: String, bmp: Bitmap?, sizeDp: Int): View {
        val box: View = if (bmp != null) ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp) }
        else T.text(c, title.take(2).uppercase(), 22f, T.BG, true).apply { gravity = Gravity.CENTER; setBackgroundColor(T.GOLD) }
        return android.widget.FrameLayout(c).apply {
            background = T.box(c, T.CARD, T.LINE, 12)
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(box, android.widget.FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(T.dp(c, sizeDp), T.dp(c, sizeDp))
        }
    }

    /** Cabeçalho do menu: capa do jogo + nome. */
    fun coverHeader(c: Context, title: String, bmp: Bitmap?): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(coverBox(c, title, bmp, 76))
        addView(T.text(c, title, 18f, T.GOLD2, true).apply { maxLines = 3 },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = T.dp(c, 14) })
    }

    /** Uma opção em forma de azulejo (ícone em cima, nome embaixo) para as grades. */
    fun tile(c: Context, icon: String?, label: String, sub: String?, checked: Boolean?, click: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = T.box(c, T.CARD, if (checked == true) T.GOLD else T.LINE, 12)
        setPadding(T.dp(c, 8), T.dp(c, 12), T.dp(c, 8), T.dp(c, 12))
        minimumHeight = T.dp(c, 88)
        setOnClickListener { click() }
        if (icon != null) addView(ImageView(c).apply { setImageDrawable(IconDrawable(icon)) },
            LinearLayout.LayoutParams(T.dp(c, 26), T.dp(c, 26)).apply { bottomMargin = T.dp(c, 6) })
        addView(T.text(c, label, 13f, T.TX, true).apply { gravity = Gravity.CENTER; maxLines = 2 })
        if (sub != null) addView(T.text(c, sub, 11f, T.MUT).apply { gravity = Gravity.CENTER; maxLines = 2 })
    }

    /** Opções em grade. Cada azulejo fecha o menu e executa a ação, igual às linhas da lista. */
    fun grid(c: Context, rows: List<Row>, cols: Int, dismiss: () -> Unit): View {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val m = T.dp(c, 4)
        rows.chunked(cols).forEach { chunk ->
            val line = LinearLayout(c).apply { isBaselineAligned = false }
            chunk.forEach { r ->
                line.addView(tile(c, r.icon, r.label, r.sub, r.checked) { dismiss(); r.click() },
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(m, m, m, m) })
            }
            repeat(cols - chunk.size) { line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, m, m, m) }) }
            col.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        return col
    }

    fun frame(c: Context, title: String, body: View, cover: Bitmap? = null, withCover: Boolean = false, wide: Boolean = false): View {
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = T.box(c, T.BG, T.GOLD, 18)
            setPadding(T.dp(c, 16), T.dp(c, 16), T.dp(c, 16), T.dp(c, 16))
            if (wide || withCover) minimumWidth = minOf((c.resources.displayMetrics.widthPixels * 0.9f).toInt(), T.dp(c, 560))
        }
        if (withCover) col.addView(coverHeader(c, title, cover)) else col.addView(T.text(c, title, 18f, T.GOLD2, true))
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(c, 4) })
        val maxH = (c.resources.displayMetrics.heightPixels * 0.85f).toInt()
        return MaxHFrame(c, maxH).apply { addView(ScrollView(c).apply { addView(col) }, android.widget.FrameLayout.LayoutParams(-1, -2)) }
    }

    fun content(c: Context, title: String, rows: List<Row>, dismiss: () -> Unit, cols: Int = 1, cover: Bitmap? = null, withCover: Boolean = false): View {
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (cols > 1) add(c, body, grid(c, rows, cols, dismiss))
        else rows.forEach { r -> add(c, body, rowView(c, r.icon, r.label, r.sub, r.checked) { dismiss(); r.click() }) }
        return frame(c, title, body, cover, withCover, cols > 1)
    }

    fun button(c: Context, text: String, solid: Boolean, click: () -> Unit): View =
        T.text(c, text, 15f, if (solid) T.BG else T.GOLD, true).apply {
            gravity = Gravity.CENTER
            background = T.box(c, if (solid) T.GOLD else T.CARD, T.GOLD)
            setPadding(0, T.dp(c, 12), 0, T.dp(c, 12))
            setOnClickListener { click() }
        }
}

/** Limita a altura do diálogo à tela; assim o ScrollView de dentro recebe uma altura finita e rola (menu não fica cortado embaixo). */
class MaxHFrame(c: Context, private val maxH: Int) : android.widget.FrameLayout(c) {
    override fun onMeasure(w: Int, h: Int) {
        val size = View.MeasureSpec.getSize(h)
        val cap = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) maxH else minOf(size, maxH)
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
