package com.meu.gbaemu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

class Emulator(private val ctx: Context, private val onFrame: () -> Unit) {
    val lock = Any()
    @Volatile var paused = false
    @Volatile var fast = false
    @Volatile var ffMul = 4
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
        thread = Thread { loop(t) }.also { it.start() }
        return null
    }

    private fun loop(t: AudioTrack) {
        val buf = ShortArray(16384)
        t.play()
        while (running) {
            if (paused) { Thread.sleep(20); continue }
            val n = if (fast) ffMul else 1
            synchronized(lock) { repeat(n) { Native.setInput(mixed()); Native.run(); frame++ } }
            onFrame()
            if (fast) Native.clearAudio()
            else {
                val c = Native.audio(buf)
                if (c > 0) {
                    val v = volume
                    if (v < 1f) for (i in 0 until c) buf[i] = (buf[i] * v).toInt().toShort()
                    t.write(buf, 0, c) // bloqueante: dá o ritmo de 60 fps
                }
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

    fun saveState(slot: Int): Boolean {
        if (!loaded) return false
        val d = synchronized(lock) { Native.saveState() } ?: return false
        File(savesDir, "$key.st$slot").writeBytes(d)
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
