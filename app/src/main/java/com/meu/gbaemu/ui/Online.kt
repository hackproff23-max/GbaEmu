package com.meu.gbaemu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/*
 * Tudo aqui é OPCIONAL e só roda quando a pessoa toca num botão (ou liga a opção automática nas Configurações).
 * O app continua 100% offline: o que é baixado fica em EmuladorPro/Banco, Cheats e Capas e depois é lido sem internet.
 * Fonte: banco público do libretro (nomes No-Intro): metadados (DAT), cheats (.cht) e capas (thumbnails).
 */

/** Acesso simples à internet. */
object Net {
    val main = Handler(Looper.getMainLooper())

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Baixa o conteúdo de um endereço (segue redirecionamentos). Devolve null se falhar ou não existir. */
    fun get(url: String, timeoutMs: Int = 20000): ByteArray? {
        var u = url
        try {
            for (n in 0 until 5) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "GbaEmu")
                val code = c.responseCode
                if (code in 301..308) {
                    val loc = c.getHeaderField("Location"); c.disconnect()
                    if (loc.isNullOrBlank()) return null
                    u = loc; continue
                }
                if (code != 200) { c.disconnect(); return null }
                val out = c.inputStream.use { it.readBytes() }
                c.disconnect()
                return out
            }
        } catch (e: Throwable) { }
        return null
    }
}

/** Janelinha de progresso com botão Cancelar, no tema do app. */
class ProgressDlg(act: Activity, title: String) {
    @Volatile var cancelled = false
    private val tv: TextView = T.text(act, "…", 14f, T.MUT)
    private val dlg: AlertDialog

    init {
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tv)
        Dlg.add(act, body, Dlg.button(act, "Cancelar", false) { cancelled = true; close() })
        dlg = AlertDialog.Builder(act, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(Dlg.frame(act, title, body)).setCancelable(false).create()
        dlg.show(); dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun set(t: String) { Net.main.post { tv.text = t } }
    fun close() { Net.main.post { try { dlg.dismiss() } catch (e: Exception) { } } }
}

/** Descobre qual é o jogo (nome oficial No-Intro) pelo CRC32 da ROM, sem depender do nome do arquivo. */
object Ident {
    const val GBA = "Nintendo - Game Boy Advance"
    const val GB = "Nintendo - Game Boy"
    const val GBC = "Nintendo - Game Boy Color"
    val SYSTEMS = listOf(GBA, GB, GBC)

    class Info(val crc: String, val serial: String, val system: String)

    fun systemFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "gb", "sgb" -> GB
        "gbc" -> GBC
        else -> GBA
    }

    fun infoOf(bytes: ByteArray, name: String): Info {
        val sys = systemFor(name)
        val c = CRC32(); c.update(bytes, 0, bytes.size)
        val serial = if (sys == GBA && bytes.size > 0xB0) String(bytes, 0xAC, 4, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() } else ""
        return Info("%08X".format(c.value), serial, sys)
    }

    /** Lê a ROM de um jogo da biblioteca (se for .zip, usa a primeira ROM de dentro). */
    fun infoOfGame(ctx: Context, g: Game): Info? = try {
        var bytes = ctx.contentResolver.openInputStream(Uri.parse(g.uri))!!.use { it.readBytes() }
        var name = g.id
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
        infoOf(bytes, name)
    } catch (e: Throwable) { null }

    private fun idxFile(ctx: Context, sys: String) = File(Store.dir(ctx, "bank"), sys.replace(" - ", "_").replace(' ', '_') + ".idx")

    fun hasIndex(ctx: Context, sys: String) = idxFile(ctx, sys).exists()

    /** Baixa a lista oficial de jogos (DAT No-Intro do libretro) e guarda só CRC / código / nome (~ poucos MB, uma vez). */
    fun downloadIndex(ctx: Context, sys: String): Boolean {
        val data = Net.get("https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/" + Net.enc(sys) + ".dat", 60000) ?: return false
        val txt = String(data, Charsets.UTF_8).replace("\r", "")
        val sb = StringBuilder()
        val nameRe = Regex("name \"([^\"]+)\"")
        val crcRe = Regex("crc ([0-9A-Fa-f]{8})")
        val serRe = Regex("serial \"?([A-Za-z0-9-]+)\"?")
        for (m in Regex("(?s)game \\((.*?)\\n\\)").findAll(txt)) {
            val body = m.groupValues[1]
            val name = nameRe.find(body)?.groupValues?.get(1) ?: continue
            val crc = crcRe.find(body)?.groupValues?.get(1)?.uppercase() ?: continue
            val ser = serRe.find(body)?.groupValues?.get(1) ?: ""
            val code = ser.split('-').let { if (it.size >= 2) it[1] else ser }
            sb.append(crc).append('\t').append(code).append('\t').append(name).append('\n')
        }
        if (sb.isEmpty()) return false
        return try { idxFile(ctx, sys).writeText(sb.toString()); true } catch (e: Exception) { false }
    }

    /** Nome oficial pelo CRC (exato) ou, se não achar, pelo código do jogo (GBA). Null se a lista não existe ou o jogo não está nela. */
    fun lookup(ctx: Context, info: Info): String? {
        val f = idxFile(ctx, info.system)
        if (!f.exists()) return null
        var bySerial: String? = null
        try {
            for (l in f.readLines()) {
                val p = l.split('\t')
                if (p.size < 3) continue
                if (p[0] == info.crc) return p[2]
                if (bySerial == null && info.serial.isNotEmpty() && p[1] == info.serial) bySerial = p[2]
            }
        } catch (e: Exception) { }
        return bySerial
    }

    /** Nome oficial do jogo da biblioteca (guardado depois da primeira vez). Sem lista e sem internet, usa o nome do arquivo. */
    fun resolve(ctx: Context, p: Prefs, g: Game, allowNet: Boolean): Pair<String, String>? {
        val cn = p.gameVal(g.id, "nm"); val cs = p.gameVal(g.id, "sys")
        if (cn != null && cs != null) return cn to cs
        val info = infoOfGame(ctx, g) ?: return null
        if (!hasIndex(ctx, info.system) && allowNet) downloadIndex(ctx, info.system)
        val nm = lookup(ctx, info)
        if (nm != null) { p.setG(g.id, "nm", nm); p.setG(g.id, "sys", info.system); return nm to info.system }
        return Store.base(g.name) to info.system
    }
}

/** Banco de cheats: arquivos .cht do libretro guardados em EmuladorPro/Banco/Cheats/<sistema>. */
object CheatBank {
    private const val RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/"

    fun dir(ctx: Context, sys: String): File = File(File(Store.dir(ctx, "bank"), "Cheats"), sys).also { it.mkdirs() }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun title(s: String) = norm(s.substringBefore(" ("))

    /** Procura no banco que já está no aparelho (sem internet): nome oficial, nome do arquivo ou mesmo título. */
    fun findLocal(ctx: Context, sys: String, official: String?, romBase: String): File? {
        val files = dir(ctx, sys).listFiles()?.filter { it.isFile && it.name.endsWith(".cht", true) } ?: return null
        if (official != null) files.firstOrNull { it.nameWithoutExtension.equals(official, true) }?.let { return it }
        val k = norm(romBase)
        files.firstOrNull { norm(it.nameWithoutExtension) == k }?.let { return it }
        val t = title(official ?: romBase)
        if (t.isEmpty()) return null
        return files.filter { title(it.nameWithoutExtension) == t }.minByOrNull { it.name.length }
    }

    /** Baixa o cheat de um jogo pelo nome oficial. */
    fun downloadOne(ctx: Context, sys: String, name: String): File? {
        val data = Net.get(RAW + Net.enc(sys) + "/" + Net.enc(name) + ".cht") ?: return null
        if (data.isEmpty()) return null
        return try { File(dir(ctx, sys), name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".cht").also { it.writeBytes(data) } } catch (e: Exception) { null }
    }

    /** Baixa o banco inteiro de um sistema (um arquivo por jogo, pequenos). Pula o que já existe. Devolve quantos baixou. */
    fun downloadAll(ctx: Context, sys: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Int {
        val listing = Net.get("https://api.github.com/repos/libretro/libretro-database/git/trees/master:cht/" + sys.replace(" ", "%20"), 40000) ?: return -1
        val names = ArrayList<String>()
        try {
            val arr = JSONObject(String(listing, Charsets.UTF_8)).getJSONArray("tree")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = o.getString("path")
                if (o.optString("type") == "blob" && n.endsWith(".cht", true)) names.add(n.removeSuffix(".cht").removeSuffix(".CHT"))
            }
        } catch (e: Exception) { return -1 }
        val d = dir(ctx, sys)
        var got = 0
        for ((i, n) in names.withIndex()) {
            if (cancelled()) break
            progress(i + 1, names.size)
            val safe = n.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".cht"
            if (File(d, safe).exists()) continue
            if (downloadOne(ctx, sys, n) != null) got++
        }
        return got
    }
}

/** Capas: imagem quadrada perfeita (corte central), vinda das miniaturas públicas do libretro. */
object Covers {
    private fun thumbName(n: String) = n.replace(Regex("[&*/:`<>?\\\\|]"), "_")

    fun urlFor(sys: String, name: String, kind: String) =
        "https://thumbnails.libretro.com/" + Net.enc(sys) + "/" + kind + "/" + Net.enc(thumbName(name)) + ".png"

    /** Corta o centro em quadrado e reduz para no máximo [size] px. */
    fun square(src: Bitmap, size: Int = 640): Bitmap {
        val s = minOf(src.width, src.height)
        val crop = if (src.width == src.height) src else Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        return if (s > size) Bitmap.createScaledBitmap(crop, size, size, true) else crop
    }

    fun saveSquare(file: File, src: Bitmap) {
        val sq = square(src)
        file.outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /** Baixa a capa de um jogo da biblioteca. Tenta a capa da caixa e, se não existir, a tela de título. */
    fun fetch(ctx: Context, p: Prefs, g: Game): Boolean {
        val (name, sys) = Ident.resolve(ctx, p, g, true) ?: return false
        for (kind in listOf("Named_Boxarts", "Named_Titles")) {
            val d = Net.get(urlFor(sys, name, kind)) ?: continue
            val bm = BitmapFactory.decodeByteArray(d, 0, d.size) ?: continue
            return try { saveSquare(p.coverFile(g.id), bm); true } catch (e: Exception) { false }
        }
        return false
    }
}
