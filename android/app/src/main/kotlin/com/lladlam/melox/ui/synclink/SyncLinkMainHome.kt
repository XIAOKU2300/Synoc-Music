package com.lladlam.melox.ui.synclink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.core.synclink.SlCmd
import com.lladlam.melox.core.synclink.SlControl
import com.lladlam.melox.core.synclink.SlPlayType
import com.lladlam.melox.core.synclink.SlPlaylist
import com.lladlam.melox.core.synclink.SlSong
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.core.synclink.slDeviceModelName
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSymbolVariant
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography
import kotlin.random.Random

/** A random pick from the DAP's "all songs" list; [position] is its index there (PlayUrl position). */
private data class SlRandomSong(val position: Int, val song: SlSong)

/** Survives tab switches so the home tab doesn't re-query the DAP (slow over Bluetooth) every time. */
private object SlHomeCache {
    var key: String? = null
    var playlists: List<SlPlaylist>? = null
    var random: List<SlRandomSong>? = null
}

private suspend fun loadRandomSongs(count: Int = 12): List<SlRandomSong>? {
    val window = 20
    val head = SyncLinkManager.songs(SlCmd.GET_ALL_SONG_REQ, SlCmd.GET_ALL_SONG_RESP, 0, window) ?: return null
    val total = head.total
    if (total <= window) return head.items.mapIndexed { i, s -> SlRandomSong(i, s) }.shuffled().take(count)
    // A few random windows spread over the library, then a random pick out of them.
    val picks = ArrayList<SlRandomSong>()
    repeat(3) {
        val offset = Random.nextInt(0, total - window + 1)
        SyncLinkManager.songs(SlCmd.GET_ALL_SONG_REQ, SlCmd.GET_ALL_SONG_RESP, offset, window)
            ?.items?.forEachIndexed { i, s -> picks += SlRandomSong(offset + i, s) }
    }
    if (picks.isEmpty()) return null
    return picks.distinctBy { it.position }.shuffled().take(count)
}

/**
 * Main-tab home while a SyncLink device is connected: the DAP's own playlists and a random
 * selection from its library instead of online recommendations.
 */
@Composable
fun SyncLinkDeviceHome() {
    val context = LocalContext.current
    val state by SyncLinkManager.state.collectAsState()
    val cacheKey = "${state.device?.host}|${state.libraryRevision}"
    var playlists by remember(cacheKey) { mutableStateOf(SlHomeCache.playlists.takeIf { SlHomeCache.key == cacheKey }) }
    var random by remember(cacheKey) { mutableStateOf(SlHomeCache.random.takeIf { SlHomeCache.key == cacheKey }) }
    var shuffleRound by remember { mutableIntStateOf(0) }
    var loadingRandom by remember { mutableStateOf(false) }

    LaunchedEffect(cacheKey, state.isConnected) {
        if (state.isConnected && playlists == null) {
            SyncLinkManager.playlists(0)?.items?.let {
                playlists = it
                SlHomeCache.key = cacheKey
                SlHomeCache.playlists = it
            }
        }
    }
    LaunchedEffect(cacheKey, state.isConnected, shuffleRound) {
        if (state.isConnected && (random == null || shuffleRound > 0)) {
            loadingRandom = true
            loadRandomSongs()?.let {
                random = it
                SlHomeCache.key = cacheKey
                SlHomeCache.random = it
            }
            loadingRandom = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 146.dp),
    ) {
        item {
            MeloXIosTopBar(
                title = state.device?.name ?: "我的设备",
                subtitle = slSubtitle("SyncLink 已连接", slDeviceModelName(state.deviceType), state.battery?.let { "电量 ${it.level}%" }),
                contentPadding = PaddingValues(horizontal = 0.dp),
                actions = {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clickable(role = Role.Button, onClickLabel = "打开 SyncLink") { SyncLinkActivity.launch(context) },
                        contentAlignment = Alignment.Center,
                    ) {
                        MeloXSymbolIcon(MeloXSymbol.Devices, Modifier.size(26.dp), MeloXSystemColors.Red, iconSize = 22.sp)
                    }
                },
            )
            Spacer(Modifier.height(18.dp))
        }
        item {
            SlHomeNowPlaying(onOpen = { SyncLinkActivity.launch(context) })
            Spacer(Modifier.height(24.dp))
        }
        item {
            SlSectionLabel("设备歌单")
            val lists = playlists
            when {
                lists == null -> SlHint("正在读取歌单…")
                lists.isEmpty() -> SlHint("设备上还没有歌单")
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(lists) { playlist ->
                        SlPlaylistTile(playlist) { SyncLinkActivity.launch(context, SlRoute.PlaylistSongs(playlist)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SlSectionLabel("随机歌曲") }
                Text(
                    if (loadingRandom) "加载中…" else "换一批",
                    modifier = Modifier
                        .padding(end = 8.dp, bottom = 8.dp)
                        .clickable(enabled = !loadingRandom, role = Role.Button) { shuffleRound++ },
                    style = MeloXTypography.subheadline,
                    color = MeloXSystemColors.Red,
                )
            }
        }
        val songs = random
        if (songs == null) {
            item { SlHint(if (loadingRandom) "正在从设备抽取歌曲…" else "暂时无法读取曲库") }
        } else if (songs.isEmpty()) {
            item { SlHint("设备曲库为空，可在 SyncLink 中“更新曲库”") }
        } else {
            itemsIndexed(songs, key = { _, it -> it.position }) { index, pick ->
                SlItemRow(
                    title = pick.song.displayTitle,
                    subtitle = slSubtitle(pick.song.artist, pick.song.album),
                    detail = pick.song.durationSec.takeIf { it > 0 }?.let { slFormatTime(it * 1000L) },
                    symbol = MeloXSymbol.MusicNote,
                    highlighted = pick.song.isCurrent(state.info),
                    shape = slRowShape(index, songs.size),
                    separator = index > 0,
                    onClick = { SyncLinkManager.playSong(SlPlayType.ALLMUSIC, pick.song, pick.position) },
                )
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            SlSectionLabel("浏览设备")
            val entries = listOf(
                Triple("全部歌曲", MeloXSymbol.MusicNote, SlRoute.Songs(SlSongSource.All)),
                Triple("我的收藏", MeloXSymbol.Heart, SlRoute.Songs(SlSongSource.Favorite)),
                Triple("专辑", MeloXSymbol.Library, SlRoute.Albums),
                Triple("文件夹", MeloXSymbol.Storage, SlRoute.Folder("", "文件夹")),
            )
            Column {
                entries.forEachIndexed { index, (title, symbol, route) ->
                    SlItemRow(
                        title = title,
                        symbol = symbol,
                        chevron = true,
                        shape = slRowShape(index, entries.size),
                        separator = index > 0,
                        onClick = { SyncLinkActivity.launch(context, route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlHomeNowPlaying(onOpen: () -> Unit) {
    val state by SyncLinkManager.state.collectAsState()
    val info = state.info
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MeloXShapes.card)
            .background(onBackground.copy(alpha = 0.045f))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlCover(state.coverPath, Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (state.hasTrack) info.displayTitle else "设备未在播放",
                style = MeloXTypography.body,
                color = onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (state.hasTrack) slSubtitle(info.artist, info.album).ifBlank { "未知艺术家" } else "点下方歌曲，在设备上播放",
                style = MeloXTypography.subheadline,
                color = onBackground.copy(alpha = .55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.qualityLabel.takeIf { it.isNotBlank() && state.hasTrack }?.let {
                Text(it, style = MeloXTypography.caption, color = MeloXSystemColors.Red.copy(alpha = .9f), maxLines = 1)
            }
        }
        val playing = state.playStatus == SlControl.PLAY
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MeloXSystemColors.Red)
                .clickable(enabled = state.hasTrack, role = Role.Button, onClickLabel = if (playing) "暂停" else "播放") {
                    SyncLinkManager.togglePlay()
                },
            contentAlignment = Alignment.Center,
        ) {
            MeloXSymbolIcon(
                if (playing) MeloXSymbol.Pause else MeloXSymbol.Play,
                Modifier.size(22.dp),
                Color.White,
                variant = MeloXSymbolVariant.Fill,
                iconSize = 20.sp,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = state.hasTrack, role = Role.Button, onClickLabel = "下一首") { SyncLinkManager.next() },
            contentAlignment = Alignment.Center,
        ) {
            MeloXSymbolIcon(MeloXSymbol.Next, Modifier.size(22.dp), onBackground.copy(alpha = .8f), iconSize = 20.sp)
        }
    }
}

@Composable
private fun SlPlaylistTile(playlist: SlPlaylist, onClick: () -> Unit) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier
            .width(128.dp)
            .clip(MeloXShapes.card)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(128.dp)
                .clip(MeloXShapes.card)
                .background(MeloXSystemColors.Red.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            MeloXSymbolIcon(MeloXSymbol.List, Modifier.size(40.dp), MeloXSystemColors.Red, iconSize = 36.sp)
        }
        Text(
            playlist.name.ifBlank { "未命名歌单" },
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MeloXTypography.subheadline,
            color = onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
