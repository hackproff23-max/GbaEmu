package com.meu.gbaemu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import com.meu.gbaemu.ui.KB_PROFILES
import com.meu.gbaemu.ui.SH_GLOBAL
import com.meu.gbaemu.ui.SH_NONE
import com.meu.gbaemu.ui.SettingsActivity
import com.meu.gbaemu.ui.Prefs as UiPrefs
import android.content.SharedPreferences
import android.graphics.Color
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
        val ui = UiPrefs(this)
        val gid = intent.getStringExtra("game_id")
        editOnly = intent.getBooleanExtra("edit_layout", false)
        profile = intent.getStringExtra("profile") ?: gid?.let { ui.kbFor(it) } ?: KB_PROFILES[0]
        requestedOrientation = if (editOnly) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else when (ui.orient) {
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
        emu.ffMul = ui.ffMultiplier
        emu.volume = if (ui.mute) 0f else ui.volume / 100f
        renderer = GbaRenderer(this, emu) { msg -> toast(msg) }
        renderer.scale = prefs.getInt("scale", 3)
        renderer.linear = prefs.getBoolean("linear", false)
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

    private fun showDlg(b: AlertDialog.Builder) {
        openDialogs++; emu.paused = true
        val d = b.create()
        d.setOnDismissListener { openDialogs--; if (openDialogs <= 0) { openDialogs = 0; emu.paused = false }; immersive() }
        d.show()
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
        emu.start(bytes, name)?.let { toast(it) }
    }

    // ---------- shaders ----------
    private fun shaderDir() = File(filesDir, "shaders").also { it.mkdirs() }

    private fun availableShaders(): List<String> {
        val d = shaderDir()
        return (d.list()?.toList() ?: emptyList()).filter {
            it.endsWith(".glsl", true) || (it.endsWith(".vsh", true) && File(d, it.substring(0, it.length - 4) + ".fsh").exists())
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
            if (l.endsWith(".glsl") || l.endsWith(".vsh") || l.endsWith(".fsh")) {
                contentResolver.openInputStream(u)?.use { i -> File(shaderDir(), nm).outputStream().use { o -> i.copyTo(o) } }; ok++
            } else skip++
        }
        toast("$ok shader(s) importado(s)" + if (skip > 0) " • $skip ignorado(s) (só .glsl/.vsh/.fsh; presets .glslp não são suportados)" else "")
    }

    private fun shadersDialog() {
        val av = availableShaders()
        if (av.isEmpty()) { toast("Nenhum shader importado ainda"); return }
        val on = enabledShaders().toMutableList()
        val checked = BooleanArray(av.size) { av[it] in on }
        showDlg(AlertDialog.Builder(this).setTitle("Shaders (ordem marcada = ordem das passadas)")
            .setMultiChoiceItems(av.toTypedArray(), checked) { _, i, c -> if (c) { if (av[i] !in on) on.add(av[i]) } else on.remove(av[i]) }
            .setPositiveButton("OK") { _, _ -> applyShaders(on) }.setNegativeButton("Cancelar", null))
    }

    private fun removeShaderDialog() {
        val av = availableShaders()
        if (av.isEmpty()) { toast("Nenhum shader importado ainda"); return }
        showDlg(AlertDialog.Builder(this).setTitle("Remover shader").setItems(av.toTypedArray()) { _, i ->
            val k = av[i]; val base = k.substring(0, k.length - 4)
            File(shaderDir(), k).delete()
            if (!k.endsWith(".glsl", true)) File(shaderDir(), "$base.fsh").delete()
            applyShaders(enabledShaders())
        })
    }

    private fun shaderMenu() {
        val items = arrayOf("Importar shaders (.glsl / .vsh+.fsh)", "Ativar / ordenar shaders", "Remover shader…",
            "Escala interna das passadas: ${renderer.scale}x", "Filtro: " + if (renderer.linear) "bilinear" else "nearest", "Desativar todos")
        showDlg(AlertDialog.Builder(this).setTitle("Shaders").setItems(items) { _, i ->
            when (i) {
                0 -> pick(2, true)
                1 -> shadersDialog()
                2 -> removeShaderDialog()
                3 -> showDlg(AlertDialog.Builder(this).setTitle("Escala interna").setItems(Array(6) { "${it + 1}x" }) { _, s ->
                    renderer.scale = s + 1; prefs.edit().putInt("scale", s + 1).apply(); glView.requestRender() })
                4 -> { renderer.linear = !renderer.linear; prefs.edit().putBoolean("linear", renderer.linear).apply(); glView.requestRender() }
                5 -> applyShaders(emptyList())
            }
        })
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
        val sb = SeekBar(this).apply { max = 80; progress = ((controls.opacity * 100).toInt() - 20).coerceIn(0, 80); setPadding(48, 48, 48, 0) }
        showDlg(AlertDialog.Builder(this).setTitle("Opacidade dos botões").setView(sb)
            .setPositiveButton("OK") { _, _ ->
                controls.opacity = (sb.progress + 20) / 100f
                prefs.edit().putFloat("opacity", controls.opacity).apply(); controls.invalidate()
            }.setNegativeButton("Cancelar", null))
    }

    private fun controlsMenu() {
        val items = arrayOf("Direcional: " + if (controls.useStick) "analógico" else "D-pad", "Editar layout (arrastar / pinça)", "Resetar posições",
            "Importar PNGs (a, b, l, r, start, select, dpad, stick_base, stick_knob, menu)", "Restaurar PNGs padrão", "Opacidade…",
            "Vibração: " + if (controls.haptic) "ligada" else "desligada")
        showDlg(AlertDialog.Builder(this).setTitle("Controles").setItems(items) { _, i ->
            when (i) {
                0 -> { controls.useStick = !controls.useStick; prefs.edit().putBoolean("stick", controls.useStick).apply() }
                1 -> controls.editMode = true
                2 -> controls.resetLayout()
                3 -> pick(3, true)
                4 -> { File(filesDir, "skin").listFiles()?.forEach { it.delete() }; controls.reloadSkin() }
                5 -> opacityDialog()
                6 -> { controls.haptic = !controls.haptic; prefs.edit().putBoolean("haptic", controls.haptic).apply() }
            }
        })
    }

    // ---------- menu ----------
    private fun stateDialog(save: Boolean) {
        showDlg(AlertDialog.Builder(this).setTitle(if (save) "Salvar estado" else "Carregar estado")
            .setItems(arrayOf("Slot 1", "Slot 2", "Slot 3")) { _, s ->
                val ok = if (save) emu.saveState(s + 1) else emu.loadState(s + 1)
                toast(if (ok) (if (save) "Estado salvo" else "Estado carregado") else "Não foi possível")
            })
    }

    private fun showMenu() {
        val items = arrayOf("Abrir ROM", "Shaders…", "Controles…", "Salvar estado…", "Carregar estado…",
            "Acelerar (${emu.ffMul}x): " + if (emu.fast) "ligado" else "desligado", "Reiniciar jogo", "Configurações…", "Voltar à biblioteca")
        showDlg(AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, i ->
            when (i) {
                0 -> pick(1, false)
                1 -> shaderMenu()
                2 -> controlsMenu()
                3 -> stateDialog(true)
                4 -> stateDialog(false)
                5 -> emu.fast = !emu.fast
                6 -> emu.reset()
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
                8 -> finish()
            }
        })
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
    override fun onPause() { super.onPause(); emu.paused = true; emu.saveSram(); glView.onPause() }
    override fun onResume() { super.onResume(); glView.onResume(); if (openDialogs == 0) emu.paused = false; immersive() }
    override fun onDestroy() { super.onDestroy(); emu.stop() }
}
