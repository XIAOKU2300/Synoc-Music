package com.lladlam.melox.core.synclink

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.lladlam.melox.core.download.MeloXDownloadStore
import com.lladlam.melox.core.lyrics.AmlldbLyricsClient
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.core.lyrics.LyricSource
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.lyrics.MeloXLyricScript
import com.lladlam.melox.core.lyrics.NeteaseLyricParser
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.provider.LyricsCapability
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.network.MeloXHttpClient
import com.lladlam.melox.ui.player.isSafeCrossProviderLyricMatch
import com.lladlam.melox.ui.player.lyricQualityScore
import com.lladlam.melox.ui.player.normalizeLyricMatchText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What we know about a DAP track, taken from SyncLink metadata only (no Bluetooth file transfer). */
data class SlMetaQuery(
    val title: String,
    val artist: String,
    val album: String,
    /** 0 when unknown (library listings only carry a length for CUE tracks). */
    val durationMs: Long,
) {
    val isUsable: Boolean get() = normalizeLyricMatchText(title).isNotEmpty()

    /** Duration is deliberately not part of the key so batch downloads (no duration) and playback share entries. */
    val cacheKey: String by lazy {
        val raw = listOf(title, artist, album).joinToString("\u0001") { normalizeLyricMatchText(it) }
        MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class SlMetaResult(
    val lyrics: LyricsDocument?,
    val coverFile: File?,
    /** Duration of the online track we matched, for re-validation once the real duration is known. */
    val matchedDurationMs: Long = 0L,
    val fromCache: Boolean = false,
)

data class SlBatchProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val lyricsFound: Int = 0,
    val coversFound: Int = 0,
    val message: String? = null,
)

/**
 * Online cover/lyric matching for SyncLink tracks. The DAP only supplies metadata; covers and
 * word-timed, translated lyrics come from the same providers MeloX already uses (AMLL TTML, QQ QRC,
 * NetEase YRC), matched by title + artist (+ duration when known), and are kept on disk so each
 * song is fetched once. Everything here is best effort and never throws to callers.
 */
object SyncLinkMeta {
    private const val TAG = "SyncLinkMeta"
    private const val DIR = "synclink_meta"
    private const val FORMAT = 1
    private const val MISS_RETRY_MS = 3L * 24 * 60 * 60 * 1000
    private const val QQ_COVER_SIZE = "R800x800"
    private const val NETEASE_COVER_PARAM = "param=1200y1200"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = HashMap<String, Mutex>()
    private val memory = object : LinkedHashMap<String, SlMetaResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SlMetaResult>?) = size > 32
    }

    private val _batch = MutableStateFlow(SlBatchProgress())
    val batch: StateFlow<SlBatchProgress> = _batch.asStateFlow()
    private var batchJob: Job? = null

    // -----------------------------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------------------------

    fun queryFor(info: SlPlayInfo, durationMs: Long): SlMetaQuery =
        buildQuery(info.title, info.artist, info.album, info.filename.ifBlank { info.filepath.substringAfterLast('/') }, durationMs)

    fun queryFor(song: SlSong): SlMetaQuery =
        buildQuery(song.title, song.artist, song.album, song.filename.ifBlank { song.filepath.substringAfterLast('/') }, song.durationSec * 1000L)

    /** MeloX's own player only sees the MediaMetadata we publish (title falls back to the file name there too). */
    fun queryFor(title: String, artist: String, album: String, durationMs: Long): SlMetaQuery =
        buildQuery(title, artist, album, "", durationMs)

    private val unknownArtist = Regex("^(unknown|未知|<unknown>|various artists?|群星)(\\s*artist|歌手|艺术家)?$", RegexOption.IGNORE_CASE)

    private fun buildQuery(title: String, artist: String, album: String, filename: String, durationMs: Long): SlMetaQuery {
        var t = title.trim()
        var a = artist.trim().takeUnless { unknownArtist.matches(it) }.orEmpty()
        if (t.isBlank()) {
            // "03 - Artist - Title.flac" / "03. Title.flac"
            t = filename.substringBeforeLast('.').replace(Regex("^\\s*\\d{1,3}\\s*[-._\\s]+\\s*"), "").trim()
            if (a.isBlank() && " - " in t) {
                val parts = t.split(" - ", limit = 2)
                a = parts[0].trim()
                t = parts[1].trim()
            }
        }
        val al = album.trim().takeUnless { it.equals("unknown", true) || it == "未知专辑" || it.equals("unknown album", true) }.orEmpty()
        return SlMetaQuery(t, a, al, durationMs.coerceAtLeast(0L))
    }

    // -----------------------------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------------------------

    private fun dir(context: Context) = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun lockFor(key: String) = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    private data class Entry(
        val lyrics: LyricsDocument?,
        val coverFile: File?,
        val matchedDurationMs: Long,
        val lyricsMissAt: Long,
        val coverMissAt: Long,
    )

    private fun readEntry(context: Context, q: SlMetaQuery): Entry? {
        val file = File(dir(context), "${q.cacheKey}.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optInt("format") != FORMAT) return null
            val store = MeloXDownloadStore.get(context)
            val lyrics = json.optJSONObject("lyrics")?.let { l ->
                val doc = store.decodeLyrics(l)
                doc.copy(
                    source = runCatching { LyricSource.valueOf(json.optString("lyricsSource")) }.getOrDefault(doc.source),
                    quality = runCatching { LyricQuality.valueOf(json.optString("lyricsQuality")) }.getOrDefault(doc.quality),
                    pseudoTimingAllowed = json.optBoolean("pseudoTiming", doc.pseudoTimingAllowed),
                )
            }?.takeIf { it.lines.isNotEmpty() }
            val cover = json.optString("cover").takeIf(String::isNotBlank)
                ?.let { File(dir(context), it) }?.takeIf { it.isFile && it.length() > 0 }
            Entry(lyrics, cover, json.optLong("matchedDurationMs"), json.optLong("lyricsMissAt"), json.optLong("coverMissAt"))
        }.getOrNull()
    }

    private fun writeEntry(context: Context, q: SlMetaQuery, entry: Entry) {
        runCatching {
            val json = JSONObject()
                .put("format", FORMAT)
                .put("title", q.title).put("artist", q.artist).put("album", q.album)
                .put("matchedDurationMs", entry.matchedDurationMs)
                .put("lyricsMissAt", entry.lyricsMissAt)
                .put("coverMissAt", entry.coverMissAt)
                .put("cover", entry.coverFile?.name ?: "")
            entry.lyrics?.let { doc ->
                json.put("lyrics", MeloXDownloadStore.get(context).encodeLyrics(doc))
                    .put("lyricsSource", doc.source.name)
                    .put("lyricsQuality", doc.quality.name)
                    .put("pseudoTiming", doc.pseudoTimingAllowed)
            }
            val target = File(dir(context), "${q.cacheKey}.json")
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) { target.writeText(json.toString()); tmp.delete() }
        }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    /** Disk/memory only; never touches the network. */
    fun cached(context: Context, q: SlMetaQuery): SlMetaResult? {
        if (!q.isUsable) return null
        synchronized(memory) { memory[q.cacheKey] }?.let { return it }
        val e = readEntry(context, q) ?: return null
        if (e.lyrics == null && e.coverFile == null) return null
        return SlMetaResult(e.lyrics, e.coverFile, e.matchedDurationMs, fromCache = true)
            .also { r -> synchronized(memory) { memory[q.cacheKey] = r } }
    }

    fun cacheStats(context: Context): Pair<Int, Long> {
        val files = dir(context).listFiles().orEmpty()
        return files.count { it.name.endsWith(".json") } to files.sumOf { it.length() }
    }

    // -----------------------------------------------------------------------------------------
    // Resolve
    // -----------------------------------------------------------------------------------------

    /**
     * Cache first, then online. A cached entry matched without a duration is re-checked when the
     * real duration is known and differs by more than 3 s (e.g. the live cut vs. the studio cut).
     */
    suspend fun resolve(context: Context, q: SlMetaQuery, force: Boolean = false): SlMetaResult {
        if (!q.isUsable) return SlMetaResult(null, null)
        val app = context.applicationContext
        return lockFor(q.cacheKey).withLock {
            val now = System.currentTimeMillis()
            val existing = if (force) null else withContext(Dispatchers.IO) { readEntry(app, q) }
            val durationMismatch = existing != null && q.durationMs > 0 && existing.matchedDurationMs > 0 &&
                kotlin.math.abs(existing.matchedDurationMs - q.durationMs) > 3_000
            val needLyrics = existing == null || durationMismatch ||
                (existing.lyrics == null && now - existing.lyricsMissAt > MISS_RETRY_MS)
            val needCover = existing == null ||
                (existing.coverFile == null && now - existing.coverMissAt > MISS_RETRY_MS)
            if (existing != null && !needLyrics && !needCover) {
                return@withLock SlMetaResult(existing.lyrics, existing.coverFile, existing.matchedDurationMs, fromCache = true)
                    .also { r -> synchronized(memory) { memory[q.cacheKey] = r } }
            }
            val online = runCatching { withTimeoutOrNull(40_000) { fetchOnline(app, q, needLyrics, needCover) } }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "online match failed: ${q.title}", it) }
                .getOrNull()
            val lyrics = if (needLyrics) online?.lyrics ?: existing?.lyrics?.takeUnless { durationMismatch } else existing?.lyrics
            val cover = if (needCover) online?.coverFile ?: existing?.coverFile else existing?.coverFile
            val entry = Entry(
                lyrics = lyrics,
                coverFile = cover,
                matchedDurationMs = online?.matchedDurationMs?.takeIf { it > 0 } ?: existing?.matchedDurationMs ?: 0L,
                lyricsMissAt = if (lyrics == null) now else 0L,
                coverMissAt = if (cover == null) now else 0L,
            )
            // Only a completed search counts as a miss; a timeout/offline run is retried next time.
            if (online != null || existing != null) withContext(Dispatchers.IO) { writeEntry(app, q, entry) }
            SlMetaResult(lyrics, cover, entry.matchedDurationMs).also { r -> synchronized(memory) { memory[q.cacheKey] = r } }
        }
    }

    private data class Online(val lyrics: LyricsDocument?, val coverFile: File?, val matchedDurationMs: Long)

    private suspend fun fetchOnline(context: Context, q: SlMetaQuery, wantLyrics: Boolean, wantCover: Boolean): Online = coroutineScope {
        val registry = MeloXMusicProviders.create(context)
        val neteaseProvider = runCatching { registry.require(MusicSource.Netease) }.getOrNull()
        val qqProvider = runCatching { registry.require(MusicSource.QQMusic) }.getOrNull()
        val neteaseMatchJob = async { neteaseProvider?.let { match(it, q) } ?: Matches.None }
        val qqMatchJob = async { qqProvider?.let { match(it, q) } ?: Matches.None }
        val neteaseMatches = neteaseMatchJob.await()
        val qqMatches = qqMatchJob.await()
        // Lyrics need the right cut (timing); artwork only needs the right recording/album.
        val netease = neteaseMatches.strict
        val qq = qqMatches.strict
        val best = netease.firstOrNull() ?: qq.firstOrNull()

        val lyrics = if (!wantLyrics) null else {
            val amll = async {
                val track = netease.firstOrNull() ?: return@async null
                val id = (track.providerMetadata as? ProviderTrackMetadata.Netease)?.numericId
                    ?: track.id.value.toLongOrNull() ?: return@async null
                withTimeoutOrNull(12_000) {
                    runCatching { AmlldbLyricsClient().lyrics(id, MeloXLyricScript.Original) }.getOrNull()
                }
            }
            val qqLyrics = async {
                val track = qq.firstOrNull() ?: return@async null
                withTimeoutOrNull(25_000) { runCatching { (qqProvider as? LyricsCapability)?.lyrics(track) }.getOrNull() }
            }
            val neLyrics = async {
                val track = netease.firstOrNull() ?: return@async null
                withTimeoutOrNull(15_000) { runCatching { (neteaseProvider as? LyricsCapability)?.lyrics(track) }.getOrNull() }
            }
            pickLyrics(listOfNotNull(amll.await(), qqLyrics.await(), neLyrics.await()))
        }

        val cover = if (!wantCover) null else coverCandidates(q, neteaseMatches.loose, qqMatches.loose)
            .firstNotNullOfOrNull { url -> download(context, url, q.cacheKey) }

        Online(lyrics, cover, best?.durationMs ?: 0L)
    }

    private data class Matches(val strict: List<MusicTrack>, val loose: List<MusicTrack>) {
        companion object {
            val None = Matches(emptyList(), emptyList())
        }
    }

    /**
     * Safe matches only (same base title/version, artist overlap), best first. [Matches.strict] also
     * requires ±2 s duration when the DAP told us the length.
     */
    private suspend fun match(provider: Any, q: SlMetaQuery): Matches {
        val search = provider as? SearchCapability ?: return Matches.None
        val clean = q.title.replace(Regex("[（(【\\[].*?[）)】\\]]"), "").trim().ifBlank { q.title }
        val queries = buildList {
            if (q.artist.isNotBlank()) add("$clean ${q.artist.substringBefore(" /").substringBefore("/")}".trim())
            add(clean)
            add(q.title)
        }.distinct()
        val album = normalizeLyricMatchText(q.album)
        fun rank(list: List<MusicTrack>) = list.sortedByDescending { c ->
            var score = 0
            val cAlbum = normalizeLyricMatchText(c.album?.name.orEmpty())
            if (album.isNotEmpty() && cAlbum == album) score += 50
            else if (album.isNotEmpty() && cAlbum.isNotEmpty() && (cAlbum.contains(album) || album.contains(cAlbum))) score += 25
            if (q.durationMs > 0) score -= ((c.durationMs?.let { kotlin.math.abs(it - q.durationMs) } ?: 5_000L) / 250L).toInt()
            score
        }
        for (query in queries) {
            val hits = withTimeoutOrNull(12_000) {
                runCatching { search.searchSongs(query, 1, 15).items }.getOrNull()
            }.orEmpty()
            val loose = hits.filter { isSafeCrossProviderLyricMatch(q.title, q.artist, 0L, it) }
            if (loose.isEmpty()) continue
            val strict = if (q.durationMs > 0) loose.filter { isSafeCrossProviderLyricMatch(q.title, q.artist, q.durationMs, it) } else loose
            return Matches(rank(strict), rank(loose))
        }
        return Matches.None
    }

    /**
     * Authored AMLL word timing wins; otherwise the richest document (word lines, then translation).
     * A missing translation is borrowed from another source, aligned by line time.
     */
    private fun pickLyrics(docs: List<LyricsDocument>): LyricsDocument? {
        val usable = docs.filter { it.lines.isNotEmpty() }
        if (usable.isEmpty()) return null
        val chosen = usable.firstOrNull { d -> d.source == LyricSource.AmlL && d.lines.any { it.syllables.isNotEmpty() } }
            ?: usable.maxBy { lyricQualityScore(it) }
        if (chosen.lines.any { !it.translation.isNullOrBlank() }) return chosen
        val donor = usable.filter { it !== chosen }
            .maxByOrNull { d -> d.lines.count { !it.translation.isNullOrBlank() } }
            ?.takeIf { d -> d.lines.count { !it.translation.isNullOrBlank() } >= 3 }
            ?: return chosen
        val aligned = NeteaseLyricParser.alignSecondary(chosen.lines, donor.lines)
        return chosen.copy(
            lines = chosen.lines.mapIndexed { i, line ->
                val t = aligned.getOrNull(i)?.translation?.takeIf { it.isNotBlank() }
                if (line.translation.isNullOrBlank() && t != null) line.copy(translation = t) else line
            },
        )
    }

    /**
     * Only artwork we can tie to the right album: an album-name match wins, and when the DAP knows the
     * album but no candidate agrees we'd rather fall back to the embedded cover than show a compilation's art.
     */
    private fun coverCandidates(q: SlMetaQuery, netease: List<MusicTrack>, qq: List<MusicTrack>): List<String> {
        val album = normalizeLyricMatchText(q.album)
        fun albumOk(t: MusicTrack): Boolean {
            if (album.isEmpty()) return true
            val a = normalizeLyricMatchText(t.album?.name.orEmpty())
            return a.isNotEmpty() && (a == album || a.contains(album) || album.contains(a))
        }
        val ordered = (netease.filter(::albumOk).map { it to MusicSource.Netease } + qq.filter(::albumOk).map { it to MusicSource.QQMusic })
            .sortedByDescending { (t, _) -> if (normalizeLyricMatchText(t.album?.name.orEmpty()) == album) 1 else 0 }
        return ordered.mapNotNull { (t, src) -> t.artworkUrl?.takeIf(String::isNotBlank)?.let { hiRes(it, src) } }.distinct().take(3)
    }

    private fun hiRes(url: String, source: MusicSource): String {
        val https = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        return when (source) {
            MusicSource.QQMusic -> https.replace(Regex("R\\d{2,4}x\\d{2,4}"), QQ_COVER_SIZE)
            MusicSource.Netease -> https.substringBefore('?') + "?" + NETEASE_COVER_PARAM
            else -> https
        }
    }

    private suspend fun download(context: Context, url: String, key: String): File? = withContext(Dispatchers.IO) {
        val target = File(dir(context), "$key.jpg")
        val tmp = File(target.parentFile, "$key.jpg.tmp")
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            MeloXHttpClient.shared.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                tmp.outputStream().buffered().use { out -> resp.body.byteStream().use { it.copyTo(out) } }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            // Placeholder "no cover" images are tiny; reject them.
            if (bounds.outWidth < 200 || bounds.outHeight < 200) { tmp.delete(); return@runCatching null }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            target
        }.onFailure { tmp.delete() }.getOrNull()
    }

    // -----------------------------------------------------------------------------------------
    // Batch download over the whole DAP library
    // -----------------------------------------------------------------------------------------

    fun startBatch(context: Context, onlyMissing: Boolean = true) {
        if (batchJob?.isActive == true) return
        val app = context.applicationContext
        _batch.value = SlBatchProgress(running = true, message = "正在读取设备曲库…")
        batchJob = scope.launch {
            try {
                val songs = ArrayList<SlSong>()
                val ok = SyncLinkManager.loadAll(cap = 20_000, loader = { SyncLinkManager.allSongs(it) }) { items, total ->
                    _batch.update { it.copy(message = "正在读取设备曲库… ${items.size}/$total") }
                    songs.clear(); songs.addAll(items)
                }
                if (!ok || songs.isEmpty()) {
                    _batch.value = SlBatchProgress(message = if (ok) "设备曲库为空" else "读取曲库失败，请确认已连接")
                    return@launch
                }
                val queries = songs.map(::queryFor).filter { it.isUsable }.distinctBy { it.cacheKey }
                _batch.value = SlBatchProgress(running = true, total = queries.size, message = "正在匹配封面与歌词")
                val gate = Semaphore(3)
                coroutineScope {
                    queries.forEach { q ->
                        launch {
                            gate.withPermit {
                                if (!isActive) return@withPermit
                                val r = if (onlyMissing) cached(app, q)?.takeIf { it.lyrics != null && it.coverFile != null } ?: resolve(app, q)
                                else resolve(app, q, force = true)
                                _batch.update {
                                    it.copy(
                                        done = it.done + 1,
                                        lyricsFound = it.lyricsFound + if (r.lyrics != null) 1 else 0,
                                        coversFound = it.coversFound + if (r.coverFile != null) 1 else 0,
                                    )
                                }
                            }
                        }
                    }
                }
                _batch.update { it.copy(running = false, message = "完成：歌词 ${it.lyricsFound} / 封面 ${it.coversFound}，共 ${it.total} 首") }
            } catch (e: CancellationException) {
                _batch.update { it.copy(running = false, message = "已取消（已处理 ${it.done}/${it.total}）") }
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "batch failed", t)
                _batch.update { it.copy(running = false, message = "批量下载出错：${t.message}") }
            }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
    }
}
