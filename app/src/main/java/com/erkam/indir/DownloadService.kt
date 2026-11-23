package com.erkam.indir

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.erkam.indir.net.SenderCore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class Status { WAITING, RUNNING, SAVING, DONE, FAILED, CANCELED }

class Job(val url: String, val audio: Boolean, val quality: Int, val wifi: Boolean = false) {
    val id: String = UUID.randomUUID().toString()
    @Volatile var title: String = url
    @Volatile var status: Status = Status.WAITING
    @Volatile var progress: Float = 0f
    @Volatile var info: String = "Sırada"
    @Volatile var savedUri: Uri? = null
    @Volatile var savedName: String? = null
}

/** İndirme listesi (uygulama açıkken bellekte tutulur). */
object Jobs {
    val list = mutableListOf<Job>()
    val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastPost = 0L

    fun changed(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPost < 300) return
        lastPost = now
        main.post { listeners.toList().forEach { it() } }
    }

    fun snapshot(): List<Job> = synchronized(list) { list.toList() }

    fun add(ctx: Context, url: String, audio: Boolean, quality: Int, wifi: Boolean = Store.isWifi(ctx)) {
        synchronized(list) { list.add(0, Job(url, audio, quality, wifi)) }
        changed()
        val i = Intent(ctx, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun next(): Job? = synchronized(list) { list.lastOrNull { it.status == Status.WAITING } }

    fun cancel(job: Job) {
        when (job.status) {
            Status.WAITING -> { job.status = Status.CANCELED; job.info = "İptal edildi" }
            Status.RUNNING -> { job.status = Status.CANCELED; job.info = "İptal ediliyor…"; YoutubeDL.getInstance().destroyProcessById(job.id) }
            Status.SAVING -> return
            else -> synchronized(list) { list.remove(job) }
        }
        changed()
    }

    fun retry(ctx: Context, job: Job) {
        synchronized(list) { list.remove(job) }
        add(ctx, job.url, job.audio, job.quality, job.wifi)
    }
}

class DownloadService : Service() {
    private var worker: Thread? = null
    // Huawei'ye gönderim ayrı iş parçacığında: bir video giderken sıradaki iner
    private val sender = Executors.newSingleThreadExecutor()
    private val pendingSends = AtomicInteger(0)
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif("Hazırlanıyor…", -1f), type)
        } catch (_: Exception) { }
        if (worker?.isAlive != true) {
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun loop() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "indir:dl").apply { acquire(3 * 3600 * 1000L) }
        WifiTarget.acquire(this)
        try {
            while (true) {
                val job = Jobs.next()
                if (job == null) {
                    if (pendingSends.get() > 0) { Thread.sleep(500); continue }
                    break
                }
                run(job)
            }
        } finally {
            WifiTarget.release()
            try { wake?.release() } catch (_: Exception) { }
            Handler(Looper.getMainLooper()).post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                showDoneSummary()
            }
        }
    }

    private fun run(job: Job) {
        job.status = Status.RUNNING
        job.progress = 0f
        job.info = "Hazırlanıyor…"
        Jobs.changed()
        update(job)

        if (!Engine.ensure(this)) {
            fail(job, "İndirici başlatılamadı: ${Engine.initError ?: ""}")
            return
        }
        val tmp = File(cacheDir, "dl/${job.id}").apply { deleteRecursively(); mkdirs() }
        var handedOff = false
        try {
            val req = YoutubeDLRequest(job.url)
            req.addOption("-o", tmp.absolutePath + "/%(title).80B.%(ext)s")
            req.addOption("--no-playlist")
            req.addOption("--windows-filenames")   // FAT32 flash disk için güvenli dosya adları
            req.addOption("--no-mtime")
            req.addOption("--newline")
            req.addOption("--no-part")
            req.addOption("-N", 4)                 // parçaları aynı anda indir: daha hızlı
            req.addOption("--retries", 5)
            req.addOption("--fragment-retries", 5)
            if (job.audio) {
                req.addOption("-f", "ba[ext=m4a]/ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            } else {
                req.addOption("-f", videoFormat(job.quality))
                req.addOption("--merge-output-format", "mp4")
            }
            req.addOption("--embed-metadata")

            YoutubeDL.getInstance().execute(req, job.id) { p, eta, line ->
                parseTitle(line)?.let { job.title = it }
                if (p > 0) {
                    job.progress = p
                    job.info = buildString {
                        append(String.format("%%%.0f", p))
                        speed(line)?.let { append(" · ").append(it) }
                        if (eta > 0) append(" · ").append(etaText(eta)).append(" kaldı")
                    }
                } else if (line.startsWith("[ExtractAudio]") || line.startsWith("[Merger]")) {
                    job.info = if (job.audio) "MP3'e çevriliyor…" else "Görüntü ve ses birleştiriliyor…"
                }
                Jobs.changed(false)
                update(job)
            }

            if (job.status == Status.CANCELED) throw YoutubeDL.CanceledException()

            val files = tmp.listFiles()?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.sortedByDescending { it.length() }.orEmpty()
            val f = files.firstOrNull() ?: throw IllegalStateException("İndirilen dosya bulunamadı.")
            job.title = f.nameWithoutExtension
            if (job.wifi) {
                handedOff = true
                sendToHuawei(job, f, tmp)
                return
            }
            job.status = Status.SAVING
            job.progress = 0f
            job.info = "Flash diske yazılıyor…"
            Jobs.changed(); update(job)

            val uri = Store.save(this, f, job.audio) { p ->
                job.progress = p
                job.info = String.format("Flash diske yazılıyor… %%%.0f", p)
                Jobs.changed(false); update(job)
            }
            job.savedUri = uri
            job.savedName = f.name
            job.status = Status.DONE
            job.progress = 100f
            job.info = "Kaydedildi: " + (if (job.audio) Store.DIR_MUSIC else Store.DIR_VIDEO) + "/" + f.name
            Jobs.changed()
        } catch (e: YoutubeDL.CanceledException) {
            job.status = Status.CANCELED
            job.info = "İptal edildi"
            Jobs.changed()
        } catch (e: InterruptedException) {
            job.status = Status.CANCELED
            job.info = "İptal edildi"
            Jobs.changed()
        } catch (e: IllegalStateException) {
            fail(job, e.message ?: "Bilinmeyen hata")
        } catch (e: Throwable) {
            if (job.status == Status.CANCELED) {
                job.info = "İptal edildi"; Jobs.changed()
            } else fail(job, friendly(e.message ?: e.toString()))
        } finally {
            if (!handedOff) tmp.deleteRecursively()
        }
    }

    /** İndirilen dosyayı Wi-Fi ile Huawei'deki TV Disk'e gönderir; bitince geçici dosya silinir. */
    private fun sendToHuawei(job: Job, f: File, tmp: File) {
        job.status = Status.SAVING
        job.progress = 0f
        job.info = "Huawei'ye gönderilmeyi bekliyor…"
        Jobs.changed(); update(job)
        pendingSends.incrementAndGet()
        sender.execute {
            try {
                if (f.length() > 4L * 1024 * 1024 * 1024 - 1)
                    throw IllegalStateException("Dosya 4 GB'tan büyük, FAT32 flash disk bunu kaldırmaz. Daha düşük kalite seç.")
                val folder = if (job.audio) "MUZIK" else "FILMLER"
                var attempt = 0
                while (true) {
                    attempt++
                    job.info = "Huawei aranıyor…"; Jobs.changed(false); update(job)
                    val peer = WifiTarget.waitFor(20_000)
                        ?: throw IllegalStateException("Huawei bulunamadı. Huawei'de TV Disk → Kablosuz Aktar → Al açık mı? İki telefon aynı Wi-Fi'de mi?")
                    if (!peer.diskReady)
                        throw IllegalStateException("Huawei'de flash disk seçili değil. Huawei'deki TV Disk'te \"USB diski seç\"e bas, sonra Tekrar'a dokun.")
                    try {
                        val name = (if (job.audio) "MUZIK/" else "") + f.name
                        val fin = SenderCore.send(peer.host, peer.port, name, f.length(), FileInputStream(f), { false }) { done, total, bps ->
                            job.progress = done * 100f / total
                            val left = if (bps > 0) ((total - done) / bps).toLong() else -1
                            job.info = buildString {
                                append(String.format(Locale("tr"), "Huawei'ye gönderiliyor %%%.0f · %.1f MB/sn", job.progress, bps / 1e6))
                                if (left >= 0) append(" · ").append(etaText(left)).append(" kaldı")
                            }
                            Jobs.changed(false); update(job)
                        }
                        job.savedUri = null
                        job.savedName = fin
                        job.status = Status.DONE
                        job.progress = 100f
                        job.info = "Huawei'deki flash diske yazıldı: $folder/$fin"
                        Jobs.changed()
                        break
                    } catch (e: IOException) {
                        val m = e.message ?: ""
                        // Bağlantı sorunlarında 3 kez dene; alıcının "yer yok" gibi cevaplarında deneme
                        val retryable = e is java.net.ConnectException || e is java.net.SocketTimeoutException ||
                            m.startsWith("Bağlantı koptu") || m.contains("reset", true)
                        if (!retryable || attempt >= 3) {
                            throw IllegalStateException(
                                if (e is java.net.ConnectException || e is java.net.SocketTimeoutException)
                                    "Huawei'ye bağlanılamadı. İki telefon aynı Wi-Fi'de mi, Huawei'de \"Al\" ekranı açık mı?"
                                else m.ifBlank { "Gönderilemedi" })
                        }
                        job.info = "Bağlantı koptu, tekrar deneniyor ($attempt/3)…"; Jobs.changed(false)
                        Thread.sleep(2000)
                    }
                }
            } catch (e: IllegalStateException) {
                fail(job, e.message ?: "Gönderilemedi")
            } catch (e: Throwable) {
                fail(job, "Gönderilemedi: ${e.message ?: e}")
            } finally {
                tmp.deleteRecursively()
                pendingSends.decrementAndGet()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sender.shutdown()
        try { sender.awaitTermination(1, TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    private fun fail(job: Job, msg: String) {
        job.status = Status.FAILED
        job.info = msg
        Jobs.changed()
    }

    private fun videoFormat(q: Int): String = when (q) {
        360 -> "b[height<=360][ext=mp4]/bv*[height<=360][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=360]+ba/b"
        1080 -> "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=1080]+ba/b"
        // TV için: H.264 + AAC, 720p — eski televizyonların açtığı biçim
        else -> "bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b"
    }

    private fun parseTitle(line: String): String? {
        val key = "Destination: "
        val i = line.indexOf(key)
        if (!line.startsWith("[download]") || i < 0) return null
        var name = File(line.substring(i + key.length).trim()).name
        name = name.substringBeforeLast('.')
        name = name.replace(Regex("\\.f\\d+$"), "")
        return name.ifBlank { null }
    }

    private fun speed(line: String): String? =
        Regex("at\\s+([\\d.]+\\s*[KMG]i?B/s)").find(line)?.groupValues?.get(1)
            ?.replace("MiB/s", " MB/sn")?.replace("KiB/s", " KB/sn")?.replace("GiB/s", " GB/sn")

    private fun etaText(s: Long): String = if (s >= 60) "${s / 60} dk ${s % 60} sn" else "$s sn"

    private fun friendly(err: String): String {
        val e = err.lowercase()
        return when {
            "unsupported url" in e -> "Bu link desteklenmiyor. Linki kontrol et."
            "sign in to confirm" in e || "not a bot" in e ->
                "YouTube şu an indirmeyi engelledi. Ana ekrandaki \"İndiriciyi güncelle\"ye bas, sonra tekrar dene."
            "private video" in e -> "Bu video gizli, indirilemez."
            "video unavailable" in e -> "Video kaldırılmış ya da bölgende kapalı."
            "unable to resolve" in e || "network is unreachable" in e || "failed to resolve" in e || "timed out" in e ->
                "İnternet bağlantısı yok ya da çok zayıf."
            "requested format is not available" in e -> "Bu kalite yok. Başka kalite seçip tekrar dene."
            "no space left" in e -> "Telefonda yer kalmadı. Biraz yer açıp tekrar dene."
            else -> {
                val last = err.lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR") } ?: err.lines().lastOrNull { it.isNotBlank() } ?: err
                "Hata: " + last.removePrefix("ERROR:").trim().take(220) + "\nİndiriciyi güncelleyip tekrar dene."
            }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    private fun notif(text: String, p: Float, title: String = "İndiriliyor"): android.app.Notification {
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp())
        if (p < 0) b.setProgress(0, 0, true) else b.setProgress(100, p.toInt(), false)
        return b.build()
    }

    @Volatile private var lastNotif = 0L
    private fun update(job: Job) {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 700) return
        lastNotif = now
        val waiting = Jobs.snapshot().count { it.status == Status.WAITING }
        val t = job.title + if (waiting > 0) "  (+$waiting sırada)" else ""
        try { nm.notify(NOTIF_ID, notif(job.info, if (job.progress > 0) job.progress else -1f, t)) } catch (_: Exception) { }
    }

    private fun showDoneSummary() {
        val all = Jobs.snapshot()
        val done = all.count { it.status == Status.DONE }
        val failed = all.count { it.status == Status.FAILED }
        if (done + failed == 0) return
        val text = buildString {
            if (done > 0) append("$done dosya kaydedildi")
            if (failed > 0) { if (isNotEmpty()) append(", "); append("$failed hata") }
        }
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("İndirme bitti")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try { nm.notify(NOTIF_DONE, n) } catch (_: Exception) { }
    }

    companion object {
        const val CH = "indir"
        const val NOTIF_ID = 7
        const val NOTIF_DONE = 8
    }
}
