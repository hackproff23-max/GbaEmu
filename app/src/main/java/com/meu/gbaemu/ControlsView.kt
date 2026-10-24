package com.meu.gbaemu

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.meu.gbaemu.ui.SKIN_NAMES
import com.meu.gbaemu.ui.Store
import com.meu.gbaemu.ui.T
import java.io.File
import java.io.InputStream
import kotlin.math.*

/** Botões em PNG (alta resolução), D-pad ou analógico, modo de edição (arrastar / pinça). */
class ControlsView(ctx: Context) : View(ctx) {
    class El(val name: String) { var cx = 0f; var cy = 0f; var size = 0.2f; var pressed = false }

    var onMask: (Int) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onLayoutChanged: () -> Unit = {}
    var useStick = false
        set(v) { field = v; invalidate() }
    var opacity = 0.85f
    var haptic = true
    var showCombo = true
        set(v) { field = v; invalidate() }
    var showTurboA = true
        set(v) { field = v; invalidate() }
    var showTurboB = true
        set(v) { field = v; invalidate() }
    var layoutPort = ""
    var layoutLand = ""
    var editMode = false
        set(v) { field = v; if (!v) { selected = null; save() }; invalidate() }

    private val els = listOf("dpad", "stick", "a", "b", "l", "r", "start", "select", "menu", "cm", "ta", "tb").associateWith { El(it) }
    private val skin = HashMap<String, Bitmap>()
    private var skinSig = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW; pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f) }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private var land = false
    private var stickPtr = -1
    private var knobX = 0f
    private var knobY = 0f
    private var prevMask = 0
    private var selected: El? = null
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pinch0 = 0f
    private var size0 = 0f

    init { reloadSkin() }

    // ---------- skin ----------
    /** Assinatura dos arquivos da pasta Controles: só recarrega os PNGs quando algum mudou (ou com force). */
    private fun currentSig(): String = try {
        val d = Store.dir(context, "controls")
        SKIN_NAMES.joinToString(",") { n -> File(d, "$n.png").let { "${it.length()}:${it.lastModified()}" } }
    } catch (e: Exception) { "" }

    fun reloadSkin(force: Boolean = true) {
        val sig = currentSig()
        if (!force && sig == skinSig && skin.isNotEmpty()) return
        skinSig = sig
        skin.clear()
        for (n in SKIN_NAMES) loadBmp(n)?.let { skin[n] = it }
        invalidate()
    }

    private fun decode(open: () -> InputStream): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (max(b.outWidth, b.outHeight) / s > 2048) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return open().use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun loadBmp(n: String): Bitmap? {
        // 1) EmuladorPro/Controles/<botao>.png  2) PNGs da versão antiga  3) padrão do app
        val custom = File(Store.dir(context, "controls"), "$n.png")
        val legacy = File(context.filesDir, "skin/$n.png")
        var bm: Bitmap? = null
        if (custom.exists()) bm = try { decode { custom.inputStream() } } catch (e: Exception) { null }
        if (bm == null && legacy.exists()) bm = try { decode { legacy.inputStream() } } catch (e: Exception) { null }
        if (bm == null) bm = try { decode { context.assets.open("skins/default/$n.png") } } catch (e: Exception) { null }
        bm?.setHasMipMap(true)
        return bm
    }

    // ---------- layout ----------
    private fun defaults(l: Boolean): Map<String, FloatArray> = if (!l) mapOf(
        "dpad" to floatArrayOf(.24f, .74f, .44f), "stick" to floatArrayOf(.24f, .74f, .44f),
        "a" to floatArrayOf(.87f, .68f, .20f), "b" to floatArrayOf(.68f, .77f, .20f),
        "l" to floatArrayOf(.16f, .45f, .30f), "r" to floatArrayOf(.84f, .45f, .30f),
        "start" to floatArrayOf(.62f, .93f, .22f), "select" to floatArrayOf(.38f, .93f, .22f),
        "menu" to floatArrayOf(.50f, .50f, .11f),
        "cm" to floatArrayOf(.88f, .52f, .12f), "ta" to floatArrayOf(.68f, .60f, .12f), "tb" to floatArrayOf(.50f, .60f, .12f)
    ) else mapOf(
        "dpad" to floatArrayOf(.13f, .66f, .46f), "stick" to floatArrayOf(.13f, .66f, .46f),
        "a" to floatArrayOf(.93f, .50f, .22f), "b" to floatArrayOf(.82f, .72f, .22f),
        "l" to floatArrayOf(.09f, .14f, .32f), "r" to floatArrayOf(.91f, .14f, .32f),
        "start" to floatArrayOf(.56f, .92f, .20f), "select" to floatArrayOf(.44f, .92f, .20f),
        "menu" to floatArrayOf(.50f, .08f, .12f),
        "cm" to floatArrayOf(.93f, .30f, .12f), "ta" to floatArrayOf(.78f, .46f, .12f), "tb" to floatArrayOf(.68f, .68f, .12f)
    )

    private fun parse(s: String): Map<String, FloatArray> = try {
        s.split(";").filter { it.contains("=") }.associate {
            val kv = it.split("=")
            val p = kv[1].split(",").map { x -> x.toFloat() }
            kv[0] to floatArrayOf(p[0], p[1], p[2])
        }
    } catch (e: Exception) { emptyMap() }

    private fun applyLayout() {
        val d = defaults(land)
        val saved = parse(if (land) layoutLand else layoutPort)
        for ((n, e) in els) { val v = saved[n] ?: d[n]!!; e.cx = v[0]; e.cy = v[1]; e.size = v[2] }
        invalidate()
    }

    private fun save() {
        val s = els.entries.joinToString(";") { "${it.key}=${it.value.cx},${it.value.cy},${it.value.size}" }
        if (land) layoutLand = s else layoutPort = s
        onLayoutChanged()
    }

    fun resetLayout() { if (land) layoutLand = "" else layoutPort = ""; applyLayout(); onLayoutChanged() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { land = w > h; applyLayout() }

    // ---------- geometria ----------
    private fun visible(e: El) = e.name != (if (useStick) "dpad" else "stick") &&
        !(e.name == "cm" && !showCombo) && !(e.name == "ta" && !showTurboA) && !(e.name == "tb" && !showTurboB)

    private fun rect(e: El): RectF {
        val bm = skin[if (e.name == "stick") "stick_base" else e.name]
        val asp = if (bm != null) bm.height.toFloat() / bm.width else 1f
        val w = e.size * min(width, height)
        val h = w * asp
        val cx = e.cx * width; val cy = e.cy * height
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun hit(e: El, x: Float, y: Float): Boolean {
        val r = rect(e); r.inset(-r.width() * 0.06f, -r.height() * 0.06f); return r.contains(x, y)
    }

    // ---------- desenho ----------
    override fun onDraw(c: Canvas) {
        for (e in els.values) {
            if (!visible(e)) continue
            val r = rect(e)
            val a = if (editMode) 1f else if (e.pressed) min(1f, opacity + 0.15f) else opacity
            paint.alpha = (a * 255).toInt()
            c.save()
            if (e.pressed && !editMode) c.scale(0.94f, 0.94f, r.centerX(), r.centerY())
            if (e.name == "stick") {
                skin["stick_base"]?.let { c.drawBitmap(it, null, r, paint) }
                val k = skin["stick_knob"]
                if (k != null) {
                    val kw = r.width() * 0.5f; val kh = kw * k.height / k.width
                    val kx = if (stickPtr >= 0) knobX else r.centerX()
                    val ky = if (stickPtr >= 0) knobY else r.centerY()
                    c.drawBitmap(k, null, RectF(kx - kw / 2, ky - kh / 2, kx + kw / 2, ky + kh / 2), paint)
                }
            } else if (skin[e.name] != null) c.drawBitmap(skin[e.name]!!, null, r, paint)
            else if (e.name in EXTRA) drawExtra(c, e, r, a)
            c.restore()
            if (editMode && e === selected) c.drawRect(r, outline)
        }
        if (editMode) c.drawText("Edição: arraste • pince p/ redimensionar • toque ☰ p/ concluir", width / 2f, 60f, textP)
    }

    private val extraFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val extraLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = T.GOLD }
    private val extraText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    /** Combo/turbo sem PNG: círculo preto com contorno dourado. */
    private fun drawExtra(c: Canvas, e: El, r: RectF, a: Float) {
        val al = (a * 255).toInt()
        extraFill.color = if (e.pressed) T.GOLD else T.CARD; extraFill.alpha = al
        extraLine.color = T.GOLD; extraLine.alpha = al
        c.drawOval(r, extraFill); c.drawOval(r, extraLine)
        extraText.color = if (e.pressed) T.BG else T.GOLD2; extraText.alpha = al; extraText.typeface = T.font ?: Typeface.DEFAULT_BOLD
        extraText.textSize = r.width() * 0.30f
        c.drawText(LABELS[e.name] ?: "", r.centerX(), r.centerY() + extraText.textSize / 3, extraText)
    }

    // ---------- toque ----------
    private fun dirMask(dx: Float, dy: Float, thr: Float): Int {
        var m = 0
        if (dx < -thr) m = m or (1 shl 6)
        if (dx > thr) m = m or (1 shl 7)
        if (dy < -thr) m = m or (1 shl 4)
        if (dy > thr) m = m or (1 shl 5)
        return m
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return editTouch(ev)
        val act = ev.actionMasked
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val i = ev.actionIndex
            if (hit(els["menu"]!!, ev.getX(i), ev.getY(i))) { onMenu(); return true }
        }
        val up = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
        val skip = if (act == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1
        var mask = 0
        els.values.forEach { it.pressed = false }
        var stickSeen = false
        if (!up) for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            val x = ev.getX(i); val y = ev.getY(i); val id = ev.getPointerId(i)
            if (useStick) {
                val s = els["stick"]!!
                if (id == stickPtr || (stickPtr == -1 && hit(s, x, y))) {
                    stickPtr = id; stickSeen = true; s.pressed = true
                    val r = rect(s); val lim = r.width() * 0.30f
                    val dx = x - r.centerX(); val dy = y - r.centerY(); val d = hypot(dx, dy)
                    val k = if (d > lim) lim / d else 1f
                    knobX = r.centerX() + dx * k; knobY = r.centerY() + dy * k
                    mask = mask or dirMask(dx, dy, lim * 0.35f)
                    continue
                }
            } else {
                val d = els["dpad"]!!
                if (hit(d, x, y)) {
                    val r = rect(d); d.pressed = true
                    mask = mask or dirMask(x - r.centerX(), y - r.centerY(), r.width() * 0.17f)
                    continue
                }
            }
            for ((n, bm) in BITS) { val e = els[n]!!; if (visible(e) && hit(e, x, y)) { e.pressed = true; mask = mask or bm } }
        }
        if (!stickSeen) stickPtr = -1
        if (mask and prevMask.inv() != 0 && haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (mask != prevMask) { prevMask = mask; onMask(mask) }
        invalidate()
        return true
    }

    private fun editTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; moved = false
                selected = els.values.filter { visible(it) }.lastOrNull { hit(it, ev.x, ev.y) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2 && selected != null) {
                pinch0 = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)); size0 = selected!!.size; moved = true
            }
            MotionEvent.ACTION_MOVE -> selected?.let { e ->
                if (ev.pointerCount >= 2) {
                    val d = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
                    if (pinch0 > 0) e.size = (size0 * d / pinch0).coerceIn(0.06f, 0.9f)
                } else {
                    e.cx = (e.cx + (ev.x - lastX) / width).coerceIn(0f, 1f)
                    e.cy = (e.cy + (ev.y - lastY) / height).coerceIn(0f, 1f)
                    lastX = ev.x; lastY = ev.y
                    if (hypot(ev.x - downX, ev.y - downY) > 12f) moved = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinch0 = 0f
                val j = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(j); lastY = ev.getY(j)
            }
            MotionEvent.ACTION_UP -> {
                if (selected?.name == "menu" && !moved) onMenu()
                pinch0 = 0f; save()
            }
        }
        invalidate()
        return true
    }

    companion object {
        // máscaras do joypad libretro: B=0 SELECT=2 START=3 A=8 L=10 R=11; extras: combo=A+B, turbo A=16, turbo B=17
        private val BITS = listOf("b" to (1 shl 0), "select" to (1 shl 2), "start" to (1 shl 3), "a" to (1 shl 8), "l" to (1 shl 10), "r" to (1 shl 11),
            "cm" to ((1 shl 0) or (1 shl 8)), "ta" to (1 shl 16), "tb" to (1 shl 17))
        private val EXTRA = setOf("cm", "ta", "tb")
        private val LABELS = mapOf("cm" to "A+B", "ta" to "TA", "tb" to "TB")
    }
}
