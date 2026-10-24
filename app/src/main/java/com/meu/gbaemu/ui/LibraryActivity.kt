package com.meu.gbaemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.text.TextUtils
import android.view.KeyEvent
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import com.meu.gbaemu.MainActivity
import java.io.InputStream
import java.util.zip.ZipInputStream

class LibraryActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var list: LinearLayout
    private lateinit var chipHost: FrameLayout
    private val covers = HashMap<String, Bitmap?>()
    private var sig = 0
    private var bgSig = ""
    private var filter = "Todos"; private var sort = "A-Z"; private var q = ""

    // ---- modo paisagem (menu em fileira horizontal) ----
    private var land = false
    private var selId: String? = null
    private var shown: List<Game> = emptyList()
    private val tiles = LinkedHashMap<String, View>()
    private lateinit var lrow: LinearLayout
    private lateinit var lhs: HorizontalScrollView
    private lateinit var lname: TextView
    private lateinit var linfo: TextView
    private lateinit var lsortLbl: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); p = Prefs(this); sig = T.sig(); window.statusBarColor = T.BG
        p.applyOrientation(this); bgSig = Backdrop.sig(p)
        land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land) { setContentView(landUi()); return } // paisagem: menu novo; o retrato continua o mesmo abaixo
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Backdrop.drawable(context, p) ?: ColorDrawable(T.BG) }
        val gear = ImageButton(this).apply {
            setImageResource(com.meu.gbaemu.R.drawable.ic_gear)
            background = T.box(this@LibraryActivity, T.CARD, T.GOLD); contentDescription = "Configurações"
            setOnClickListener { startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java)) }
        }
        root.addView(T.header(this, T.text(this, "Biblioteca", 18f, T.GOLD2, true), gear))
        val search = EditText(this).apply {
            hint = "Buscar jogo"; setHintTextColor(T.MUT); T.font?.let { typeface = it }; setTextColor(T.TX); setSingleLine()
            background = T.box(this@LibraryActivity, T.CARD, T.LINE)
            setPadding(T.dp(context, 14), T.dp(context, 10), T.dp(context, 14), T.dp(context, 10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { q = s.toString(); fill() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val add = T.text(this, "＋  Adicionar jogos (.gba / .zip)", 15f, T.BG, true).apply {
            gravity = Gravity.CENTER; background = T.box(context, T.GOLD, T.GOLD)
            setPadding(0, T.dp(context, 12), 0, T.dp(context, 12)); setOnClickListener { pickRoms() }
        }
        chipHost = FrameLayout(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(T.dp(context, 16), 0, T.dp(context, 16), T.dp(context, 24))
            addView(search); addView(chipHost); addView(T.note(context, "Toque para jogar · segure um jogo para configurar e trocar a capa")); addView(list)
            addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 12) })
            addView(T.text(context, "📁  Procurar todas as ROMs numa pasta", 14f, T.GOLD, true).apply {
                gravity = Gravity.CENTER; background = T.box(context, T.CARD, T.GOLD)
                setPadding(0, T.dp(context, 12), 0, T.dp(context, 12)); setOnClickListener { pickFolder() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 10) })
            addView(T.text(context, "⬇  Baixar capas dos jogos (online, opcional)", 14f, T.GOLD, true).apply {
                gravity = Gravity.CENTER; background = T.box(context, T.CARD, T.GOLD)
                setPadding(0, T.dp(context, 12), 0, T.dp(context, 12)); setOnClickListener { downloadCovers(p.games(), false) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 10) })
        }
        root.addView(ScrollView(this).apply { addView(body) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // reinstalou o app? as configurações salvas em EmuladorPro/Configuracoes voltam sozinhas
        if (Backup.restoreIfEmpty(this)) { recreate(); return }
        if (T.sig() != sig || Backdrop.sig(p) != bgSig) { recreate(); return }
        p.applyOrientation(this)
        chips(); fill(); askStorage()
    }

    override fun onPause() { super.onPause(); Backup.export(this) }

    /** Na primeira vez, pede acesso à memória interna para criar a pasta EmuladorPro onde o usuário enxerga. */
    private fun askStorage() {
        if (Store.canUseShared(this) || p.askedStorage) return
        p.askedStorage = true
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Pasta EmuladorPro")
            .setMessage("Para criar a pasta EmuladorPro na memória interna (saves, estados, cheats, capas e botões), o app precisa de acesso aos arquivos. Sem isso, tudo fica numa área privada do app. Você pode permitir depois em Configurações > Avançado.")
            .setPositiveButton("Permitir") { _, _ -> Store.requestAccess(this) }
            .setNegativeButton("Agora não", null).show()
    }

    private fun chips() {
        chipHost.removeAllViews()
        if (land) { chipHost.addView(T.chips(this, listOf("Todos", "Favoritos", "Recentes"), filter) { filter = it; chips(); fill() }); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(T.chips(this, listOf("Todos", "Favoritos", "Recentes"), filter) { filter = it; chips(); fill() })
        col.addView(T.chips(this, listOf("A-Z", "Último jogado"), sort) { sort = it; chips(); fill() })
        chipHost.addView(col)
    }

    private fun visible(): List<Game> {
        var g = p.games().filter { it.name.contains(q, true) }
        g = when (filter) { "Favoritos" -> g.filter { p.isFav(it.id) }; "Recentes" -> g.filter { p.lastPlayed(it.id) > 0 }; else -> g }
        return if (sort == "A-Z") g.sortedBy { it.name.lowercase() } else g.sortedByDescending { p.lastPlayed(it.id) }
    }

    private fun fill() {
        if (land) { fillLand(); return }
        list.removeAllViews()
        val g = visible()
        if (g.isEmpty()) list.addView(T.note(this, "Nenhum jogo na lista. Toque em “Adicionar jogos”."))
        g.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { setPadding(0, T.dp(context, 6), 0, T.dp(context, 6)) }
            pair.forEach { row.addView(card(it), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = T.dp(this@LibraryActivity, 6); rightMargin = T.dp(this@LibraryActivity, 6) }) }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            list.addView(row)
        }
    }

    // ======================= MODO PAISAGEM =======================

    private fun landUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop.drawable(context, p)
                ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(T.BG, T.BG, T.shade(T.GOLD, 0.30f)))
        }
        val gear = ImageButton(this).apply {
            setImageResource(com.meu.gbaemu.R.drawable.ic_gear)
            background = T.box(this@LibraryActivity, T.CARD, T.GOLD); contentDescription = "Configurações"
            setOnClickListener { startActivity(Intent(this@LibraryActivity, SettingsActivity::class.java)) }
        }
        val logo = ImageView(this).apply { setImageResource(com.meu.gbaemu.R.drawable.logo_gba); scaleType = ImageView.ScaleType.FIT_CENTER }
        chipHost = FrameLayout(this)
        val search = EditText(this).apply {
            hint = "Buscar"; setHintTextColor(T.MUT); T.font?.let { typeface = it }; setTextColor(T.TX); setSingleLine(); textSize = 13f
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = T.box(this@LibraryActivity, T.CARD, T.LINE, 20)
            setPadding(T.dp(context, 14), T.dp(context, 7), T.dp(context, 14), T.dp(context, 7))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { q = s.toString(); fill() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(T.dp(context, 16), T.dp(context, 8), T.dp(context, 16), T.dp(context, 4))
            addView(logo, LinearLayout.LayoutParams(T.dp(context, 62), T.dp(context, 34)))
            addView(chipHost, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = T.dp(context, 12) })
            addView(search, LinearLayout.LayoutParams(T.dp(context, 150), -2).apply { rightMargin = T.dp(context, 10) })
            addView(gear, LinearLayout.LayoutParams(T.dp(context, 42), T.dp(context, 42)))
        })
        lrow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false
            setPadding(T.dp(context, 24), T.dp(context, 14), T.dp(context, 24), T.dp(context, 14))
        }
        lhs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lrow) }
        root.addView(lhs, LinearLayout.LayoutParams(-1, -2))
        lname = T.text(this, "", 26f, T.TX, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        linfo = T.text(this, "", 13f, T.MUT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(T.dp(context, 28), T.dp(context, 2), T.dp(context, 28), 0)
            addView(lname); addView(linfo)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(hintBar())
        return root
    }

    /** Barra de dicas embaixo (como na referência): cada item é tocável. */
    private fun hintBar(): View {
        fun hint(badge: String, label: String, click: () -> Unit): Pair<View, TextView> {
            val lb = T.text(this, label, 12f, T.TX)
            val v = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(T.dp(context, 8), T.dp(context, 4), T.dp(context, 8), T.dp(context, 4))
                setOnClickListener { click() }
                addView(T.text(context, badge, 12f, T.GOLD2, true).apply {
                    setPadding(T.dp(context, 7), T.dp(context, 2), T.dp(context, 7), T.dp(context, 2)); background = T.box(context, T.CARD, T.GOLD, 8)
                })
                addView(lb, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = T.dp(context, 6) })
            }
            return v to lb
        }
        val inner = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        inner.addView(hint("＋", "Adicionar") { pickRoms() }.first)
        inner.addView(hint("📁", "Pasta") { pickFolder() }.first)
        inner.addView(hint("⬇", "Capas") { downloadCovers(p.games(), false) }.first)
        val ord = hint("⇅", "Ordem: $sort") { toggleSort() }
        lsortLbl = ord.second; inner.addView(ord.first)
        inner.addView(hint("⋯", "Opções") { selGame()?.let { options(it) } }.first)
        inner.addView(hint("▶", "Jogar") { selGame()?.let { play(it) } }.first)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true; setBackgroundColor(0x66000000)
            setPadding(T.dp(context, 12), T.dp(context, 6), T.dp(context, 12), T.dp(context, 8))
            addView(inner, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun toggleSort() { sort = if (sort == "A-Z") "Último jogado" else "A-Z"; fill() }

    private fun selGame(): Game? = shown.firstOrNull { it.id == selId }

    private fun fillLand() {
        shown = visible()
        lrow.removeAllViews(); tiles.clear()
        lsortLbl.text = "Ordem: $sort"
        if (shown.isEmpty()) {
            lrow.addView(T.note(this, "Nenhum jogo na lista. Toque em “Adicionar”."))
            lname.text = ""; linfo.text = ""; return
        }
        val size = (resources.displayMetrics.heightPixels * 0.40f).toInt().coerceIn(T.dp(this, 110), T.dp(this, 190))
        shown.forEach { g ->
            val t = tile(g)
            tiles[g.id] = t
            lrow.addView(t, LinearLayout.LayoutParams(size, size).apply { rightMargin = T.dp(this@LibraryActivity, 14) })
        }
        select(selId?.takeIf { id -> shown.any { it.id == id } } ?: shown.first().id)
    }

    private fun tile(g: Game): View {
        val bmp = coverBitmap(g.id)
        val inner = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(T.CARD); cornerRadius = T.dp(context, 12).toFloat() }
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            val cov: View = if (bmp != null) ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            else T.text(context, g.name.take(2).uppercase(), 30f, T.BG, true).apply { gravity = Gravity.CENTER; setBackgroundColor(T.GOLD) }
            addView(cov, FrameLayout.LayoutParams(-1, -1))
            if (p.isFav(g.id)) addView(T.text(context, "★", 16f, T.GOLD2, true).apply {
                setPadding(T.dp(context, 7), T.dp(context, 1), T.dp(context, 7), T.dp(context, 1)); setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        return FrameLayout(this).apply {
            setPadding(T.dp(context, 3), T.dp(context, 3), T.dp(context, 3), T.dp(context, 3))
            addView(inner, FrameLayout.LayoutParams(-1, -1))
            styleTile(this, false)
            // 1º toque seleciona; toque no selecionado joga; segurar abre as opções
            setOnClickListener { if (selId == g.id) play(g) else select(g.id) }
            setOnLongClickListener { select(g.id); options(g); true }
        }
    }

    private fun styleTile(v: View, on: Boolean) {
        v.background = GradientDrawable().apply {
            setColor(0x00000000); cornerRadius = T.dp(this@LibraryActivity, 15).toFloat()
            setStroke(T.dp(this@LibraryActivity, if (on) 3 else 1), if (on) T.GOLD2 else T.LINE)
        }
        val k = if (on) 1.08f else 1f
        v.animate().scaleX(k).scaleY(k).setDuration(120).start()
    }

    private fun select(id: String) {
        selId = id
        tiles.forEach { (k, v) -> styleTile(v, k == id) }
        val g = shown.firstOrNull { it.id == id } ?: return
        lname.text = g.name
        linfo.text = listOfNotNull(lastTxt(g.id), if (p.isFav(g.id)) "★ Favorito" else null, shLabel(p.shaderFor(g.id)), p.kbFor(g.id)).joinToString("  ·  ")
        tiles[id]?.let { t -> lhs.post { lhs.smoothScrollTo(t.left + t.width / 2 - lhs.width / 2, 0) } }
    }

    private fun lastTxt(id: String): String {
        val t = p.lastPlayed(id); if (t <= 0L) return "Nunca jogado"
        val z = java.util.TimeZone.getDefault(); val day = 86400000L
        fun d(x: Long) = (x + z.getOffset(x)) / day
        val diff = d(System.currentTimeMillis()) - d(t)
        return when {
            diff <= 0L -> "Hoje"
            diff == 1L -> "Ontem"
            else -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(t))
        }
    }

    /** Controle/teclado no menu paisagem: ←/→ navega, A joga, Y opções, X ordem, START configurações. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (land && e.action == KeyEvent.ACTION_DOWN && currentFocus !is EditText) {
            val i = shown.indexOfFirst { it.id == selId }
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (i > 0) select(shown[i - 1].id); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i >= 0 && i < shown.size - 1) select(shown[i + 1].id); return true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> { selGame()?.let { play(it) }; return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { selGame()?.let { options(it) }; return true }
                KeyEvent.KEYCODE_BUTTON_X -> { toggleSort(); return true }
                KeyEvent.KEYCODE_BUTTON_START -> { startActivity(Intent(this, SettingsActivity::class.java)); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun shLabel(v: String) = when (v) { SH_GLOBAL -> "Shader padrão"; SH_NONE -> "Sem shader"; else -> v }

    private fun coverBitmap(id: String): Bitmap? {
        val f = p.coverFile(id); if (!f.exists()) return null
        return covers.getOrPut(f.path + f.lastModified()) { BitmapFactory.decodeFile(f.path) }
    }

    private fun card(g: Game): View {
        val bmp = coverBitmap(g.id)
        val cov: View = if (bmp != null) ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
        else T.text(this, g.name.take(2).uppercase(), 30f, T.BG, true).apply { gravity = Gravity.CENTER; setBackgroundColor(T.GOLD) }
        // SquareFrame: a altura acompanha a largura, então a capa fica sempre quadrada perfeita
        val box = SquareFrame(this).apply {
            addView(cov, FrameLayout.LayoutParams(-1, -1))
            if (p.isFav(g.id)) addView(T.text(context, "★", 18f, T.GOLD2, true).apply {
                setPadding(T.dp(context, 8), T.dp(context, 2), T.dp(context, 8), T.dp(context, 2)); setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = T.box(context, T.CARD, T.LINE, 14)
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND // cantos arredondados também na imagem
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(T.text(context, g.name, 14f, T.TX, true).apply { maxLines = 2; setPadding(T.dp(context, 10), T.dp(context, 8), T.dp(context, 10), 0) })
            addView(T.note(context, "${shLabel(p.shaderFor(g.id))} · ${p.kbFor(g.id)}").apply { setPadding(T.dp(context, 10), 0, T.dp(context, 10), T.dp(context, 8)) })
            setOnClickListener { play(g) }
            setOnLongClickListener { options(g); true }
        }
    }

    private fun options(g: Game) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = T.box(context, T.BG, T.GOLD, 18)
            setPadding(T.dp(context, 16), T.dp(context, 16), T.dp(context, 16), T.dp(context, 16))
            minimumWidth = minOf((resources.displayMetrics.widthPixels * 0.9f).toInt(), T.dp(context, 560))
        }
        lateinit var dlg: AlertDialog
        val cols = Dlg.cols(this)
        fun again() { dlg.dismiss(); options(g); fill() }
        fun section(title: String, rows: List<Row>) {
            col.addView(T.h(this, title))
            col.addView(Dlg.grid(this, rows, cols) { dlg.dismiss() })
        }
        /** Opção por jogo: 1ª linha = "Padrão" (apaga a escolha do jogo e volta a valer a das configurações). */
        fun choice(icon: String, title: String, key: String, labels: List<String>, values: List<String>): Row {
            val cur = p.gameVal(g.id, key)
            val shown = cur?.let { labels.getOrNull(values.indexOf(it)) } ?: "Padrão"
            return Row(icon, title, shown) {
                pick(title, listOf("Padrão (das configurações)") + labels, if (cur == null) 0 else values.indexOf(cur) + 1) { i ->
                    p.setG(g.id, key, if (i == 0) null else values[i - 1]); again()
                }
            }
        }
        val hasCover = p.coverFile(g.id).exists()
        col.addView(Dlg.coverHeader(this, g.name, coverBitmap(g.id)))
        col.addView(T.text(this, "▶  JOGAR", 16f, T.BG, true).apply {
            gravity = Gravity.CENTER; background = T.box(context, T.GOLD, T.GOLD); setPadding(0, T.dp(context, 13), 0, T.dp(context, 13))
            setOnClickListener { dlg.dismiss(); play(g) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 12) })
        col.addView(Dlg.button(this, "↻  Reiniciar jogo", false) { dlg.dismiss(); play(g, true) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 8) })

        section("Capa", listOfNotNull(
            Row("image", if (hasCover) "Trocar capa" else "Escolher capa", "da galeria") { pickCover(g) },
            Row("import", "Baixar capa", "online, opcional") { downloadCovers(listOf(g), true) },
            Row("image", "Capa do jogo original", "usar a de outro jogo da lista") { pickOriginal(g) { again() } },
            if (hasCover) Row("trash", "Remover capa") { p.coverFile(g.id).delete(); covers.clear(); again() } else null))

        val ratios = listOf("Original 3:2", "4:3", "16:9", "Tela cheia")
        val av = p.availableShaders(); val shKeys = listOf(SH_GLOBAL, SH_NONE) + av
        section("Configuração só deste jogo", listOf(
            Row("filter", "Shader", shLabel(p.shaderFor(g.id))) {
                pick("Shader deste jogo", listOf("Shader padrão (das configurações)", "Sem shader") + av, shKeys.indexOf(p.shaderFor(g.id))) { p.setShader(g.id, shKeys[it]); again() }
            },
            Row("pad", "Teclado", p.kbFor(g.id)) {
                pick("Perfil de teclado", KB_PROFILES, KB_PROFILES.indexOf(p.kbFor(g.id))) { p.setKb(g.id, KB_PROFILES[it]); again() }
            },
            choice("scale", "Orientação", "orient", listOf("Automático", "Retrato", "Paisagem"), listOf("auto", "retrato", "paisagem")),
            choice("scale", "Proporção", "ratio", ratios, ratios),
            choice("ff", "Fast-forward", "ff", FF_STEPS, FF_STEPS),
            choice("vibrate", "Volume", "vol", listOf("0% (mudo)", "25%", "50%", "75%", "100%"), listOf("0", "25", "50", "75", "100")),
            Row("restart", "Restaurar", "padrões do jogo") { p.resetGame(g.id); again() }))

        section("Biblioteca", listOf(
            Row("check", if (p.isFav(g.id)) "Tirar dos favoritos" else "Favoritar") { p.setFav(g.id, !p.isFav(g.id)); fill() },
            Row("trash", "Remover da lista") { p.removeGame(g.id); fill() }))

        dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(ScrollView(this).apply { addView(col) }).create(); dlg.show(); dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /** Baixa capas online (opcional). Identifica o jogo pelo CRC da ROM, então não depende do nome do arquivo. */
    private fun downloadCovers(list: List<Game>, force: Boolean) {
        val todo = if (force) list else list.filter { !p.coverFile(it.id).exists() }
        if (todo.isEmpty()) { Toast.makeText(this, "Todos os jogos já têm capa", Toast.LENGTH_SHORT).show(); return }
        val pd = ProgressDlg(this, "Baixando capas")
        Thread {
            var ok = 0; var fail = 0
            for ((i, g) in todo.withIndex()) {
                if (pd.cancelled) break
                pd.set("${i + 1} de ${todo.size}\n${g.name}")
                if (try { Covers.fetch(this, p, g) } catch (e: Throwable) { false }) ok++ else fail++
            }
            Net.main.post {
                pd.close(); covers.clear(); fill(); linkCovers()
                Toast.makeText(this, if (ok == 0 && fail > 0) "Não consegui baixar (sem internet ou jogo não encontrado)" else "$ok capa(s) baixada(s)" + if (fail > 0) " · $fail não encontrada(s)" else "", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun pickCover(g: Game) {
        p.pendingCover = g.id
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 2)
    }

    private fun saveCover(u: Uri?) {
        val id = p.pendingCover
        if (u == null || id.isBlank()) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
            var ss = 1
            while (o.outWidth / ss > 1200 || o.outHeight / ss > 1200) ss *= 2
            val src = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }
                ?: throw Exception("imagem inválida")
            Covers.saveSquare(p.coverFile(id), src) // corte central quadrado
            covers.clear(); fill()
            Toast.makeText(this, "Capa definida", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro na capa: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pick(title: String, labels: List<String>, cur: Int, done: (Int) -> Unit) {
        lateinit var dlg: AlertDialog
        val rows = labels.mapIndexed { i, t -> Row(null, t, checked = i == cur) { done(i) } }
        dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(Dlg.content(this, title, rows, { dlg.dismiss() })).create()
        dlg.show(); dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun play(g: Game, fresh: Boolean = false) {
        p.touch(g.id)
        startActivity(Intent(this, MainActivity::class.java).putExtra("rom_uri", g.uri).putExtra("game_id", g.id).putExtra("fresh", fresh))
    }

    private fun pickRoms() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 1)
    }

    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "jogo"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == 2) { saveCover(data.data); return }
        if (req == 3) { data.data?.let { scanFolder(it) }; return }
        if (req != 1) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        val ok = listOf(".gba", ".gb", ".gbc", ".zip")
        val add = uris.mapNotNull { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            val n = displayName(u); if (ok.any { n.lowercase().endsWith(it) }) u.toString() to n else null
        }
        if (add.isEmpty()) Toast.makeText(this, "Nenhum arquivo .gba/.zip válido", Toast.LENGTH_SHORT).show()
        p.addGames(add); fill(); linkCovers()
    }

    // ---------------- Procurar todas as ROMs de uma pasta (com subpastas) ----------------

    private val romExt = listOf(".gba", ".gb", ".gbc", ".zip")

    private fun pickFolder() {
        Toast.makeText(this, "Escolha a pasta das ROMs e toque em “Usar esta pasta”", Toast.LENGTH_LONG).show()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, 3)
    }

    private fun scanFolder(tree: Uri) {
        try { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        val pd = ProgressDlg(this, "Procurando ROMs")
        val known = p.games().map { it.uri }.toHashSet()
        Thread {
            val found = ArrayList<Pair<String, String>>()
            val stack = ArrayList<String>()
            stack.add(DocumentsContract.getTreeDocumentId(tree))
            var folders = 0
            try {
                while (stack.isNotEmpty() && !pd.cancelled && folders < 5000) {
                    val dir = stack.removeAt(stack.size - 1); folders++
                    val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
                    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                    contentResolver.query(kids, cols, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { stack.add(id); continue }
                            val low = name.lowercase()
                            if (romExt.none { low.endsWith(it) }) continue
                            val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            if (u.toString() in known) continue
                            if (low.endsWith(".zip") && !zipHasRom(u)) continue
                            found += u.toString() to name
                            pd.set("${found.size} jogo(s) encontrado(s)\n$name")
                        }
                    }
                }
            } catch (e: Throwable) { }
            Net.main.post {
                pd.close()
                if (found.isEmpty()) Toast.makeText(this, "Nenhuma ROM nova encontrada nessa pasta", Toast.LENGTH_LONG).show()
                else {
                    p.addGames(found); fill()
                    Toast.makeText(this, "${found.size} jogo(s) adicionado(s)", Toast.LENGTH_LONG).show()
                    linkCovers()
                }
            }
        }.start()
    }

    /** Um .zip só entra na lista se tiver uma ROM dentro (olha só as primeiras entradas). */
    private fun zipHasRom(u: Uri): Boolean = try {
        var ok = false
        contentResolver.openInputStream(u)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                var n = 0
                var e = z.nextEntry
                while (e != null && n < 8 && !ok) {
                    val l = e.name.lowercase()
                    ok = l.endsWith(".gba") || l.endsWith(".gb") || l.endsWith(".gbc")
                    n++
                    if (!ok) e = z.nextEntry
                }
            }
        }
        ok
    } catch (e: Throwable) { false }

    // ---------------- Mods (hacks) usam a capa do jogo original ----------------

    private val codes = HashMap<String, String?>()

    private fun readN(ins: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var o = 0
        while (o < n) { val r = ins.read(b, o, n - o); if (r < 0) return null; o += r }
        return b
    }

    /** Código de 4 letras do jogo, lido do cabeçalho da ROM (os hacks costumam manter o do original). */
    private fun gameCode(g: Game): String? = codes.getOrPut(g.id) {
        try {
            val low = g.name.lowercase()
            val h: ByteArray? = contentResolver.openInputStream(Uri.parse(g.uri))?.use { ins ->
                if (low.endsWith(".zip")) ZipInputStream(ins.buffered()).use { z ->
                    var r: ByteArray? = null
                    var n = 0
                    var e = z.nextEntry
                    while (e != null && n < 8 && r == null) {
                        if (e.name.lowercase().endsWith(".gba")) r = readN(z, 0xB0) else { n++; e = z.nextEntry }
                    }
                    r
                } else if (low.endsWith(".gba")) readN(ins.buffered(), 0xB0) else null
            }
            h?.let { String(it, 0xAC, 4, Charsets.US_ASCII) }?.takeIf { c -> c.matches(Regex("[A-Z0-9]{4}")) }
        } catch (e: Throwable) { null }
    }

    private fun norm(s: String) = s.substringBeforeLast('.').replace(Regex("[\\(\\[].*?[\\)\\]]"), "").lowercase().filter { it.isLetterOrDigit() }

    /** Plano B: o nome do hack começa com o nome do original (ex.: "Pokemon Emerald Kaizo" → "Pokemon Emerald"). */
    private fun byName(m: Game, cands: List<Game>): Game? {
        val nm = norm(m.name); if (nm.length < 5) return null
        return cands.filter { it.id != m.id && norm(it.name).let { c -> c.length >= 5 && nm.startsWith(c) } }.maxByOrNull { norm(it.name).length }
    }

    private fun copyCover(src: Game, dst: Game): Boolean = try {
        val d = p.coverFile(dst.id); d.parentFile?.mkdirs()
        p.coverFile(src.id).copyTo(d, true); true
    } catch (e: Throwable) { false }

    /** Jogos sem capa (mods) pegam a capa do original que já tem capa na lista. */
    private fun linkCovers() {
        val all = p.games()
        Thread {
            var n = 0
            val comCapa = all.filter { p.coverFile(it.id).exists() }
            val without = all.filter { !p.coverFile(it.id).exists() }
            if (comCapa.isNotEmpty() && without.isNotEmpty()) {
                val byCode = HashMap<String, Game>()
                for (o in comCapa) gameCode(o)?.let { byCode.putIfAbsent(it, o) }
                for (m in without) {
                    val src = gameCode(m)?.let { byCode[it] } ?: byName(m, comCapa)
                    if (src != null && src.id != m.id && copyCover(src, m)) n++
                }
            }
            if (n > 0) Net.main.post {
                covers.clear(); fill()
                Toast.makeText(this, "$n capa(s) do jogo original aplicada(s)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Escolha manual: usa a capa de outro jogo da lista como capa deste mod. */
    private fun pickOriginal(g: Game, done: () -> Unit) {
        val c = p.games().filter { it.id != g.id && p.coverFile(it.id).exists() }.sortedBy { it.name.lowercase() }
        if (c.isEmpty()) { Toast.makeText(this, "Nenhum outro jogo tem capa ainda. Baixe as capas primeiro.", Toast.LENGTH_LONG).show(); return }
        pick("Capa de qual jogo original?", c.map { it.name }, -1) { i ->
            if (copyCover(c[i], g)) { covers.clear(); Toast.makeText(this, "Capa definida", Toast.LENGTH_SHORT).show(); done() }
        }
    }
}
