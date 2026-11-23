package com.erkam.indir

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.erkam.indir.net.Peer
import com.erkam.indir.net.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Aynı Wi-Fi'deki TV Disk alıcısını (Huawei → Kablosuz Aktar → Al) bulur.
 * Uygulama ekranı ya da indirme servisi çalışırken dinler.
 */
object WifiTarget {
    private val peers = ConcurrentHashMap<String, Peer>()
    val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private var users = 0
    private var thread: Thread? = null
    private var sock: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        users++
        if (thread != null) return
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("indir:bul").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "indir:gonder")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        thread = Thread { listen() }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun release() {
        users--
        if (users > 0) return
        users = 0
        try { sock?.close() } catch (_: Exception) { }
        sock = null
        thread = null
        try { lock?.release() } catch (_: Exception) { }
        try { wifiLock?.release() } catch (_: Exception) { }
        lock = null; wifiLock = null
    }

    private fun listen() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1500
                bind(InetSocketAddress(Proto.UDP_PORT))
            }
            sock = s
            val buf = ByteArray(1024)
            var lastNotify = 0L
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) {
                    prune(); continue
                }
                val host = p.address?.hostAddress ?: continue
                val peer = Proto.parseBeacon(String(p.data, 0, p.length, Charsets.UTF_8), host) ?: continue
                peers["${peer.host}:${peer.port}"] = peer
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1500) { lastNotify = now; notifyChanged() }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) { if (thread == Thread.currentThread()) thread = null }
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val gone = peers.filterValues { now - it.lastSeen > 6000 }.keys
        if (gone.isNotEmpty()) { gone.forEach { peers.remove(it) }; notifyChanged() }
    }

    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    /** En uygun alıcı: flash diski hazır olan, en son duyulan. */
    fun best(): Peer? {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastSeen < 6000 }
            .sortedWith(compareByDescending<Peer> { it.diskReady }.thenByDescending { it.lastSeen })
            .firstOrNull()
    }

    /** Alıcı bulunana kadar en fazla [ms] bekler. */
    fun waitFor(ms: Long): Peer? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            best()?.let { return it }
            Thread.sleep(300)
        }
        return best()
    }
}
