package com.meu.gbaemu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import com.meu.gbaemu.ui.Dlg
import com.meu.gbaemu.ui.KB_PROFILES
import com.meu.gbaemu.ui.Row
import com.meu.gbaemu.ui.T
import com.meu.gbaemu.ui.SH_GLOBAL
import com.meu.gbaemu.ui.SH_NONE
import com.meu.gbaemu.ui.SettingsActivity
import com.meu.gbaemu.ui.Prefs as UiPrefs
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
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
import android.view.WindowManager
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
    private fun layKey(base: String) = if (profile == KB_PROFILES[0]) base else "$base.$profile"
    private val skinNames = setOf("dpad", "stick_base", "stick_knob", "a", "b", "l", "r", "start", "select", "menu")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("gba", MODE_PRIVATE)
        uiP = UiPrefs(this)
        val ui = uiP
        val gid = intent.getStringExtra("game_id")
        editOnly = intent.getBooleanExtra("edit_layout", false)
        profile = intent.getStringExtra("profile") ?: gid?.let { ui.kbFor(it) } ?: KB_PROFILES[0]
        requestedOrientation = if (editOnly) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else when (ui.orientFor(gid)) {
            "retrato" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "paisagem" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emu = Emulator(this) { glView.requestRender() }
        emu.ffMul = ui.ffMultiplierFor(gid); emu.ffAudio = ui.ffAudio; emu.autoEveryMs = ui.autoSaveMs
        emu.volume = if (ui.mute) 0f else ui.volumeFor(gid) / 100f
        renderer = GbaRenderer(this, emu) { msg -> toast(msg) }
        renderer.scale = prefs.getInt("scale", 3)
        renderer.linear = prefs.getBoolean("linear", false)
        renderer.ratio = ui.ratioFor(gid); renderer.rectPort = ui.screenRect(false); renderer.rectLand = ui.screenRect(true)
        renderer.shaderKeys = when (val sh = gid?.let { ui.shaderFor(it) } ?: SH_GLOBAL) {
            SH_GLOBAL -> enabledShaders()
            SH_NONE -> emptyList()
            else -> listOf(sh).filter { it in availableShaders() }
        }
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
    }

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
        d.setOnDismissListener { openDialogs--; if (openDialogs <= 0) { openDialogs = 0; emu.paused = false }; immersive() }
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
            3 -> importSkin(uris)
        }
    }

    // ---------- ROM ----------
    private fun loadRom(u: Uri) {
        var name = displayName(u)
        var bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
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
        val err = emu.start(bytes, name)
        if (err != null) toast(err)
        else if (uiP.autoResume && !editOnly && emu.loadState(0)) toast("Continuando de onde você parou")
    }

    // ---------- shaders ----------
    private fun shaderDir() = File(filesDir, "shaders").also { it.mkdirs() }

    private fun availableShaders(): List<String> {
        val d = shaderDir()
        return (d.list()?.toList() ?: emptyList()).filter {
            it.endsWith(".glsl", true) || it.endsWith(".glslp", true) || (it.endsWith(".vsh", true) && File(d, it.substring(0, it.length - 4) + ".fsh").exists())
        }.sorted()
    }

    private fun enabledShaders(): List<String> {
        val av = availableShaders()
        return (prefs.getString("sh_on", "") ?: "").split("\n").filter { it.isNotBlank() && it in av }
    }

    private fun applyShaders(list: List<String>) {
        prefs.edit().putString("sh_on", list.joinToString("\n")).apply()
        renderer.shaderKeys = list; renderer.dirty = true; glView.requestRender()
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
    private fun menuDlg(title: String, rows: List<Row>): AlertDialog {
        lateinit var dlg: AlertDialog
        val content = Dlg.content(this, title, rows) { dlg.dismiss() }
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
            Row("power", "Desativar todos") { applyShaders(emptyList()) }))
    }

    // ---------- controles ----------
    private fun importSkin(uris: List<Uri>) {
        val dir = File(filesDir, "skin").also { it.mkdirs() }
        var ok = 0; var skip = 0
        for (u in uris) {
            val nm = displayName(u).lowercase()
            if (nm.endsWith(".png") && nm.removeSuffix(".png") in skinNames) {
                contentResolver.openInputStream(u)?.use { i -> File(dir, nm).outputStream().use { o -> i.copyTo(o) } }; ok++
            } else skip++
        }
        controls.reloadSkin()
        toast("$ok PNG(s) aplicado(s)" + if (skip > 0) " • $skip ignorado(s). Nomes válidos: ${skinNames.joinToString(", ") { "$it.png" }}" else "")
    }

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
            Row("image", "Importar PNGs dos botões", sub = "a, b, l, r, start, select, dpad, stick_base, stick_knob, menu") { pick(3, true) },
            Row("trash", "Restaurar PNGs padrão") { File(filesDir, "skin").listFiles()?.forEach { it.delete() }; controls.reloadSkin() },
            Row("drop", "Opacidade") { opacityDialog() },
            Row("vibrate", "Vibração", sub = if (controls.haptic) "ligada" else "desligada") {
                controls.haptic = !controls.haptic; prefs.edit().putBoolean("haptic", controls.haptic).apply()
            }))
    }

    // ---------- menu ----------
    private fun stateDialog(save: Boolean) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dlg: AlertDialog
        for (s in (if (save) (1..5).toList() else (0..5).toList())) { // slot 0 = auto-save (só carregar)
            val sf = emu.stateFile(s); val tf = emu.thumbFile(s); val has = sf.exists()
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(T.BG)
                if (has && tf.exists()) BitmapFactory.decodeFile(tf.path)?.let { setImageBitmap(it) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(T.dp(context, 14), 0, 0, 0)
                addView(T.text(context, if (s == 0) "Auto-save" else "Slot $s", 15f, T.TX, true))
                addView(T.text(context, if (has) DateFormat.format("dd/MM  HH:mm", sf.lastModified()).toString() else "vazio", 12f, T.MUT))
            }
            Dlg.add(this, body, LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = T.box(context, T.CARD, T.LINE, 12)
                setPadding(T.dp(context, 8), T.dp(context, 8), T.dp(context, 8), T.dp(context, 8))
                addView(img, LinearLayout.LayoutParams(T.dp(context, 120), T.dp(context, 80)))
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener {
                    dlg.dismiss()
                    val ok = if (save) emu.saveState(s) else emu.loadState(s)
                    toast(if (ok) (if (save) "Estado salvo no slot $s" else "Estado carregado") else (if (save) "Não foi possível salvar" else "Slot vazio"))
                }
            })
        }
        dlg = showDlgGet(AlertDialog.Builder(this, THEME).setView(Dlg.frame(this, if (save) "Salvar estado" else "Carregar estado", body)))
        themed(dlg)
    }

    private fun showMenu() {
        menuDlg("Menu", listOf(
            Row("folder", "Abrir ROM") { pick(1, false) },
            Row("layers", "Shaders") { shaderMenu() },
            Row("pad", "Controles") { controlsMenu() },
            Row("save", "Salvar estado") { stateDialog(true) },
            Row("load", "Carregar estado") { stateDialog(false) },
            Row("ff", "Acelerar (${emu.ffMul}x)", sub = if (emu.fast) "ligado" else "desligado", checked = emu.fast) { emu.fast = !emu.fast },
            Row("restart", "Reiniciar jogo") { emu.reset() },
            Row("gear", "Configurações") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Row("home", "Voltar à biblioteca") { finish() }))
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
    override fun onPause() { super.onPause(); emu.paused = true; emu.saveSram()
        if (::uiP.isInitialized && (uiP.autoSaveMs > 0 || uiP.autoResume)) emu.saveState(0)
        glView.onPause() }
    override fun onResume() { super.onResume(); glView.onResume(); if (openDialogs == 0) emu.paused = false; immersive() }
    override fun onDestroy() { super.onDestroy(); emu.stop() }
}
