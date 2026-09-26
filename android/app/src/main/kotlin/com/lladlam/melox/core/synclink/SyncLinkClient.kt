package com.lladlam.melox.core.synclink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class SlFrame(val seq: Int, val pri: Int, val cmd: Int, val payload: ByteArray) {
    val msg: ProtoMsg by lazy { ProtoMsg.parse(payload) }
}

/**
 * One session with a SyncLink server (the DAP) over TCP or Bluetooth RFCOMM. Frames are a 12-byte little-endian
 * header — seq(3) pri(1) cmd(4) len(4) — followed by a proto3 payload.
 *
 * Responses are matched by response command number, not by seq: the server numbers its own
 * frames (100..1000) and never echoes ours, exactly like the official client relies on.
 */
class SyncLinkClient(
    private val device: SlDevice,
    private val context: Context,
    private val onClosed: (SyncLinkClient, Throwable?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicInteger(1000)
    private val waiters = HashMap<Int, ArrayDeque<CompletableDeferred<SlFrame>>>()
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingWrites = AtomicInteger(0)
    private var socket: Closeable? = null
    private var output: BufferedOutputStream? = null
    private var heartbeatJob: Job? = null
    @Volatile private var lastRxAt = 0L
    @Volatile private var writeStartedAt = 0L
    @Volatile private var closed = false

    private val _frames = MutableSharedFlow<SlFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<SlFrame> = _frames

    /** Blocking connect; call from IO. */
    fun connect(timeoutMs: Int = 6000) {
        if (device.bluetooth) connectBluetooth() else connectTcp(timeoutMs)
    }

    private fun connectTcp(timeoutMs: Int) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(device.host, device.port), timeoutMs)
        attach(s, s.getInputStream(), s.getOutputStream())
    }

    /**
     * Classic Bluetooth RFCOMM, the channel the official Android client uses with the DAP.
     * Same framing as TCP; the phone is the RFCOMM client, the DAP listens on the SPP record.
     */
    @SuppressLint("MissingPermission")
    private fun connectBluetooth() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("本机不支持蓝牙")
        if (!adapter.isEnabled) throw IOException("蓝牙未开启")
        val remote = adapter.getRemoteDevice(device.host.uppercase())
        // Needs BLUETOOTH_SCAN on Android 12+; discovery is rarely running, so failure is harmless.
        runCatching { adapter.cancelDiscovery() }
        var lastError: Throwable? = null
        val attempts = listOf<() -> BluetoothSocket>(
            { remote.createRfcommSocketToServiceRecord(RFCOMM_SECURE) },
            { remote.createInsecureRfcommSocketToServiceRecord(RFCOMM_INSECURE) },
        )
        for (open in attempts) {
            if (closed) break
            val s = try { open() } catch (e: IOException) { lastError = e; continue }
            try {
                s.connect()
                attach(s, s.inputStream, s.outputStream)
                return
            } catch (e: IOException) {
                lastError = e
                runCatching { s.close() }
            }
        }
        throw IOException("蓝牙连接失败，请确认已配对且播放器已开启 SyncLink", lastError)
    }

    private fun attach(link: Closeable, input: InputStream, out: OutputStream) {
        socket = link
        lastRxAt = SystemClock.elapsedRealtime()
        val buffered = BufferedOutputStream(out, 8192)
        output = buffered
        scope.launch { writeLoop(buffered) }
        scope.launch { readLoop(BufferedInputStream(input, 64 * 1024)) }
    }

    /**
     * The official client resets its counter on every parsed frame and gives up after 4 silent seconds.
     * Over RFCOMM a single cover or queue page can take several seconds to arrive, so liveness here is
     * "any byte received" (tracked inside the read loop), with a wider window, plus a stalled-write check.
     */
    fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastRxAt = SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val limit = if (device.bluetooth) SILENCE_LIMIT_BT_MS else SILENCE_LIMIT_TCP_MS
                if (now - lastRxAt > limit) {
                    close(IOException("心跳超时（${limit / 1000} 秒未收到设备数据）"))
                    return@launch
                }
                val writeStarted = writeStartedAt
                if (writeStarted > 0 && now - writeStarted > limit) {
                    close(IOException("发送阻塞，连接已失效"))
                    return@launch
                }
                // Don't pile heartbeats behind a backed-up link; one pending is enough.
                if (pendingWrites.get() < 4) send(SlCmd.HEART_BEAT_REQ)
            }
        }
    }

    private fun nextSeq(): Int {
        while (true) {
            val cur = seq.get()
            val next = if (cur >= 8388607) 1000 else cur + 1
            if (seq.compareAndSet(cur, next)) return cur
        }
    }

    /**
     * Queues a frame for the writer thread and returns immediately, like the official
     * addCommandSendToServer. Callers are often on the main thread (UI, MediaSession); a blocking
     * socket write there is an ANR when the Bluetooth link stalls, and a crash on TCP.
     */
    fun send(cmd: Int, payload: ByteArray? = null, pri: Int = 64, fixedSeq: Int? = null): Boolean {
        if (closed || output == null) return false
        val body = payload ?: EMPTY
        val frame = ByteArray(12 + body.size)
        val s = fixedSeq ?: nextSeq()
        frame[0] = s.toByte(); frame[1] = (s shr 8).toByte(); frame[2] = (s shr 16).toByte()
        frame[3] = pri.toByte()
        putIntLe(frame, 4, cmd)
        putIntLe(frame, 8, body.size)
        if (body.isNotEmpty()) System.arraycopy(body, 0, frame, 12, body.size)
        pendingWrites.incrementAndGet()
        if (writeQueue.trySend(frame).isFailure) {
            pendingWrites.decrementAndGet()
            return false
        }
        return true
    }

    private suspend fun writeLoop(out: BufferedOutputStream) {
        try {
            for (frame in writeQueue) {
                writeStartedAt = SystemClock.elapsedRealtime()
                out.write(frame)
                if (writeQueue.isEmpty) out.flush()
                writeStartedAt = 0L
                pendingWrites.decrementAndGet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            close(if (closed) null else e)
        }
    }

    /** Sends [cmd] and suspends until a frame with [respCmd] arrives, or null on timeout/disconnect. */
    suspend fun request(
        cmd: Int,
        payload: ByteArray?,
        respCmd: Int,
        timeoutMs: Long = 8000,
        pri: Int = 64,
        fixedSeq: Int? = null,
    ): SlFrame? {
        val waiter = CompletableDeferred<SlFrame>()
        synchronized(waiters) { waiters.getOrPut(respCmd) { ArrayDeque() }.addLast(waiter) }
        return try {
            if (!send(cmd, payload, pri, fixedSeq)) return null
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            synchronized(waiters) { waiters[respCmd]?.remove(waiter) }
        }
    }

    private fun readLoop(input: InputStream) {
        val header = ByteArray(12)
        try {
            while (!closed) {
                readFully(input, header, 12)
                val s = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or ((header[2].toInt() and 0xFF) shl 16)
                val pri = header[3].toInt() and 0xFF
                val cmd = getIntLe(header, 4)
                val len = getIntLe(header, 8)
                if (len < 0 || len > MAX_PAYLOAD) throw IOException("帧长度异常: $len (cmd=$cmd)")
                val payload = if (len == 0) EMPTY else ByteArray(len).also { readFully(input, it, len) }
                val frame = SlFrame(s, pri, cmd, payload)
                val waiter = synchronized(waiters) { waiters[cmd]?.pollFirst() }
                waiter?.complete(frame)
                if (cmd != SlCmd.HEART_BEAT_RESP && cmd != SlCmd.HEART_BEAT_REQ) {
                    if (!_frames.tryEmit(frame)) Log.w(TAG, "frame buffer full, dropped cmd=$cmd")
                }
            }
        } catch (e: Throwable) {
            close(if (closed) null else e)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            // The official client ignores -1 and spins; treat EOF as the peer hanging up.
            if (n < 0) throw EOFException("设备已断开连接")
            if (n > 0) lastRxAt = SystemClock.elapsedRealtime()
            off += n
        }
    }

    fun close(cause: Throwable? = null) {
        if (closed) return
        closed = true
        heartbeatJob?.cancel()
        writeQueue.close()
        runCatching { socket?.close() }
        synchronized(waiters) {
            waiters.values.forEach { q -> q.forEach { it.cancel() } }
            waiters.clear()
        }
        scope.cancel()
        onClosed(this, cause)
    }

    /** Polite logout before closing (seq 98, pri 0), mirroring the official client. Blocks briefly; call off the main thread. */
    fun logoutAndClose() {
        if (!closed) {
            send(SlCmd.LOGOUT_REQ, null, pri = 0, fixedSeq = 98)
            closed = true
            writeQueue.close()
            // Let the writer drain the logout frame, but never hang on a dead link.
            val deadline = SystemClock.elapsedRealtime() + 600
            while (pendingWrites.get() > 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        }
        closed = true
        heartbeatJob?.cancel()
        writeQueue.close()
        runCatching { socket?.close() }
        synchronized(waiters) {
            waiters.values.forEach { q -> q.forEach { it.cancel() } }
            waiters.clear()
        }
        scope.cancel()
    }

    val isClosed: Boolean get() = closed

    companion object {
        private const val TAG = "SyncLinkClient"
        private const val MAX_PAYLOAD = 16 * 1024 * 1024
        private const val SILENCE_LIMIT_TCP_MS = 10_000L
        private const val SILENCE_LIMIT_BT_MS = 15_000L
        private val EMPTY = ByteArray(0)
        private val RFCOMM_SECURE: UUID = UUID.fromString("0000cc0c-0000-1000-8000-00805f9b34fb")
        private val RFCOMM_INSECURE: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

        private fun putIntLe(b: ByteArray, off: Int, v: Int) {
            b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte(); b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
        }

        private fun getIntLe(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
