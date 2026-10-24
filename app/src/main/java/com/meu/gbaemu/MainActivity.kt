package com.meu.gbaemu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import com.meu.gbaemu.ui.Backup
import com.meu.gbaemu.ui.CheatBank
import com.meu.gbaemu.ui.Ident
import com.meu.gbaemu.ui.Net
import com.meu.gbaemu.ui.ProgressDlg
import com.meu.gbaemu.ui.RatioFrame
import com.meu.gbaemu.ui.Store
import com.meu.gbaemu.ui.Cheat
import com.meu.gbaemu.ui.Cheats
import com.meu.gbaemu.ui.Dlg
import com.meu.gbaemu.ui.KB_PROFILES
import com.meu.gbaemu.ui.orientCode
import com.meu.gbaemu.ui.Row
import com.meu.gbaemu.ui.T
import com.meu.gbaemu.ui.SH_GLOBAL
import com.meu.gbaemu.ui.SH_NONE
import com.meu.gbaemu.ui.SettingsActivity
import com.meu.gbaemu.ui.Prefs as UiPrefs
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var emu: Emulator
    private lateinit var uiP: UiPrefs
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GbaRenderer
    private lateinit var controls: ControlsView
    private lateinit var prefs: SharedPreferences
    private var touchMask = 0
    private var padMask = 0
    private var axisMask = 0
    private var openDialogs = 0
    private var profile = KB_PROFILES[0]
    private var editOnly = false
    private var gameId: String? = null
    private var gameName = ""
    private var cheats = ArrayList<Cheat>()
    private var bgSig = ""
    // estado do menu aberto: se o app for fechado pelo sistema enquanto você está fora, ele volta com o mesmo menu (e o texto do cheat) aberto
    private var openMenu = ""
    private var draftName = ""
    private var draftCode = ""
    private var restored = false
    private var romInfo: Ident.Info? = null
    private var coverCache: Bitmap? = null
    private var coverTried = false
    private fun layKey(base: String) = if (profile == KB_PROFILES[0]) base else "$base.$profile"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        restored = b != null
        prefs = getSharedPreferences("gba", MODE_PRIVATE)
        uiP = UiPrefs(this)
        val ui = uiP
        val gid = intent.getStringExtra("game_id")
        gameId = gid
        editOnly = intent.getBooleanExtra("edit_layout", false)
        profile = intent.getStringExtra("profile") ?: gid?.let { ui.kbFor(it) } ?: KB_PROFILES[0]
        applyOrientationNow()
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emu = Emulator(this) { glView.requestRender() }
        // guarda de cheats: marcado enquanto há cheats ativos; se o app morrer assim, na próxima abertura eles vêm desligados
        val guard = getSharedPreferences("guard", MODE_PRIVATE)
        emu.onCheatArm = { on -> guard.edit().apply { if (on) putString("cheatGuard", gameName) else remove("cheatGuard") }.commit() }
        emu.onFatal = { m -> toast("O jogo parou por um erro ($m). Use Carregar estado ou volte à biblioteca.") }
        emu.ffMul = ui.ffMultiplierFor(gid); emu.ffAudio = ui.ffAudio; emu.autoEveryMs = ui.autoSaveMs
        emu.volume = if (ui.mute) 0f else ui.volumeFor(gid) / 100f
        renderer = GbaRenderer(this, emu) { msg -> toast(msg) }
        renderer.scale = prefs.getInt("scale", 3)
        renderer.linear = prefs.getBoolean("linear", false)
        renderer.ratio = ui.ratioFor(gid); renderer.rectPort = ui.screenRect(false); renderer.rectLand = ui.screenRect(true)
        renderer.shaderKeys = shaderKeysFor(gid)
        renderer.fxBright = ui.fxBright; renderer.fxContrast = ui.fxContrast; renderer.fxSat = ui.fxSat; renderer.fxLcd = ui.fxLcd
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        controls = ControlsView(this).apply {
            useStick = prefs.getBoolean("stick", false)
            opacity = prefs.getFloat("opacity", 0.85f)
            haptic = prefs.getBoolean("haptic", true)
            showCombo = ui.showCombo; showTurboA = ui.showTurboA; showTurboB = ui.showTurboB
            layoutPort = prefs.getString(layKey("lay_port"), "") ?: ""
            layoutLand = prefs.getString(layKey("lay_land"), "") ?: ""
            onMask = { m -> touchMask = m; pushInput() }
            onMenu = {
                touchMask = 0; pushInput()
                if (editMode) { editMode = false; if (editOnly) finish() } else showMenu()
            }
            onLayoutChanged = { prefs.edit().putString(layKey("lay_port"), layoutPort).putString(layKey("lay_land"), layoutLand).apply() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        })
        if (editOnly) controls.editMode = true
        else (intent.getStringExtra("rom_uri") ?: prefs.getString("rom_uri", null))?.let { u ->
            try { loadRom(Uri.parse(u)) } catch (e: Exception) { toast("Erro: ${e.message}") }
        }
        if (b != null && !editOnly) {
            val m = b.getString("menu") ?: ""
            if (m.isNotEmpty()) { draftName = b.getString("dn") ?: ""; draftCode = b.getString("dc") ?: ""; glView.post { reopen(m) } }
        }
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putString("menu", openMenu); o.putString("dn", draftName); o.putString("dc", draftCode)
    }

    /** Reabre o menu que estava aberto quando o app foi fechado pelo sistema. */
    private fun reopen(id: String) {
        when {
            id == "menu" -> showMenu()
            id == "cheats" -> cheatsDialog()
            id.startsWith("cheatEdit:") -> cheatEdit(id.substringAfter(':').toIntOrNull() ?: -1, draftName, draftCode)
            id == "state:save" -> stateDialog(true)
            id == "state:load" -> stateDialog(false)
            id == "fx" -> fxDialog()
        }
    }

    private fun coverBmp(): Bitmap? {
        if (!coverTried) {
            coverTried = true
            coverCache = gameId?.let { uiP.coverFile(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
        return coverCache
    }

    private fun gameTitle(): String = if (gameName.isNotEmpty()) Store.base(gameName) else "Menu"

    // ---------- util ----------
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
    private fun pushInput() { emu.baseMask = touchMask or padMask or axisMask }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }

    private fun showDlg(b: AlertDialog.Builder) { showDlgGet(b) }

    private fun showDlgGet(b: AlertDialog.Builder): AlertDialog {
        openDialogs++; emu.paused = true
        val d = b.create()
        d.setOnDismissListener { openDialogs--; if (openDialogs <= 0) { openDialogs = 0; openMenu = ""; emu.paused = false }; immersive() }
        d.show()
        return d
    }

    private fun pick(req: Int, multiple: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, req)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        when (req) {
            1 -> uris.firstOrNull()?.let { u ->
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                prefs.edit().putString("rom_uri", u.toString()).apply()
                try { loadRom(u) } catch (e: Exception) { toast("Erro: ${e.message}") }
            }
            2 -> importShaders(uris)
        }
    }

    // ---------- ROM ----------
    private fun loadRom(u: Uri) {
        // Permissão do endereço salva perdida (reinstalou/atualizou o app)? Tenta abrir direto pelo caminho do arquivo.
        val direct = try { Backup.pathOf(u.toString())?.takeIf { it.exists() && it.canRead() } } catch (e: Exception) { null }
        var name = try { displayName(u) } catch (e: SecurityException) { direct?.name ?: "jogo.gba" }
        var bytes = try { contentResolver.openInputStream(u)!!.use { it.readBytes() } }
        catch (e: Exception) {
            if (direct != null) direct.readBytes()
            else throw RuntimeException("sem permissão para ler este jogo. Em Configurações toque em “Permitir acesso à memória interna” ou adicione a ROM de novo na biblioteca")
        }
        if (name.lowercase().endsWith(".zip")) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val l = e.name.lowercase()
                    if (l.endsWith(".gba") || l.endsWith(".gb") || l.endsWith(".gbc")) { bytes = z.readBytes(); name = e.name.substringAfterLast('/'); break }
                    e = z.nextEntry
                }
            }
        }
        romInfo = Ident.infoOf(bytes, name)
        var resumedMsg: String? = null
        val err = emu.start(bytes, name) {
            // roda com o núcleo carregado e a thread do jogo ainda parada
            gameName = name
            cheats = Cheats.load(this, name)
            val g = getSharedPreferences("guard", MODE_PRIVATE)
            if (g.getString("cheatGuard", null) == name) {
                // da última vez o app morreu com cheats ligados neste jogo: desliga todos antes de qualquer coisa
                cheats.forEach { it.on = false }; Cheats.save(this, name, cheats)
                g.edit().remove("cheatGuard").commit()
                resumedMsg = "O jogo travou com cheats ligados da última vez. Eles foram desligados: ligue um por vez para achar o que causa o problema"
            }
            emu.applyCheats(cheats)
            if (!editOnly) {
                // o sistema fechou o app enquanto você estava fora: volta exatamente de onde parou
                if (restored && emu.loadResume()) { emu.applyCheats(cheats); if (resumedMsg == null) resumedMsg = "Voltando de onde você parou" }
                else if (uiP.autoResume && !(intent.getBooleanExtra("fresh", false) && !restored) && emu.loadState(0)) { emu.applyCheats(cheats); if (resumedMsg == null) resumedMsg = "Continuando de onde você parou" }
            }
        }
        if (err != null) toast(err)
        else {
            resumedMsg?.let { toast(it) }
            if (!editOnly && uiP.onlineAuto && cheats.isEmpty() && !restored) fetchCheats(true)
            restored = false // vale só para a primeira ROM aberta depois que o sistema recriou a tela
        }
    }

    // ---------- orientação e configurações ao vivo ----------
    private fun applyOrientationNow() {
        requestedOrientation = if (editOnly) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else orientCode(uiP.orientFor(gameId))
    }

    /** Lê de novo as configurações (shader, tela, controles, som, fundo...). Roda ao voltar das Configurações: nada exige sair do jogo. */
    private fun applyPrefsLive() {
        val gid = gameId; val ui = uiP
        applyOrientationNow()
        emu.ffMul = ui.ffMultiplierFor(gid); emu.ffAudio = ui.ffAudio; emu.autoEveryMs = ui.autoSaveMs
        emu.volume = if (ui.mute) 0f else ui.volumeFor(gid) / 100f
        renderer.scale = prefs.getInt("scale", 3); renderer.linear = prefs.getBoolean("linear", false)
        renderer.ratio = ui.ratioFor(gid); renderer.rectPort = ui.screenRect(false); renderer.rectLand = ui.screenRect(true)
        renderer.fxBright = ui.fxBright; renderer.fxContrast = ui.fxContrast; renderer.fxSat = ui.fxSat; renderer.fxLcd = ui.fxLcd
        val keys = shaderKeysFor(gid)
        if (keys != renderer.shaderKeys) renderer.shaderKeys = keys
        renderer.dirty = true
        val bg = ui.bgFile()
        val sig = "${bg?.path}|${bg?.lastModified()}|${ui.bgDim}"
        if (sig != bgSig) { bgSig = sig; renderer.bgDim = ui.bgDim; renderer.setBackground(bg) }
        controls.useStick = prefs.getBoolean("stick", false)
        controls.opacity = prefs.getFloat("opacity", 0.85f)
        controls.haptic = prefs.getBoolean("haptic", true)
        controls.showCombo = ui.showCombo; controls.showTurboA = ui.showTurboA; controls.showTurboB = ui.showTurboB
        controls.reloadSkin(false)
        glView.requestRender()
    }

    // ---------- shaders ----------
    private fun shaderDir() = File(filesDir, "shaders").also { it.mkdirs() }

    private fun availableShaders(): List<String> {
        val d = shaderDir()
        return (d.list()?.toList() ?: emptyList()).filter {
            it.endsWith(".glsl", true) || it.endsWith(".glslp", true) || (it.endsWith(".vsh", true) && File(d, it.substring(0, it.length - 4) + ".fsh").exists())
        }.sorted()
    }

    private fun shaderKeysFor(gid: String?): List<String> = when (val sh = gid?.let { uiP.shaderFor(it) } ?: SH_GLOBAL) {
        SH_GLOBAL -> enabledShaders()
        SH_NONE -> emptyList()
        else -> listOf(sh).filter { it in availableShaders() }
    }

    private fun enabledShaders(): List<String> {
        val av = availableShaders()
        return (prefs.getString("sh_on", "") ?: "").split("\n").filter { it.isNotBlank() && it in av }
    }

    private fun applyShaders(list: List<String>) {
        prefs.edit().putString("sh_on", list.joinToString("\n")).apply()
        gameId?.let { uiP.setShader(it, SH_GLOBAL) } // a escolha feita aqui passa a valer neste jogo também
        renderer.shaderKeys = list; renderer.dirty = true; glView.requestRender()
    }

    /** Escolhe o shader só deste jogo e já aplica na tela, sem sair. */
    private fun setGameShader(v: String) {
        val gid = gameId
        if (gid == null) {
            applyShaders(if (v == SH_NONE) emptyList() else if (v == SH_GLOBAL) enabledShaders() else listOf(v))
            return
        }
        uiP.setShader(gid, v)
        renderer.shaderKeys = shaderKeysFor(gid); renderer.dirty = true; glView.requestRender()
    }

    private fun quickShaderDialog() {
        val cur = gameId?.let { uiP.shaderFor(it) } ?: SH_GLOBAL
        val rows = ArrayList<Row>()
        rows.add(Row("power", "Padrão das configurações", checked = cur == SH_GLOBAL) { setGameShader(SH_GLOBAL) })
        rows.add(Row("power", "Sem shader (pixels puros)", checked = cur == SH_NONE) { setGameShader(SH_NONE) })
        for (k in availableShaders()) rows.add(Row(if (k.endsWith(".glslp", true)) "layers" else "filter", k, checked = cur == k) { setGameShader(k) })
        menuDlg("Shader deste jogo", rows)
    }

    private fun importShaders(uris: List<Uri>) {
        var ok = 0; var skip = 0
        for (u in uris) {
            val nm = displayName(u).replace("/", "_"); val l = nm.lowercase()
            if (l.endsWith(".glsl") || l.endsWith(".glslp") || l.endsWith(".vsh") || l.endsWith(".fsh") || l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".bmp")) {
                contentResolver.openInputStream(u)?.use { i -> File(shaderDir(), nm).outputStream().use { o -> i.copyTo(o) } }; ok++
            } else skip++
        }
        toast("$ok arquivo(s) importado(s)" + if (skip > 0) " • $skip ignorado(s) (aceito: .glsl, .glslp, .vsh/.fsh e imagens PNG)" else "")
    }

    private fun themed(d: AlertDialog) { d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
    private val THEME = AlertDialog.THEME_DEVICE_DEFAULT_DARK

    /** Menu no tema do app (cores RGB e fonte importada): cada linha fecha o menu e executa a ação. */
    private fun menuDlg(title: String, rows: List<Row>, cols: Int = 1, withCover: Boolean = false): AlertDialog {
        lateinit var dlg: AlertDialog
        val content = Dlg.content(this, title, rows, { dlg.dismiss() }, cols, if (withCover) coverBmp() else null, withCover)
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(content))
        themed(dlg)
        return dlg
    }

    private fun okCancel(dlg: () -> AlertDialog, onOk: () -> Unit): View = LinearLayout(this).apply {
        addView(Dlg.button(this@MainActivity, "Cancelar", false) { dlg().dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = T.dp(this@MainActivity, 8) })
        addView(Dlg.button(this@MainActivity, "OK", true) { onOk(); dlg().dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun shadersDialog() {
        val av = availableShaders()
        if (av.isEmpty()) { toast("Nenhum shader importado ainda"); return }
        val on = enabledShaders().toMutableList()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            list.removeAllViews()
            for (k in av) {
                val pos = on.indexOf(k)
                Dlg.add(this, list, Dlg.rowView(this, if (k.endsWith(".glslp", true)) "layers" else "filter", k,
                    if (pos >= 0) "passada ${pos + 1}" else null, pos >= 0) { if (k in on) on.remove(k) else on.add(k); render() })
            }
        }
        render()
        lateinit var dlg: AlertDialog
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(list) }
        Dlg.add(this, body, okCancel({ dlg }) { applyShaders(on) })
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, "Shaders (ordem marcada = ordem das passadas)", body)))
        themed(dlg)
    }

    private fun removeShaderDialog() {
        val av = availableShaders()
        if (av.isEmpty()) { toast("Nenhum shader importado ainda"); return }
        menuDlg("Remover shader", av.map { k ->
            Row("trash", k) {
                File(shaderDir(), k).delete()
                if (k.endsWith(".vsh", true)) File(shaderDir(), k.substring(0, k.length - 4) + ".fsh").delete()
                applyShaders(enabledShaders())
            }
        })
    }

    private fun shaderMenu() {
        menuDlg("Shaders", listOf(
            Row("filter", "Escolher shader deste jogo", sub = "aplica na hora, sem sair") { quickShaderDialog() },
            Row("import", "Importar shaders", sub = ".glsl, .glslp (multipass), .vsh/.fsh e PNGs dos presets") { pick(2, true) },
            Row("layers", "Ativar e ordenar shaders") { shadersDialog() },
            Row("trash", "Remover shader") { removeShaderDialog() },
            Row("scale", "Escala interna das passadas", sub = "${renderer.scale}x") {
                menuDlg("Escala interna", (1..6).map { s ->
                    Row(null, "${s}x", checked = renderer.scale == s) { renderer.scale = s; prefs.edit().putInt("scale", s).apply(); glView.requestRender() }
                })
            },
            Row("filter", "Filtro", sub = if (renderer.linear) "bilinear" else "nearest") {
                renderer.linear = !renderer.linear; prefs.edit().putBoolean("linear", renderer.linear).apply(); glView.requestRender()
            },
            Row("drop", "Cores e efeitos", sub = fxSummary()) { fxDialog() },
            Row("power", "Desativar todos") { applyShaders(emptyList()) }))
    }

    // ---------- cores e efeitos ----------
    private fun fxSummary(): String {
        val ui = uiP
        return if (ui.fxBright == 0 && ui.fxContrast == 0 && ui.fxSat == 0 && ui.fxLcd == 0) "desligado" else "ligado"
    }

    private fun fxSlider(label: String, lo: Int, hi: Int, start: Int, onChange: (Int) -> Unit, onDone: (Int) -> Unit): View {
        val lb = T.text(this, "", 14f, T.MUT)
        val sb = SeekBar(this).apply {
            max = hi - lo; progress = (start - lo).coerceIn(0, hi - lo)
            progressTintList = ColorStateList.valueOf(T.GOLD); thumbTintList = ColorStateList.valueOf(T.GOLD)
        }
        fun upd() { val v = sb.progress + lo; lb.text = "$label: ${if (v > 0 && lo < 0) "+" else ""}$v" }
        upd()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { upd(); onChange(v + lo) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onDone(sb.progress + lo) }
        })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, T.dp(context, 6), 0, T.dp(context, 6)); addView(lb); addView(sb) }
    }

    private fun fxDialog() {
        openMenu = "fx"
        val ui = uiP
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fxSlider("Brilho", -100, 100, ui.fxBright, { renderer.fxBright = it; glView.requestRender() }, { ui.fxBright = it }))
        body.addView(fxSlider("Contraste", -100, 100, ui.fxContrast, { renderer.fxContrast = it; glView.requestRender() }, { ui.fxContrast = it }))
        body.addView(fxSlider("Saturação", -100, 100, ui.fxSat, { renderer.fxSat = it; glView.requestRender() }, { ui.fxSat = it }))
        body.addView(fxSlider("Efeito LCD (opacidade da grade)", 0, 100, ui.fxLcd, { renderer.fxLcd = it; glView.requestRender() }, { ui.fxLcd = it }))
        body.addView(T.note(this, "Tudo em 0 = imagem original, sem gastar desempenho. O efeito LCD escurece a grade entre os pixels do jogo: comece baixo e aumente aos poucos. Vale para todos os jogos e para qualquer shader."))
        lateinit var dlg: AlertDialog
        val row = LinearLayout(this).apply {
            addView(Dlg.button(this@MainActivity, "Zerar tudo", false) {
                ui.fxBright = 0; ui.fxContrast = 0; ui.fxSat = 0; ui.fxLcd = 0
                renderer.fxBright = 0; renderer.fxContrast = 0; renderer.fxSat = 0; renderer.fxLcd = 0
                glView.requestRender(); dlg.dismiss(); fxDialog()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = T.dp(this@MainActivity, 8) })
            addView(Dlg.button(this@MainActivity, "Fechar", true) { dlg.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Dlg.add(this, body, row)
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, "Cores e efeitos", body)))
        themed(dlg)
        dlg.window?.setDimAmount(0f); dlg.window?.setGravity(Gravity.BOTTOM) // deixa ver o jogo enquanto ajusta
    }

    // ---------- controles ----------

    private fun opacityDialog() {
        val lb = T.text(this, "", 14f, T.MUT)
        val sb = SeekBar(this).apply {
            max = 80; progress = ((controls.opacity * 100).toInt() - 20).coerceIn(0, 80)
            progressTintList = ColorStateList.valueOf(T.GOLD); thumbTintList = ColorStateList.valueOf(T.GOLD)
        }
        fun upd() { lb.text = "Opacidade: ${sb.progress + 20}%" }
        upd()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { upd() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        lateinit var dlg: AlertDialog
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(lb); addView(sb) }
        Dlg.add(this, body, okCancel({ dlg }) {
            controls.opacity = (sb.progress + 20) / 100f
            prefs.edit().putFloat("opacity", controls.opacity).apply(); controls.invalidate()
        })
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, "Opacidade dos botões", body)))
        themed(dlg)
    }

    private fun controlsMenu() {
        menuDlg("Controles", listOf(
            Row("dpad", "Direcional", sub = if (controls.useStick) "analógico" else "D-pad") {
                controls.useStick = !controls.useStick; prefs.edit().putBoolean("stick", controls.useStick).apply()
            },
            Row("pencil", "Editar layout", sub = "arrastar e pinça") { controls.editMode = true },
            Row("restart", "Resetar posições") { controls.resetLayout() },
            Row("image", "Botões em PNG", sub = "escolher a imagem de cada botão, exportar para editar") {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "skin"))
            },
            Row("drop", "Opacidade") { opacityDialog() },
            Row("vibrate", "Vibração", sub = if (controls.haptic) "ligada" else "desligada") {
                controls.haptic = !controls.haptic; prefs.edit().putBoolean("haptic", controls.haptic).apply()
            }))
    }

    // ---------- menu ----------
    private fun stateDialog(save: Boolean) {
        openMenu = if (save) "state:save" else "state:load"
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dlg: AlertDialog
        val cols = Dlg.cols(this); val m = T.dp(this, 4)
        fun tiles(slots: List<Int>, label: (Int) -> String) {
            slots.chunked(cols).forEach { chunk ->
                val line = LinearLayout(this).apply { isBaselineAligned = false }
                chunk.forEach { s ->
                    val sf = emu.stateFile(s); val tf = emu.thumbFile(s); val has = sf.exists()
                    val pic = RatioFrame(this, 1.5f).apply {
                        setBackgroundColor(T.BG)
                        val bmp = if (has && tf.exists()) BitmapFactory.decodeFile(tf.path) else null
                        if (bmp != null) addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp) }, FrameLayout.LayoutParams(-1, -1))
                        else addView(T.text(context, if (has) "sem foto" else "vazio", 13f, T.MUT).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    }
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; background = T.box(context, T.CARD, T.LINE, 12)
                        clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
                        addView(pic, LinearLayout.LayoutParams(-1, -2))
                        addView(T.text(context, label(s), 14f, T.TX, true).apply { setPadding(T.dp(context, 10), T.dp(context, 8), T.dp(context, 10), 0) })
                        addView(T.text(context, if (has) DateFormat.format("dd/MM  HH:mm", sf.lastModified()).toString() else "vazio", 12f, T.MUT).apply { setPadding(T.dp(context, 10), 0, T.dp(context, 10), T.dp(context, 8)) })
                        setOnClickListener {
                            dlg.dismiss()
                            val ok = if (save) emu.saveState(s) else emu.loadState(s)
                            if (!save && ok) emu.applyCheats(cheats) // garante os cheats ligados depois de carregar o estado
                            toast(if (ok) (if (save) "Estado salvo" else "Estado carregado") else (if (save) "Não foi possível salvar" else "Slot vazio ou arquivo inválido"))
                        }
                    }
                    line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(m, m, m, m) })
                }
                repeat(cols - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, m, m, m) }) }
                body.addView(line, LinearLayout.LayoutParams(-1, -2))
            }
        }
        // slot 0 = auto-save ao sair/periódico (só carregar)
        tiles(if (save) (1..5).toList() else (0..5).toList()) { if (it == 0) "Auto-save" else "Slot $it" }
        if (!save) {
            // 5 auto-saves temporários (um a cada 3 min de jogo): do mais novo para o mais antigo
            val ring = (Emulator.AUTO_FIRST until Emulator.AUTO_FIRST + Emulator.AUTO_COUNT)
                .sortedByDescending { emu.stateFile(it).let { f -> if (f.exists()) f.lastModified() else -1L } }
            body.addView(T.note(this, "Auto-saves temporários · um a cada 3 min de jogo, do mais novo ao mais antigo"))
            tiles(ring) { "Auto ${it - Emulator.AUTO_FIRST + 1}" }
        }
        val head = (if (save) "Salvar estado" else "Carregar estado") + "\n" + gameTitle()
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, head, body, coverBmp(), true, true)))
        themed(dlg)
    }

    private fun showMenu() {
        openMenu = "menu"
        // 3 colunas x 3 linhas, lidas de cima para baixo: coluna 1, depois coluna 2, depois coluna 3
        val byColumn = listOf(
            Row("load", "Carregar estado") { stateDialog(false) },
            Row("save", "Salvar estado") { stateDialog(true) },
            Row("gear", "Configurações") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Row("layers", "Shaders") { shaderMenu() },
            Row("ff", "Acelerar", sub = "${emu.ffMul}x · " + (if (emu.fast) "ligado" else "desligado"), checked = emu.fast) { emu.fast = !emu.fast },
            Row("pad", "Controles") { controlsMenu() },
            Row("restart", "Reiniciar jogo") { emu.reset(); emu.applyCheats(cheats) },
            Row("home", "Voltar à biblioteca") { finish() },
            if (uiP.cheatsOn) Row("cheat", "Cheats", sub = "${cheats.count { it.on }} ligado(s)") { cheatsDialog() }
            else Row("drop", "Cores e efeitos", sub = fxSummary()) { fxDialog() })
        // a grade preenche linha por linha; reordena para que a leitura vertical saia como acima
        val rowMajor = (0 until 9).map { i -> byColumn[(i % 3) * 3 + i / 3] }
        menuDlg(gameTitle(), rowMajor, 3, true)
    }

    // ---------- cheats ----------
    private fun saveCheats() {
        Cheats.save(this, gameName, cheats); emu.applyCheats(cheats)
        if (emu.skippedCheatLines > 0) toast("${emu.skippedCheatLines} linha(s) ignorada(s): o núcleo só aceita códigos de 8+8 dígitos (GameShark/Action Replay)")
    }

    private fun cheatsDialog() {
        openMenu = "cheats"
        lateinit var dlg: AlertDialog
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (cheats.isEmpty()) body.addView(T.note(this, "Nenhum cheat neste jogo ainda. Toque em “Adicionar cheat”."))
        cheats.forEachIndexed { i, c ->
            val code = if (c.code.length > 40) c.code.take(40) + "…" else c.code
            val v = Dlg.rowView(this, null, c.name, code, c.on) {
                c.on = !c.on; saveCheats(); dlg.dismiss(); cheatsDialog()
            }
            v.setOnLongClickListener { dlg.dismiss(); cheatOptions(i); true }
            Dlg.add(this, body, v)
        }
        Dlg.add(this, body, Dlg.button(this, "＋  Adicionar cheat", true) { dlg.dismiss(); cheatEdit(-1) })
        Dlg.add(this, body, Dlg.button(this, "⬇  Buscar cheats online deste jogo (opcional)", false) { dlg.dismiss(); fetchCheats(false) })
        if (cheats.isNotEmpty()) body.addView(T.note(this, "Toque para ligar/desligar · segure para editar ou excluir"))
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, "Cheats", body)))
        themed(dlg)
    }

    private fun cheatOptions(i: Int) {
        val c = cheats.getOrNull(i) ?: return
        menuDlg(c.name, listOf(
            Row("pencil", "Editar") { cheatEdit(i) },
            Row("trash", "Excluir") { cheats.removeAt(i); saveCheats(); cheatsDialog() }))
    }

    /** Procura os cheats do jogo (opcional). Usa primeiro o banco já baixado (sem internet); só então tenta a internet. */
    private fun fetchCheats(silent: Boolean) {
        val info = romInfo ?: return
        val name = gameName
        val pd = if (silent) null else ProgressDlg(this, "Cheats online")
        Thread {
            var found: java.io.File? = null
            try {
                var official = Ident.lookup(this, info)
                if (official == null && !Ident.hasIndex(this, info.system)) {
                    pd?.set("Baixando a lista de jogos…")
                    if (Ident.downloadIndex(this, info.system)) official = Ident.lookup(this, info)
                }
                val base = Store.base(name)
                found = CheatBank.findLocal(this, info.system, official, base)
                if (found == null) {
                    pd?.set("Procurando cheats para este jogo…")
                    for (cand in listOfNotNull(official, base).distinct()) {
                        found = CheatBank.downloadOne(this, info.system, cand)
                        if (found != null) break
                    }
                }
            } catch (e: Throwable) { }
            val added = found?.let { Cheats.importInto(this, name, Cheats.parseCht(it)) } ?: -1
            runOnUiThread {
                pd?.close()
                if (added >= 0) { cheats = Cheats.load(this, name); emu.applyCheats(cheats) }
                if (!silent) {
                    toast(when {
                        added > 0 -> "$added cheat(s) adicionado(s), desligados: ligue os que quiser"
                        added == 0 -> "Esses cheats já estavam na lista"
                        else -> "Nenhum cheat encontrado (sem internet ou jogo sem cheats no banco)"
                    })
                    cheatsDialog()
                } else if (added > 0) toast("$added cheat(s) baixado(s) para este jogo")
            }
        }.start()
    }

    private fun cheatEdit(idx: Int, dn: String? = null, dc: String? = null) {
        openMenu = "cheatEdit:$idx"
        val old = cheats.getOrNull(idx)
        fun field(dica: String, v: String, multi: Boolean) = EditText(this).apply {
            setHint(dica); setHintTextColor(T.MUT); setTextColor(T.TX); setText(v); T.font?.let { typeface = it }
            background = T.box(this@MainActivity, T.CARD, T.LINE)
            setPadding(T.dp(context, 14), T.dp(context, 10), T.dp(context, 14), T.dp(context, 10))
            if (multi) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3; gravity = Gravity.TOP
            } else setSingleLine()
        }
        val nome = field("Nome do cheat", dn ?: old?.name ?: "", false)
        val cod = field("Código (GameShark / Action Replay / CodeBreaker)", dc ?: old?.code?.replace('+', '\n') ?: "", true)
        // guarda o que você digitou: se for copiar um código em outro app, nada se perde
        draftName = nome.text.toString(); draftCode = cod.text.toString()
        fun watch(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(x: Editable?) { f(x?.toString() ?: "") }
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        watch(nome) { draftName = it }; watch(cod) { draftCode = it }
        lateinit var dlg: AlertDialog
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Dlg.add(this, body, nome); Dlg.add(this, body, cod)
        body.addView(T.note(this, "Uma linha por código, no formato 82025840 0001 ou 12345678 9ABCDEF0. Códigos de 2 linhas (muito comuns no Action Replay) ficam no MESMO cheat, na ordem, uma linha embaixo da outra. Use só um tipo de código (GameShark ou Action Replay) por jogo."))
        val row = LinearLayout(this).apply {
            addView(Dlg.button(this@MainActivity, "Cancelar", false) { dlg.dismiss(); cheatsDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = T.dp(this@MainActivity, 8) })
            addView(Dlg.button(this@MainActivity, "Salvar", true) {
                val norm = Cheats.normalize(cod.text.toString())
                if (norm == null) toast("Código inválido. Use linhas como 82025840 0001 ou 12345678 9ABCDEF0")
                else {
                    val nm = nome.text.toString().trim().ifEmpty { "Cheat ${cheats.size + 1}" }
                    if (old == null) cheats.add(Cheat(nm, norm, true)) else { old.name = nm; old.code = norm }
                    saveCheats(); dlg.dismiss(); cheatsDialog()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Dlg.add(this, body, row)
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, if (old == null) "Novo cheat" else "Editar cheat", body)))
        themed(dlg)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // ---------- gamepad físico ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val bit = when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 8
            KeyEvent.KEYCODE_BUTTON_B -> 0
            KeyEvent.KEYCODE_BUTTON_L1 -> 10
            KeyEvent.KEYCODE_BUTTON_R1 -> 11
            KeyEvent.KEYCODE_BUTTON_START -> 3
            KeyEvent.KEYCODE_BUTTON_SELECT -> 2
            KeyEvent.KEYCODE_DPAD_UP -> 4
            KeyEvent.KEYCODE_DPAD_DOWN -> 5
            KeyEvent.KEYCODE_DPAD_LEFT -> 6
            KeyEvent.KEYCODE_DPAD_RIGHT -> 7
            else -> -1
        }
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) { if (e.action == KeyEvent.ACTION_DOWN) showMenu(); return true }
        if (bit >= 0) {
            padMask = if (e.action == KeyEvent.ACTION_DOWN) padMask or (1 shl bit) else padMask and (1 shl bit).inv()
            pushInput(); return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != 0 && ev.action == MotionEvent.ACTION_MOVE) {
            val x = ev.getAxisValue(MotionEvent.AXIS_X) + ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = ev.getAxisValue(MotionEvent.AXIS_Y) + ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or (1 shl 6)
            if (x > 0.5f) m = m or (1 shl 7)
            if (y < -0.5f) m = m or (1 shl 4)
            if (y > 0.5f) m = m or (1 shl 5)
            axisMask = m; pushInput(); return true
        }
        return super.onGenericMotionEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { showMenu() }

    // ---------- ciclo de vida ----------
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) immersive() }
    override fun onPause() { super.onPause(); emu.paused = true; emu.saveSram(); emu.armGuard(false)
        if (::uiP.isInitialized && emu.loaded && (uiP.autoSaveMs > 0 || uiP.autoResume)) emu.saveState(0)
        Backup.export(this)
        glView.onPause() }
    override fun onResume() { super.onResume(); applyPrefsLive(); if (emu.loaded) emu.armGuard(true); glView.onResume(); if (openDialogs == 0) emu.paused = false; immersive() }
    /** Ao sair do app (mesmo por um instante) guarda o ponto exato do jogo: se o sistema fechar o app, ele volta daqui. */
    override fun onStop() { super.onStop(); if (!isFinishing && !editOnly && ::emu.isInitialized && emu.loaded) emu.saveResume() }
    override fun onDestroy() { super.onDestroy(); if (::emu.isInitialized) { if (isFinishing) emu.clearResume(); emu.onFatal = null; emu.armGuard(false); emu.stop(); emu.onCheatArm = null } }
}
