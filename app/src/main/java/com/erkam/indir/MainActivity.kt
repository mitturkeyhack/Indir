package com.erkam.indir

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var etUrl: EditText
    private lateinit var tvPlace: TextView
    private lateinit var list: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvEngine: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnUpdate: TextView
    private lateinit var qViews: Map<Int, TextView>
    private var quality = 720
    private val rows = HashMap<String, View>()

    private val jobListener: () -> Unit = { render() }
    private val engineListener: () -> Unit = { renderEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etUrl = findViewById(R.id.etUrl)
        tvPlace = findViewById(R.id.tvPlace)
        list = findViewById(R.id.list)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvEngine = findViewById(R.id.tvEngine)
        tvVersion = findViewById(R.id.tvVersion)
        btnUpdate = findViewById(R.id.btnUpdate)

        val prefs = getSharedPreferences("indir", Context.MODE_PRIVATE)
        quality = prefs.getInt("kalite", 720)
        qViews = mapOf(360 to findViewById(R.id.q360), 720 to findViewById(R.id.q720), 1080 to findViewById(R.id.q1080))
        qViews.forEach { (q, v) ->
            v.setOnClickListener {
                quality = q
                prefs.edit().putInt("kalite", q).apply()
                renderQuality()
            }
        }
        renderQuality()

        findViewById<View>(R.id.btnPlace).setOnClickListener { Store.showPicker(this, REQ_TREE) }
        findViewById<View>(R.id.cardPlace).setOnClickListener { Store.showPicker(this, REQ_TREE) }
        findViewById<View>(R.id.btnPaste).setOnClickListener { pasteFromClipboard(true) }
        findViewById<View>(R.id.btnClear).setOnClickListener { etUrl.setText("") }
        findViewById<View>(R.id.btnMp3).setOnClickListener { start(true) }
        findViewById<View>(R.id.btnMp4).setOnClickListener { start(false) }
        btnUpdate.setOnClickListener { updateEngine() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        handleShare(intent)
        if (Store.treeUri(this) == null) {
            tvPlace.post { Store.showPicker(this, REQ_TREE) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Jobs.listeners.add(jobListener)
        Engine.listeners.add(engineListener)
        tvPlace.text = Store.label(this)
        render()
        renderEngine()
    }

    override fun onPause() {
        super.onPause()
        Jobs.listeners.remove(jobListener)
        Engine.listeners.remove(engineListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Uygulamaya dönünce panodaki link boş kutuya kendiliğinden gelsin
        if (hasFocus && etUrl.text.isBlank()) pasteFromClipboard(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            Store.handlePick(this, data)
            tvPlace.text = Store.label(this)
            if (!Store.isUsb(this)) toast("Telefon klasörü seçildi. Flash disk için tekrar seçebilirsin.")
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        extractUrl(text)?.let {
            etUrl.setText(it)
            toast("Link geldi. Müzik mi video mu?")
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    private fun pasteFromClipboard(userAsked: Boolean) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null }
        val url = t?.let { extractUrl(it) }
        if (url != null) etUrl.setText(url)
        else if (userAsked) toast("Panoda link yok. Önce linki kopyala.")
    }

    private fun start(audio: Boolean) {
        val url = extractUrl(etUrl.text.toString())
        if (url == null) { toast("Önce bir link yapıştır."); return }
        if (Store.root(this) == null) {
            AlertDialog.Builder(this)
                .setMessage(if (Store.treeUri(this) == null) "Önce dosyaların kaydedileceği yeri seç."
                    else "Kayıt yeri bulunamadı. Flash disk takılı mı? Takılıysa yerini tekrar seç.")
                .setPositiveButton("Yeri seç") { _, _ -> Store.showPicker(this, REQ_TREE) }
                .setNegativeButton("Vazgeç", null)
                .show()
            return
        }
        Jobs.add(this, url, audio, quality)
        etUrl.setText("")
        // Aynı linki panodan tekrar çekmesin
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        } catch (_: Exception) { }
        toast(if (audio) "Müzik indiriliyor" else "Video indiriliyor")
    }

    private fun updateEngine() {
        if (Engine.updating) return
        toast("Güncelleniyor…")
        Thread {
            val r = Engine.update(this)
            runOnUiThread {
                toast(when (r) {
                    true -> "İndirici güncellendi ✓"
                    false -> "İndirici zaten güncel ✓"
                    null -> "Güncellenemedi. İnternet bağlantını kontrol et."
                })
                renderEngine()
            }
        }.start()
    }

    private fun renderQuality() {
        qViews.forEach { (q, v) ->
            v.setBackgroundResource(if (q == quality) R.drawable.bg_option_sel else R.drawable.bg_option)
        }
    }

    private fun renderEngine() {
        when {
            Engine.initError != null && !Engine.ready -> {
                tvEngine.visibility = View.VISIBLE
                tvEngine.text = "İndirici başlatılamadı: ${Engine.initError}"
            }
            !Engine.ready -> {
                tvEngine.visibility = View.VISIBLE
                tvEngine.text = "İlk açılış: indirici hazırlanıyor… (yarım dakika sürebilir)"
            }
            Engine.updating -> {
                tvEngine.visibility = View.VISIBLE
                tvEngine.text = "İndirici güncelleniyor…"
            }
            else -> tvEngine.visibility = View.GONE
        }
        btnUpdate.alpha = if (Engine.updating) 0.5f else 1f
        val v = if (Engine.ready) Engine.version(this) else ""
        tvVersion.text = if (v.isNotBlank()) "yt-dlp $v" else ""
    }

    private fun render() {
        val jobs = Jobs.snapshot()
        tvEmpty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        val ids = jobs.map { it.id }.toSet()
        rows.keys.filter { it !in ids }.forEach { list.removeView(rows.remove(it)) }
        jobs.forEachIndexed { idx, job ->
            val row = rows.getOrPut(job.id) {
                LayoutInflater.from(this).inflate(R.layout.item_job, list, false)
            }
            if (list.indexOfChild(row) != idx) {
                list.removeView(row)
                list.addView(row, idx)
            }
            bind(row, job)
        }
    }

    private fun bind(row: View, job: Job) {
        row.findViewById<TextView>(R.id.tvIcon).text = if (job.audio) "🎵" else "🎬"
        row.findViewById<TextView>(R.id.tvTitle).text = job.title
        val info = row.findViewById<TextView>(R.id.tvInfo)
        info.text = job.info
        info.setTextColor(getColor(when (job.status) {
            Status.DONE -> R.color.ok
            Status.FAILED -> R.color.err
            else -> R.color.sub
        }))
        val pb = row.findViewById<ProgressBar>(R.id.pb)
        val active = job.status == Status.RUNNING || job.status == Status.SAVING
        pb.visibility = if (active || job.status == Status.DONE) View.VISIBLE else View.GONE
        pb.progress = (job.progress * 10).toInt()
        val act = row.findViewById<TextView>(R.id.btnAct)
        act.visibility = View.VISIBLE
        when (job.status) {
            Status.WAITING, Status.RUNNING -> { act.text = "İptal"; act.setOnClickListener { Jobs.cancel(job) } }
            Status.SAVING -> act.visibility = View.GONE
            Status.DONE -> { act.text = "Aç"; act.setOnClickListener { open(job) } }
            Status.FAILED -> { act.text = "Tekrar"; act.setOnClickListener { Jobs.retry(this, job) } }
            Status.CANCELED -> { act.text = "Kaldır"; act.setOnClickListener { Jobs.cancel(job) } }
        }
        row.setOnLongClickListener {
            if (job.status == Status.DONE || job.status == Status.FAILED || job.status == Status.CANCELED) {
                Jobs.cancel(job); true
            } else false
        }
    }

    private fun open(job: Job) {
        val u = job.savedUri ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, if (job.audio) "audio/mpeg" else "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) { toast("Bu dosyayı açacak uygulama bulunamadı.") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { const val REQ_TREE = 11 }
}
