package com.meu.gbaemu.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Palco que simula a tela do celular; o retângulo dourado é a tela do jogo (arraste para mover).
 *  Mesma geometria do GbaRenderer: largura = s * palco, posição = fração do espaço livre. */
class EditorView(c: Context, var px: Float, var py: Float, var s: Float, var ar: Float) : View(c) {
    var onChange: (() -> Unit)? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.LINE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = T.dp(c, 2).toFloat(); color = T.GOLD }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.GOLD2; textAlign = Paint.Align.CENTER; textSize = T.dp(c, 13).toFloat(); typeface = Typeface.DEFAULT_BOLD }
    private var dx = 0f; private var dy = 0f; private var drag = false

    private fun rect(): RectF {
        var w = s * width; var h = w / ar
        if (h > height) { h = height.toFloat(); w = h * ar }
        val l = px * (width - w).coerceAtLeast(0f); val t = py * (height - h).coerceAtLeast(0f)
        return RectF(l, t, l + w, t + h)
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(T.BG)
        cv.drawRect(1f, 1f, width - 1f, height - 1f, line.apply { alpha = 90 }); line.alpha = 255
        val r = rect(); cv.drawRect(r, fill); cv.drawRect(r, line)
        cv.drawText("GBA", r.centerX(), r.centerY() + txt.textSize / 3, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { val r = rect(); drag = r.contains(e.x, e.y); dx = e.x - r.left; dy = e.y - r.top }
            MotionEvent.ACTION_MOVE -> if (drag) {
                val r = rect(); val fx = (width - r.width()).coerceAtLeast(1f); val fy = (height - r.height()).coerceAtLeast(1f)
                px = ((e.x - dx) / fx).coerceIn(0f, 1f); py = ((e.y - dy) / fy).coerceIn(0f, 1f); invalidate()
            }
            MotionEvent.ACTION_UP -> { if (drag) onChange?.invoke(); drag = false }
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
