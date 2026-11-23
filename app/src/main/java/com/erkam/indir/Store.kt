package com.erkam.indir

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** Kayıt yeri: kullanıcının seçtiği flash disk (ya da telefon klasörü). Müzik ve Video klasörleri açılır. */
object Store {
    const val DIR_MUSIC = "Muzik"
    const val DIR_VIDEO = "Video"
    private const val PREF_TREE = "tree"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("indir", Context.MODE_PRIVATE)

    fun treeUri(ctx: Context): Uri? = prefs(ctx).getString(PREF_TREE, null)?.let { Uri.parse(it) }

    /** true: indirilenler Wi-Fi ile Huawei'deki TV Disk'e (flash diske) gönderilir. */
    fun isWifi(ctx: Context): Boolean = prefs(ctx).getBoolean("wifi", false)
    fun setWifi(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wifi", on).apply()

    fun wifiLabel(): String {
        val p = WifiTarget.best() ?: return "Aranıyor… Huawei'de TV Disk → Kablosuz Aktar → Al açık olmalı"
        if (!p.diskReady) return "${p.name} bulundu · ama orada flash disk seçilmemiş"
        return buildString {
            append("Bağlı · ").append(p.diskName.ifBlank { "Flash disk" })
            if (p.free >= 0) append(" · ").append(String.format(Locale("tr"), "%.1f GB boş", p.free / 1e9))
        }
    }

    fun isUsb(ctx: Context): Boolean {
        val u = treeUri(ctx) ?: return false
        val id = try { DocumentsContract.getTreeDocumentId(u) } catch (_: Exception) { return false }
        return !(id.startsWith("primary") || id.startsWith("home") || id.startsWith("downloads") || id.startsWith("raw:"))
    }

    fun root(ctx: Context): DocumentFile? {
        val u = treeUri(ctx) ?: return null
        return try {
            val d = DocumentFile.fromTreeUri(ctx, u)
            if (d != null && d.exists() && d.canWrite()) d else null
        } catch (_: Exception) { null }
    }

    fun label(ctx: Context): String {
        val r = root(ctx) ?: return if (treeUri(ctx) == null) "Seçilmedi" else "Bulunamadı (disk takılı mı?)"
        val kind = if (isUsb(ctx)) "Flash disk" else "Telefon"
        val free = freeBytes(ctx)
        val name = r.name ?: ""
        return buildString {
            append(kind)
            if (name.isNotBlank()) append(" · ").append(name)
            if (free >= 0) append(" · ").append(String.format(Locale("tr"), "%.1f GB boş", free / 1e9))
        }
    }

    fun freeBytes(ctx: Context): Long {
        val u = treeUri(ctx) ?: return -1
        return try {
            val id = DocumentsContract.getTreeDocumentId(u).substringBefore(':')
            val path = if (id == "primary") "/storage/emulated/0" else "/storage/$id"
            StatFs(path).availableBytes
        } catch (_: Exception) { -1 }
    }

    fun showPicker(a: Activity, requestCode: Int) {
        AlertDialog.Builder(a)
            .setTitle("Kayıt yerini seç")
            .setMessage("Açılan pencerede sol üstteki ☰ menüden USB belleği (flash diski) seç.\n\n" +
                "Sonra alttaki \"BU KLASÖRÜ KULLAN\" / \"İZİN VER\" düğmesine bas.\n\n" +
                "Flash disk yoksa telefonun Download klasörünü de seçebilirsin.")
            .setPositiveButton("Tamam") { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.putExtra("android.content.extra.SHOW_ADVANCED", true)
                try {
                    @Suppress("DEPRECATION") a.startActivityForResult(i, requestCode)
                } catch (_: Exception) { }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    fun handlePick(a: Activity, data: Intent?): Boolean {
        val uri = data?.data ?: return false
        try {
            a.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        prefs(a).edit().putString(PREF_TREE, uri.toString()).apply()
        return true
    }

    private fun subDir(ctx: Context, name: String): DocumentFile? {
        val r = root(ctx) ?: return null
        return try {
            if (r.name.equals(name, true)) r
            else r.findFile(name)?.takeIf { it.isDirectory } ?: r.createDirectory(name)
        } catch (_: Exception) { null }
    }

    /** Geçici dosyayı flash diske kopyalar; oluşturulan dosyanın Uri'sini döner. */
    fun save(ctx: Context, f: File, audio: Boolean, onProgress: (Float) -> Unit): Uri {
        val dir = subDir(ctx, if (audio) DIR_MUSIC else DIR_VIDEO)
            ?: throw IllegalStateException("Kayıt yeri bulunamadı. Flash disk takılı mı? Uygulamadan kayıt yerini tekrar seç.")
        val doc = dir.createFile("application/octet-stream", f.name)
            ?: throw IllegalStateException("Diske dosya yazılamadı. Disk dolu ya da çıkarılmış olabilir.")
        val total = f.length().coerceAtLeast(1)
        try {
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { out ->
                FileInputStream(f).use { inp ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (4 shl 20)) { last = done; onProgress(done * 100f / total) }
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            try { doc.delete() } catch (_: Exception) { }
            if (f.length() > 4L * 1024 * 1024 * 1024 - 1)
                throw IllegalStateException("Dosya 4 GB'tan büyük, FAT32 flash disk bunu kaldırmaz. Daha düşük kalite seç.")
            throw IllegalStateException("Diske yazarken hata: ${e.message ?: "bilinmiyor"}. Disk dolu ya da çıkarılmış olabilir.")
        }
        return doc.uri
    }
}
