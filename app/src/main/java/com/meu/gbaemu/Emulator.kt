package com.meu.gbaemu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.graphics.Bitmap
import android.media.AudioTrack
import android.os.SystemClock
import com.meu.gbaemu.ui.Cheat
import com.meu.gbaemu.ui.Store
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class Emulator(private val ctx: Context, private val onFrame: () -> Unit) {
    val lock = Any()
    @Volatile var paused = false
    @Volatile var fast = false
    @Volatile var ffMul = 4
    @Volatile var ffAudio = true
    @Volatile var autoEveryMs = 0L
    private var lastAuto = 0L
    // 5 auto-saves temporários em rodízio (slots 10..14), um a cada AUTO_RING_MS de jogo rodando
    private var ringAccum = 0L
    private var ringTick = 0L
    private var io = Executors.newSingleThreadExecutor()
    // cheats: só linhas válidas, aplicadas de forma adiada e nunca no meio de um carregamento de estado
    @Volatile private var cheatCodes: List<String> = emptyList()
    @Volatile private var cheatPending = false
    @Volatile private var cheatDelay = 0
    @Volatile var skippedCheatLines = 0
    /** Avisa a tela para marcar/desmarcar o "guarda de cheats" (se o app morrer com ele marcado, os cheats são desligados na próxima vez). */
    @Volatile var onCheatArm: ((Boolean) -> Unit)? = null
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
    private var base = "jogo"
    private var lastSram: ByteArray? = null
    private var lastSramMs = 0L
    // tudo fica em EmuladorPro: Saves (.sav) e SaveStates
    private val savesDir: File get() = Store.dir(ctx, "saves")
    private val statesDir: File get() = Store.dir(ctx, "states")
    fun saveFile() = File(savesDir, "$base.sav")

    /** Traz para EmuladorPro os saves e estados da versão antiga (pasta interna do app), sem apagar nada. */
    private fun migrateLegacy() {
        val old = File(ctx.filesDir, "saves")
        if (!old.isDirectory) return
        val pairs = ArrayList<Pair<File, File>>()
        pairs.add(File(old, "$key.srm") to saveFile())
        for (s in 0..5) {
            pairs.add(File(old, "$key.st$s") to stateFile(s))
            pairs.add(File(old, "$key.st$s.jpg") to thumbFile(s))
        }
        for ((a, b) in pairs) if (a.exists() && !b.exists()) try { a.copyTo(b) } catch (e: Exception) { }
    }

    /** Retorna null em sucesso, ou a mensagem de erro. */
    fun start(rom: ByteArray, name: String, beforeRun: (() -> Unit)? = null): String? {
        stop()
        val core = File(ctx.applicationInfo.nativeLibraryDir, "libmgba_core.so")
        if (!core.exists()) return "Núcleo mGBA não encontrado no APK (libmgba_core.so)"
        key = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        base = Store.base(name); lastSram = null
        migrateLegacy()
        val ok = synchronized(lock) { Native.load(core.absolutePath, rom, name, ctx.filesDir.absolutePath) }
        if (!ok) return "Falha ao carregar a ROM"
        (saveFile().takeIf { it.exists() } ?: File(savesDir, "$base.srm").takeIf { it.exists() })
            ?.let { f -> synchronized(lock) { Native.writeSram(f.readBytes()) } }
        val rate = Native.sampleRate().toInt().coerceAtLeast(8000)
        val t = buildTrack(rate)
        track = t
        loaded = true
        // cheats e retomada acontecem ANTES da thread do jogo começar: nada roda sobre um estado pela metade
        try { beforeRun?.invoke() } catch (e: Throwable) { }
        running = true; paused = false
        lastAuto = SystemClock.uptimeMillis()
        ringAccum = 0L; ringTick = lastAuto
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()
        thread = Thread({ guardedLoop(t) }, "emu-loop").also { it.start() }
        return null
    }

    /** Uma exceção na thread do jogo não pode derrubar o app: o jogo para, mas o menu continua vivo. */
    private fun guardedLoop(t: AudioTrack) {
        try { loop(t) } catch (e: Throwable) { running = false; onFatal?.invoke(e.message ?: e.javaClass.simpleName) }
    }

    @Volatile var onFatal: ((String) -> Unit)? = null

    /** Escreve áudio; se o sistema derrubou a saída (fone/bluetooth/serviço de áudio), recria a faixa em vez de correr sem freio. */
    private fun writeAudio(t: AudioTrack, b: ShortArray, n: Int): AudioTrack {
        val r = try { t.write(b, 0, n) } catch (e: Exception) { -1 }
        if (r >= 0 || !running) return t
        return try {
            try { t.release() } catch (e: Exception) { }
            val nt = buildTrack(Native.sampleRate().toInt().coerceAtLeast(8000))
            track = nt; nt.play(); nt
        } catch (e: Exception) { Thread.sleep(16); t }
    }

    private fun buildTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    private fun loop(first: AudioTrack) {
        var t = first
        val buf = ShortArray(16384)
        val acc = ShortArray(16 * 4096)
        t.play()
        while (running) {
            if (paused) { Thread.sleep(20); ringTick = SystemClock.uptimeMillis(); continue }
            if (!fast) {
                synchronized(lock) { Native.setInput(mixed()); Native.run(); frame++ }
                onFrame()
                val c = Native.audio(buf)
                if (c > 0) {
                    val v = volume
                    if (v < 1f) for (i in 0 until c) buf[i] = (buf[i] * v).toInt().toShort()
                    t = writeAudio(t, buf, c) // bloqueante: dá o ritmo de 60 fps
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
                        t = writeAudio(t, buf, pairs * 2) // bloqueante: mantém o ritmo (n x velocidade)
                    }
                }
            }
            if (cheatPending && --cheatDelay <= 0) { cheatPending = false; applyCheatsNow() }
            if (autoEveryMs > 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastAuto >= autoEveryMs) { lastAuto = now; saveState(0) } // slot 0 = auto-save
            }
            val t0 = SystemClock.uptimeMillis()
            // auto-saves em rodízio: só conta o tempo em que o jogo está de fato rodando (pausa não conta)
            ringAccum += (t0 - ringTick).coerceIn(0L, 250L); ringTick = t0
            if (ringAccum >= AUTO_RING_MS) { ringAccum = 0L; autoRingSave() }
            if (t0 - lastSramMs >= 15000) { lastSramMs = t0; saveSram() } // grava o .sav sozinho se mudou
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

    fun reset() {
        synchronized(lock) { Native.cheatReset(); Native.reset() }
        scheduleCheats()
    }

    private val lineRe = Regex("^[0-9A-F]{8} [0-9A-F]{8}$")

    /** Define quais cheats devem valer. Nada vai ao núcleo na hora: só depois de ~2 s de jogo rodando (o núcleo já assentou). */
    fun applyCheats(list: List<Cheat>) {
        if (!loaded) return
        var skipped = 0
        val lines = ArrayList<String>()
        for (c in list) if (c.on) for (raw in c.code.split('+')) {
            val l = raw.trim().uppercase()
            if (l.isBlank()) continue
            if (lineRe.matches(l)) lines.add(l) else skipped++ // linhas de 12 dígitos (CodeBreaker) o mGBA não entende: ignora em vez de arriscar
        }
        skippedCheatLines = skipped
        cheatCodes = lines
        if (lines.isEmpty()) { cheatPending = false; synchronized(lock) { try { Native.cheatReset() } catch (e: Throwable) { } }; onCheatArm?.invoke(false) }
        else scheduleCheats()
    }

    private fun scheduleCheats() { if (cheatCodes.isNotEmpty()) { cheatDelay = CHEAT_DELAY_FRAMES; cheatPending = true } }

    private fun applyCheatsNow() {
        val lines = cheatCodes
        onCheatArm?.invoke(true) // marca ANTES de mexer no núcleo
        synchronized(lock) {
            try {
                Native.cheatReset()
                var i = 0
                for (l in lines) Native.cheatSet(i++, true, l)
            } catch (e: Throwable) { cheatCodes = emptyList() }
        }
    }

    /** Liga/desliga o guarda ao entrar/sair do app (quem chamar decide). */
    fun armGuard(on: Boolean) { if (!on || cheatCodes.isNotEmpty()) onCheatArm?.invoke(on) }

    fun saveSram() {
        if (!loaded) return
        val d = synchronized(lock) { Native.readSram() } ?: return
        if (lastSram?.contentEquals(d) == true) return
        try {
            val f = saveFile(); val tmp = File(f.path + ".tmp")
            tmp.writeBytes(d)
            if (!tmp.renameTo(f)) { f.writeBytes(d); tmp.delete() }
            lastSram = d
        } catch (e: Exception) { }
    }

    fun stateFile(slot: Int) = File(statesDir, "$base.st$slot")
    fun thumbFile(slot: Int) = File(statesDir, "$base.st$slot.jpg")

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

    // ---------- arquivos de estado: gravação atômica + conferência de integridade ----------
    // Um estado gravado pela metade (app fechado no meio da escrita) e entregue ao núcleo derruba o app inteiro.
    // Por isso: grava em arquivo temporário e renomeia, com um selo (CRC) no fim; ao ler, estado sem selo válido é descartado.
    private fun sealed(d: ByteArray): ByteArray {
        val c = CRC32().also { it.update(d) }.value
        val o = ByteArray(d.size + 8)
        System.arraycopy(d, 0, o, 0, d.size)
        o[d.size] = 'G'.code.toByte(); o[d.size + 1] = 'S'.code.toByte(); o[d.size + 2] = 'V'.code.toByte(); o[d.size + 3] = '1'.code.toByte()
        for (i in 0..3) o[d.size + 4 + i] = (c shr (8 * i)).toByte()
        return o
    }

    /** Devolve os bytes do estado já conferidos, ou null se o arquivo não existe / está corrompido (nesse caso ele é apagado). */
    private fun readSealed(f: File): ByteArray? {
        if (!f.exists()) return null
        return try {
            val all = f.readBytes()
            val n = all.size
            val hasSeal = n > 8 && all[n - 8] == 'G'.code.toByte() && all[n - 7] == 'S'.code.toByte() && all[n - 6] == 'V'.code.toByte() && all[n - 5] == '1'.code.toByte()
            if (hasSeal) {
                var want = 0L
                for (i in 0..3) want = want or ((all[n - 4 + i].toLong() and 0xFF) shl (8 * i))
                val payload = all.copyOf(n - 8)
                val got = CRC32().also { it.update(payload) }.value
                if (got != want) { f.delete(); null } else payload
            } else if (n > 30_000) all // estado antigo (sem selo): aceita só se o tamanho for plausível
            else { f.delete(); null }
        } catch (e: Exception) { null }
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.writeBytes(data); tmp.delete() }
    }

    private fun writeThumb(slot: Int, bmp: Bitmap?) {
        if (bmp == null) return
        try {
            val t = thumbFile(slot); val tmp = File(t.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (!tmp.renameTo(t)) { tmp.copyTo(t, true); tmp.delete() }
        } catch (e: Exception) { }
    }

    fun saveState(slot: Int): Boolean {
        if (!loaded) return false
        val d = synchronized(lock) { Native.saveState() } ?: return false
        val bmp = snapshot()
        return try {
            synchronized(saveLock) { writeAtomic(stateFile(slot), sealed(d)); writeThumb(slot, bmp) }
            true
        } catch (e: Exception) { false }
    }

    fun loadState(slot: Int): Boolean {
        if (!loaded) return false
        val d = readSealed(stateFile(slot)) ?: return false
        return unserializeSafely(d)
    }

    /** Carrega o estado com os cheats DESLIGADOS no núcleo; eles voltam pouco depois, já com o jogo rodando. */
    private fun unserializeSafely(d: ByteArray): Boolean {
        val ok = try { synchronized(lock) { try { Native.cheatReset() } catch (e: Throwable) { }; Native.loadState(d) } } catch (e: Throwable) { false }
        scheduleCheats()
        return ok
    }

    // ---------- 5 auto-saves temporários (slots 10..14), um a cada 3 minutos, em rodízio ----------
    /** Escolhe o próximo slot do rodízio: primeiro os vazios, depois o mais antigo. */
    private fun nextRingSlot(): Int {
        var best = AUTO_FIRST; var bestT = Long.MAX_VALUE
        for (s in AUTO_FIRST until AUTO_FIRST + AUTO_COUNT) {
            val f = stateFile(s)
            val t = if (f.exists()) f.lastModified() else -1L
            if (t < bestT) { bestT = t; best = s }
        }
        return best
    }

    /** Roda na thread do jogo: tira a foto do estado (rápido, sob o lock) e entrega a gravação em disco a outra thread, sem engasgar o jogo. */
    private fun autoRingSave() {
        if (!loaded) return
        val d = synchronized(lock) { Native.saveState() } ?: return
        val bmp = snapshot()
        val slot = nextRingSlot()
        val bytes = sealed(d)
        try {
            io.execute {
                try { synchronized(saveLock) { writeAtomic(stateFile(slot), bytes); writeThumb(slot, bmp) } } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    /** Ponto de retomada: estado guardado ao sair do app, usado só se o sistema fechar o jogo enquanto você estava fora. */
    fun resumeFile() = File(statesDir, "$base.resume")

    fun saveResume(): Boolean {
        if (!loaded) return false
        val d = synchronized(lock) { Native.saveState() } ?: return false
        return try { synchronized(saveLock) { writeAtomic(resumeFile(), sealed(d)) }; true } catch (e: Exception) { false }
    }

    fun loadResume(): Boolean {
        if (!loaded) return false
        val d = readSealed(resumeFile()) ?: return false
        return unserializeSafely(d)
    }

    fun clearResume() { try { resumeFile().delete() } catch (e: Exception) { } }

    fun stop() {
        running = false
        // faixa parada = qualquer write bloqueado na thread do jogo volta na hora (antes, a tela podia travar esperando o áudio)
        try { track?.pause(); track?.flush() } catch (e: Exception) { }
        val th = thread
        th?.join(3000)
        val alive = th?.isAlive == true
        thread = null
        try { io.shutdown(); io.awaitTermination(1500, TimeUnit.MILLISECONDS) } catch (e: Exception) { }
        if (loaded) {
            if (!alive) { saveSram(); synchronized(lock) { Native.unload() } } // com a thread ainda viva, descarregar o núcleo derrubaria o app
            loaded = false
        }
        try { track?.release() } catch (e: Exception) { }
        track = null
    }

    companion object {
        const val TURBO_A = 1 shl 16; const val TURBO_B = 1 shl 17
        const val AUTO_FIRST = 10; const val AUTO_COUNT = 5
        const val AUTO_RING_MS = 3 * 60 * 1000L
        const val CHEAT_DELAY_FRAMES = 120
    }
}
