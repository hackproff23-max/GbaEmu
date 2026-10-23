package com.meu.gbaemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import com.meu.gbaemu.MainActivity
import java.io.File

class SettingsActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var title: TextView
    private lateinit var body: FrameLayout
    private var page = "menu"
    private var editKb = KB_PROFILES[0]

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); p = Prefs(this); window.statusBarColor = T.BG
        title = T.text(this, "Configurações", 18f, T.GOLD2, true); body = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(T.BG)
            addView(T.header(context, title, null)); addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        show("menu")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (page != "menu") show("menu") else super.onBackPressed() }

    private fun show(pg: String) {
        page = pg; body.removeAllViews()
        title.text = mapOf("menu" to "Configurações", "video" to "Vídeo", "audio" to "Áudio", "ctrl" to "Controle", "shaders" to "Shaders", "adv" to "Avançado")[pg]
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(T.dp(context, 16), 0, T.dp(context, 16), T.dp(context, 30)) }
        when (pg) { "menu" -> menu(col); "video" -> video(col); "audio" -> audio(col); "ctrl" -> ctrl(col); "shaders" -> shaders(col); "adv" -> adv(col) }
        body.addView(ScrollView(this).apply { addView(col) })
    }

    private fun btn(text: String, solid: Boolean, click: () -> Unit) = T.text(this, text, 15f, if (solid) T.BG else T.GOLD, true).apply {
        gravity = Gravity.CENTER; background = T.box(context, if (solid) T.GOLD else 0xFF0C0C0C.toInt(), T.GOLD)
        setPadding(0, T.dp(context, 12), 0, T.dp(context, 12)); setOnClickListener { click() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this, 12) } }

    private fun menu(col: LinearLayout) {
        listOf(Triple("video", "Vídeo", "Orientação, filtro e escala"), Triple("audio", "Áudio", "Volume e mudo"),
            Triple("ctrl", "Controle", "Editor de layout, combo e turbo"), Triple("shaders", "Shaders", "Importar e escolher o filtro visual"),
            Triple("adv", "Avançado", "Fast-forward e mais")).forEach { (k, t, d) ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = T.box(context, T.CARD, T.LINE, 14)
                setPadding(T.dp(context, 16), T.dp(context, 14), T.dp(context, 16), T.dp(context, 14))
                addView(T.text(context, t, 16f, T.GOLD2, true)); addView(T.text(context, d, 12f, T.MUT)); setOnClickListener { show(k) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@SettingsActivity, 10) })
        }
    }

    private fun video(col: LinearLayout) {
        val names = mapOf("auto" to "Automático", "retrato" to "Retrato", "paisagem" to "Paisagem")
        col.addView(T.h(this, "Orientação do jogo"))
        col.addView(T.chips(this, names.values.toList(), names[p.orient]!!) { n -> p.orient = names.entries.first { it.value == n }.key; show("video") })
        col.addView(T.h(this, "Escala interna das shaders"))
        col.addView(T.chips(this, (1..6).map { "${it}x" }, "${p.scale}x") { p.scale = it.dropLast(1).toInt(); show("video") })
        col.addView(T.switchRow(this, "Filtro bilinear (suave)", p.linear) { p.linear = it })
        col.addView(T.note(this, "Vale na próxima vez que você abrir um jogo."))
    }

    private fun audio(col: LinearLayout) {
        col.addView(T.switchRow(this, "Mudo", p.mute) { p.mute = it })
        col.addView(T.seek(this, "Volume", 0, 100, p.volume) { p.volume = it })
        col.addView(T.note(this, "Vale na próxima vez que você abrir um jogo."))
    }

    private fun ctrl(col: LinearLayout) {
        col.addView(T.h(this, "Editor de layout livre"))
        col.addView(T.note(this, "Escolha o perfil, abra o editor, arraste os botões e pince com 2 dedos para mudar o tamanho. Gire o celular para editar retrato e paisagem separadamente. Toque em ☰ para concluir."))
        col.addView(T.chips(this, KB_PROFILES, editKb) { editKb = it; show("ctrl") })
        col.addView(btn("Abrir editor — $editKb", true) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("edit_layout", true).putExtra("profile", editKb))
        })
        col.addView(T.h(this, "Botões extras"))
        col.addView(T.switchRow(this, "Botão combo (A+B)", p.showCombo) { p.showCombo = it })
        col.addView(T.switchRow(this, "Turbo A", p.showTurboA) { p.showTurboA = it })
        col.addView(T.switchRow(this, "Turbo B", p.showTurboB) { p.showTurboB = it })
        col.addView(T.h(this, "Geral"))
        col.addView(T.switchRow(this, "Direcional analógico (em vez de D-pad)", p.stick) { p.stick = it })
        col.addView(T.switchRow(this, "Vibração", p.haptic) { p.haptic = it })
        col.addView(T.seek(this, "Opacidade dos botões", 20, 100, p.opacityPct.coerceIn(20, 100)) { p.opacityPct = it })
        col.addView(T.note(this, "Os perfis de teclado de cada jogo são escolhidos na Biblioteca. Vale na próxima vez que você abrir um jogo."))
    }

    private fun shaders(col: LinearLayout) {
        col.addView(T.note(this, "Shader padrão para jogos sem escolha própria. Cada jogo pode ter a sua na Biblioteca."))
        col.addView(btn("Importar shaders (.glsl / .vsh + .fsh)", true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 2)
        })
        val cur = p.globalShader
        (listOf("") + p.availableShaders()).forEach { s ->
            val on = s == cur
            col.addView(LinearLayout(this).apply {
                background = T.box(context, T.CARD, if (on) T.GOLD else T.LINE)
                setPadding(T.dp(context, 14), T.dp(context, 13), T.dp(context, 14), T.dp(context, 13))
                addView(T.text(context, if (s.isEmpty()) "Nenhum (pixels puros)" else s), LinearLayout.LayoutParams(0, -2, 1f))
                addView(T.text(context, if (on) "✓ ativo" else "", 13f, T.GOLD))
                setOnClickListener { p.globalShader = s; show("shaders") }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@SettingsActivity, 8) })
        }
    }

    private fun adv(col: LinearLayout) {
        col.addView(T.h(this, "Velocidade do fast-forward"))
        col.addView(T.chips(this, FF_STEPS, p.ff) { p.ff = it; show("adv") })
        col.addView(T.note(this, "Liga e desliga pelo menu do jogo (Acelerar). Máx. = 16x. Vale na próxima vez que você abrir um jogo."))
        col.addView(T.h(this, "Biblioteca"))
        col.addView(btn("Limpar lista de jogos", false) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Limpar a lista?").setMessage("Os arquivos e os saves não são apagados.")
                .setPositiveButton("Limpar") { _, _ -> p.clearLibrary() }.setNegativeButton("Cancelar", null).show()
        })
    }

    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 2 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        var ok = 0; var skip = 0
        for (u in uris) {
            val nm = displayName(u).replace("/", "_"); val l = nm.lowercase()
            if (l.endsWith(".glsl") || l.endsWith(".vsh") || l.endsWith(".fsh")) {
                contentResolver.openInputStream(u)?.use { i -> File(p.shaderDir(), nm).outputStream().use { o -> i.copyTo(o) } }; ok++
            } else skip++
        }
        Toast.makeText(this, "$ok shader(s) importado(s)" + if (skip > 0) " • $skip ignorado(s) (só .glsl/.vsh/.fsh)" else "", Toast.LENGTH_LONG).show()
        show("shaders")
    }
}
