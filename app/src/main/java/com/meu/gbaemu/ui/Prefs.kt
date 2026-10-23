package com.meu.gbaemu.ui

import android.content.Context
import android.graphics.Typeface
import java.io.File

val KB_PROFILES = listOf("Padrão", "Perfil 2", "Perfil 3", "Perfil 4")
val AUTO_STEPS = listOf("Desligado", "30 s", "1 min", "2 min", "5 min")
val COLOR_KEYS = listOf("BG", "CARD", "LINE", "GOLD", "GOLD2", "TX", "MUT")
val COLOR_NAMES = listOf("Fundo", "Cartões", "Bordas", "Destaque", "Destaque claro", "Texto", "Texto secundário")
val FF_STEPS = listOf("2x", "3x", "4x", "6x", "8x", "Máx.")
const val SH_GLOBAL = "@global"
const val SH_NONE = "@none"

data class Game(val id: String, val name: String, val uri: String)

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
    var orient get() = s("orient", "auto"); set(v) = put("orient", v)          // auto | retrato | paisagem
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
            it.endsWith(".glsl", true) || (it.endsWith(".vsh", true) && File(d, it.substring(0, it.length - 4) + ".fsh").exists())
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
    fun coverFile(id: String) = File(File(ctx.filesDir, "covers").also { it.mkdirs() }, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")

    fun shaderFor(id: String) = s("g.$id.sh", SH_GLOBAL)
    fun setShader(id: String, v: String) = put("g.$id.sh", v)
    fun kbFor(id: String) = s("g.$id.kb", KB_PROFILES[0])
    fun setKb(id: String, v: String) = put("g.$id.kb", v)
    fun isFav(id: String) = b("g.$id.fav", false)
    fun setFav(id: String, v: Boolean) = put("g.$id.fav", v)
    fun lastPlayed(id: String) = sp.getLong("g.$id.last", 0L)
    fun touch(id: String) = put("g.$id.last", System.currentTimeMillis())
}
