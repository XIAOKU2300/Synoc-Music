package com.lladlam.melox.ui.synclink

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lladlam.melox.MainActivity
import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricSource
import com.lladlam.melox.core.synclink.SlAlbum
import com.lladlam.melox.core.synclink.SlArtist
import com.lladlam.melox.core.synclink.SlGenre
import com.lladlam.melox.core.synclink.SlPage
import com.lladlam.melox.core.synclink.SlSetting
import com.lladlam.melox.core.synclink.SlSong
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.core.synclink.SyncLinkMeta
import com.lladlam.melox.core.synclink.slDeviceModelName
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassSegmentedControl
import com.lladlam.melox.ui.glass.MeloXGlassSlider
import com.lladlam.melox.ui.glass.MeloXGlassTextField
import com.lladlam.melox.ui.glass.MeloXIosGroupedList
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

private val SearchTypes = listOf("歌曲", "艺术家", "专辑", "流派")

@Composable
internal fun SearchPage(nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val submit = {
        keyword = text.trim()
        focus.clearFocus()
    }
    val paged = rememberSlPaged(keyword, type, state.libraryRevision) { offset ->
        if (keyword.isBlank()) {
            SlPage(0, emptyList<Any>())
        } else {
            SyncLinkManager.search(keyword, type, offset)?.let { r ->
                val items: List<Any> = when (type) {
                    0 -> r.songs
                    1 -> r.artists
                    2 -> r.albums
                    else -> r.genres
                }
                SlPage(r.total, items)
            }
        }
    }
    SlListPage(title = "搜索", nav = nav) {
        item {
            MeloXGlassTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索设备曲库") },
                leadingContent = {
                    MeloXSymbolIcon(MeloXSymbol.Search, Modifier.size(20.dp), MaterialTheme.colorScheme.onBackground.copy(alpha = .5f))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            Spacer(Modifier.height(12.dp))
            MeloXGlassSegmentedControl(
                items = SearchTypes,
                selectedIndex = type,
                onSelected = { type = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
        }
        if (keyword.isBlank()) {
            item { SlHint("输入关键词后点击键盘上的搜索") }
        } else {
            slPagedItems(paged, emptyText = "没有找到“$keyword”") { index, item, shape, separator ->
                when (item) {
                    is SlSong -> SlItemRow(
                        title = item.displayTitle,
                        subtitle = slSubtitle(item.artist, item.album),
                        symbol = MeloXSymbol.MusicNote,
                        highlighted = item.isCurrent(state.info),
                        shape = shape,
                        separator = separator,
                        onClick = {
                            SyncLinkManager.playSearchResult(keyword, paged.items.filterIsInstance<SlSong>(), index)
                        },
                    )
                    is SlArtist -> SlItemRow(
                        title = item.artist.ifBlank { "未知艺术家" },
                        detail = item.count.takeIf { it > 0 }?.let { "$it 首" },
                        symbol = MeloXSymbol.Person,
                        chevron = true,
                        shape = shape,
                        separator = separator,
                        onClick = { nav.push(SlRoute.ArtistDetail(item)) },
                    )
                    is SlAlbum -> SlItemRow(
                        title = item.album.ifBlank { "未知专辑" },
                        subtitle = item.artist,
                        symbol = MeloXSymbol.Library,
                        chevron = true,
                        shape = shape,
                        separator = separator,
                        onClick = { nav.push(SlRoute.AlbumSongs(item)) },
                    )
                    is SlGenre -> SlItemRow(
                        title = item.genre.ifBlank { "未知流派" },
                        detail = item.albumTotal.takeIf { it > 0 }?.let { "$it 张专辑" },
                        symbol = MeloXSymbol.RadioWaves,
                        chevron = true,
                        shape = shape,
                        separator = separator,
                        onClick = { nav.push(SlRoute.GenreAlbums(item)) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Queue
// ---------------------------------------------------------------------------------------------

@Composable
internal fun QueuePage(nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    val queue by SyncLinkManager.queue.collectAsState()
    val listState = rememberLazyListState()
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(queue.size, state.queueIndex) {
        if (!scrolled && state.queueIndex in queue.indices) {
            listState.scrollToItem((state.queueIndex - 3).coerceAtLeast(0) + 1)
            scrolled = true
        }
    }
    SlListPage(
        title = "播放队列",
        nav = nav,
        subtitle = if (state.queueTotal > 0) "共 ${state.queueTotal} 首" else null,
        state = listState,
    ) {
        itemsIndexed(queue) { index, song ->
            val current = index == state.queueIndex
            SlItemRow(
                title = song.displayTitle,
                subtitle = slSubtitle(song.artist, song.album),
                leadingText = "${index + 1}",
                highlighted = current,
                shape = slRowShape(index, queue.size),
                separator = index > 0,
                onClick = { if (!current) SyncLinkManager.playQueueIndex(index) },
            )
        }
        item {
            when {
                queue.isEmpty() && state.queueTotal > 0 -> SlHint("正在加载队列…")
                queue.isEmpty() -> SlHint("队列为空")
                queue.size < state.queueTotal -> SlHint("已显示前 ${queue.size} 首")
                else -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lyrics
// ---------------------------------------------------------------------------------------------

/**
 * Lyrics matched online from SyncLink metadata (word timing + translation when available), or the
 * DAP's own LRC as a line-timed fallback. Word lines are highlighted syllable by syllable.
 */
@Composable
internal fun LyricsPage(nav: SlNavigator) {
    val context = LocalContext.current
    val state by SyncLinkManager.state.collectAsState()
    val document = remember(state.metaLyrics, state.metaKey, state.lyrics, state.lyricsKey, state.info.filepath, state.info.title) {
        SyncLinkManager.currentLyrics(state)
    }
    val lines = document?.lines.orEmpty()
    val synced = lines.size > 1 && lines.any { it.timeMs > 0 }
    val wordTimed = lines.any { it.syllables.isNotEmpty() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isPlaying, synced, wordTimed) {
        while (synced && state.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(if (wordTimed) 50 else 200)
        }
        now = SystemClock.elapsedRealtime()
    }
    val position = state.positionMs(now)
    val current = if (synced) document?.highlightedIndex(position) ?: -1 else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 3).coerceAtLeast(0) + 1)
    }
    val sourceLabel = when {
        document == null -> null
        state.metaLyrics != null && state.metaLyrics === document -> when (document.source) {
            LyricSource.AmlL -> "AMLL 逐字"
            LyricSource.QQMusic -> "QQ 音乐"
            LyricSource.Netease -> "网易云音乐"
            else -> document.source.name
        } + if (lines.any { !it.translation.isNullOrBlank() }) " · 含翻译" else ""
        else -> "设备歌词"
    }
    SlListPage(
        title = "歌词",
        nav = nav,
        subtitle = slSubtitle(state.info.displayTitle.ifBlank { null }, state.info.artist, sourceLabel),
        state = listState,
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MeloXGlassButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                action = MainActivity.ACTION_OPEN_NOW_PLAYING
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    style = MeloXGlassButtonStyle.Plain,
                ) {
                    Text("全屏歌词", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                MeloXGlassButton(
                    onClick = { SyncLinkManager.rematchCurrent() },
                    modifier = Modifier.weight(1f),
                    style = MeloXGlassButtonStyle.Plain,
                ) {
                    Text(if (state.metaLoading) "匹配中…" else "重新匹配", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
        if (lines.isEmpty()) {
            item {
                SlHint(if (state.metaLoading) "正在匹配歌词…" else "没有找到这首歌的歌词")
                if (!state.metaLoading) {
                    MeloXGlassButton(
                        onClick = { SyncLinkManager.requestLyrics() },
                        modifier = Modifier.fillMaxWidth(),
                        style = MeloXGlassButtonStyle.Plain,
                    ) {
                        Text("读取设备内歌词", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            itemsIndexed(lines) { index, line ->
                SlLyricLine(
                    line = line,
                    active = index == current,
                    synced = synced,
                    positionMs = if (index == current) position else -1L,
                    onClick = if (synced) ({ SyncLinkManager.seekTo(line.timeMs) }) else null,
                )
            }
        }
    }
}

@Composable
private fun SlLyricLine(
    line: LyricLine,
    active: Boolean,
    synced: Boolean,
    positionMs: Long,
    onClick: (() -> Unit)?,
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val dim = if (synced) onBg.copy(alpha = .42f) else onBg.copy(alpha = .85f)
    val text = if (active && line.syllables.isNotEmpty()) {
        buildAnnotatedString {
            line.syllables.forEach { s ->
                val color = when {
                    positionMs >= s.endTimeMs -> MeloXSystemColors.Red
                    positionMs >= s.startTimeMs -> MeloXSystemColors.Red.copy(
                        alpha = .45f + .55f * ((positionMs - s.startTimeMs).toFloat() / (s.endTimeMs - s.startTimeMs).coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                    else -> onBg.copy(alpha = .45f)
                }
                withStyle(SpanStyle(color = color)) { append(s.text) }
            }
        }
    } else {
        AnnotatedString(line.text.ifBlank { "♪" })
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 8.dp),
    ) {
        Text(
            text,
            style = if (active) MeloXTypography.title2 else MeloXTypography.headline,
            color = if (active) MeloXSystemColors.Red else dim,
        )
        line.translation?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 2.dp),
                style = MeloXTypography.subheadline,
                color = if (active) onBg.copy(alpha = .75f) else onBg.copy(alpha = .35f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Covers & lyrics batch download
// ---------------------------------------------------------------------------------------------

@Composable
internal fun MetaDownloadPage(nav: SlNavigator) {
    val context = LocalContext.current
    val state by SyncLinkManager.state.collectAsState()
    val batch by SyncLinkMeta.batch.collectAsState()
    var stats by remember { mutableStateOf(0 to 0L) }
    LaunchedEffect(batch.done, batch.running) {
        stats = withContext(Dispatchers.IO) { SyncLinkMeta.cacheStats(context) }
    }
    SlListPage(title = "封面与歌词", nav = nav, subtitle = "按设备曲库信息在线匹配，保存在本机") {
        item {
            MeloXIosGroupedList {
                MeloXIosListRow(title = "已缓存", detail = "${stats.first} 首 · ${"%.1f".format(stats.second / 1024f / 1024f)} MB")
                MeloXIosListRow(title = "歌词来源", detail = "AMLL / QQ 音乐 / 网易云（逐字 + 翻译）", showTopSeparator = true)
                MeloXIosListRow(title = "封面来源", detail = "专辑一致时取网易云 / QQ 高清图", showTopSeparator = true)
            }
            Spacer(Modifier.height(18.dp))
            if (batch.running || batch.total > 0) {
                val ratio = if (batch.total > 0) batch.done.toFloat() / batch.total else 0f
                Text(
                    if (batch.total > 0) "${batch.done} / ${batch.total} · 歌词 ${batch.lyricsFound} · 封面 ${batch.coversFound}" else batch.message.orEmpty(),
                    style = MeloXTypography.subheadline,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .1f)),
                ) {
                    Box(Modifier.fillMaxWidth(ratio).height(6.dp).clip(CircleShape).background(MeloXSystemColors.Red))
                }
                Spacer(Modifier.height(14.dp))
            }
            if (!batch.running && batch.message != null && batch.total > 0) {
                SlHint(batch.message.orEmpty())
            } else if (!batch.running && batch.total == 0 && batch.message != null) {
                SlHint(batch.message.orEmpty())
            }
            if (batch.running) {
                MeloXGlassButton(onClick = { SyncLinkMeta.cancelBatch() }, modifier = Modifier.fillMaxWidth(), style = MeloXGlassButtonStyle.Plain) {
                    Text("取消", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            } else {
                MeloXGlassButton(
                    onClick = { SyncLinkMeta.startBatch(context) },
                    enabled = state.isConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("一键下载全部封面与歌词", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                SlHint("只补全缺失的条目，已下载的不会重复请求；未匹配到的 3 天后才会重试。只通过 SyncLink 读取曲目信息，不从设备传输文件。")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Device info
// ---------------------------------------------------------------------------------------------

@Composable
internal fun DevicePage(nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    LaunchedEffect(Unit) { SyncLinkManager.refreshDeviceDetails() }
    val info = state.deviceInfo
    val device = state.device
    SlListPage(title = "设备信息", nav = nav, subtitle = device?.name) {
        item {
            val rows = listOfNotNull(
                "型号" to slDeviceModelName(state.deviceType),
                device?.name?.let { "名称" to it },
                device?.let { "地址" to it.addressLabel },
                info?.version?.takeIf { it.isNotBlank() }?.let { "固件版本" to it },
                info?.ip?.takeIf { it.isNotBlank() }?.let { "设备 IP" to it },
                info?.mac?.takeIf { it.isNotBlank() }?.let { "Wi-Fi MAC" to it },
                info?.btMac?.takeIf { it.isNotBlank() }?.let { "蓝牙 MAC" to it },
                info?.serial?.takeIf { it.isNotBlank() }?.let { "序列号" to it },
                state.battery?.let { "电量" to "${it.level}%" },
                device?.uuid?.takeIf { it.isNotBlank() }?.let { "UUID" to it },
                "固件类型" to if (state.isAndroidDevice) "Android" else "Linux",
            )
            MeloXIosGroupedList {
                rows.forEachIndexed { index, (label, value) ->
                    MeloXIosListRow(title = label, detail = value, showTopSeparator = index > 0)
                }
            }
            Spacer(Modifier.height(22.dp))
            SlSectionLabel("存储")
        }
        if (state.storages.isEmpty()) {
            item { SlHint("设备未返回存储信息") }
        }
        itemsIndexed(state.storages) { index, storage ->
            val ratio = if (storage.total > 0f) (storage.usage / storage.total).coerceIn(0f, 1f) else 0f
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(slRowShape(index, state.storages.size))
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.045f))
                    .padding(16.dp),
            ) {
                Text(storage.name.ifBlank { storage.path }, style = MeloXTypography.body, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text(
                    "已用 ${formatSize(storage.usage)} / 共 ${formatSize(storage.total)}（${(ratio * 100).toInt()}%）· 剩余 ${formatSize((storage.total - storage.usage).coerceAtLeast(0f))}",
                    style = MeloXTypography.subheadline,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .1f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MeloXSystemColors.Red),
                    )
                }
            }
        }
    }
}

/** Values arrive as floats without a documented unit; the firmware uses GB for its own UI. */
private fun formatSize(v: Float): String = if (v >= 100f) "%.0f GB".format(v) else "%.2f GB".format(v)

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SettingsPage(nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    LaunchedEffect(Unit) { SyncLinkManager.refreshDeviceDetails() }
    val settings = state.settings
    val editable = listOf(SlSetting.Gain, SlSetting.Filter, SlSetting.Balance, SlSetting.Backlight).filter { it in settings }
    val readOnly = SlSetting.values().filter { it in settings && it !in editable }
    SlListPage(title = "设备设置", nav = nav, subtitle = state.device?.name) {
        if (settings.isEmpty()) {
            item { SlHint("设备尚未返回任何设置项") }
            return@SlListPage
        }
        if (editable.isNotEmpty()) {
            item {
                MeloXIosGroupedList {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        editable.forEach { setting -> SettingEditor(setting, settings.getValue(setting)) }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
        if (readOnly.isNotEmpty()) {
            item {
                SlSectionLabel("其他（只读）")
                MeloXIosGroupedList {
                    readOnly.forEachIndexed { index, setting ->
                        val value = settings.getValue(setting)
                        val swatch: @Composable () -> Unit = {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF000000.toInt() or (value and 0xFFFFFF))),
                            )
                        }
                        MeloXIosListRow(
                            title = setting.label,
                            detail = if (setting == SlSetting.ToneChoice) "#%06X".format(value and 0xFFFFFF) else value.toString(),
                            trailing = swatch.takeIf { setting == SlSetting.ToneChoice },
                            showTopSeparator = index > 0,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "这些设置项的取值含义因机型而异，为避免误操作仅显示设备返回的原始值。",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MeloXTypography.caption,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
                )
            }
        }
    }
}

@Composable
private fun SettingEditor(setting: SlSetting, value: Int) {
    when (setting) {
        SlSetting.Gain -> {
            Column {
                Text(setting.label, style = MeloXTypography.subheadline)
                Spacer(Modifier.height(8.dp))
                MeloXGlassSegmentedControl(
                    items = listOf("低", "中", "高"),
                    selectedIndex = value.coerceIn(0, 2),
                    onSelected = { if (it != value) SyncLinkManager.setSetting(setting, it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SlSetting.Filter -> {
            var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
            MeloXGlassSlider(
                title = setting.label,
                value = local,
                onValueChange = {
                    local = it
                    if (it.toInt() != value) SyncLinkManager.setSetting(setting, it.toInt())
                },
                valueRange = 0f..5f,
                steps = 4,
                valueLabel = { "滤波器 ${it.toInt() + 1}" },
            )
        }
        SlSetting.Balance -> {
            var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
            MeloXGlassSlider(
                title = setting.label,
                value = local.coerceIn(-12f, 12f),
                onValueChange = {
                    local = it
                    if (it.toInt() != value) SyncLinkManager.setSetting(setting, it.toInt())
                },
                valueRange = -12f..12f,
                steps = 23,
                valueLabel = {
                    val v = it.toInt()
                    when {
                        v < 0 -> "左 ${-v}"
                        v > 0 -> "右 $v"
                        else -> "居中"
                    }
                },
            )
        }
        SlSetting.Backlight -> {
            var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
            MeloXGlassSlider(
                title = setting.label,
                value = local.coerceIn(5f, 100f),
                onValueChange = {
                    local = it
                    if (it.toInt() != value) SyncLinkManager.setSetting(setting, it.toInt())
                },
                valueRange = 5f..100f,
                valueLabel = { "${it.toInt()}" },
            )
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(setting.label, style = MeloXTypography.subheadline, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MeloXTypography.subheadline)
        }
    }
}
