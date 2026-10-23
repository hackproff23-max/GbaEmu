package com.meu.gbaemu.ui

import android.content.Context
import java.io.File

val KB_PROFILES = listOf("Padrão", "Perfil 2", "Perfil 3", "Perfil 4")
val FF_STEPS = listOf("2x", "3x", "4x", "6x", "8x", "Máx.")
const val SH_GLOBAL = "@global"
const val SH_NONE = "@none"

data class Game(val id: String, val name: String, val uri: String)

/** Usa o mesmo arquivo de preferências do app ("gba"), então chaves antigas continuam valendo. */
class Prefs(c: Context) {
    private val ctx = c.applicationContext
    private val sp = ctx.getSharedPreferences("gba", Context.MODE_PRIVATE)
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
    var ff get() = s("ff", "4x"); set(v) = put("ff", v)
    val ffMultiplier get() = if (ff == "Máx.") 16 else ff.dropLast(1).toIntOrNull() ?: 4

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
    fun shaderFor(id: String) = s("g.$id.sh", SH_GLOBAL)
    fun setShader(id: String, v: String) = put("g.$id.sh", v)
    fun kbFor(id: String) = s("g.$id.kb", KB_PROFILES[0])
    fun setKb(id: String, v: String) = put("g.$id.kb", v)
    fun isFav(id: String) = b("g.$id.fav", false)
    fun setFav(id: String, v: Boolean) = put("g.$id.fav", v)
    fun lastPlayed(id: String) = sp.getLong("g.$id.last", 0L)
    fun touch(id: String) = put("g.$id.last", System.currentTimeMillis())
}
