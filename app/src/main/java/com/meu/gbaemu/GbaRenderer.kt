package com.meu.gbaemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Cadeia de shaders em várias passadas.
 * Aceita .glsl (formato RetroArch/libretro, arquivo único com VERTEX/FRAGMENT), pares .vsh/.fsh
 * e presets multipass .glslp (várias passadas, escala por passada, parâmetros, apelidos e texturas PNG).
 * Uniforms e atributos são detectados por nome/tipo.
 */
class GbaRenderer(private val ctx: Context, private val emu: Emulator, private val report: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var shaderKeys: List<String> = emptyList()
    @Volatile var scale = 3
    @Volatile var linear = false
    @Volatile var dirty = true
    @Volatile var ratio = "Original 3:2"
    @Volatile var rectPort: FloatArray? = null
    @Volatile var rectLand: FloatArray? = null
    @Volatile var bgFile: File? = null
    @Volatile var bgDim = 50
    @Volatile private var bgDirty = true
    // cores e efeitos (0 = original): brilho, contraste e saturação de -100 a 100; fxLcd de 0 a 100
    @Volatile var fxBright = 0
    @Volatile var fxContrast = 0
    @Volatile var fxSat = 0
    @Volatile var fxLcd = 0

    /** Define (ou tira, com null) a imagem de fundo desenhada atrás da tela do jogo. PNG, JPG ou GIF animado. */
    fun setBackground(f: File?) { bgFile = f; bgDirty = true }

    private class U(val loc: Int, val name: String, val type: Int) {
        val n = name.lowercase()
        val isOut = n.contains("output") || n.contains("outsize") || n.contains("viewport")
    }
    /** Como uma passada é dimensionada: orig = tamanho original * escala global (modo antigo); source, viewport, absolute = libretro. */
    private class PassSpec(val file: String, val alias: String = "", val typeX: String = "orig", val typeY: String = "orig",
                           val scaleX: Float = 1f, val scaleY: Float = 1f, val linear: Boolean? = null)
    private class Built(val prog: Int, val uniforms: List<U>)
    private class Pass(val prog: Int, val uniforms: List<U>, val spec: PassSpec, val params: Map<String, Float>)
    private class Ref(val tex: Int, val w: Int, val h: Int)
    private class Lut(val tex: Int, val w: Int, val h: Int, val linear: Boolean, val wrap: String)
    private class LutSpec(val name: String, val file: String, val linear: Boolean, val wrap: String)
    private class Preset(val passes: List<PassSpec>, val luts: List<LutSpec>, val params: Map<String, Float>)

    private val luts = HashMap<String, Lut>()
    private val rePrev = Regex("PassPrev(\\d+)", RegexOption.IGNORE_CASE)
    private val rePass = Regex("Pass(\\d+)", RegexOption.IGNORE_CASE)

    private var chain: List<Pass> = emptyList()
    private var passthrough: Pass? = null
    private var srcTex = 0
    private var srcW = 0
    private var srcH = 0
    private val vbo = IntArray(3)
    private var bgTex = 0
    private var bgW = 0
    private var bgH = 0
    private var bgMovie: Movie? = null
    private var bgBmp: Bitmap? = null
    private var bgCanvas: Canvas? = null
    private var bgScale = 1f
    private var bgStart = 0L
    private var bgLastUpd = 0L
    private var bgQuadW = 0
    private var bgQuadH = 0
    private var fxProg = 0
    private var fxLocAdj = -1
    private var fxLocLcd = -1
    private var fxLocSrc = -1
    private var fxFbo = 0
    private var fxTex = 0
    private var fxW = 0
    private var fxH = 0
    private val fbo = IntArray(16)
    private val fboTex = IntArray(16)
    private val fboW = IntArray(16)
    private val fboH = IntArray(16)
    private var surfW = 1
    private var surfH = 1
    private var frame = 0
    private var frameBuf: ByteBuffer? = null
    private val ident = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
        fbo.fill(0); fboTex.fill(0); fboW.fill(0); fboH.fill(0)
        srcW = 0; srcH = 0; chain = emptyList(); luts.clear(); dirty = true
        bgTex = 0; bgDirty = true; bgQuadW = 0 // o contexto GL foi recriado: texturas antigas não valem mais
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0); srcTex = t[0]
        GLES20.glGenBuffers(3, vbo, 0)
        GLES20.glDisable(GLES20.GL_DITHER); GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST); GLES20.glDisable(GLES20.GL_SCISSOR_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        upload(vbo[0], floatArrayOf(-1f, 1f, 0f, 0f, -1f, -1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, -1f, 1f, 1f))   // tela
        upload(vbo[1], floatArrayOf(-1f, -1f, 0f, 0f, -1f, 1f, 0f, 1f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 1f))   // FBO (invertido)
        passthrough = try { build(PASS_VS, PASS_FS).let { Pass(it.prog, it.uniforms, PassSpec("@pass"), emptyMap()) } } catch (e: Exception) { report("Shader base: ${e.message}"); null }
        fxFbo = 0; fxTex = 0; fxW = 0; fxH = 0
        fxProg = try { build(PASS_VS, FX_FS).prog } catch (e: Exception) { report("Cores e efeitos: ${e.message}"); 0 }
        if (fxProg != 0) {
            fxLocAdj = GLES20.glGetUniformLocation(fxProg, "u_adj"); fxLocLcd = GLES20.glGetUniformLocation(fxProg, "u_lcd"); fxLocSrc = GLES20.glGetUniformLocation(fxProg, "u_src")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { surfW = w; surfH = h }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) { dirty = false; rebuild() }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfW, surfH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawBackground()
        val sz = Native.frameSize()
        val w = sz[0]; val h = sz[1]
        if (w <= 0 || h <= 0 || chain.isEmpty()) return
        if (frameBuf == null) frameBuf = Native.frameBuffer()
        synchronized(emu.lock) {
            val b = frameBuf!!
            b.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            if (w != srcW || h != srcH) {
                srcW = w; srcH = h
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
            }
        }
        // retângulo de destino: proporção escolhida + posição/tamanho do editor de tela (por orientação)
        val land = surfW > surfH
        val aspect = when (ratio) { "4:3" -> 4f / 3; "16:9" -> 16f / 9; else -> srcW.toFloat() / srcH }
        val r = if (land) rectLand else rectPort
        var dw: Int; var dh: Int; var dx: Int; var dy: Int
        if (ratio == "Tela cheia") { dw = surfW; dh = surfH; dx = 0; dy = 0 }
        else if (r != null) {
            dw = (r[2] * surfW).toInt().coerceIn(1, surfW); dh = (dw / aspect).toInt()
            if (dh > surfH) { dh = surfH; dw = (dh * aspect).toInt() }
            dx = (r[0] * (surfW - dw)).toInt(); dy = surfH - dh - (r[1] * (surfH - dh)).toInt()
        } else if (!land) { dw = surfW; dh = (dw / aspect).toInt(); dx = 0; dy = surfH - dh }
        else {
            dh = surfH; dw = (dh * aspect).toInt()
            if (dw > surfW) { dw = surfW; dh = (dw / aspect).toInt() }
            dx = (surfW - dw) / 2; dy = (surfH - dh) / 2
        }
        frame++
        val orig = Ref(srcTex, srcW, srcH)
        var cur = orig
        val n = chain.size
        val sc = scale.coerceIn(1, 6)
        val outs = arrayOfNulls<Ref>(n)
        // Cores e efeitos: só gasta uma passada extra quando algum valor é diferente de 0
        val fx = fxProg != 0 && (fxBright != 0 || fxContrast != 0 || fxSat != 0 || fxLcd != 0)
        for (i in 0 until n) {
            val last = i == n - 1
            val p = chain[i]; val spc = p.spec
            val outW: Int; val outH: Int
            if (last) {
                if (fx) { ensureFxFbo(dw, dh); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fxFbo); GLES20.glViewport(0, 0, dw, dh); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) }
                else { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(dx, dy, dw, dh) }
                outW = dw; outH = dh
            } else {
                outW = outDim(spc.typeX, spc.scaleX, cur.w, dw, srcW, sc)
                outH = outDim(spc.typeY, spc.scaleY, cur.h, dh, srcH, sc)
                ensureFbo(i, outW, outH)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                GLES20.glViewport(0, 0, outW, outH)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) // conteúdo antigo não importa: poupa leitura de memória na GPU
            }
            GLES20.glUseProgram(p.prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cur.tex)
            texParams(spc.linear ?: linear, "clamp")
            setUniforms(p, i, cur, orig, outs, outW, outH)
            drawQuad(if (last && !fx) vbo[0] else vbo[1])
            if (!last) { cur = Ref(fboTex[i], outW, outH); outs[i] = cur }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (fx) {
            GLES20.glViewport(dx, dy, dw, dh)
            GLES20.glUseProgram(fxProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fxTex)
            texParams(false, "clamp")
            GLES20.glUniform3f(fxLocAdj, fxBright / 100f, fxContrast / 100f, fxSat / 100f)
            GLES20.glUniform1f(fxLocLcd, fxLcd / 100f)
            GLES20.glUniform2f(fxLocSrc, srcW.toFloat(), srcH.toFloat())
            drawQuad(vbo[0])
        }
    }

    private fun ensureFxFbo(w: Int, h: Int) {
        val t = IntArray(1)
        if (fxFbo == 0) {
            GLES20.glGenFramebuffers(1, t, 0); fxFbo = t[0]
            GLES20.glGenTextures(1, t, 0); fxTex = t[0]
        }
        if (fxW != w || fxH != h) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fxTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            texParams(false, "clamp")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fxFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fxTex, 0)
            fxW = w; fxH = h
        }
    }

    // ---------- fundo (imagem / GIF) ----------
    private fun paintMovieFrame(mv: Movie) {
        val cv = bgCanvas ?: return
        val dur = mv.duration().coerceAtLeast(1)
        mv.setTime(((SystemClock.uptimeMillis() - bgStart) % dur).toInt())
        cv.drawColor(Color.BLACK)
        cv.save(); cv.scale(bgScale, bgScale); mv.draw(cv, 0f, 0f); cv.restore()
        cv.drawColor(Color.argb(bgDim.coerceIn(0, 95) * 255 / 100, 0, 0, 0))
    }

    private fun loadBackground() {
        if (bgTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(bgTex), 0); bgTex = 0 }
        bgMovie = null; bgBmp?.recycle(); bgBmp = null; bgCanvas = null; bgW = 0; bgH = 0; bgQuadW = 0
        val f = bgFile ?: return
        if (!f.exists()) return
        try {
            val head = ByteArray(3)
            f.inputStream().use { it.read(head) }
            val bmp: Bitmap
            if (String(head, Charsets.ISO_8859_1) == "GIF") {
                val mv = Movie.decodeFile(f.path)
                if (mv == null || mv.width() <= 0 || mv.height() <= 0) throw RuntimeException("GIF inválido")
                bgScale = minOf(1f, 512f / maxOf(mv.width(), mv.height()))
                bmp = Bitmap.createBitmap((mv.width() * bgScale).toInt().coerceAtLeast(1), (mv.height() * bgScale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bgCanvas = Canvas(bmp)
                bgStart = SystemClock.uptimeMillis()
                paintMovieFrame(mv)
                if (mv.duration() > 0) bgMovie = mv
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > 2048) s *= 2
                val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: throw RuntimeException("imagem inválida")
                bmp = if (raw.isMutable) raw else raw.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(bmp).drawColor(Color.argb(bgDim.coerceIn(0, 95) * 255 / 100, 0, 0, 0))
            }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0); bgTex = t[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
            texParams(true, "clamp")
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bgW = bmp.width; bgH = bmp.height
            if (bgMovie != null) bgBmp = bmp else bmp.recycle()
        } catch (e: Exception) {
            report("Fundo: ${e.message}")
            if (bgTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(bgTex), 0); bgTex = 0 }
            bgMovie = null; bgW = 0; bgH = 0
        }
    }

    private fun drawBackground() {
        if (bgDirty) { bgDirty = false; loadBackground() }
        if (bgTex == 0 || bgW <= 0 || bgH <= 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
        val mv = bgMovie; val bm = bgBmp
        if (mv != null && bm != null) {
            val now = SystemClock.uptimeMillis()
            if (now - bgLastUpd >= 40) { // GIF: ~25 quadros por segundo no máximo
                bgLastUpd = now
                paintMovieFrame(mv)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bm)
            }
        }
        val pt = passthrough ?: return
        if (bgQuadW != surfW || bgQuadH != surfH) {
            bgQuadW = surfW; bgQuadH = surfH
            // corte central ("cover"): a imagem preenche a tela inteira sem esticar
            val sa = surfW.toFloat() / surfH; val ia = bgW.toFloat() / bgH
            var u0 = 0f; var v0 = 0f
            if (ia > sa) u0 = (1f - sa / ia) / 2f else v0 = (1f - ia / sa) / 2f
            val u1 = 1f - u0; val v1 = 1f - v0
            upload(vbo[2], floatArrayOf(-1f, 1f, u0, v0, -1f, -1f, u0, v1, 1f, 1f, u1, v0, 1f, -1f, u1, v1))
        }
        GLES20.glUseProgram(pt.prog)
        texParams(true, "clamp")
        drawQuad(vbo[2])
    }

    private fun outDim(type: String, s: Float, inD: Int, vpD: Int, origD: Int, sc: Int): Int = when (type) {
        "viewport" -> (vpD * s).toInt()
        "absolute" -> s.toInt()
        "orig" -> origD * sc
        else -> (inD * s).toInt()
    }.coerceIn(1, 4096)

    private fun texParams(l: Boolean, wrap: String) {
        val f = if (l) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        val w = when (wrap) { "repeat" -> GLES20.GL_REPEAT; "mirrored_repeat" -> GLES20.GL_MIRRORED_REPEAT; else -> GLES20.GL_CLAMP_TO_EDGE }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, w)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, w)
    }

    /** Textura citada por nome: Orig, PassPrevN, PassN, apelido da passada ou textura PNG do preset. */
    private fun refFor(base: String, idx: Int, orig: Ref, outs: Array<Ref?>): Ref? {
        if (base.isEmpty()) return null
        if (base.equals("Orig", true) || base.equals("Original", true)) return orig
        if (base.startsWith("Pass", true)) {
            rePrev.matchEntire(base)?.let { m -> val j = idx - m.groupValues[1].toInt(); return if (j >= 0) outs[j] else orig }
            rePass.matchEntire(base)?.let { m -> val j = m.groupValues[1].toInt() - 1; return if (j in 0 until idx) outs[j] else null }
        }
        luts[base]?.let { return Ref(it.tex, it.w, it.h) }
        val a = chain.indexOfFirst { it.spec.alias.isNotEmpty() && it.spec.alias == base }
        if (a in 0 until idx) return outs[a]
        return null
    }

    private fun sizeRefFor(nm: String, idx: Int, orig: Ref, outs: Array<Ref?>): Ref? {
        val base = when {
            nm.endsWith("TextureSize") -> nm.removeSuffix("TextureSize")
            nm.endsWith("InputSize") -> nm.removeSuffix("InputSize")
            nm.endsWith("OutputSize") -> return null
            nm.endsWith("Size") -> nm.removeSuffix("Size")
            nm.endsWith("_size") -> nm.removeSuffix("_size")
            else -> return null
        }
        return refFor(base, idx, orig, outs)
    }

    private fun setUniforms(p: Pass, idx: Int, cur: Ref, orig: Ref, outs: Array<Ref?>, outW: Int, outH: Int) {
        var unit = 1
        val lin = p.spec.linear ?: linear
        val inW = cur.w; val inH = cur.h
        for (u in p.uniforms) {
            val nm = u.name; val n = u.n; val isOut = u.isOut
            if (u.type == GLES20.GL_SAMPLER_2D) {
                val lut = luts[nm]
                val ref = if (lut != null) Ref(lut.tex, lut.w, lut.h)
                    else if (nm.length > 7 && nm.endsWith("Texture")) refFor(nm.removeSuffix("Texture"), idx, orig, outs) else null
                if (ref == null || unit > 7) GLES20.glUniform1i(u.loc, 0)
                else {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ref.tex)
                    if (lut != null) texParams(lut.linear, lut.wrap) else texParams(lin, "clamp")
                    GLES20.glUniform1i(u.loc, unit); unit++
                }
                continue
            }
            val pv = if (u.type == GLES20.GL_FLOAT) p.params[nm] else null
            if (pv != null) { GLES20.glUniform1f(u.loc, pv); continue }
            if (u.type == GLES20.GL_FLOAT_VEC2 || u.type == GLES20.GL_FLOAT_VEC4) {
                val r = sizeRefFor(nm, idx, orig, outs)
                if (r != null) {
                    if (u.type == GLES20.GL_FLOAT_VEC2) GLES20.glUniform2f(u.loc, r.w.toFloat(), r.h.toFloat())
                    else GLES20.glUniform4f(u.loc, r.w.toFloat(), r.h.toFloat(), 1f / r.w, 1f / r.h)
                    continue
                }
            }
            when (u.type) {
                GLES20.GL_FLOAT_MAT4 -> GLES20.glUniformMatrix4fv(u.loc, 1, false, ident, 0)
                GLES20.GL_INT -> when {
                    n.contains("framecount") || n.contains("frame_count") -> GLES20.glUniform1i(u.loc, frame)
                    n.contains("direction") -> GLES20.glUniform1i(u.loc, 1)
                }
                GLES20.GL_FLOAT -> when {
                    n.contains("framecount") || n.contains("frame_count") || n.contains("time") -> GLES20.glUniform1f(u.loc, frame.toFloat())
                    n.contains("direction") -> GLES20.glUniform1f(u.loc, 1f)
                }
                GLES20.GL_FLOAT_VEC2 -> when {
                    n.contains("pixel") && n.contains("delta") -> GLES20.glUniform2f(u.loc, 1f / outW, 1f / outH)
                    n.contains("delta") || n.contains("texel") -> GLES20.glUniform2f(u.loc, 1f / inW, 1f / inH)
                    isOut -> GLES20.glUniform2f(u.loc, outW.toFloat(), outH.toFloat())
                    n.contains("size") || n.contains("texture") || n.contains("input") -> GLES20.glUniform2f(u.loc, inW.toFloat(), inH.toFloat())
                }
                GLES20.GL_FLOAT_VEC4 -> if (n.contains("size")) {
                    val w = if (isOut) outW else inW; val h = if (isOut) outH else inH
                    GLES20.glUniform4f(u.loc, w.toFloat(), h.toFloat(), 1f / w, 1f / h)
                }
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawQuad(vb: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vb)
        GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(1); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ensureFbo(i: Int, w: Int, h: Int) {
        val t = IntArray(1)
        if (fbo[i] == 0) {
            GLES20.glGenFramebuffers(1, t, 0); fbo[i] = t[0]
            GLES20.glGenTextures(1, t, 0); fboTex[i] = t[0]
        }
        if (fboW[i] != w || fboH[i] != h) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
            fboW[i] = w; fboH[i] = h
        }
    }

    private fun upload(buf: Int, data: FloatArray) {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buf)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
    }

    // ---------- carregamento de shaders ----------
    private fun shaderDir() = File(ctx.filesDir, "shaders")

    private fun rebuild() {
        chain.filter { it !== passthrough }.forEach { GLES20.glDeleteProgram(it.prog) }
        luts.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0) }; luts.clear()
        val list = ArrayList<Pass>()
        for (k in shaderKeys) {
            if (list.size >= 16) break
            val tmp = ArrayList<Pass>()
            try {
                if (k.endsWith(".glslp", true)) {
                    val pre = parsePreset(k)
                    for (l in pre.luts) { try { luts[l.name] = loadLut(l) } catch (e: Exception) { report("Textura ${l.name}: ${e.message}") } }
                    for (s in pre.passes) tmp += loadPass(s, pre.params)
                } else tmp += loadPass(PassSpec(k), emptyMap())
                list += tmp
            } catch (e: Exception) {
                tmp.forEach { GLES20.glDeleteProgram(it.prog) } // preset incompleto não entra na cadeia
                report("Shader $k: ${e.message}")
            }
        }
        chain = if (list.isEmpty()) listOfNotNull(passthrough) else list.take(16)
    }

    /** Lê um preset .glslp (chave = valor). Os arquivos citados são procurados pelo nome, na pasta de shaders importados. */
    private fun parsePreset(key: String): Preset {
        val kv = HashMap<String, String>()
        File(shaderDir(), key).readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val i = line.indexOf('='); if (i <= 0) return@forEach
            kv[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"').trim()
        }
        val n = kv["shaders"]?.toIntOrNull() ?: throw RuntimeException("preset sem a linha \"shaders = N\"")
        fun bool(s: String?) = s?.let { it == "true" || it == "1" }
        val passes = (0 until n.coerceAtMost(16)).map { i ->
            val f = kv["shader$i"] ?: throw RuntimeException("falta shader$i no preset")
            val st = kv["scale_type$i"]
            val stx = kv["scale_type_x$i"] ?: st; val sty = kv["scale_type_y$i"] ?: st
            val sc = kv["scale$i"]?.toFloatOrNull()
            val dflt = if (i == n - 1) "viewport" else "source"
            PassSpec(
                file = f.substringAfterLast('/').substringAfterLast('\\'), alias = kv["alias$i"] ?: "",
                typeX = stx ?: dflt, typeY = sty ?: dflt,
                scaleX = kv["scale_x$i"]?.toFloatOrNull() ?: sc ?: 1f, scaleY = kv["scale_y$i"]?.toFloatOrNull() ?: sc ?: 1f,
                linear = bool(kv["filter_linear$i"])
            )
        }
        val lutSpecs = (kv["textures"] ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() && kv.containsKey(it) }.map { nm ->
            LutSpec(nm, kv.getValue(nm).substringAfterLast('/').substringAfterLast('\\'), bool(kv["${nm}_linear"]) ?: true, kv["${nm}_wrap_mode"] ?: "clamp_to_edge")
        }
        val params = HashMap<String, Float>()
        (kv["parameters"] ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { nm -> kv[nm]?.toFloatOrNull()?.let { params[nm] = it } }
        return Preset(passes, lutSpecs, params)
    }

    private fun loadLut(l: LutSpec): Lut {
        val f = File(shaderDir(), l.file)
        if (!f.exists()) throw RuntimeException("arquivo ${l.file} não foi importado")
        val bmp = BitmapFactory.decodeFile(f.path) ?: throw RuntimeException("imagem inválida")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        return Lut(t[0], w, h, l.linear, l.wrap)
    }

    private val paramRe = Regex("""#pragma\s+parameter\s+(\w+)\s+"[^"]*"\s+(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)""")
    private fun pragmaParams(src: String): Map<String, Float> =
        paramRe.findAll(src).associate { it.groupValues[1] to (it.groupValues[2].toFloatOrNull() ?: 0f) }

    private fun loadPass(s: PassSpec, preset: Map<String, Float>): Pass {
        val dir = shaderDir()
        val f = File(dir, s.file)
        if (!f.exists()) throw RuntimeException("arquivo ${s.file} não foi importado")
        val defaults: Map<String, Float>
        val make: (Map<String, Float>) -> Built
        if (s.file.endsWith(".glsl", true)) {
            val src = f.readText()
            defaults = pragmaParams(src)
            make = { pm -> build(prep(src, "VERTEX", false, pm), prep(src, "FRAGMENT", true, pm)) }
        } else {
            val vs = f.readText()
            val fsFile = File(dir, s.file.substring(0, s.file.length - 4) + ".fsh")
            if (!fsFile.exists()) throw RuntimeException("falta ${fsFile.name}")
            val fs = fsFile.readText()
            defaults = pragmaParams(vs) + pragmaParams(fs)
            make = { pm -> build(prep(vs, null, false, pm), prep(fs, null, true, pm)) }
        }
        val all = defaults + preset
        // Os parâmetros não mudam durante o jogo: viram constantes no código e a GPU simplifica as contas.
        // Se por algum motivo não compilar assim, volta ao modo antigo (uniforms).
        val b = try { make(all) } catch (e: Exception) { make(emptyMap()) }
        return Pass(b.prog, b.uniforms, s, all)
    }

    /** Troca "uniform float NOME;" por "const float NOME = valor;" nos parâmetros conhecidos (mesmo valor que seria enviado à GPU). */
    private fun bakeParams(src: String, params: Map<String, Float>): String {
        var out = src
        for ((nm, v) in params) {
            if (!v.isFinite()) continue
            val re = Regex("""uniform\s+((?:lowp|mediump|highp|COMPAT_PRECISION)\s+)?float\s+${Regex.escape(nm)}\s*;""")
            out = re.replace(out) { m -> "const " + m.groupValues[1] + "float " + nm + " = " + v.toString() + ";" }
        }
        return out
    }

    /** Injeta #define VERTEX/FRAGMENT, ajusta #version para ES e garante precisão no fragment. */
    private fun prep(src: String, stage: String?, frag: Boolean, pm: Map<String, Float> = emptyMap()): String {
        val lines = src.lines().toMutableList()
        val vi = lines.indexOfFirst { it.trim().startsWith("#version") }
        var header = "#version 100"
        if (vi >= 0) {
            val v = lines[vi].trim()
            header = if (v.contains("es") || v.endsWith(" 100")) v else "#version 100"
            lines.removeAt(vi)
        }
        val sb = StringBuilder(header).append('\n')
        sb.append("#define PARAMETER_UNIFORM 1\n")
        if (stage != null) sb.append("#define $stage 1\n")
        val body = bakeParams(lines.joinToString("\n"), pm)
        if (frag && !body.contains("precision")) sb.append("#ifdef GL_ES\nprecision mediump float;\n#endif\n")
        return sb.append(body).toString()
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw RuntimeException(log.take(300)) }
        return s
    }

    private fun link(p: Int) {
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES20.glGetProgramInfoLog(p).take(300))
    }

    private fun build(vsSrc: String, fsSrc: String): Built {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = try { compile(GLES20.GL_FRAGMENT_SHADER, fsSrc) } catch (e: Exception) { GLES20.glDeleteShader(vs); throw e }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        link(p)
        val cnt = IntArray(1); val sz = IntArray(1); val tp = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_ACTIVE_ATTRIBUTES, cnt, 0)
        var extra = 3
        for (i in 0 until cnt[0]) {
            val name = GLES20.glGetActiveAttrib(p, i, sz, 0, tp, 0)
            val l = name.lowercase()
            val loc = when {
                l.contains("vertex") || l.contains("pos") -> 0
                l.contains("tex") || l.contains("coord") || l.contains("uv") -> 1
                l.contains("color") -> 2
                else -> extra++
            }
            GLES20.glBindAttribLocation(p, loc, name)
        }
        link(p)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        GLES20.glGetProgramiv(p, GLES20.GL_ACTIVE_UNIFORMS, cnt, 0)
        val us = ArrayList<U>()
        for (i in 0 until cnt[0]) {
            val name = GLES20.glGetActiveUniform(p, i, sz, 0, tp, 0)
            val loc = GLES20.glGetUniformLocation(p, name)
            if (loc >= 0) us += U(loc, name.substringBefore('['), tp[0])
        }
        return Built(p, us)
    }

    companion object {
        private const val PASS_VS = "attribute vec2 a_position;attribute vec2 a_texcoord0;varying vec2 v_uv;void main(){gl_Position=vec4(a_position,0.0,1.0);v_uv=a_texcoord0;}"
        // Cores e efeitos: contraste, brilho e saturação (0 = neutro) e grade LCD entre os pixels do jogo (u_lcd = opacidade, 0 = desligada)
        private const val FX_FS = "precision mediump float;varying vec2 v_uv;uniform sampler2D u_tex;uniform vec3 u_adj;uniform float u_lcd;uniform vec2 u_src;" +
            "void main(){vec3 c=texture2D(u_tex,v_uv).rgb;" +
            "c=(c-0.5)*(1.0+u_adj.y)+0.5;c+=u_adj.x*0.4;" +
            "float l=dot(c,vec3(0.299,0.587,0.114));c=mix(vec3(l),c,1.0+u_adj.z);" +
            "if(u_lcd>0.0){vec2 f=fract(v_uv*u_src);vec2 e=min(f,1.0-f);float m=smoothstep(0.0,0.22,min(e.x,e.y));c*=mix(1.0-u_lcd,1.0,m);}" +
            "gl_FragColor=vec4(clamp(c,0.0,1.0),1.0);}"
        private const val PASS_FS = "precision mediump float;varying vec2 v_uv;uniform sampler2D u_tex;void main(){gl_FragColor=texture2D(u_tex,v_uv);}"
    }
}
