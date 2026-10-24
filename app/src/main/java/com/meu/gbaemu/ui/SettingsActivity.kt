package com.meu.gbaemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
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
        p.applyOrientation(this)
        title = T.text(this, "Configurações", 18f, T.GOLD2, true); body = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Backdrop.drawable(context, p) ?: ColorDrawable(T.BG)
            addView(T.header(context, title, null)); addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        editColor = (b?.getInt("ec") ?: 0).coerceIn(0, COLOR_KEYS.size - 1)
        show(b?.getString("page") ?: intent.getStringExtra("page") ?: "menu")
        b?.getInt("sy")?.takeIf { it > 0 }?.let { y -> scroll?.post { scroll?.scrollTo(0, y) } }
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putString("page", page); o.putInt("ec", editColor); o.putInt("sy", scroll?.scrollY ?: 0)
    }

    override fun onPause() { super.onPause(); Backup.export(this) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (page == "colors") show("adv") else if (page == "skin") show("ctrl") else if (page != "menu") show("menu") else super.onBackPressed() }

    private fun show(pg: String) {
        page = pg; body.removeAllViews()
        title.text = mapOf("menu" to "Configurações", "video" to "Vídeo", "audio" to "Áudio", "ctrl" to "Controle", "shaders" to "Shaders", "adv" to "Avançado", "colors" to "Cores do app", "skin" to "Botões em PNG")[pg]
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(T.dp(context, 16), 0, T.dp(context, 16), T.dp(context, 30)) }
        when (pg) { "menu" -> menu(col); "video" -> video(col); "audio" -> audio(col); "ctrl" -> ctrl(col); "shaders" -> shaders(col); "adv" -> adv(col); "colors" -> colorsPage(col); "skin" -> skinPage(col) }
        scroll = ScrollView(this).apply { addView(col) }
        body.addView(scroll)
    }

    private fun btn(text: String, solid: Boolean, click: () -> Unit) = T.text(this, text, 15f, if (solid) T.BG else T.GOLD, true).apply {
        gravity = Gravity.CENTER; background = T.box(context, if (solid) T.GOLD else T.CARD, T.GOLD)
        setPadding(0, T.dp(context, 12), 0, T.dp(context, 12)); setOnClickListener { click() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@SettingsActivity, 12) } }

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

    private var editLand = false

    private fun video(col: LinearLayout) {
        val names = mapOf("auto" to "Automático", "retrato" to "Retrato", "paisagem" to "Paisagem")
        col.addView(T.h(this, "Orientação do app"))
        col.addView(T.chips(this, names.values.toList(), names[p.orientApp] ?: names.getValue("auto")) { n ->
            p.orientApp = names.entries.first { it.value == n }.key; p.applyOrientation(this); show("video")
        })
        col.addView(T.note(this, "Vale para a biblioteca e as configurações. Automático segue o giro automático do celular: com o giro travado, a tela não vira sozinha."))
        col.addView(T.h(this, "Orientação do jogo"))
        col.addView(T.chips(this, names.values.toList(), names[p.orient] ?: names.getValue("auto")) { n ->
            p.orient = names.entries.first { it.value == n }.key; show("video")
        })
        col.addView(T.note(this, "Vale só dentro do jogo, separada da orientação do app. Cada jogo pode ter a sua própria na Biblioteca (Configuração só deste jogo)."))
        col.addView(T.h(this, "Proporção da tela"))
        val ratios = listOf("Original 3:2", "4:3", "16:9", "Tela cheia")
        col.addView(T.chips(this, ratios, p.ratio) { p.ratio = it; show("video") })
        col.addView(T.h(this, "Editor de tela"))
        if (p.ratio == "Tela cheia") {
            col.addView(T.note(this, "Tela cheia ocupa a tela inteira; não há posição para editar."))
        } else {
            col.addView(T.note(this, "Escolha a direção, arraste a tela dourada e ajuste o tamanho. Cada direção tem a sua posição."))
            col.addView(T.chips(this, listOf("Retrato", "Paisagem"), if (editLand) "Paisagem" else "Retrato") { editLand = it == "Paisagem"; show("video") })
            val dm = resources.displayMetrics; val shortS = minOf(dm.widthPixels, dm.heightPixels); val longS = maxOf(dm.widthPixels, dm.heightPixels)
            val avail = dm.widthPixels - T.dp(this, 32)
            val sw = if (editLand) avail else (avail * 0.55f).toInt()
            val sh = if (editLand) sw * shortS / longS else sw * longS / shortS
            val ar = when (p.ratio) { "4:3" -> 4f / 3; "16:9" -> 16f / 9; else -> 1.5f }
            val saved = p.screenRect(editLand)
            val def = if (editLand) floatArrayOf(.5f, .5f, minOf(1f, sh * ar / sw)) else floatArrayOf(0f, 0f, 1f)
            val r = saved ?: def
            val ev = EditorView(this, r[0], r[1], r[2], ar)
            ev.onChange = { p.saveScreenRect(editLand, floatArrayOf(ev.px, ev.py, ev.s)) }
            col.addView(ev, LinearLayout.LayoutParams(sw, sh).apply { topMargin = T.dp(this@SettingsActivity, 10); bottomMargin = T.dp(this@SettingsActivity, 6); gravity = Gravity.CENTER_HORIZONTAL })
            col.addView(T.seek(this, "Tamanho da tela", 30, 100, (r[2] * 100).toInt().coerceIn(30, 100)) { v -> ev.s = v / 100f; ev.invalidate(); p.saveScreenRect(editLand, floatArrayOf(ev.px, ev.py, ev.s)) })
            col.addView(btn("Restaurar posição padrão", false) { p.saveScreenRect(editLand, null); show("video") })
        }
        col.addView(T.h(this, "Imagem"))
        col.addView(T.chips(this, (1..6).map { "${it}x" }, "${p.scale}x") { p.scale = it.dropLast(1).toInt(); show("video") })
        col.addView(T.note(this, "Escala interna das shaders."))
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
        col.addView(T.h(this, "Botões em PNG"))
        col.addView(T.note(this, "Escolha a imagem de cada botão, ou exporte os PNGs para a memória interna, edite em outro app e use aqui mesmo."))
        col.addView(btn("Escolher / exportar botões PNG", true) { show("skin") })
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
        col.addView(T.note(this, "Shader padrão para jogos sem escolha própria. Cada jogo pode ter a sua na Biblioteca. Aceita .glsl, .vsh/.fsh e presets multipass .glslp: para um preset, selecione de uma vez o .glslp, todos os .glsl que ele usa e os PNGs (texturas) dele."))
        col.addView(btn("Importar shaders", true) {
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
        col.addView(T.note(this, "Liga e desliga pelo menu do jogo (Acelerar). Máx. = 16x. Cada jogo pode ter a sua velocidade na Biblioteca (segure o jogo). Vale na próxima vez que você abrir um jogo."))
        col.addView(T.switchRow(this, "Som durante o fast-forward", p.ffAudio) { p.ffAudio = it })
        col.addView(T.note(this, "O som acelera junto com o jogo (fica mais agudo). Desligue se preferir silêncio ao acelerar."))
        col.addView(T.h(this, "Auto-save e retomada"))
        col.addView(T.note(this, "Salva sozinho o estado do jogo, com foto da tela, no slot Auto-save (aparece em Menu > Carregar estado). Também salva ao fechar o jogo."))
        col.addView(T.chips(this, AUTO_STEPS, p.autoSave) { p.autoSave = it; show("adv") })
        col.addView(T.switchRow(this, "Auto-resume (continuar de onde parou)", p.autoResume) { p.autoResume = it })
        col.addView(T.note(this, "Ao fechar o jogo ele salva sozinho; ao abrir de novo volta exatamente de onde você parou. Há 5 slots manuais + 1 automático. Vale na próxima vez que você abrir um jogo."))
        col.addView(T.h(this, "Cheats"))
        col.addView(T.switchRow(this, "Mostrar menu de cheats no jogo", p.cheatsOn) { p.cheatsOn = it })
        col.addView(T.note(this, "Ligado: aparece “Cheats” no menu do jogo (☰). Cada jogo guarda seus códigos em EmuladorPro/Cheats."))
        col.addView(T.h(this, "Cheats e capas online (opcional)"))
        col.addView(T.note(this, "O app funciona 100% offline. Estas opções só usam a internet quando você toca nelas. O que for baixado fica em EmuladorPro (Banco, Cheats e Capas) e depois funciona sem internet. O jogo é identificado pelo conteúdo da ROM (CRC), não pelo nome do arquivo. Fonte: banco público do libretro."))
        col.addView(T.switchRow(this, "Buscar cheats sozinho ao abrir um jogo sem cheats", p.onlineAuto) { p.onlineAuto = it })
        col.addView(btn("Baixar banco de cheats (usar offline depois)", true) { downloadCheatBank() })
        col.addView(btn("Baixar capas dos jogos da biblioteca", false) { downloadLibCovers() })
        col.addView(T.h(this, "Imagem de fundo"))
        col.addView(T.note(this, "PNG, JPG ou GIF (animado). Aparece atrás da tela do jogo e, se quiser, nos menus do app."))
        col.addView(btn(if (p.bgFile() != null) "Trocar imagem de fundo" else "Escolher imagem de fundo", true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 5)
        })
        if (p.bgFile() != null) {
            col.addView(btn("Remover fundo", false) { p.clearBg(); recreate() })
            col.addView(T.switchRow(this, "Usar o fundo também nos menus", p.bgMenus) { p.bgMenus = it; recreate() })
            col.addView(T.seek(this, "Escurecer o fundo", 0, 90, p.bgDim.coerceIn(0, 90)) { p.bgDim = it })
            col.addView(T.note(this, "O escurecimento vale na próxima vez que você abrir o jogo ou o app."))
        }
        col.addView(T.h(this, "Pasta EmuladorPro"))
        col.addView(T.note(this, Store.root(this).absolutePath + "\nConfiguracoes · SaveStates · Saves · Cheats · Capas · Controles"))
        if (!Store.canUseShared(this)) {
            col.addView(T.note(this, "Sem permissão de acesso à memória interna, a pasta fica numa área privada do app. Permita para ela aparecer no seu celular."))
            col.addView(btn("Permitir acesso à memória interna", true) { Store.requestAccess(this) })
        }
        col.addView(btn("Salvar configurações na pasta agora", false) {
            Backup.export(this); Toast.makeText(this, "Configurações salvas em Configuracoes", Toast.LENGTH_SHORT).show()
        })
        col.addView(btn("Restaurar configurações da pasta", false) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Restaurar configurações?")
                .setMessage("As configurações salvas na pasta Configuracoes substituem as atuais.")
                .setPositiveButton("Restaurar") { _, _ ->
                    val ok = Backup.restore(this)
                    Toast.makeText(this, if (ok) "Configurações restauradas" else "Nenhum arquivo de configurações encontrado", Toast.LENGTH_LONG).show()
                    if (ok) { p = Prefs(this); recreate() }
                }.setNegativeButton("Cancelar", null).show()
        })
        col.addView(T.h(this, "Aparência"))
        col.addView(btn("Importar fonte das letras (.ttf)", true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 3)
        })
        if (p.hasFont()) col.addView(btn("Voltar à fonte padrão", false) { p.clearFont(); recreate() })
        col.addView(btn("Cores do app (RGB)", false) { show("colors") })
        col.addView(T.h(this, "Biblioteca"))
        col.addView(btn("Limpar lista de jogos", false) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Limpar a lista?").setMessage("Os arquivos e os saves não são apagados.")
                .setPositiveButton("Limpar") { _, _ -> p.clearLibrary() }.setNegativeButton("Cancelar", null).show()
        })
    }

    /** Baixa todos os cheats do banco público (GBA, GB e GBC) para usar offline. Pode cancelar; o que já veio fica. */
    private fun downloadCheatBank() {
        val pd = ProgressDlg(this, "Baixando cheats")
        Thread {
            var total = 0; var failed = false
            for (sys in Ident.SYSTEMS) {
                if (pd.cancelled) break
                val n = CheatBank.downloadAll(this, sys, { i, t -> pd.set(sys.removePrefix("Nintendo - ") + ": " + i + " de " + t) }, { pd.cancelled })
                if (n < 0) failed = true else total += n
            }
            Net.main.post {
                pd.close()
                Toast.makeText(this, if (total == 0 && failed) "Não consegui baixar (sem internet?)" else "$total arquivo(s) de cheats baixado(s)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Baixa as capas que faltam (identifica cada jogo pela ROM). */
    private fun downloadLibCovers() {
        val todo = p.games().filter { !p.coverFile(it.id).exists() }
        if (todo.isEmpty()) { Toast.makeText(this, "Todos os jogos já têm capa (ou a biblioteca está vazia)", Toast.LENGTH_LONG).show(); return }
        val pd = ProgressDlg(this, "Baixando capas")
        Thread {
            var ok = 0; var fail = 0
            for ((i, g) in todo.withIndex()) {
                if (pd.cancelled) break
                pd.set("${i + 1} de ${todo.size}\n${g.name}")
                if (try { Covers.fetch(this, p, g) } catch (e: Throwable) { false }) ok++ else fail++
            }
            Net.main.post {
                pd.close()
                Toast.makeText(this, if (ok == 0 && fail > 0) "Não consegui baixar (sem internet ou jogo não encontrado)" else "$ok capa(s) baixada(s)" + if (fail > 0) " · $fail não encontrada(s)" else "", Toast.LENGTH_LONG).show()
            }
        }.start()
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
        if (res != RESULT_OK || data == null) return
        if (req == 3) { importFont(data.data); return }
        if (req == 4) { importSkin(data.data); return }
        if (req == 5) { importBg(data.data); return }
        if (req != 2) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        var ok = 0; var skip = 0
        for (u in uris) {
            val nm = displayName(u).replace("/", "_"); val l = nm.lowercase()
            if (l.endsWith(".glsl") || l.endsWith(".glslp") || l.endsWith(".vsh") || l.endsWith(".fsh") || l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".bmp")) {
                contentResolver.openInputStream(u)?.use { i -> File(p.shaderDir(), nm).outputStream().use { o -> i.copyTo(o) } }; ok++
            } else skip++
        }
        Toast.makeText(this, "$ok arquivo(s) importado(s)" + if (skip > 0) " • $skip ignorado(s) (aceito: .glsl, .glslp, .vsh/.fsh e imagens PNG)" else "", Toast.LENGTH_LONG).show()
        show("shaders")
    }

    private fun importSkin(u: Uri?) {
        val n = p.pendingSkin
        if (u == null || n.isBlank()) return
        val ok = p.importSkin(n, u)
        Toast.makeText(this, if (ok) "${SKIN_LABELS[n] ?: n}: imagem aplicada" else "Não consegui ler essa imagem", Toast.LENGTH_LONG).show()
        show("skin")
    }

    private fun importBg(u: Uri?) {
        if (u == null) return
        val ok = try { contentResolver.openInputStream(u)?.use { p.importBg(it) } ?: false } catch (e: Exception) { false }
        Toast.makeText(this, if (ok) "Fundo aplicado" else "Escolha uma imagem PNG, JPG ou GIF", Toast.LENGTH_LONG).show()
        if (ok) recreate()
    }

    private fun skinPage(col: LinearLayout) {
        col.addView(T.note(this, "Cada botão tem o seu PNG. Toque em “Escolher” para trocar a imagem de um botão. Os arquivos ficam em:\n" + Store.dir(this, "controls").absolutePath))
        col.addView(T.note(this, "Para editar em outro app: toque em “Exportar”, edite os PNGs da pasta Controles e volte para o jogo. O app lê os arquivos da pasta sozinho. O botão ↺ volta ao padrão."))
        col.addView(btn("Exportar botões para a memória interna", true) {
            val n = p.exportSkinDefaults()
            Toast.makeText(this, if (n > 0) "$n arquivo(s) copiado(s) para a pasta Controles" else "Os arquivos já estão na pasta Controles", Toast.LENGTH_LONG).show()
            show("skin")
        })
        SKIN_NAMES.forEach { n ->
            val inFolder = p.hasSkinFile(n)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = T.box(context, T.CARD, T.LINE, 12)
                setPadding(T.dp(context, 12), T.dp(context, 10), T.dp(context, 12), T.dp(context, 10))
            }
            row.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0x33FFFFFF)
                p.skinPreview(n)?.let { setImageBitmap(it) }
            }, LinearLayout.LayoutParams(T.dp(this, 56), T.dp(this, 56)))
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(T.text(context, SKIN_LABELS[n] ?: n, 15f)); addView(T.text(context, if (inFolder) "arquivo na pasta Controles" else "padrão do app", 12f, T.MUT))
            }
            row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = T.dp(this@SettingsActivity, 12) })
            row.addView(T.chip(this, "Escolher", false) {
                p.pendingSkin = n
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 4)
            })
            row.addView(T.chip(this, "↺", false) { p.deleteSkin(n); show("skin") }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = T.dp(this@SettingsActivity, 8) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@SettingsActivity, 8) })
        }
        col.addView(btn("Restaurar todos os botões padrão", false) {
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Restaurar todos os botões?")
                .setMessage("Os PNGs da pasta Controles serão apagados e o app volta a usar as imagens padrão.")
                .setPositiveButton("Restaurar") { _, _ -> SKIN_NAMES.forEach { p.deleteSkin(it) }; show("skin") }
                .setNegativeButton("Cancelar", null).show()
        })
    }

    private fun importFont(u: Uri?) {
        if (u == null) return
        val nm = displayName(u).lowercase()
        if (!(nm.endsWith(".ttf") || nm.endsWith(".otf"))) { Toast.makeText(this, "Escolha um arquivo .ttf ou .otf", Toast.LENGTH_LONG).show(); return }
        val ok = try { contentResolver.openInputStream(u)?.use { p.importFont(it) } ?: false } catch (e: Exception) { false }
        Toast.makeText(this, if (ok) "Fonte aplicada" else "Arquivo de fonte inválido", Toast.LENGTH_LONG).show()
        if (ok) recreate()
    }

    private var editColor = 0
    private var scroll: ScrollView? = null

    private fun colorsPage(col: LinearLayout) {
        col.addView(T.note(this, "Escolha qual cor mudar e ajuste R, G e B (0 a 255). Ao soltar o controle, o app inteiro recarrega com a nova cor."))
        col.addView(T.chips(this, COLOR_NAMES, COLOR_NAMES[editColor]) { editColor = COLOR_NAMES.indexOf(it); show("colors") })
        val key = COLOR_KEYS[editColor]; val cur = T.get(key)
        val rgb = intArrayOf(Color.red(cur), Color.green(cur), Color.blue(cur))
        val sw = T.text(this, "", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, T.dp(context, 22), 0, T.dp(context, 22)) }
        fun refresh() {
            val c = Color.rgb(rgb[0], rgb[1], rgb[2])
            sw.background = T.box(sw.context, c, T.LINE)
            sw.text = "%s  #%02X%02X%02X".format(COLOR_NAMES[editColor], rgb[0], rgb[1], rgb[2])
            sw.setTextColor(if (rgb[0] * 0.3 + rgb[1] * 0.59 + rgb[2] * 0.11 > 140) Color.BLACK else Color.WHITE)
        }
        refresh()
        col.addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@SettingsActivity, 10) })
        fun slider(label: String, idx: Int): View {
            val lb = T.text(this, "$label: ${rgb[idx]}", 13f, T.MUT)
            val sb = SeekBar(this).apply {
                max = 255; progress = rgb[idx]
                progressTintList = ColorStateList.valueOf(T.GOLD); thumbTintList = ColorStateList.valueOf(T.GOLD)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { rgb[idx] = v; lb.text = "$label: $v"; refresh() }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { p.setColor(key, Color.rgb(rgb[0], rgb[1], rgb[2])); recreate() }
                })
            }
            return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, T.dp(context, 8), 0, T.dp(context, 8)); addView(lb); addView(sb) }
        }
        col.addView(slider("Vermelho (R)", 0)); col.addView(slider("Verde (G)", 1)); col.addView(slider("Azul (B)", 2))
        col.addView(btn("Restaurar cores padrão", false) { p.resetColors(); recreate() })
    }
}
