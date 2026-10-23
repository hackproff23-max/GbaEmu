package com.meu.gbaemu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.graphics.Bitmap
import android.media.AudioTrack
import android.os.SystemClock
import java.io.File

class Emulator(private val ctx: Context, private val onFrame: () -> Unit) {
    val lock = Any()
    @Volatile var paused = false
    @Volatile var fast = false
    @Volatile var ffMul = 4
    @Volatile var ffAudio = true
    @Volatile var autoEveryMs = 0L
    private var lastAuto = 0L
    private val saveLock = Any()
    @Volatile var volume = 1f
    @Volatile var baseMask = 0
    private var frame = 0L
    @Volatile private var running = false
    @Volatile var loaded = false
        private set
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var key = ""
    private val savesDir: File get() = File(ctx.filesDir, "saves").also { it.mkdirs() }

    /** Retorna null em sucesso, ou a mensagem de erro. */
    fun start(rom: ByteArray, name: String): String? {
        stop()
        val core = File(ctx.applicationInfo.nativeLibraryDir, "libmgba_core.so")
        if (!core.exists()) return "Núcleo mGBA não encontrado no APK (libmgba_core.so)"
        key = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ok = synchronized(lock) { Native.load(core.absolutePath, rom, name, ctx.filesDir.absolutePath) }
        if (!ok) return "Falha ao carregar a ROM"
        File(savesDir, "$key.srm").takeIf { it.exists() }?.let { f -> synchronized(lock) { Native.writeSram(f.readBytes()) } }
        val rate = Native.sampleRate().toInt().coerceAtLeast(8000)
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        running = true; paused = false; loaded = true
        lastAuto = SystemClock.uptimeMillis()
        thread = Thread { loop(t) }.also { it.start() }
        return null
    }

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(16384)
        val acc = ShortArray(16 * 4096)
        t.play()
        while (running) {
            if (paused) { Thread.sleep(20); continue }
            if (!fast) {
                synchronized(lock) { Native.setInput(mixed()); Native.run(); frame++ }
                onFrame()
                val c = Native.audio(buf)
                if (c > 0) {
                    val v = volume
                    if (v < 1f) for (i in 0 until c) buf[i] = (buf[i] * v).toInt().toShort()
                    t.write(buf, 0, c) // bloqueante: dá o ritmo de 60 fps
                }
            } else {
                val n = ffMul.coerceIn(2, 16)
                var total = 0
                synchronized(lock) {
                    repeat(n) {
                        Native.setInput(mixed()); Native.run(); frame++
                        if (ffAudio) {
                            val c = Native.audio(buf) // esvazia o áudio a cada quadro
                            if (c > 0 && total + c <= acc.size) { System.arraycopy(buf, 0, acc, total, c); total += c }
                        } else Native.clearAudio()
                    }
                }
                onFrame()
                if (ffAudio) {
                    // junta n quadros de som em ~1 quadro (média de n amostras): o som acelera junto, mais agudo
                    val pairs = minOf(total / 2 / n, buf.size / 2)
                    if (pairs > 0) {
                        val v = volume
                        for (k in 0 until pairs) {
                            var l = 0; var r = 0
                            for (j in 0 until n) { val ix = (k * n + j) * 2; l += acc[ix]; r += acc[ix + 1] }
                            buf[2 * k] = (l / n * v).toInt().coerceIn(-32768, 32767).toShort()
                            buf[2 * k + 1] = (r / n * v).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        t.write(buf, 0, pairs * 2) // bloqueante: mantém o ritmo (n x velocidade)
                    }
                }
            }
            if (autoEveryMs > 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastAuto >= autoEveryMs) { lastAuto = now; saveState(0) } // slot 0 = auto-save
            }
        }
    }

    /** Aplica turbo A/B (bits 16/17 da máscara) alternando a cada 2 frames. */
    private fun mixed(): Int {
        val m = baseMask; var o = m and 0xFFFF
        val pulse = (frame / 2) % 2 == 0L
        if (m and TURBO_A != 0) o = if (pulse) o or (1 shl 8) else o and (1 shl 8).inv()
        if (m and TURBO_B != 0) o = if (pulse) o or 1 else o and 1.inv()
        return o
    }

    fun reset() { synchronized(lock) { Native.reset() } }

    fun saveSram() {
        if (!loaded) return
        val d = synchronized(lock) { Native.readSram() } ?: return
        File(savesDir, "$key.srm").writeBytes(d)
    }

    fun stateFile(slot: Int) = File(savesDir, "$key.st$slot")
    fun thumbFile(slot: Int) = File(savesDir, "$key.st$slot.jpg")

    /** Foto da tela atual (do quadro bruto do núcleo, sem shader). */
    fun snapshot(): Bitmap? = try {
        synchronized(lock) {
            val sz = Native.frameSize(); val w = sz[0]; val h = sz[1]
            if (w <= 0 || h <= 0) null else {
                val b = Native.frameBuffer() ?: return@synchronized null
                val px = IntArray(w * h)
                for (i in 0 until w * h) {
                    val o = i * 4
                    px[i] = (0xFF shl 24) or ((b.get(o).toInt() and 0xFF) shl 16) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o + 2).toInt() and 0xFF)
                }
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
        }
    } catch (e: Exception) { null }

    fun saveState(slot: Int): Boolean {
        if (!loaded) return false
        val d = synchronized(lock) { Native.saveState() } ?: return false
        val bmp = snapshot()
        synchronized(saveLock) {
            stateFile(slot).writeBytes(d)
            if (bmp != null) try { thumbFile(slot).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } } catch (e: Exception) { }
        }
        return true
    }

    fun loadState(slot: Int): Boolean {
        if (!loaded) return false
        val f = File(savesDir, "$key.st$slot")
        if (!f.exists()) return false
        return synchronized(lock) { Native.loadState(f.readBytes()) }
    }

    fun stop() {
        running = false
        thread?.join(2000); thread = null
        if (loaded) {
            saveSram()
            synchronized(lock) { Native.unload() }
            loaded = false
        }
        track?.release(); track = null
    }

    companion object { const val TURBO_A = 1 shl 16; const val TURBO_B = 1 shl 17 }
}
