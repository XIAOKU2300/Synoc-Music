package com.lladlam.melox.core.synclink

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * A SyncLink endpoint. For Wi-Fi devices [host]/[port] is the TCP address; for Bluetooth
 * devices [bluetooth] is true, [host] holds the MAC address and [port] is unused.
 */
data class SlDevice(
    val name: String,
    val host: String,
    val port: Int,
    val uuid: String = "",
    val bluetooth: Boolean = false,
) {
    val addressLabel: String get() = if (bluetooth) "蓝牙 · $host" else "$host:$port"
}

/**
 * LAN discovery: probe "shanling_synclink" to 239.250.255.225:1751; each DAP answers with a
 * 116-byte struct unicast back to port 1751 of the sender:
 * port(4, LE) + ip(16) + name(64) + uuid(32), NUL padded.
 */
object SyncLinkDiscovery {
    const val PORT = 1751
    private const val GROUP = "239.250.255.225"
    private const val PROBE = "shanling_synclink"
    private const val TAG = "SyncLinkDiscovery"

    fun scan(context: Context, durationMs: Long = 6000): Flow<SlDevice> = callbackFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("melox-synclink")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val socket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.recoverCatching {
            Log.w(TAG, "port $PORT busy, falling back to ephemeral port", it)
            MulticastSocket()
        }.getOrThrow()
        socket.timeToLive = 8
        socket.broadcast = true
        socket.soTimeout = 500
        val seen = HashSet<String>()
        val sender = launch(Dispatchers.IO) {
            val probe = PROBE.toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(GROUP)
            val broadcast = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()
            val start = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - start < durationMs) {
                runCatching { socket.send(DatagramPacket(probe, probe.size, group, PORT)) }
                    .onFailure { Log.w(TAG, "multicast probe failed", it) }
                if (broadcast != null) runCatching { socket.send(DatagramPacket(probe, probe.size, broadcast, PORT)) }
                kotlinx.coroutines.delay(1200)
            }
            channel.close()
        }
        launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            while (isActive && !socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    break
                }
                if (packet.length < 116) continue
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val port = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
                    ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
                val ip = cString(data, 4, 16).ifBlank { packet.address?.hostAddress.orEmpty() }
                val name = cString(data, 20, 64).ifBlank { "Shanling" }
                val uuid = cString(data, 84, 32)
                if (ip.isBlank() || port !in 1..65535) continue
                val key = "$ip:$port"
                if (seen.add(key)) trySend(SlDevice(name, ip, port, uuid))
            }
        }
        awaitClose {
            sender.cancel()
            runCatching { socket.close() }
            runCatching { lock?.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && end < b.size && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8).trim()
    }
}
