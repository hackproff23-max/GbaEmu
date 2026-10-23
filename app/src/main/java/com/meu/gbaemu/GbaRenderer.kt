package com.meu.gbaemu

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Cadeia de shaders em várias passadas.
 * Aceita .glsl (formato RetroArch/libretro, arquivo único com VERTEX/FRAGMENT)
 * e pares .vsh/.fsh. Uniforms e atributos são detectados por nome/tipo.
 */
class GbaRenderer(private val ctx: Context, private val emu: Emulator, private val report: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var shaderKeys: List<String> = emptyList()
    @Volatile var scale = 3
    @Volatile var linear = false
    @Volatile var dirty = true

    private class U(val loc: Int, val name: String, val type: Int)
    private class Pass(val prog: Int, val uniforms: List<U>)

    private var chain: List<Pass> = emptyList()
    private var passthrough: Pass? = null
    private var srcTex = 0
    private var srcW = 0
    private var srcH = 0
    private val vbo = IntArray(2)
    private val fbo = IntArray(8)
    private val fboTex = IntArray(8)
    private val fboW = IntArray(8)
    private val fboH = IntArray(8)
    private var surfW = 1
    private var surfH = 1
    private var frame = 0
    private var frameBuf: ByteBuffer? = null
    private val ident = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
        fbo.fill(0); fboTex.fill(0); fboW.fill(0); fboH.fill(0)
        srcW = 0; srcH = 0; chain = emptyList(); dirty = true
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0); srcTex = t[0]
        GLES20.glGenBuffers(2, vbo, 0)
        upload(vbo[0], floatArrayOf(-1f, 1f, 0f, 0f, -1f, -1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, -1f, 1f, 1f))   // tela
        upload(vbo[1], floatArrayOf(-1f, -1f, 0f, 0f, -1f, 1f, 0f, 1f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 1f))   // FBO (invertido)
        passthrough = try { build(PASS_VS, PASS_FS) } catch (e: Exception) { report("Shader base: ${e.message}"); null }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { surfW = w; surfH = h }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) { dirty = false; rebuild() }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfW, surfH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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
        // retângulo de destino (3:2): topo em retrato, centralizado em paisagem
        val aspect = srcW.toFloat() / srcH
        var dw: Int; var dh: Int; val dx: Int; val dy: Int
        if (surfH >= surfW) { dw = surfW; dh = (dw / aspect).toInt(); dx = 0; dy = surfH - dh }
        else {
            dh = surfH; dw = (dh * aspect).toInt()
            if (dw > surfW) { dw = surfW; dh = (dw / aspect).toInt() }
            dx = (surfW - dw) / 2; dy = (surfH - dh) / 2
        }
        frame++
        var inTex = srcTex; var inW = srcW; var inH = srcH
        val n = chain.size
        val sc = scale.coerceIn(1, 6)
        for (i in 0 until n) {
            val last = i == n - 1
            val p = chain[i]
            val outW: Int; val outH: Int
            if (last) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(dx, dy, dw, dh); outW = dw; outH = dh
            } else {
                outW = srcW * sc; outH = srcH * sc
                ensureFbo(i, outW, outH)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                GLES20.glViewport(0, 0, outW, outH)
            }
            GLES20.glUseProgram(p.prog)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inTex)
            val f = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            setUniforms(p, inW, inH, outW, outH)
            drawQuad(if (last) vbo[0] else vbo[1])
            if (!last) { inTex = fboTex[i]; inW = outW; inH = outH }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun setUniforms(p: Pass, inW: Int, inH: Int, outW: Int, outH: Int) {
        for (u in p.uniforms) {
            val n = u.name.lowercase()
            val isOut = n.contains("output") || n.contains("outsize") || n.contains("viewport")
            when (u.type) {
                GLES20.GL_SAMPLER_2D -> GLES20.glUniform1i(u.loc, 0)
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
    private fun rebuild() {
        chain.filter { it !== passthrough }.forEach { GLES20.glDeleteProgram(it.prog) }
        val list = ArrayList<Pass>()
        for (k in shaderKeys.take(8)) {
            try { list += loadPass(k) } catch (e: Exception) { report("Shader $k: ${e.message}") }
        }
        chain = if (list.isEmpty()) listOfNotNull(passthrough) else list
    }

    private fun loadPass(key: String): Pass {
        val dir = File(ctx.filesDir, "shaders")
        return if (key.endsWith(".glsl", true)) {
            val src = File(dir, key).readText()
            build(prep(src, "VERTEX", false), prep(src, "FRAGMENT", true))
        } else {
            val vs = File(dir, key).readText()
            val fs = File(dir, key.substring(0, key.length - 4) + ".fsh").readText()
            build(prep(vs, null, false), prep(fs, null, true))
        }
    }

    /** Injeta #define VERTEX/FRAGMENT, ajusta #version para ES e garante precisão no fragment. */
    private fun prep(src: String, stage: String?, frag: Boolean): String {
        val lines = src.lines().toMutableList()
        val vi = lines.indexOfFirst { it.trim().startsWith("#version") }
        var header = "#version 100"
        if (vi >= 0) {
            val v = lines[vi].trim()
            header = if (v.contains("es") || v.endsWith(" 100")) v else "#version 100"
            lines.removeAt(vi)
        }
        val sb = StringBuilder(header).append('\n')
        if (stage != null) sb.append("#define $stage 1\n")
        val body = lines.joinToString("\n")
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

    private fun build(vsSrc: String, fsSrc: String): Pass {
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
        return Pass(p, us)
    }

    companion object {
        private const val PASS_VS = "attribute vec2 a_position;attribute vec2 a_texcoord0;varying vec2 v_uv;void main(){gl_Position=vec4(a_position,0.0,1.0);v_uv=a_texcoord0;}"
        private const val PASS_FS = "precision mediump float;varying vec2 v_uv;uniform sampler2D u_tex;void main(){gl_FragColor=texture2D(u_tex,v_uv);}"
    }
}
