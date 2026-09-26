package com.lladlam.melox.core.synclink

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Tiny HTTP/1.0 GET for artwork served by the DAP on the LAN. MeloX forbids cleartext traffic
 * app-wide (network_security_config), which only gates the platform HTTP stacks; a raw socket
 * restricted to private addresses keeps that policy intact for everything else.
 */
internal object SyncLinkLanHttp {
    fun get(url: String, maxBytes: Int = 4 * 1024 * 1024, timeoutMs: Int = 5000): ByteArray? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (!isPrivate(address)) return null
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifBlank { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                socket.getOutputStream().apply {
                    write("GET $path HTTP/1.0\r\nHost: $host\r\nConnection: close\r\nUser-Agent: MeloX-SyncLink\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = socket.getInputStream()
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > maxBytes + 16 * 1024) return null
                }
                val raw = buffer.toByteArray()
                val headerEnd = indexOf(raw, byteArrayOf(13, 10, 13, 10))
                if (headerEnd < 0) return null
                val statusLine = String(raw, 0, headerEnd, Charsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty()
                if (statusLine.split(' ').getOrNull(1) != "200") return null
                raw.copyOfRange(headerEnd + 4, raw.size).takeIf { it.isNotEmpty() }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun isPrivate(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            (address is Inet4Address && address.address[0].toInt() and 0xFF == 100 && (address.address[1].toInt() and 0xC0) == 64)

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
