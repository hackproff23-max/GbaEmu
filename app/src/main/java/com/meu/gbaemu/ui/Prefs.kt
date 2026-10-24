package com.meu.gbaemu.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.InputStream

val KB_PROFILES = listOf("Padrão", "Perfil 2", "Perfil 3", "Perfil 4")
val AUTO_STEPS = listOf("Desligado", "30 s", "1 min", "2 min", "5 min")
val COLOR_KEYS = listOf("BG", "CARD", "LINE", "GOLD", "GOLD2", "TX", "MUT")
val COLOR_NAMES = listOf("Fundo", "Cartões", "Bordas", "Destaque", "Destaque claro", "Texto", "Texto secundário")
val FF_STEPS = listOf("2x", "3x", "4x", "6x", "8x", "Máx.")
const val SH_GLOBAL = "@global"
const val SH_NONE = "@none"

data class Game(val id: String, val name: String, val uri: String)

/** Botões que podem ter PNG próprio (nome do arquivo = nome + ".png"). */
val SKIN_NAMES = listOf("a", "b", "l", "r", "start", "select", "dpad", "stick_base", "stick_knob", "menu")
val SKIN_LABELS = mapOf("a" to "Botão A", "b" to "Botão B", "l" to "Gatilho L", "r" to "Gatilho R", "start" to "Start", "select" to "Select",
    "dpad" to "Direcional (D-pad)", "stick_base" to "Analógico (base)", "stick_knob" to "Analógico (bolinha)", "menu" to "Botão de menu")

/** automático = segue o giro automático do celular (se estiver travado, não gira); retrato/paisagem = fixo. */
fun orientCode(v: String): Int = when (v) {
    "retrato" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    "paisagem" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
}

/** Usa o mesmo arquivo de preferências do app ("gba"), então chaves antigas continuam valendo. */
class Prefs(c: Context) {
    private val ctx = c.applicationContext
    private val sp = ctx.getSharedPreferences("gba", Context.MODE_PRIVATE)
    init {
        COLOR_KEYS.forEach { T.set(it, sp.getInt("c.$it", T.DEF.getValue(it))) }
        T.font = loadFont()
    }
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Any) = sp.edit().apply {
        when (v) { is String -> putString(k, v); is Int -> putInt(k, v); is Boolean -> putBoolean(k, v); is Long -> putLong(k, v) }
    }.apply()

    // vídeo
    var orient get() = s("orient", "auto"); set(v) = put("orient", v)          // orientação do JOGO: auto | retrato | paisagem
    var orientApp get() = s("orientApp", "auto"); set(v) = put("orientApp", v) // orientação do APP (biblioteca e configurações)
    /** Aplica a orientação do app (biblioteca e configurações). O jogo usa orientFor(), que lê a orientação do jogo. */
    fun applyOrientation(a: Activity) { a.requestedOrientation = orientCode(orientApp) }
    // cores e efeitos do jogo (0 = imagem original)
    var fxBright get() = i("fxBright", 0); set(v) = put("fxBright", v)          // -100..100
    var fxContrast get() = i("fxContrast", 0); set(v) = put("fxContrast", v)    // -100..100
    var fxSat get() = i("fxSat", 0); set(v) = put("fxSat", v)                   // -100..100
    var fxLcd get() = i("fxLcd", 0); set(v) = put("fxLcd", v)                   // 0..100 (opacidade da grade LCD)
    var ratio get() = s("ratio", "Original 3:2"); set(v) = put("ratio", v)      // Original 3:2 | 4:3 | 16:9 | Tela cheia
    /** Posição/tamanho da tela por orientação: [x, y, s] ou null (padrão). */
    fun screenRect(land: Boolean): FloatArray? = s(if (land) "scrL" else "scrP", "").split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 }?.toFloatArray()
    fun saveScreenRect(land: Boolean, r: FloatArray?) = put(if (land) "scrL" else "scrP", r?.joinToString(",") ?: "")
    var linear get() = b("linear", false); set(v) = put("linear", v)           // chave já existente
    var scale get() = i("scale", 3); set(v) = put("scale", v)                  // chave já existente
    // áudio
    var volume get() = i("vol", 100); set(v) = put("vol", v)
    var mute get() = b("mute", false); set(v) = put("mute", v)
    // controle
    var stick get() = b("stick", false); set(v) = put("stick", v)              // chave já existente
    var haptic get() = b("haptic", true); set(v) = put("haptic", v)            // chave já existente
    var opacityPct get() = (sp.getFloat("opacity", 0.85f) * 100).toInt(); set(v) { sp.edit().putFloat("opacity", v / 100f).apply() }
    var showCombo get() = b("showCombo", true); set(v) = put("showCombo", v)
    var showTurboA get() = b("showTA", true); set(v) = put("showTA", v)
    var showTurboB get() = b("showTB", true); set(v) = put("showTB", v)
    // avançado
    var autoSave get() = s("autoSave", "2 min"); set(v) = put("autoSave", v)
    val autoSaveMs: Long get() = when (autoSave) { "30 s" -> 30_000L; "1 min" -> 60_000L; "2 min" -> 120_000L; "5 min" -> 300_000L; else -> 0L }
    var autoResume get() = b("autoResume", false); set(v) = put("autoResume", v)
    var ffAudio get() = b("ffAudio", true); set(v) = put("ffAudio", v)
    var cheatsOn get() = b("cheatsOn", false); set(v) = put("cheatsOn", v)
    var onlineAuto get() = b("onlineAuto", false); set(v) = put("onlineAuto", v) // buscar cheats online sozinho ao abrir um jogo sem cheats (desligado = tudo offline)
    var bgMenus get() = b("bgMenus", true); set(v) = put("bgMenus", v)
    var bgDim get() = i("bgDim", 50); set(v) = put("bgDim", v)
    var askedStorage get() = b("askedStorage", false); set(v) = put("askedStorage", v)
    var pendingSkin get() = s("pendingSkin", ""); set(v) = put("pendingSkin", v)
    var ff get() = s("ff", "4x"); set(v) = put("ff", v)
    val ffMultiplier get() = if (ff == "Máx.") 16 else ff.dropLast(1).toIntOrNull() ?: 4

    // aparência: cores (RGB) e fonte do app
    fun setColor(k: String, v: Int) { put("c.$k", v); T.set(k, v) }
    fun resetColors() { sp.edit().apply { COLOR_KEYS.forEach { remove("c.$it") } }.apply(); COLOR_KEYS.forEach { T.set(it, T.DEF.getValue(it)) } }
    fun fontFile() = File(ctx.filesDir, "font.ttf")
    fun hasFont() = fontFile().exists()
    fun loadFont(): Typeface? = fontFile().takeIf { it.exists() }?.let { try { Typeface.createFromFile(it) } catch (e: Exception) { null } }
    /** Copia a fonte (.ttf/.otf) para dentro do app, só se o cabeçalho do arquivo for de fonte. */
    fun importFont(input: java.io.InputStream): Boolean {
        val tmp = File(ctx.filesDir, "font.tmp")
        tmp.outputStream().use { o -> input.copyTo(o) }
        val head = ByteArray(4)
        val n = tmp.inputStream().use { it.read(head) }
        val ok = n == 4 && (head.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(head, Charsets.ISO_8859_1) in listOf("true", "OTTO", "ttcf"))
        if (!ok) { tmp.delete(); return false }
        fontFile().delete(); tmp.renameTo(fontFile())
        T.font = loadFont()
        return T.font != null
    }
    fun clearFont() { fontFile().delete(); T.font = null }

    // shaders (usa a mesma pasta e a mesma chave "sh_on" do app)
    fun shaderDir() = File(ctx.filesDir, "shaders").also { it.mkdirs() }
    fun availableShaders(): List<String> {
        val d = shaderDir()
        return (d.list()?.toList() ?: emptyList()).filter {
            it.endsWith(".glsl", true) || it.endsWith(".glslp", true) || (it.endsWith(".vsh", true) && File(d, it.substring(0, it.length - 4) + ".fsh").exists())
        }.sorted()
    }
    /** Shader padrão (global): primeiro da lista ativa, ou "" se nenhum. */
    var globalShader
        get() = s("sh_on", "").split("\n").firstOrNull { it.isNotBlank() && it in availableShaders() } ?: ""
        set(v) = put("sh_on", v)

    // biblioteca
    fun games(): List<Game> = s("lib", "").split("\n").filter { it.contains("\t") }.map {
        val (u, n) = it.split("\t", limit = 2); Game(n, n.substringBeforeLast('.'), u)
    }
    fun addGames(list: List<Pair<String, String>>) {
        val cur = games().associateBy { it.id }.toMutableMap()
        list.forEach { (u, n) -> cur[n] = Game(n, n.substringBeforeLast('.'), u) }
        put("lib", cur.values.joinToString("\n") { "${it.uri}\t${it.id}" })
    }
    fun removeGame(id: String) = put("lib", games().filter { it.id != id }.joinToString("\n") { "${it.uri}\t${it.id}" })
    fun clearLibrary() = put("lib", "")

    // por jogo (id = nome do arquivo)
    /** Valor próprio do jogo para a chave, ou null = usar o padrão das configurações. */
    fun gameVal(id: String?, k: String): String? = id?.let { sp.getString("g.$it.$k", null) }
    fun setG(id: String, k: String, v: String?) = sp.edit().apply { if (v == null) remove("g.$id.$k") else putString("g.$id.$k", v) }.apply()
    fun orientFor(id: String?) = gameVal(id, "orient") ?: orient
    fun ratioFor(id: String?) = gameVal(id, "ratio") ?: ratio
    fun ffFor(id: String?) = gameVal(id, "ff") ?: ff
    fun ffMultiplierFor(id: String?): Int { val f = ffFor(id); return if (f == "Máx.") 16 else f.dropLast(1).toIntOrNull() ?: 4 }
    fun volumeFor(id: String?) = gameVal(id, "vol")?.toIntOrNull() ?: volume
    fun resetGame(id: String) = sp.edit().apply { listOf("sh", "kb", "orient", "ratio", "ff", "vol").forEach { remove("g.$id.$it") } }.apply()

    // capa do jogo (imagem copiada para dentro do app)
    var pendingCover get() = s("pendingCover", ""); set(v) = put("pendingCover", v)
    /** Capas ficam em EmuladorPro/Capas/<jogo>.jpg (capas antigas são movidas para lá na primeira vez). */
    fun coverFile(id: String): File {
        val f = File(Store.dir(ctx, "covers"), Store.base(id) + ".jpg")
        if (!f.exists()) {
            val old = File(File(ctx.filesDir, "covers"), id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
            if (old.exists()) try { old.copyTo(f); old.delete() } catch (e: Exception) { }
        }
        return f
    }

    // imagem de fundo (EmuladorPro/Configuracoes/fundo.png|jpg|gif)
    fun bgFile(): File? = Store.dir(ctx, "config").listFiles()?.filter { it.isFile && it.name.startsWith("fundo.") && it.name != "fundo.tmp" }?.maxByOrNull { it.lastModified() }
    fun clearBg() { Store.dir(ctx, "config").listFiles()?.filter { it.name.startsWith("fundo.") && it.name != "fundo.tmp" }?.forEach { it.delete() } }
    /** Copia a imagem escolhida (PNG, JPG ou GIF) para a pasta Configuracoes, só se o arquivo for mesmo uma imagem. */
    fun importBg(input: InputStream): Boolean {
        val dir = Store.dir(ctx, "config")
        val tmp = File(dir, "fundo.tmp")
        try { tmp.outputStream().use { o -> input.copyTo(o) } } catch (e: Exception) { tmp.delete(); return false }
        val h = ByteArray(8)
        val n = try { tmp.inputStream().use { it.read(h) } } catch (e: Exception) { 0 }
        val ext = when {
            n >= 4 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() -> "png"
            n >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "jpg"
            n >= 3 && h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() -> "gif"
            else -> null
        }
        if (ext == null) { tmp.delete(); return false }
        val ok = try {
            if (ext == "gif") (android.graphics.Movie.decodeFile(tmp.path)?.width() ?: 0) > 0
            else { val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(tmp.path, o); o.outWidth > 0 }
        } catch (e: Exception) { false }
        if (!ok) { tmp.delete(); return false }
        clearBg()
        return tmp.renameTo(File(dir, "fundo.$ext"))
    }

    // botões PNG (EmuladorPro/Controles/<botao>.png)
    fun skinFile(n: String) = File(Store.dir(ctx, "controls"), "$n.png")
    private fun legacySkin(n: String) = File(ctx.filesDir, "skin/$n.png")
    fun hasSkinFile(n: String) = skinFile(n).exists() || legacySkin(n).exists()
    fun deleteSkin(n: String) { skinFile(n).delete(); legacySkin(n).delete() }

    /** Copia os PNGs padrão (ou os personalizados antigos) para a pasta Controles, sem sobrescrever o que já está lá. */
    fun exportSkinDefaults(): Int {
        var c = 0
        for (n in SKIN_NAMES) {
            val dst = skinFile(n)
            if (dst.exists()) continue
            try {
                val old = legacySkin(n)
                if (old.exists()) old.copyTo(dst)
                else ctx.assets.open("skins/default/$n.png").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                c++
            } catch (e: Exception) { }
        }
        return c
    }

    /** Troca o PNG de um botão pela imagem escolhida (qualquer PNG/JPG; é regravada como PNG, até 2048 px). */
    fun importSkin(name: String, u: Uri): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 2048) s *= 2
        val bm = ctx.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
        if (bm == null) false else {
            skinFile(name).outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            legacySkin(name).delete()
            true
        }
    } catch (e: Exception) { false }

    /** Miniatura do botão para a tela de escolha (arquivo da pasta, ou o padrão do app). */
    fun skinPreview(n: String): Bitmap? {
        val f = listOf(skinFile(n), legacySkin(n)).firstOrNull { it.exists() }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (f != null) BitmapFactory.decodeFile(f.path, o) else ctx.assets.open("skins/default/$n.png").use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > 256) s *= 2
            val op = BitmapFactory.Options().apply { inSampleSize = s }
            if (f != null) BitmapFactory.decodeFile(f.path, op) else ctx.assets.open("skins/default/$n.png").use { BitmapFactory.decodeStream(it, null, op) }
        } catch (e: Exception) { null }
    }

    fun shaderFor(id: String) = s("g.$id.sh", SH_GLOBAL)
    fun setShader(id: String, v: String) = put("g.$id.sh", v)
    fun kbFor(id: String) = s("g.$id.kb", KB_PROFILES[0])
    fun setKb(id: String, v: String) = put("g.$id.kb", v)
    fun isFav(id: String) = b("g.$id.fav", false)
    fun setFav(id: String, v: Boolean) = put("g.$id.fav", v)
    fun lastPlayed(id: String) = sp.getLong("g.$id.last", 0L)
    fun touch(id: String) = put("g.$id.last", System.currentTimeMillis())
}
