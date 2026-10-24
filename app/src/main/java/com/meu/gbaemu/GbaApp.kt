package com.meu.gbaemu

import android.app.Application
import android.content.SharedPreferences
import com.meu.gbaemu.ui.Backup
import java.io.File

/** Qualquer mudança nas configurações (de qualquer tela) é copiada para EmuladorPro/Configuracoes logo depois. */
class GbaApp : Application() {
    // guardado num campo: o Android só mantém uma referência fraca do listener
    private val onChange = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> Backup.schedule(this) }

    /** Copia os shaders otimizados (assets/shaders) para a pasta de shaders do app, uma única vez. */
    private fun seedShaders() {
        try {
            val seed = getSharedPreferences("seed", MODE_PRIVATE)
            if (seed.getInt("shaders", 0) >= 1) return
            val dir = File(filesDir, "shaders").also { it.mkdirs() }
            for (n in assets.list("shaders") ?: emptyArray()) {
                assets.open("shaders/$n").use { i -> File(dir, n).outputStream().use { o -> i.copyTo(o) } }
            }
            seed.edit().putInt("shaders", 1).apply()
        } catch (e: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        seedShaders()
        getSharedPreferences("gba", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onChange)
    }
}
