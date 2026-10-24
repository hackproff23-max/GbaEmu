package com.meu.gbaemu.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Ícones desenhados em código (grade 24x24), na cor do tema. */
class IconDrawable(private val name: String, private val color: Int = T.GOLD) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    private fun path(vararg pts: Float, close: Boolean = false): Path = Path().apply {
        moveTo(pts[0], pts[1]); var i = 2
        while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) close()
    }

    override fun draw(c: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f); c.scale(s, s)
        p.color = color; p.strokeWidth = 1.9f; p.style = Paint.Style.STROKE
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
        fun dot(x: Float, y: Float, r: Float) { p.style = Paint.Style.FILL; c.drawCircle(x, y, r, p); p.style = Paint.Style.STROKE }
        fun tray() = c.drawPath(path(4f, 16f, 4f, 20f, 20f, 20f, 20f, 16f), p)
        when (name) {
            "folder" -> c.drawPath(path(3f, 6f, 9f, 6f, 11f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f, close = true), p)
            "layers" -> {
                c.drawPath(path(12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f, close = true), p)
                c.drawPath(path(3f, 12f, 12f, 17f, 21f, 12f), p)
                c.drawPath(path(3f, 16f, 12f, 21f, 21f, 16f), p)
            }
            "pad" -> {
                c.drawRoundRect(RectF(2.5f, 7f, 21.5f, 17.5f), 5f, 5f, p)
                line(6f, 12.2f, 10f, 12.2f); line(8f, 10.2f, 8f, 14.2f)
                dot(15.5f, 13.5f, 1.3f); dot(18.2f, 11f, 1.3f)
            }
            "save" -> {
                c.drawPath(path(4f, 4f, 17f, 4f, 20f, 7f, 20f, 20f, 4f, 20f, close = true), p)
                c.drawRect(7.5f, 4f, 15f, 9f, p); c.drawRect(7f, 13.5f, 17f, 20f, p)
            }
            "load" -> {
                line(12f, 4f, 12f, 14.5f); c.drawPath(path(7.5f, 10f, 12f, 14.5f, 16.5f, 10f), p); tray()
            }
            "import" -> {
                line(12f, 15f, 12f, 4.5f); c.drawPath(path(7.5f, 9f, 12f, 4.5f, 16.5f, 9f), p); tray()
            }
            "ff" -> {
                p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f
                c.drawPath(path(4f, 6.5f, 12f, 12f, 4f, 17.5f, close = true), p)
                c.drawPath(path(12f, 6.5f, 20f, 12f, 12f, 17.5f, close = true), p)
            }
            "restart" -> {
                c.drawArc(RectF(5f, 5f, 19f, 19f), -50f, 280f, false, p)
                val a = Math.toRadians(230.0); val ex = 12f + 7f * cos(a).toFloat(); val ey = 12f + 7f * sin(a).toFloat()
                val dx = -sin(a).toFloat(); val dy = cos(a).toFloat()
                p.style = Paint.Style.FILL
                c.drawPath(path(ex + dx * 3.2f, ey + dy * 3.2f, ex - dy * 3.2f, ey + dx * 3.2f, ex + dy * 3.2f, ey - dx * 3.2f, close = true), p)
            }
            "gear" -> {
                c.drawCircle(12f, 12f, 3.2f, p); c.drawCircle(12f, 12f, 6.6f, p)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    line(12f + 6.6f * cos(a).toFloat(), 12f + 6.6f * sin(a).toFloat(), 12f + 9.4f * cos(a).toFloat(), 12f + 9.4f * sin(a).toFloat())
                }
            }
            "home" -> {
                c.drawPath(path(3f, 11f, 12f, 3.5f, 21f, 11f), p)
                c.drawPath(path(5.5f, 9.5f, 5.5f, 20.5f, 18.5f, 20.5f, 18.5f, 9.5f), p)
                c.drawPath(path(10f, 20.5f, 10f, 14f, 14f, 14f, 14f, 20.5f), p)
            }
            "trash" -> {
                line(4f, 7f, 20f, 7f); c.drawPath(path(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f), p)
                c.drawPath(path(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), p)
                line(10f, 10.5f, 10f, 16.5f); line(14f, 10.5f, 14f, 16.5f)
            }
            "image" -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, p)
                c.drawPath(path(5f, 17f, 10f, 11f, 13.5f, 15f, 15.5f, 13f, 19f, 17f), p); dot(8f, 9f, 1.5f)
            }
            "pencil" -> {
                c.drawPath(path(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, close = true), p); line(13.5f, 6.5f, 17.5f, 10.5f)
            }
            "drop" -> {
                val d = Path()
                d.moveTo(12f, 3f); d.cubicTo(16f, 8f, 19f, 11f, 19f, 15f); d.cubicTo(19f, 18.5f, 15.9f, 21f, 12f, 21f)
                d.cubicTo(8.1f, 21f, 5f, 18.5f, 5f, 15f); d.cubicTo(5f, 11f, 8f, 8f, 12f, 3f); d.close()
                c.drawPath(d, p)
            }
            "vibrate" -> {
                c.drawRoundRect(RectF(8f, 3.5f, 16f, 20.5f), 2.2f, 2.2f, p)
                line(4.5f, 9f, 4.5f, 15f); line(19.5f, 9f, 19.5f, 15f)
            }
            "dpad" -> c.drawPath(path(9f, 3f, 15f, 3f, 15f, 9f, 21f, 9f, 21f, 15f, 15f, 15f, 15f, 21f, 9f, 21f, 9f, 15f, 3f, 15f, 3f, 9f, 9f, 9f, close = true), p)
            "power" -> {
                c.drawArc(RectF(5f, 5.5f, 19f, 19.5f), -50f, 280f, false, p); line(12f, 3f, 12f, 11.5f)
            }
            "scale" -> { c.drawRect(4f, 4f, 20f, 20f, p); c.drawRect(8.5f, 8.5f, 15.5f, 15.5f, p) }
            "filter" -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                dot(9f, 7f, 2.2f); dot(15f, 12f, 2.2f); dot(8f, 17f, 2.2f)
            }
            "check" -> c.drawPath(path(5f, 12.5f, 10f, 17.5f, 19f, 7f), p)
            "cheat" -> c.drawPath(path(12f, 3f, 14.2f, 9.8f, 21f, 12f, 14.2f, 14.2f, 12f, 21f, 9.8f, 14.2f, 3f, 12f, 9.8f, 9.8f, close = true), p)
            else -> dot(12f, 12f, 3f)
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
