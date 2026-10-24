package com.meu.gbaemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import com.meu.gbaemu.MainActivity

class LibraryActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var list: LinearLayout
    private lateinit var chipHost: FrameLayout
    private val covers = HashMap<String, Bitmap?>()
    private var sig = 0
    private var bgSig = ""
    private var filter = "Todos"; private var sort = "A-Z"; private var q = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); p = Prefs(this); sig = T.sig(); window.statusBarColor = T.BG
        p.applyOrientation(this); bgSig = Backdrop.sig(p)
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(T.chips(this, listOf("Todos", "Favoritos", "Recentes"), filter) { filter = it; chips(); fill() })
        col.addView(T.chips(this, listOf("A-Z", "Último jogado"), sort) { sort = it; chips(); fill() })
        chipHost.addView(col)
    }

    private fun fill() {
        list.removeAllViews()
        var g = p.games().filter { it.name.contains(q, true) }
        g = when (filter) { "Favoritos" -> g.filter { p.isFav(it.id) }; "Recentes" -> g.filter { p.lastPlayed(it.id) > 0 }; else -> g }
        g = if (sort == "A-Z") g.sortedBy { it.name.lowercase() } else g.sortedByDescending { p.lastPlayed(it.id) }
        if (g.isEmpty()) list.addView(T.note(this, "Nenhum jogo na lista. Toque em “Adicionar jogos”."))
        g.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { setPadding(0, T.dp(context, 6), 0, T.dp(context, 6)) }
            pair.forEach { row.addView(card(it), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = T.dp(this@LibraryActivity, 6); rightMargin = T.dp(this@LibraryActivity, 6) }) }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            list.addView(row)
        }
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
        val box = FrameLayout(this).apply {
            addView(cov, FrameLayout.LayoutParams(-1, -1))
            if (p.isFav(g.id)) addView(T.text(context, "★", 18f, T.GOLD2, true).apply {
                setPadding(T.dp(context, 8), T.dp(context, 2), T.dp(context, 8), T.dp(context, 2)); setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = T.box(context, T.CARD, T.LINE, 14)
            addView(box, LinearLayout.LayoutParams(-1, T.dp(context, 110)))
            addView(T.text(context, g.name, 14f, T.TX, true).apply { maxLines = 2; setPadding(T.dp(context, 10), T.dp(context, 8), T.dp(context, 10), 0) })
            addView(T.note(context, "${shLabel(p.shaderFor(g.id))} · ${p.kbFor(g.id)}").apply { setPadding(T.dp(context, 10), 0, T.dp(context, 10), T.dp(context, 8)) })
            setOnClickListener { play(g) }
            setOnLongClickListener { options(g); true }
        }
    }

    private fun options(g: Game) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = T.box(context, T.BG, T.GOLD, 18); setPadding(T.dp(context, 18), T.dp(context, 16), T.dp(context, 18), T.dp(context, 16)) }
        lateinit var dlg: AlertDialog
        fun again() { dlg.dismiss(); options(g); fill() }
        fun rowBtn(label: String, click: () -> Unit) = col.addView(
            T.text(this, label, 15f, T.GOLD2).apply { background = T.box(context, T.CARD, T.GOLD); setPadding(T.dp(context, 14), T.dp(context, 12), T.dp(context, 14), T.dp(context, 12)); setOnClickListener { click() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 10) })
        /** Opção por jogo: 1ª linha = "Padrão" (apaga a escolha do jogo e volta a valer a das configurações). */
        fun choice(title: String, key: String, labels: List<String>, values: List<String>) {
            val cur = p.gameVal(g.id, key)
            val shown = cur?.let { labels.getOrNull(values.indexOf(it)) } ?: "Padrão"
            rowBtn("$title: $shown") {
                pick(title, listOf("Padrão (das configurações)") + labels, if (cur == null) 0 else values.indexOf(cur) + 1) { i ->
                    p.setG(g.id, key, if (i == 0) null else values[i - 1]); again()
                }
            }
        }
        col.addView(T.text(this, g.name, 18f, T.GOLD2, true))
        col.addView(T.text(this, "▶  JOGAR", 16f, T.BG, true).apply {
            gravity = Gravity.CENTER; background = T.box(context, T.GOLD, T.GOLD); setPadding(0, T.dp(context, 13), 0, T.dp(context, 13))
            setOnClickListener { dlg.dismiss(); play(g) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = T.dp(this@LibraryActivity, 12) })
        col.addView(T.h(this, "Capa"))
        rowBtn(if (p.coverFile(g.id).exists()) "Trocar imagem da capa" else "Escolher imagem da capa") { dlg.dismiss(); pickCover(g) }
        if (p.coverFile(g.id).exists()) rowBtn("Remover capa") { p.coverFile(g.id).delete(); covers.clear(); again() }
        col.addView(T.h(this, "Configuração só deste jogo"))
        rowBtn("Shader deste jogo: ${shLabel(p.shaderFor(g.id))}") {
            val av = p.availableShaders(); val keys = listOf(SH_GLOBAL, SH_NONE) + av
            pick("Shader deste jogo", listOf("Shader padrão (das configurações)", "Sem shader") + av, keys.indexOf(p.shaderFor(g.id))) { p.setShader(g.id, keys[it]); again() }
        }
        rowBtn("Teclado deste jogo: ${p.kbFor(g.id)}") {
            pick("Perfil de teclado", KB_PROFILES, KB_PROFILES.indexOf(p.kbFor(g.id))) { p.setKb(g.id, KB_PROFILES[it]); again() }
        }
        choice("Orientação", "orient", listOf("Automático", "Retrato", "Paisagem"), listOf("auto", "retrato", "paisagem"))
        val ratios = listOf("Original 3:2", "4:3", "16:9", "Tela cheia")
        choice("Proporção da tela", "ratio", ratios, ratios)
        choice("Fast-forward", "ff", FF_STEPS, FF_STEPS)
        choice("Volume", "vol", listOf("0% (mudo)", "25%", "50%", "75%", "100%"), listOf("0", "25", "50", "75", "100"))
        rowBtn("Restaurar configurações deste jogo") { p.resetGame(g.id); again() }
        col.addView(T.h(this, "Biblioteca"))
        rowBtn(if (p.isFav(g.id)) "★ Remover dos favoritos" else "☆ Favoritar") { p.setFav(g.id, !p.isFav(g.id)); dlg.dismiss(); fill() }
        rowBtn("Remover da lista") { p.removeGame(g.id); dlg.dismiss(); fill() }
        dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(ScrollView(this).apply { addView(col) }).create(); dlg.show(); dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
            val k = minOf(1f, 640f / maxOf(src.width, src.height))
            val w = (src.width * k).toInt().coerceAtLeast(1); val h = (src.height * k).toInt().coerceAtLeast(1)
            val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(0xFF0A0A0A.toInt()); drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, null) }
            p.coverFile(id).outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            covers.clear(); fill()
            Toast.makeText(this, "Capa definida", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro na capa: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pick(title: String, labels: List<String>, cur: Int, done: (Int) -> Unit) {
        lateinit var dlg: AlertDialog
        val rows = labels.mapIndexed { i, t -> Row(null, t, checked = i == cur) { done(i) } }
        dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(Dlg.content(this, title, rows) { dlg.dismiss() }).create()
        dlg.show(); dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun play(g: Game) {
        p.touch(g.id)
        startActivity(Intent(this, MainActivity::class.java).putExtra("rom_uri", g.uri).putExtra("game_id", g.id))
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
        p.addGames(add); fill()
    }
}
