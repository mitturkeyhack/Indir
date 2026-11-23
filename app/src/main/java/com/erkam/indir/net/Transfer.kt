package com.erkam.indir.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** TV Disk ile aynı kablosuz aktarım protokolü (TV Disk/net/Transfer.kt ile uyumlu tutulmalı). */
object Proto {
    const val TCP_PORT = 47300
    const val UDP_PORT = 47301
    const val BEACON = "TVDISK1"
    const val MAGIC_FILE = 0x54564431 // "TVD1"

    const val OK = 0
    const val NO_DISK = 1
    const val NO_SPACE = 2
    const val TOO_BIG = 3
    const val ERROR = 4

    fun beacon(name: String, port: Int, diskReady: Boolean, free: Long, diskName: String): String =
        listOf(BEACON, clean(name), port.toString(), if (diskReady) "1" else "0", free.toString(), clean(diskName))
            .joinToString("|")

    fun parseBeacon(s: String, host: String): Peer? {
        val p = s.split("|")
        if (p.size < 6 || p[0] != BEACON) return null
        val port = p[2].toIntOrNull() ?: return null
        return Peer(p[1], host, port, p[3] == "1", p[4].toLongOrNull() ?: -1, p[5], System.currentTimeMillis())
    }

    private fun clean(s: String) = s.replace('|', ' ').take(40)

    /** Wi-Fi / hotspot arayüzündeki IPv4 adresi. */
    fun localIPv4(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    val ip = a.hostAddress ?: continue
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("ap") || ni.name.startsWith("swlan")) return ip
                    if (a.isSiteLocalAddress && fallback == null && !ni.name.startsWith("rmnet")) fallback = ip
                }
            }
        } catch (_: Exception) { }
        return fallback
    }

    fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { list += it }
            }
        } catch (_: Exception) { }
        try { list += InetAddress.getByName("255.255.255.255") } catch (_: Exception) { }
        return list.distinct()
    }

    fun statusText(code: Int, msg: String): String = when (code) {
        NO_DISK -> "Alıcı telefonda flash disk seçili değil"
        NO_SPACE -> "Flash diskte yer kalmadı"
        TOO_BIG -> "Dosya 4 GB'tan büyük, FAT32'ye sığmaz"
        else -> msg.ifEmpty { "Alıcı telefonda hata oluştu" }
    }
}

data class Peer(
    val name: String,
    val host: String,
    val port: Int,
    val diskReady: Boolean,
    val free: Long,
    val diskName: String,
    var lastSeen: Long,
)

/** Saniyelik hız ölçer. */
class SpeedMeter {
    private var lastT = System.nanoTime()
    private var lastB = 0L
    var bytesPerSec = 0.0
        private set

    /** true dönerse ekrana yazmanın zamanı gelmiştir. */
    fun update(done: Long): Boolean {
        val now = System.nanoTime()
        val dt = (now - lastT) / 1e9
        if (dt < 0.5) return false
        val inst = (done - lastB) / dt
        bytesPerSec = if (bytesPerSec == 0.0) inst else bytesPerSec * 0.6 + inst * 0.4
        lastT = now; lastB = done
        return true
    }
}

// ---------------- Gönderen ----------------

object SenderCore {
    /** Gönderir ve alıcıdaki son dosya adını döndürür. */
    fun send(
        host: String, port: Int, name: String, size: Long, input: InputStream,
        cancelled: () -> Boolean,
        progress: (done: Long, total: Long, bytesPerSec: Double) -> Unit,
    ): String {
        Socket().use { s ->
            s.sendBufferSize = 1 shl 20
            s.connect(InetSocketAddress(host, port), 6000)
            s.soTimeout = 120_000
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 512 * 1024))
            val inp = DataInputStream(s.getInputStream())
            out.writeInt(Proto.MAGIC_FILE)
            out.writeUTF(name)
            out.writeLong(size)
            out.flush()
            val code = inp.readByte().toInt()
            val msg = inp.readUTF()
            if (code != Proto.OK) throw IOException(Proto.statusText(code, msg))

            val buf = ByteArray(512 * 1024)
            val meter = SpeedMeter()
            var done = 0L
            input.use { src ->
                while (done < size) {
                    if (cancelled()) throw IOException("İptal edildi")
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt())
                    if (n < 0) throw IOException("Dosya beklenenden kısa")
                    try {
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw IOException("Bağlantı koptu (alıcıda yer bitmiş ya da Wi-Fi kopmuş olabilir)")
                    }
                    done += n
                    if (meter.update(done)) progress(done, size, meter.bytesPerSec)
                }
            }
            try { out.flush() } catch (e: IOException) {
                throw IOException("Bağlantı koptu (alıcıda yer bitmiş ya da Wi-Fi kopmuş olabilir)")
            }
            progress(size, size, meter.bytesPerSec)
            val fin = inp.readByte().toInt()
            val finName = inp.readUTF()
            if (fin != Proto.OK) throw IOException(Proto.statusText(fin, finName))
            return finName
        }
    }
}
