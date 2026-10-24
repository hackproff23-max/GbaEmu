package com.meu.gbaemu.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/** Pasta própria do app: /EmuladorPro na memória interna, com uma subpasta para cada tipo de arquivo. */
object Store {
    const val ROOT_NAME = "EmuladorPro"
    private val SUB = mapOf(
        "config" to "Configuracoes", "states" to "SaveStates", "saves" to "Saves",
        "cheats" to "Cheats", "covers" to "Capas", "controls" to "Controles", "bank" to "Banco"
    )
    private val ROM_EXT = setOf("gba", "gb", "gbc", "zip", "sgb", "srl", "bin")
    private var cached: File? = null
    private var cachedShared = false

    fun canUseShared(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Pede acesso a todos os arquivos (Android 11+) ou à memória (Android 10 e anteriores). */
    fun requestAccess(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}")))
            } catch (e: Exception) {
                try { a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
            }
        } else {
            a.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 77)
        }
    }

    private fun make(r: File): Boolean = try {
        SUB.values.forEach { File(r, it).mkdirs() }
        r.isDirectory && r.canWrite()
    } catch (e: Exception) { false }

    /** Copia (sem sobrescrever) o que ficou na pasta reserva para a pasta principal, quando a permissão é concedida. */
    private fun mergeInto(from: File, to: File) {
        if (!from.isDirectory) return
        from.listFiles()?.forEach { f ->
            val t = File(to, f.name)
            if (f.isDirectory) { t.mkdirs(); mergeInto(f, t) }
            else if (!t.exists()) try { f.copyTo(t) } catch (e: Exception) { }
        }
    }

    @Synchronized
    fun root(ctx: Context): File {
        val app = ctx.applicationContext
        val shared = canUseShared(app)
        cached?.let { if (cachedShared == shared && it.exists()) return it }
        val cands = ArrayList<File>()
        if (shared) cands += File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        // Android/media/<app>: não é apagada ao desinstalar o app, então saves e configurações sobrevivem mesmo sem a permissão total
        @Suppress("DEPRECATION")
        try { app.externalMediaDirs.firstOrNull { it != null }?.let { cands += File(it, ROOT_NAME) } } catch (e: Exception) { }
        app.getExternalFilesDir(null)?.let { cands += File(it, ROOT_NAME) }
        cands += File(app.filesDir, ROOT_NAME)
        val ok = cands.firstOrNull { make(it) } ?: cands.last()
        if (shared && ok == cands.first()) {
            @Suppress("DEPRECATION")
            try { app.externalMediaDirs.firstOrNull { it != null }?.let { mergeInto(File(it, ROOT_NAME), ok) } } catch (e: Exception) { }
            app.getExternalFilesDir(null)?.let { mergeInto(File(it, ROOT_NAME), ok) }
            mergeInto(File(app.filesDir, ROOT_NAME), ok)
        }
        cached = ok; cachedShared = shared
        return ok
    }

    /** key: config | states | saves | cheats | covers | controls */
    fun dir(ctx: Context, key: String): File = File(root(ctx), SUB.getValue(key)).also { it.mkdirs() }

    /** Nome do jogo sem a extensão da ROM e sem caracteres proibidos em nomes de arquivo. */
    fun base(name: String): String {
        var n = name.trim().substringAfterLast('/')
        val i = n.lastIndexOf('.')
        if (i > 0 && n.substring(i + 1).lowercase() in ROM_EXT) n = n.substring(0, i)
        n = n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        return n.ifEmpty { "jogo" }
    }
}

/** Cópia das configurações em EmuladorPro/Configuracoes, para sobreviver a reinstalações do app. */
object Backup {
    private const val NAME = "configuracoes.json"
    private val SKIP = setOf("rom_uri", "pendingCover", "pendingSkin", "askedStorage", "restoredOnce")
    private val IGNORE_EMPTY = setOf("pendingCover", "pendingSkin", "askedStorage")

    fun file(ctx: Context) = File(Store.dir(ctx, "config"), NAME)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Grava a cópia pouco depois de QUALQUER mudança de configuração (junta várias mudanças seguidas numa só gravação). */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { Thread { export(app) }.start() }
        pending = r
        handler.postDelayed(r, 500)
    }

    /** Converte o endereço de um jogo da biblioteca em arquivo real, quando dá (precisa do acesso a todos os arquivos). */
    fun pathOf(u: String): File? = try {
        val uri = Uri.parse(u)
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }
            "content" -> if (uri.authority == "com.android.externalstorage.documents") {
                val id = uri.lastPathSegment ?: ""
                if (id.startsWith("primary:")) File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:")) else null
            } else null
            else -> null
        }
    } catch (e: Exception) { null }

    /** Depois de reinstalar, as permissões dos arquivos escolhidos se perdem; se o jogo está na memória interna, abre direto pelo caminho. */
    private fun fixLib(v: String): String = v.split("\n").filter { it.contains("\t") }.joinToString("\n") { line ->
        val i = line.indexOf('\t'); val u = line.substring(0, i); val id = line.substring(i + 1)
        val f = if (u.startsWith("file:")) null else pathOf(u)
        if (f != null && f.exists() && f.canRead()) "${Uri.fromFile(f)}\t$id" else line
    }

    fun export(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences("gba", Context.MODE_PRIVATE)
            // app recém-instalado ainda vazio: não sobrescreve uma cópia boa com "nada"
            if (sp.all.keys.none { it !in IGNORE_EMPTY && it !in SKIP }) return
            val o = JSONObject()
            for ((k, v) in sp.all) {
                if (k in SKIP) continue
                val e = JSONObject()
                when (v) {
                    is String -> { e.put("t", "s"); e.put("v", v) }
                    is Int -> { e.put("t", "i"); e.put("v", v) }
                    is Boolean -> { e.put("t", "b"); e.put("v", v) }
                    is Long -> { e.put("t", "l"); e.put("v", v) }
                    is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                    else -> continue
                }
                o.put(k, e)
            }
            file(ctx).writeText(o.toString(1))
        } catch (e: Exception) { }
    }

    fun restoreIfEmpty(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("gba", Context.MODE_PRIVATE)
        if (sp.all.keys.any { it !in IGNORE_EMPTY }) return false
        return restore(ctx)
    }

    fun restore(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) return false
        return try {
            val o = JSONObject(f.readText())
            val ed = ctx.getSharedPreferences("gba", Context.MODE_PRIVATE).edit()
            var n = 0
            for (k in o.keys()) {
                if (k in SKIP) continue
                n++
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, if (k == "lib") fixLib(e.getString("v")) else e.getString("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                }
            }
            ed.putBoolean("restoredOnce", true) // marca que já restaurou: evita repetir (e girar em loop) se o arquivo estiver vazio
            ed.commit()
            n > 0
        } catch (e: Exception) { false }
    }
}

class Cheat(var name: String, var code: String, var on: Boolean)

/** Cheats de cada jogo em EmuladorPro/Cheats/<jogo>.cht, uma linha por cheat: 1|Nome|CODIGO (1 = ligado). */
object Cheats {
    fun file(ctx: Context, game: String) = File(Store.dir(ctx, "cheats"), Store.base(game) + ".cht")

    fun load(ctx: Context, game: String): ArrayList<Cheat> {
        val out = ArrayList<Cheat>()
        val f = file(ctx, game)
        if (!f.exists()) return out
        try {
            for (l in f.readLines()) {
                val p = l.split("|", limit = 3)
                if (p.size != 3) continue
                val code = normalize(p[2]) ?: continue
                out.add(Cheat(p[1].trim().ifEmpty { "Cheat ${out.size + 1}" }, code, p[0].trim() == "1"))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, game: String, list: List<Cheat>) {
        try {
            val f = file(ctx, game); val tmp = File(f.path + ".tmp")
            tmp.writeText(list.joinToString("\n") {
                "${if (it.on) 1 else 0}|${it.name.replace('|', '/').replace('\n', ' ')}|${it.code}"
            } + "\n")
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) { }
    }

    /** Lê um .cht do libretro (cheat0_desc / cheat0_code). Todos entram desligados. */
    fun parseCht(f: File): List<Cheat> {
        val desc = HashMap<Int, String>(); val code = HashMap<Int, String>()
        val re = Regex("^cheat(\\d+)_(desc|code)\\s*=\\s*\"(.*)\"\\s*$")
        try {
            for (l in f.readLines()) {
                val m = re.find(l.trim()) ?: continue
                val i = m.groupValues[1].toIntOrNull() ?: continue
                if (m.groupValues[2] == "desc") desc[i] = m.groupValues[3] else code[i] = m.groupValues[3]
            }
        } catch (e: Exception) { }
        return code.keys.sorted().mapNotNull { i ->
            val c = normalize(code[i] ?: return@mapNotNull null) ?: return@mapNotNull null
            Cheat(desc[i]?.trim()?.ifEmpty { null } ?: "Cheat ${i + 1}", c, false)
        }
    }

    /** Junta cheats novos aos do jogo (sem repetir códigos). Devolve quantos foram adicionados. */
    fun importInto(ctx: Context, game: String, add: List<Cheat>): Int {
        val cur = load(ctx, game)
        val have = cur.map { it.code }.toHashSet()
        var n = 0
        for (c in add) if (have.add(c.code)) { cur.add(c); n++ }
        if (n > 0) save(ctx, game, cur)
        return n
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    /** Aceita GameShark / Action Replay / CodeBreaker do GBA (linhas "XXXXXXXX YYYYYYYY" ou "XXXXXXXX YYYY").
     *  Devolve as linhas separadas por "+", ou null se o texto não for um código válido. */
    fun normalize(raw: String): String? {
        val parts = raw.uppercase().split(Regex("[\\s+;,]+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            val a = parts[i]
            if (!isHex(a)) return null
            if (a.length == 16 || a.length == 12) { lines.add(a.substring(0, 8) + " " + a.substring(8)); i++ }
            else if (a.length == 8 && i + 1 < parts.size) {
                val b = parts[i + 1]
                if (!isHex(b) || !(b.length == 8 || b.length == 4)) return null
                lines.add("$a $b"); i += 2
            } else return null
        }
        return if (lines.isEmpty()) null else lines.joinToString("+")
    }
}

/** Desenha a imagem preenchendo o espaço (corte central) com uma camada escura por cima. */
class CoverDrawable(private val bmp: Bitmap, dimPct: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint().apply { color = Color.argb((dimPct.coerceIn(0, 95) * 255) / 100, 0, 0, 0) }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        val sw = bmp.width * s; val sh = bmp.height * s
        val l = b.left + (b.width() - sw) / 2f; val t = b.top + (b.height() - sh) / 2f
        c.save(); c.clipRect(b)
        c.drawBitmap(bmp, null, RectF(l, t, l + sw, t + sh), p)
        c.restore()
        c.drawRect(b, scrim)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Imagem de fundo (PNG, JPG ou GIF) usada nos menus do app. Em GIF, os menus mostram o primeiro quadro. */
object Backdrop {
    private var key = ""
    private var bmp: Bitmap? = null

    fun sig(p: Prefs): String { val f = p.bgFile(); return "${f?.path}|${f?.lastModified()}|${p.bgDim}|${p.bgMenus}" }

    private fun decode(f: File): Bitmap? {
        val head = ByteArray(3)
        f.inputStream().use { it.read(head) }
        if (String(head, Charsets.ISO_8859_1) == "GIF") {
            val mv = Movie.decodeFile(f.path) ?: return null
            if (mv.width() <= 0 || mv.height() <= 0) return null
            val k = minOf(1f, 1280f / maxOf(mv.width(), mv.height()))
            val bm = Bitmap.createBitmap((mv.width() * k).toInt().coerceAtLeast(1), (mv.height() * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            c.drawColor(Color.BLACK); c.scale(k, k); mv.setTime(0); mv.draw(c, 0f, 0f)
            return bm
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    @Synchronized
    private fun bitmap(f: File): Bitmap? {
        val k = f.path + f.lastModified()
        if (k == key) return bmp
        val b = try { decode(f) } catch (e: Throwable) { null }
        key = k; bmp = b
        return b
    }

    fun drawable(ctx: Context, p: Prefs): Drawable? {
        if (!p.bgMenus) return null
        val f = p.bgFile() ?: return null
        val b = bitmap(f) ?: return null
        return CoverDrawable(b, p.bgDim)
    }
}
