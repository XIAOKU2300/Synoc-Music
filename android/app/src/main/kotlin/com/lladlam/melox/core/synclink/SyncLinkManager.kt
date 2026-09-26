package com.lladlam.melox.core.synclink

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lladlam.melox.core.lyrics.LrcLyricsParser
import com.lladlam.melox.core.lyrics.LyricsDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class SlConnection { Idle, Connecting, Connected, Failed }

data class SyncLinkState(
    val connection: SlConnection = SlConnection.Idle,
    val error: String? = null,
    val device: SlDevice? = null,
    val deviceType: Int = -1,
    val info: SlPlayInfo = SlPlayInfo(),
    val playStatus: Int = SlControl.STOP,
    val queueIndex: Int = -1,
    val queueTotal: Int = 0,
    /** Raw playtime reported by the device (seconds, may include the CUE offset on Linux firmware). */
    val positionSec: Int = 0,
    val positionAt: Long = 0L,
    val durationSec: Int = 0,
    val volume: SlVolume = SlVolume(),
    val mode: Int = SlMode.NORMAL,
    val coverPath: String? = null,
    val coverKey: String = "",
    /** True when [coverPath] is matched online artwork; the DAP's own push must not replace it. */
    val coverFromMeta: Boolean = false,
    val lyrics: String? = null,
    val lyricsKey: String = "",
    /** Word-timed/translated lyrics matched online (or from the local meta cache) for [metaKey]. */
    val metaLyrics: LyricsDocument? = null,
    val metaKey: String = "",
    val metaLoading: Boolean = false,
    val battery: SlBattery? = null,
    val deviceInfo: SlDeviceInfo? = null,
    val storages: List<SlStorage> = emptyList(),
    val settings: Map<SlSetting, Int> = emptyMap(),
    val scanningCount: Int? = null,
    val remotePaused: Boolean = false,
    val libraryRevision: Int = 0,
    /** A dropped session is being retried automatically (backoff pending or reconnect in flight). */
    val reconnecting: Boolean = false,
) {
    val isConnected: Boolean get() = connection == SlConnection.Connected
    /** Whether the DAP should keep owning the MediaSession, including across automatic reconnects. */
    val holdsSession: Boolean get() = isConnected || (reconnecting && connection != SlConnection.Idle)
    val isAndroidDevice: Boolean get() = slIsAndroidDevice(deviceType)
    val isPlaying: Boolean get() = isConnected && playStatus == SlControl.PLAY
    val hasTrack: Boolean get() = isConnected && !info.isEmpty

    /** Linux firmware reports CUE tracks with absolute file time; Android firmware reports track-relative time. */
    val cueOffsetSec: Int get() = if (!isAndroidDevice && info.time > 0) info.time else 0

    val durationMs: Long
        get() {
            val cue = if (info.time >= 0 && info.timeEnd > info.time) info.timeEnd - info.time else 0
            val sec = when {
                cue > 0 -> cue
                durationSec > 0 -> durationSec
                else -> info.totalTime
            }
            return sec.coerceAtLeast(0) * 1000L
        }

    fun positionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        var ms = (positionSec - cueOffsetSec).coerceAtLeast(0) * 1000L
        if (isPlaying && positionAt > 0) ms += (now - positionAt).coerceIn(0L, 5_000L)
        val dur = durationMs
        return if (dur > 0) ms.coerceAtMost(dur) else ms
    }

    /** Linux firmware reports bitrate in bps, Android firmware in kbps. */
    val bitrateKbps: Int get() = if (isAndroidDevice) info.bitrate else info.bitrate / 1000

    /** DSD64/128/… from the native DSD rate, or null for PCM (or DSD reported at a PCM rate). */
    private val dsdLabel: String?
        get() = if (info.isDsd && info.sampleRate >= 2_822_400) "DSD${(info.sampleRate + 22_050) / 44_100}" else null

    private val rateLabel: String?
        get() {
            val rate = info.sampleRate
            return when {
                rate <= 0 -> null
                rate >= 1_000_000 -> "%.1fMHz".format(rate / 1_000_000f)
                rate % 1000 == 0 -> "${rate / 1000}kHz"
                else -> "%.1fkHz".format(rate / 1000f)
            }
        }

    /** Full format line, as the official app shows it: "FLAC · 24bit/96kHz · 2304kbps". */
    val qualityLabel: String
        get() {
            if (info.isEmpty) return ""
            val depth = if (dsdLabel != null) rateLabel else listOfNotNull(
                info.bitsPerSample.takeIf { it > 0 }?.let { "${it}bit" },
                rateLabel,
            ).joinToString("/").ifBlank { null }
            return listOfNotNull(
                (dsdLabel ?: info.codec).ifBlank { null },
                depth,
                bitrateKbps.takeIf { it > 0 }?.let { "${it}kbps" },
            ).joinToString(" · ")
        }

    /** Compact chip text for the Now Playing screen: "FLAC 24/96", "DSD128", "MP3 320k". */
    val qualityBadge: String
        get() {
            if (info.isEmpty) return ""
            dsdLabel?.let { return it }
            val codec = info.codec.ifBlank { "SyncLink" }
            val bits = info.bitsPerSample
            val rate = info.sampleRate
            return when {
                bits > 0 && rate > 0 -> {
                    val k = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(rate / 1000f)
                    "$codec $bits/$k"
                }
                bitrateKbps > 0 -> "$codec ${bitrateKbps}k"
                else -> codec
            }
        }
}

/**
 * Process-wide SyncLink session. Owns the TCP client, mirrors every piece of state the DAP
 * pushes into [state], and exposes typed commands and library queries.
 */
object SyncLinkManager {
    private const val TAG = "SyncLinkManager"
    private const val PREFS = "melox_synclink"
    private const val PAGE = 100
    private const val QUEUE_CAP = 3000
    private const val MAX_RECONNECTS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SyncLinkState())
    val state: StateFlow<SyncLinkState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<SlSong>>(emptyList())
    val queue: StateFlow<List<SlSong>> = _queue.asStateFlow()

    private val _prompts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val prompts: SharedFlow<String> = _prompts

    /**
     * Whether MeloX's MediaSession should drive the DAP. Claimed on connect and on every remote
     * command; released when the user starts local playback from the regular MeloX screens.
     */
    private val _remoteRoute = MutableStateFlow(false)
    val remoteRoute: StateFlow<Boolean> = _remoteRoute.asStateFlow()

    fun claimRoute() {
        if (client != null) _remoteRoute.value = true
    }

    fun releaseRoute() {
        _remoteRoute.value = false
    }

    private var appContext: Context? = null
    @Volatile private var client: SyncLinkClient? = null
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private var queueJob: Job? = null
    private var userDisconnect = false
    private val connectLock = Mutex()
    private val listLock = Mutex()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // -----------------------------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------------------------

    fun lastDevice(context: Context): SlDevice? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString("host", null) ?: return null
        return SlDevice(
            name = p.getString("name", null) ?: host,
            host = host,
            port = p.getInt("port", SyncLinkDiscovery.PORT),
            uuid = p.getString("uuid", null).orEmpty(),
            bluetooth = p.getBoolean("bluetooth", false),
        )
    }

    private fun remember(device: SlDevice) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("host", device.host)?.putInt("port", device.port)
            ?.putString("name", device.name)?.putString("uuid", device.uuid)
            ?.putBoolean("bluetooth", device.bluetooth)?.apply()
    }

    fun connect(device: SlDevice) {
        userDisconnect = false
        reconnectJob?.cancel()
        appContext?.let { SyncLinkConnectionService.start(it) }
        scope.launch { connectInternal(device, attempt = 0) }
    }

    /**
     * Called when the SyncLink screen returns to the foreground: if the session dropped while we were
     * in the background (or reconnects gave up), reconnect right away instead of showing a stale error.
     */
    fun resumeIfNeeded() {
        val s = _state.value
        val device = s.device ?: return
        if (userDisconnect || client != null || connectLock.isLocked) return
        if (s.connection != SlConnection.Failed) return
        reconnectJob?.cancel()
        appContext?.let { SyncLinkConnectionService.start(it) }
        scope.launch { connectInternal(device, attempt = 1) }
    }

    private suspend fun connectInternal(device: SlDevice, attempt: Int): Unit = connectLock.withLock {
        client?.let { old -> client = null; withContext(Dispatchers.IO) { old.logoutAndClose() } }
        sessionJob?.cancel()
        val prev = _state.value
        // A reconnect keeps the last known track/cover/lyrics so the UI doesn't flash empty.
        val resume = attempt > 0 && prev.device?.host == device.host
        _state.value = if (resume) {
            prev.copy(connection = SlConnection.Connecting, device = device)
        } else {
            lastTrackKey = ""
            _queue.value = emptyList()
            SyncLinkState(connection = SlConnection.Connecting, device = device)
        }
        val ctx = appContext ?: run {
            _state.update { it.copy(connection = SlConnection.Failed, error = "SyncLink 未初始化") }
            return@withLock
        }
        val c = SyncLinkClient(device, ctx, ::onClientClosed)
        try {
            withContext(Dispatchers.IO) { c.connect() }
            client = c
            sessionJob = scope.launch { c.frames.collect { handleFrame(it) } }
            // Login uses fixed seq 99; the DAP answers with its DeviceType (258).
            val loginFrame = c.request(SlCmd.LOGIN_REQ, null, SlCmd.LOGIN_RESP, fixedSeq = 99)
                ?: throw java.io.IOException("设备未响应登录请求")
            val type = loginFrame.msg.int(1)
            c.startHeartbeat()
            _state.update { it.copy(connection = SlConnection.Connected, deviceType = type, error = null, reconnecting = false) }
            _remoteRoute.value = true
            remember(device)
            initialSync(c)
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed", t)
            if (client === c) client = null
            withContext(Dispatchers.IO) { c.logoutAndClose() }
            sessionJob?.cancel()
            _state.update { it.copy(connection = SlConnection.Failed, error = t.message ?: "连接失败") }
            if (attempt > 0) scheduleReconnect(device, attempt) else stopKeepAlive()
        }
    }

    private fun initialSync(c: SyncLinkClient) {
        // Same order as the official client's syncLinkOnLogged + initSyncLinkCommand, minus cover/LRC:
        // those are fetched per track (online first, DAP only as fallback) in onTrackChanged.
        c.send(SlCmd.GET_PLAY_STATUS_REQ)
        c.send(SlCmd.GET_VOLUME_INFO_REQ)
        c.send(SlCmd.GET_PLAY_INFO_REQ)
        c.send(SlCmd.GET_PLAY_MODE_REQ)
        c.send(SlCmd.ENABLE_PLAYTIME_NOTIFY_REQ)
        refreshQueue()
        refreshDeviceDetails()
    }

    fun refreshDeviceDetails() {
        val c = client ?: return
        c.send(SlCmd.GET_DEVICEINFO_REQ)
        c.send(SlCmd.GET_BATTERY_REQ)
        c.send(SlCmd.GET_MEMORY_INFO_REQ)
        SlSetting.values().forEach { c.send(it.get) }
    }

    fun refreshAll() {
        val c = client ?: return
        c.send(SlCmd.GET_PLAY_STATUS_REQ)
        c.send(SlCmd.GET_PLAY_INFO_REQ)
        c.send(SlCmd.GET_VOLUME_INFO_REQ)
        c.send(SlCmd.GET_PLAY_MODE_REQ)
        refreshQueue()
        refreshDeviceDetails()
    }

    fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        metaJob?.cancel()
        val c = client
        client = null
        sessionJob?.cancel()
        queueJob?.cancel()
        scope.launch(Dispatchers.IO) { c?.logoutAndClose() }
        _queue.value = emptyList()
        _remoteRoute.value = false
        _state.value = SyncLinkState()
        stopKeepAlive()
    }

    private fun stopKeepAlive() {
        appContext?.let { SyncLinkConnectionService.stop(it) }
    }

    private fun onClientClosed(c: SyncLinkClient, cause: Throwable?) {
        if (client !== c) return
        client = null
        sessionJob?.cancel()
        val device = _state.value.device
        Log.i(TAG, "session closed", cause)
        val retry = !userDisconnect && device != null
        // Set reconnecting in the same update so observers never see a "dead" gap and drop the session.
        _state.update { it.copy(connection = SlConnection.Failed, error = cause?.message ?: "连接已断开", playStatus = SlControl.STOP, reconnecting = retry) }
        if (retry) scheduleReconnect(device, 0) else stopKeepAlive()
    }

    /** The DAP ended our session (260, e.g. another phone took over); like the official client, stay logged out. */
    private fun onServerLogout() {
        val c = client ?: return
        client = null
        userDisconnect = true
        reconnectJob?.cancel()
        sessionJob?.cancel()
        scope.launch(Dispatchers.IO) { c.logoutAndClose() }
        _remoteRoute.value = false
        _state.update {
            it.copy(connection = SlConnection.Failed, error = "设备已结束本次 SyncLink 会话（可能已被其他手机连接），请重新连接", playStatus = SlControl.STOP, reconnecting = false)
        }
        stopKeepAlive()
    }

    private fun scheduleReconnect(device: SlDevice, attempt: Int) {
        if (userDisconnect) return
        if (attempt >= MAX_RECONNECTS) {
            // Give up in the background; resumeIfNeeded() retries once the user comes back.
            _state.update { it.copy(reconnecting = false) }
            stopKeepAlive()
            return
        }
        _state.update { it.copy(reconnecting = true) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay((1500L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
            if (!userDisconnect && client == null) connectInternal(device, attempt + 1)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Incoming frames
    // -----------------------------------------------------------------------------------------

    private var lastTrackKey = ""

    private fun handleFrame(frame: SlFrame) {
        val m = frame.msg
        when (frame.cmd) {
            SlCmd.GET_PLAY_INFO_RESP, SlCmd.GET_PLAY_INFO_NOTIFY -> {
                // Both carry a GetPlayInfoResp wrapper in practice; fall back to a bare PlayInfo.
                val info = SlPlayInfo.from(m.msg(1) ?: m)
                // Some firmware pushes an empty PlayInfo between tracks; keep showing the last track
                // instead of blanking the session (which tears down the media notification/island).
                if (info.isEmpty && !_state.value.info.isEmpty) return
                val key = "${info.songId}|${info.filepath}|${info.title}|${info.time}"
                val now = SystemClock.elapsedRealtime()
                _state.update {
                    it.copy(info = info, positionSec = info.currentTime, positionAt = now,
                        durationSec = if (info.totalTime > 0) info.totalTime else it.durationSec)
                }
                if (key != lastTrackKey) {
                    lastTrackKey = key
                    onTrackChanged(info)
                }
            }
            SlCmd.CUR_PLAY_TIME_NOTIFY -> {
                val now = SystemClock.elapsedRealtime()
                _state.update { it.copy(positionSec = m.int(1), durationSec = m.int(2), positionAt = now) }
            }
            SlCmd.GET_PLAY_STATUS_RESP, SlCmd.GET_PLAY_STATUS_NOTIFY -> {
                val now = SystemClock.elapsedRealtime()
                val prevIndex = _state.value.queueIndex
                val prevTotal = _state.value.queueTotal
                _state.update {
                    val pos = if (it.isPlaying) it.positionMs(now) / 1000 + it.cueOffsetSec else it.positionSec.toLong()
                    it.copy(playStatus = m.int(1), queueIndex = m.int(2), queueTotal = m.int(3),
                        positionSec = pos.toInt(), positionAt = now)
                }
                val s = _state.value
                if (s.queueTotal != prevTotal || (_queue.value.isEmpty() && s.queueTotal > 0) ||
                    (s.queueIndex != prevIndex && _queue.value.size != s.queueTotal)
                ) refreshQueue()
            }
            SlCmd.PLAY_CONTROL_RESP, SlCmd.PLAY_CONTROL_NOTIFY -> {
                val control = m.int(1)
                if (control == SlControl.PLAY || control == SlControl.PAUSE || control == SlControl.STOP) {
                    val now = SystemClock.elapsedRealtime()
                    _state.update {
                        val pos = it.positionMs(now) / 1000 + it.cueOffsetSec
                        it.copy(playStatus = control, positionSec = pos.toInt(), positionAt = now)
                    }
                }
            }
            SlCmd.GET_PLAY_MODE_RESP, SlCmd.SET_PLAY_MODE_RESP, SlCmd.SET_PLAY_MODE_NOTIFY ->
                _state.update { it.copy(mode = m.int(1)) }
            SlCmd.GET_VOLUME_INFO_RESP, SlCmd.VOLUME_INFO_NOTIFY, SlCmd.SET_VOLUME_RESP ->
                _state.update { it.copy(volume = SlVolume.from(m.msg(1) ?: m)) }
            SlCmd.FAVOR_NOTIFY, SlCmd.ADD_FAVOR_RESP ->
                _state.update { it.copy(info = it.info.copy(favor = m.int(1))) }
            SlCmd.GET_COVER_RESP -> handleCover(m)
            SlCmd.GET_LRC_RESP -> handleLyrics(m)
            SlCmd.GET_BATTERY_RESP -> _state.update { it.copy(battery = SlBattery(m.int(1), m.int(2))) }
            SlCmd.GET_DEVICEINFO_RESP -> _state.update {
                it.copy(deviceInfo = SlDeviceInfo(m.string(1), m.string(2), m.string(3), m.string(4), m.string(5)))
            }
            SlCmd.GET_MEMORY_INFO_RESP, SlCmd.GET_MEMORY_INFO_NOTIFY -> parseStorages(m)?.let { list ->
                _state.update { it.copy(storages = list) }
            }
            SlCmd.UPDATING_NOTIFY -> _state.update { it.copy(scanningCount = m.int(1)) }
            SlCmd.FINISH_UPDATE_NOTIFY -> {
                _state.update { it.copy(scanningCount = null, libraryRevision = it.libraryRevision + 1) }
                _prompts.tryEmit("曲库更新完成，共 ${m.int(1)} 首")
            }
            SlCmd.REFRESH_NOTIFY -> {
                val type = m.int(1)
                if (type in 1..4) refreshQueue() else _state.update { it.copy(libraryRevision = it.libraryRevision + 1) }
            }
            SlCmd.CLEAR_DATA_NOTIFY, SlCmd.SORT_TYPE_NOTIFY ->
                _state.update { it.copy(libraryRevision = it.libraryRevision + 1) }
            SlCmd.PROMPT_NOTIFY, SlCmd.MESSAGE_NOTIFY -> m.string(1).takeIf { it.isNotBlank() }?.let { _prompts.tryEmit(it) }
            SlCmd.SYNCLINK_PAUSED -> _state.update { it.copy(remotePaused = m.bool(1)) }
            SlCmd.LOGOUT_NOTIFY -> onServerLogout()
            else -> SlSetting.forResponse(frame.cmd)?.let { setting ->
                _state.update { it.copy(settings = it.settings + (setting to m.int(1))) }
            }
        }
    }

    private var metaJob: Job? = null

    /**
     * 1286 comes in two shapes (official BluetoothChatService tries both): MemoryListResp
     * {repeated MemoryInfoNewResp items = 2} on newer firmware, and the legacy MemoryInfoResp
     * {tfUsage, tfTotal, otgUsage, otgTotal, tfPath, otgPath} that M0-class Linux players still send.
     * Sizes are GB. Returns null when neither shape carries a volume.
     */
    private fun parseStorages(m: ProtoMsg): List<SlStorage>? {
        val items = m.msgs(2).map { s -> SlStorage(s.string(3), s.string(4), s.float(1), s.float(2)) }
            .filter { it.path.isNotBlank() }
        if (items.isNotEmpty()) return items
        val legacy = buildList {
            val tfPath = m.string(5)
            if (tfPath.isNotBlank() && m.float(2) > 0f) {
                add(SlStorage(if (_state.value.isAndroidDevice) "内部存储" else "TF 卡", tfPath, m.float(1), m.float(2)))
            }
            val otgPath = m.string(6)
            if (otgPath.isNotBlank() && m.float(4) > 0f) add(SlStorage("OTG", otgPath, m.float(3), m.float(4)))
        }
        return legacy.ifEmpty { null }
    }

    /**
     * Artwork and lyrics come from SyncLink metadata matched online (cached on disk), not from pulling
     * the file's embedded art/LRC over the link: over RFCOMM a single cover can take seconds and
     * starves heartbeats. The DAP is only asked (1637/1639) for what the online match couldn't find.
     */
    private fun onTrackChanged(info: SlPlayInfo) {
        val key = trackKey(info)
        _state.update {
            val keepCover = it.coverKey == key
            it.copy(
                lyrics = null, lyricsKey = "", metaLyrics = null, metaKey = key, metaLoading = true,
                coverPath = if (keepCover) it.coverPath else null,
                coverFromMeta = keepCover && it.coverFromMeta,
                coverKey = if (keepCover) it.coverKey else "",
            )
        }
        client?.send(SlCmd.GET_PLAY_STATUS_REQ)
        val ctx = appContext ?: return
        metaJob?.cancel()
        metaJob = scope.launch(Dispatchers.IO) {
            val query = SyncLinkMeta.queryFor(info, _state.value.durationMs)
            SyncLinkMeta.cached(ctx, query)?.let { applyMeta(key, it) }
            val result = SyncLinkMeta.resolve(ctx, query)
            applyMeta(key, result)
            _state.update { if (it.metaKey == key) it.copy(metaLoading = false) else it }
            if (trackKey(_state.value.info) != key) return@launch
            val c = client ?: return@launch
            if (result.lyrics == null) c.send(SlCmd.GET_LRC_REQ, null, pri = 0)
            if (result.coverFile == null) {
                c.send(SlCmd.GET_COVER_REQ, null, pri = 0)
                val coverUrl = info.coverUrl
                if (coverUrl.startsWith("http", ignoreCase = true)) {
                    delay(1500)
                    val current = _state.value
                    if (current.coverPath == null && trackKey(current.info) == key) {
                        SyncLinkLanHttp.get(coverUrl)?.let { storeCover(info, it) }
                    }
                }
            }
        }
    }

    private fun applyMeta(key: String, result: SlMetaResult) {
        _state.update {
            if (it.metaKey != key) return@update it
            val cover = result.coverFile?.absolutePath
            it.copy(
                metaLyrics = result.lyrics ?: it.metaLyrics,
                coverPath = cover ?: it.coverPath,
                coverKey = if (cover != null) key else it.coverKey,
                coverFromMeta = cover != null || it.coverFromMeta,
            )
        }
    }

    /** Re-run the online match for the current track, ignoring the cache (user "重新匹配"). */
    fun rematchCurrent() {
        val ctx = appContext ?: return
        val s = _state.value
        if (s.info.isEmpty) return
        val info = s.info
        val key = trackKey(info)
        metaJob?.cancel()
        _state.update { it.copy(metaLoading = true) }
        metaJob = scope.launch(Dispatchers.IO) {
            val result = SyncLinkMeta.resolve(ctx, SyncLinkMeta.queryFor(info, _state.value.durationMs), force = true)
            applyMeta(key, result)
            _state.update { if (it.metaKey == key) it.copy(metaLoading = false) else it }
        }
    }

    private var parsedLrcKey: String? = null
    private var parsedLrc: LyricsDocument? = null

    /** Best lyrics for the current track: online match, else the DAP's LRC parsed as line-timed. */
    fun currentLyrics(s: SyncLinkState = _state.value): LyricsDocument? {
        val key = trackKey(s.info)
        if (s.metaKey == key) s.metaLyrics?.let { return it }
        val text = s.lyrics?.takeIf { s.lyricsKey == key } ?: return null
        synchronized(this) {
            val memo = "$key\u0001${text.hashCode()}"
            if (parsedLrcKey != memo) {
                parsedLrc = runCatching { LrcLyricsParser.parse(text) }.getOrNull()?.takeIf { it.lines.isNotEmpty() }
                parsedLrcKey = memo
            }
            return parsedLrc
        }
    }

    internal fun trackKey(info: SlPlayInfo) = info.filepath.ifBlank { "${info.title}|${info.artist}" }

    private fun handleCover(m: ProtoMsg) {
        val icon = m.bytes(4)
        val prompt = m.string(5)
        val info = _state.value.info
        val path = m.string(1)
        if (path.isNotBlank() && info.filepath.isNotBlank() && path != info.filepath) return
        // Online artwork already matched for this track; the DAP's (often low-res) push is redundant.
        if (_state.value.let { it.coverFromMeta && it.coverKey == trackKey(it.info) }) return
        if (icon == null || icon.isEmpty()) {
            if (prompt.isNotBlank()) Log.d(TAG, "cover prompt: $prompt")
            // No embedded art for this track; drop the previous track's artwork.
            _state.update { if (it.coverKey != trackKey(it.info)) it.copy(coverPath = null, coverKey = trackKey(it.info), coverFromMeta = false) else it }
            return
        }
        scope.launch(Dispatchers.IO) { storeCover(info, icon) }
    }

    private fun storeCover(info: SlPlayInfo, bytes: ByteArray) {
        val ctx = appContext ?: return
        val dir = File(ctx.cacheDir, "synclink_cover").apply { mkdirs() }
        val key = trackKey(info)
        val file = File(dir, "c_${Integer.toHexString(key.hashCode())}_${bytes.size}.jpg")
        runCatching { if (!file.exists()) file.writeBytes(bytes) }.onFailure { return }
        // Bound the cache: keep the newest 24 covers (these are our own runtime cache files).
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
        _state.update {
            if (trackKey(it.info) == key && !(it.coverFromMeta && it.coverKey == key)) {
                it.copy(coverPath = file.absolutePath, coverKey = key, coverFromMeta = false)
            } else it
        }
    }

    private fun handleLyrics(m: ProtoMsg) {
        val info = _state.value.info
        val path = m.string(1)
        if (path.isNotBlank() && info.filepath.isNotBlank() && path != info.filepath) return
        val text = m.bytes(4)?.let(::decodeText)?.takeIf { it.isNotBlank() }
        _state.update { it.copy(lyrics = text, lyricsKey = trackKey(it.info)) }
    }

    private fun decodeText(bytes: ByteArray): String {
        var data = bytes
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data = data.copyOfRange(3, data.size)
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        } catch (_: CharacterCodingException) {
            runCatching { String(data, Charset.forName("GB18030")) }.getOrElse { String(data, Charsets.ISO_8859_1) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Transport commands
    // -----------------------------------------------------------------------------------------

    private fun control(type: Int) {
        claimRoute()
        client?.send(SlCmd.PLAY_CONTROL_REQ, ProtoWriter().int(1, type).build())
    }

    fun play() = control(SlControl.PLAY)
    fun pause() = control(SlControl.PAUSE)
    fun stop() = control(SlControl.STOP)
    fun next() = control(SlControl.NEXT)
    fun previous() = control(SlControl.PREV)
    fun togglePlay() = if (_state.value.playStatus == SlControl.PLAY) pause() else play()

    /** [positionMs] is relative to the current track. */
    fun seekTo(positionMs: Long) {
        val s = _state.value
        val sec = (positionMs / 1000L).toInt().coerceAtLeast(0) + s.cueOffsetSec
        client?.send(SlCmd.PLAY_SEEK_REQ, ProtoWriter().int(1, sec).build())
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(positionSec = sec, positionAt = now) }
    }

    fun setMode(mode: Int) {
        client?.send(SlCmd.SET_PLAY_MODE_REQ, ProtoWriter().int(1, mode).build())
        _state.update { it.copy(mode = mode) }
    }

    fun cycleMode() {
        val next = when (_state.value.mode) {
            SlMode.NORMAL -> SlMode.REPEAT_ALL
            SlMode.REPEAT_ALL -> SlMode.REPEAT_ONE
            SlMode.REPEAT_ONE -> SlMode.RANDOM
            else -> SlMode.NORMAL
        }
        setMode(next)
    }

    /** Returns false when the DAP is in LO (line out) mode and refuses volume changes. */
    fun setVolume(level: Int, mute: Boolean = _state.value.volume.mute == 1): Boolean {
        val v = _state.value.volume
        if (v.lo == 1) {
            _prompts.tryEmit("设备处于 LO 固定电平输出，无法调节音量")
            return false
        }
        val cur = if (v.max > 0) level.coerceIn(0, v.max) else level.coerceAtLeast(0)
        val info = ProtoWriter().int(1, v.max).int(2, cur).int(3, if (mute) 1 else 0)
        client?.send(SlCmd.SET_VOLUME_REQ, ProtoWriter().message(1, info).build())
        _state.update { it.copy(volume = v.copy(cur = cur, mute = if (mute) 1 else 0)) }
        return true
    }

    fun toggleMute() = setVolume(_state.value.volume.cur, _state.value.volume.mute != 1)

    fun toggleFavorite() {
        val favored = _state.value.info.favor == 1
        val next = if (favored) 0 else 1
        client?.send(SlCmd.SET_FAVOR_REQ, ProtoWriter().int(1, next).build())
        _state.update { it.copy(info = it.info.copy(favor = next)) }
    }

    fun setSetting(setting: SlSetting, value: Int) {
        client?.send(setting.set, ProtoWriter().int(1, value).build())
        _state.update { it.copy(settings = it.settings + (setting to value)) }
    }

    fun startLibraryScan() {
        client?.send(SlCmd.START_UPDATE_SONGS_REQ)
    }

    fun requestLyrics() {
        client?.send(SlCmd.GET_LRC_REQ, null, pri = 0)
    }

    // -----------------------------------------------------------------------------------------
    // Play requests (PlayUrlReq: playType=1 keyword=2 path=3 position=4 playlistid=5 id=6 parentPath=7 searchKey=8)
    // -----------------------------------------------------------------------------------------

    private fun playUrl(build: ProtoWriter.() -> Unit) {
        claimRoute()
        client?.send(SlCmd.PLAY_URL_REQ, ProtoWriter().apply(build).build())
    }

    fun playSongList(playType: Int, songs: List<SlSong>, index: Int) {
        playSong(playType, songs.getOrNull(index) ?: return, index)
    }

    /** [position] is the song's index inside the whole [playType] list on the DAP. */
    fun playSong(playType: Int, song: SlSong, position: Int) {
        playUrl {
            int(1, playType); string(3, song.filepath); int(4, position); int(6, song.songId.toInt()); string(7, song.parentPath)
        }
    }

    fun playAlbum(album: SlAlbum, index: Int) = playUrl {
        int(1, SlPlayType.ALBUM); message(2, ProtoWriter().string(8, album.album)); int(4, index); int(6, album.id)
    }

    fun playArtist(artist: SlArtist, index: Int) = playUrl {
        int(1, SlPlayType.SINGER); message(2, ProtoWriter().string(2, artist.artist)); int(4, index); int(6, artist.id)
    }

    fun playGenreAlbum(genre: SlGenre, album: SlAlbum, index: Int) = playUrl {
        int(1, SlPlayType.GENRE_ALBUM)
        message(2, ProtoWriter().string(6, genre.genre).string(8, album.album))
        int(4, index); int(6, album.id)
    }

    fun playPlaylist(playlist: SlPlaylist, index: Int) = playUrl {
        int(1, SlPlayType.PLAYLIST); int(4, index); int(5, playlist.id); int(6, playlist.id)
    }

    fun playFolderItem(items: List<SlPathItem>, index: Int) {
        val item = items.getOrNull(index) ?: return
        playUrl {
            int(1, SlPlayType.FOLDER); string(3, item.path); int(4, index); int(6, item.id); string(7, item.parentPath)
        }
    }

    fun playSearchResult(keyword: String, songs: List<SlSong>, index: Int) {
        val song = songs.getOrNull(index) ?: return
        playUrl {
            int(1, SlPlayType.ALLMUSIC_SEARCH); string(3, song.filepath); int(4, index); int(6, song.songId.toInt())
            string(7, song.parentPath); string(8, keyword)
        }
    }

    fun playQueueIndex(index: Int) {
        val song = _queue.value.getOrNull(index)
        playUrl {
            int(1, SlPlayType.QUEUE); int(4, index)
            if (song != null) { string(3, song.filepath); int(6, song.songId.toInt()); string(7, song.parentPath) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Library queries
    // -----------------------------------------------------------------------------------------

    private suspend fun query(cmd: Int, respCmd: Int, payload: ByteArray): ProtoMsg? {
        val c = client ?: return null
        // The server answers list requests strictly in order; one in flight keeps the cmd-keyed
        // matching unambiguous even when several screens page at once.
        return listLock.withLock { c.request(cmd, payload, respCmd, timeoutMs = 12_000)?.msg }
    }

    private fun paging(offset: Int, size: Int) = ProtoWriter().int(1, offset).int(2, size)

    suspend fun songs(reqCmd: Int, respCmd: Int, offset: Int, size: Int = PAGE): SlPage<SlSong>? =
        query(reqCmd, respCmd, paging(offset, size).build())?.let { SlPage(it.int(1), it.msgs(2).map(SlSong::from)) }

    suspend fun allSongs(offset: Int) = songs(SlCmd.GET_ALL_SONG_REQ, SlCmd.GET_ALL_SONG_RESP, offset)
    suspend fun hrSongs(offset: Int) = songs(SlCmd.GET_ALL_HR_REQ, SlCmd.GET_ALL_HR_RESP, offset)
    suspend fun favoriteSongs(offset: Int) = songs(SlCmd.GET_ALL_FAV_REQ, SlCmd.GET_ALL_FAV_RESP, offset)
    suspend fun recentSongs(offset: Int) = songs(SlCmd.GET_RECENTPLAY_REQ, SlCmd.GET_RECENTPLAY_RESP, offset)

    suspend fun albums(offset: Int): SlPage<SlAlbum>? =
        query(SlCmd.GET_ALBUM_LIST_REQ, SlCmd.GET_ALBUM_LIST_RESP, paging(offset, PAGE).build())
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlAlbum::from)) }

    suspend fun artists(offset: Int): SlPage<SlArtist>? =
        query(SlCmd.GET_ARTIST_LIST_REQ, SlCmd.GET_ARTIST_LIST_RESP, paging(offset, PAGE).build())
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlArtist::from)) }

    suspend fun genres(offset: Int): SlPage<SlGenre>? =
        query(SlCmd.GET_GENRE_LIST_REQ, SlCmd.GET_GENRE_LIST_RESP, paging(offset, PAGE).build())
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlGenre::from)) }

    suspend fun playlists(offset: Int): SlPage<SlPlaylist>? =
        query(SlCmd.GET_PLAYLIST_LIST_REQ, SlCmd.GET_PLAYLIST_LIST_RESP, paging(offset, PAGE).build())
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlPlaylist::from)) }

    private fun idPaging(id: Int, offset: Int) = paging(offset, PAGE).int(3, id).build()

    suspend fun albumSongs(albumId: Int, offset: Int): SlPage<SlSong>? =
        query(SlCmd.GET_SONG_LIST_BY_ALBUM_REQ, SlCmd.GET_SONG_LIST_BY_ALBUM_RESP, idPaging(albumId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlSong::from)) }

    suspend fun artistSongs(artistId: Int, offset: Int): SlPage<SlSong>? =
        query(SlCmd.GET_SONG_LIST_BY_ARTIST_REQ, SlCmd.GET_SONG_LIST_BY_ARTIST_RESP, idPaging(artistId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlSong::from)) }

    suspend fun artistAlbums(artistId: Int, offset: Int): SlPage<SlAlbum>? =
        query(SlCmd.GET_ALBUM_BY_ARTIST_REQ, SlCmd.GET_ALBUM_BY_ARTIST_RESP, idPaging(artistId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlAlbum::from)) }

    suspend fun genreAlbums(genreId: Int, offset: Int): SlPage<SlAlbum>? =
        query(SlCmd.GET_ALBUM_BY_GENRE_REQ, SlCmd.GET_ALBUM_BY_GENRE_RESP, idPaging(genreId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlAlbum::from)) }

    suspend fun genreAlbumSongs(albumId: Int, offset: Int): SlPage<SlSong>? =
        query(SlCmd.GET_SONG_LIST_BY_GENRE_ALBUM_REQ, SlCmd.GET_SONG_LIST_BY_GENRE_ALBUM_RESP, idPaging(albumId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlSong::from)) }

    suspend fun playlistSongs(playlistId: Int, offset: Int): SlPage<SlSong>? =
        query(SlCmd.GET_SONG_LIST_BY_PLAYLIST_REQ, SlCmd.GET_SONG_LIST_BY_PLAYLIST_RESP, idPaging(playlistId, offset))
            ?.let { SlPage(it.int(1), it.msgs(2).map(SlSong::from)) }

    /** type 4 = folder listing (official FolderActivity); roots come from the storage list. */
    fun refreshStorages() {
        client?.send(SlCmd.GET_MEMORY_INFO_REQ)
    }

    /** [type] mirrors the official FolderActivity: the tapped PathItem's own type (4 folder, 1 directory root). */
    suspend fun browse(path: String, offset: Int, type: Int = 4): SlPage<SlPathItem>? =
        query(SlCmd.BROWSE_PATH_REQ, SlCmd.BROWSE_PATH_RESP,
            ProtoWriter().int(1, offset).int(2, PAGE).int(3, type).string(4, path).build())
            ?.let { SlPage(it.int(1), it.msgs(3).map(SlPathItem::from)) }

    data class SearchResult(
        val type: Int,
        val total: Int,
        val songs: List<SlSong>,
        val artists: List<SlArtist>,
        val albums: List<SlAlbum>,
        val genres: List<SlGenre>,
    )

    /** type: 0 songs, 1 artists, 2 albums, 3 genres. */
    suspend fun search(keyword: String, type: Int, offset: Int): SearchResult? =
        query(SlCmd.GET_SEARCH_REQ, SlCmd.GET_SEARCH_RESP,
            ProtoWriter().string(1, keyword).int(2, type).int(3, offset).int(4, PAGE).build())
            ?.let {
                SearchResult(it.int(1), it.int(2), it.msgs(5).map(SlSong::from), it.msgs(6).map(SlArtist::from),
                    it.msgs(7).map(SlAlbum::from), it.msgs(8).map(SlGenre::from))
            }

    /** Loads every page of [loader] (bounded), invoking [onPage] with the accumulated list. */
    suspend fun <T> loadAll(
        cap: Int = 5000,
        loader: suspend (offset: Int) -> SlPage<T>?,
        onPage: (items: List<T>, total: Int) -> Unit,
    ): Boolean {
        val acc = ArrayList<T>()
        var total: Int
        while (true) {
            val page = loader(acc.size) ?: return acc.isNotEmpty()
            total = page.total
            acc.addAll(page.items)
            onPage(ArrayList(acc), maxOf(total, acc.size))
            if (page.items.isEmpty() || acc.size >= total || acc.size >= cap) break
        }
        return true
    }

    fun refreshQueue() {
        if (client == null) return
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(250)
            val acc = ArrayList<SlSong>()
            while (acc.size < QUEUE_CAP) {
                val msg = query(SlCmd.GET_PLAY_QUEUE_REQ, SlCmd.GET_PLAY_QUEUE_RESP, paging(acc.size, PAGE).build()) ?: break
                val items = msg.msgs(2).map(SlSong::from)
                acc.addAll(items)
                _queue.value = ArrayList(acc)
                if (items.isEmpty() || acc.size >= msg.int(1)) break
            }
        }
    }
}
