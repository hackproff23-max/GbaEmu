package com.meu.gbaemu

import android.app.Application
import android.content.SharedPreferences
import com.meu.gbaemu.ui.Backup

/** Qualquer mudança nas configurações (de qualquer tela) é copiada para EmuladorPro/Configuracoes logo depois. */
class GbaApp : Application() {
    // guardado num campo: o Android só mantém uma referência fraca do listener
    private val onChange = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> Backup.schedule(this) }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("gba", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onChange)
    }
}
