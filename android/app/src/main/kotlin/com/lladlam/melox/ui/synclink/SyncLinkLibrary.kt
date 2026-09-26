package com.lladlam.melox.ui.synclink

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.core.synclink.SlAlbum
import com.lladlam.melox.core.synclink.SlArtist
import com.lladlam.melox.core.synclink.SlGenre
import com.lladlam.melox.core.synclink.SlPathItem
import com.lladlam.melox.core.synclink.SlPlayInfo
import com.lladlam.melox.core.synclink.SlPlayType
import com.lladlam.melox.core.synclink.SlPlaylist
import com.lladlam.melox.core.synclink.SlSong
import com.lladlam.melox.core.synclink.SlStorage
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography

internal fun SlSong.isCurrent(info: SlPlayInfo): Boolean =
    filepath.isNotBlank() && filepath == info.filepath && time == info.time

@Composable
private fun libraryRevision(): Int = SyncLinkManager.state.collectAsState().value.libraryRevision

@Composable
private fun SongListPage(
    title: String,
    nav: SlNavigator,
    key: Any,
    loader: suspend (Int) -> com.lladlam.melox.core.synclink.SlPage<SlSong>?,
    onPlay: (songs: List<SlSong>, index: Int) -> Unit,
) {
    val state by SyncLinkManager.state.collectAsState()
    val paged = rememberSlPaged(key, state.libraryRevision, loader = loader)
    SlListPage(title = title, nav = nav, subtitle = paged.total.takeIf { it >= 0 }?.let { "$it 首" }) {
        slPagedItems(paged, emptyText = "没有歌曲") { index, song, shape, separator ->
            SlItemRow(
                title = song.displayTitle,
                subtitle = slSubtitle(song.artist, song.album),
                detail = song.durationSec.takeIf { it > 0 }?.let { slFormatTime(it * 1000L) },
                leadingText = "${index + 1}",
                highlighted = song.isCurrent(state.info),
                shape = shape,
                separator = separator,
                onClick = { onPlay(paged.items, index) },
            )
        }
    }
}

@Composable
internal fun SongSourcePage(source: SlSongSource, nav: SlNavigator) {
    val playType = when (source) {
        SlSongSource.All -> SlPlayType.ALLMUSIC
        SlSongSource.Hr -> SlPlayType.HR
        SlSongSource.Favorite -> SlPlayType.FAV
        SlSongSource.Recent -> SlPlayType.RECENTPLAY
    }
    SongListPage(
        title = source.title,
        nav = nav,
        key = source,
        loader = { offset ->
            when (source) {
                SlSongSource.All -> SyncLinkManager.allSongs(offset)
                SlSongSource.Hr -> SyncLinkManager.hrSongs(offset)
                SlSongSource.Favorite -> SyncLinkManager.favoriteSongs(offset)
                SlSongSource.Recent -> SyncLinkManager.recentSongs(offset)
            }
        },
        onPlay = { songs, index -> SyncLinkManager.playSongList(playType, songs, index) },
    )
}

@Composable
internal fun AlbumSongsPage(album: SlAlbum, nav: SlNavigator) = SongListPage(
    title = album.album.ifBlank { "未知专辑" },
    nav = nav,
    key = album,
    loader = { SyncLinkManager.albumSongs(album.id, it) },
    onPlay = { _, index -> SyncLinkManager.playAlbum(album, index) },
)

@Composable
internal fun ArtistSongsPage(artist: SlArtist, nav: SlNavigator) = SongListPage(
    title = artist.artist.ifBlank { "未知艺术家" },
    nav = nav,
    key = artist,
    loader = { SyncLinkManager.artistSongs(artist.id, it) },
    onPlay = { _, index -> SyncLinkManager.playArtist(artist, index) },
)

@Composable
internal fun GenreAlbumSongsPage(genre: SlGenre, album: SlAlbum, nav: SlNavigator) = SongListPage(
    title = album.album.ifBlank { "未知专辑" },
    nav = nav,
    key = genre to album,
    loader = { SyncLinkManager.genreAlbumSongs(album.id, it) },
    onPlay = { _, index -> SyncLinkManager.playGenreAlbum(genre, album, index) },
)

@Composable
internal fun PlaylistSongsPage(playlist: SlPlaylist, nav: SlNavigator) = SongListPage(
    title = playlist.name.ifBlank { "歌单" },
    nav = nav,
    key = playlist,
    loader = { SyncLinkManager.playlistSongs(playlist.id, it) },
    onPlay = { _, index -> SyncLinkManager.playPlaylist(playlist, index) },
)

@Composable
private fun AlbumListPage(
    title: String,
    nav: SlNavigator,
    key: Any,
    loader: suspend (Int) -> com.lladlam.melox.core.synclink.SlPage<SlAlbum>?,
    onOpen: (SlAlbum) -> Unit,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val paged = rememberSlPaged(key, libraryRevision(), loader = loader)
    SlListPage(title = title, nav = nav, subtitle = paged.total.takeIf { it >= 0 }?.let { "$it 张专辑" }) {
        header?.invoke(this)
        slPagedItems(paged, emptyText = "没有专辑") { _, album, shape, separator ->
            SlItemRow(
                title = album.album.ifBlank { "未知专辑" },
                subtitle = album.artist,
                symbol = MeloXSymbol.Library,
                chevron = true,
                shape = shape,
                separator = separator,
                onClick = { onOpen(album) },
            )
        }
    }
}

@Composable
internal fun AlbumsPage(nav: SlNavigator) = AlbumListPage(
    title = "专辑",
    nav = nav,
    key = "albums",
    loader = { SyncLinkManager.albums(it) },
    onOpen = { nav.push(SlRoute.AlbumSongs(it)) },
)

@Composable
internal fun ArtistDetailPage(artist: SlArtist, nav: SlNavigator) = AlbumListPage(
    title = artist.artist.ifBlank { "未知艺术家" },
    nav = nav,
    key = artist,
    loader = { SyncLinkManager.artistAlbums(artist.id, it) },
    onOpen = { nav.push(SlRoute.AlbumSongs(it)) },
    header = {
        item {
            SlItemRow(
                title = "全部歌曲",
                detail = artist.count.takeIf { it > 0 }?.let { "$it 首" },
                symbol = MeloXSymbol.MusicNote,
                chevron = true,
                shape = slRowShape(0, 1),
                separator = false,
                onClick = { nav.push(SlRoute.ArtistSongs(artist)) },
            )
            Spacer(Modifier.height(22.dp))
            SlSectionLabel("专辑")
        }
    },
)

@Composable
internal fun GenreAlbumsPage(genre: SlGenre, nav: SlNavigator) = AlbumListPage(
    title = genre.genre.ifBlank { "未知流派" },
    nav = nav,
    key = genre,
    loader = { SyncLinkManager.genreAlbums(genre.id, it) },
    onOpen = { nav.push(SlRoute.GenreAlbumSongs(genre, it)) },
)

@Composable
internal fun ArtistsPage(nav: SlNavigator) {
    val paged = rememberSlPaged("artists", libraryRevision()) { SyncLinkManager.artists(it) }
    SlListPage(title = "艺术家", nav = nav, subtitle = paged.total.takeIf { it >= 0 }?.let { "$it 位艺术家" }) {
        slPagedItems(paged, emptyText = "没有艺术家") { _, artist, shape, separator ->
            SlItemRow(
                title = artist.artist.ifBlank { "未知艺术家" },
                detail = artist.count.takeIf { it > 0 }?.let { "$it 首" },
                symbol = MeloXSymbol.Person,
                chevron = true,
                shape = shape,
                separator = separator,
                onClick = { nav.push(SlRoute.ArtistDetail(artist)) },
            )
        }
    }
}

@Composable
internal fun GenresPage(nav: SlNavigator) {
    val paged = rememberSlPaged("genres", libraryRevision()) { SyncLinkManager.genres(it) }
    SlListPage(title = "流派", nav = nav, subtitle = paged.total.takeIf { it >= 0 }?.let { "$it 个流派" }) {
        slPagedItems(paged, emptyText = "没有流派") { _, genre, shape, separator ->
            SlItemRow(
                title = genre.genre.ifBlank { "未知流派" },
                detail = genre.albumTotal.takeIf { it > 0 }?.let { "$it 张专辑" },
                symbol = MeloXSymbol.RadioWaves,
                chevron = true,
                shape = shape,
                separator = separator,
                onClick = { nav.push(SlRoute.GenreAlbums(genre)) },
            )
        }
    }
}

@Composable
internal fun PlaylistsPage(nav: SlNavigator) {
    val paged = rememberSlPaged("playlists", libraryRevision()) { SyncLinkManager.playlists(it) }
    SlListPage(title = "歌单", nav = nav, subtitle = paged.total.takeIf { it >= 0 }?.let { "$it 个歌单" }) {
        slPagedItems(paged, emptyText = "设备上没有歌单") { _, playlist, shape, separator ->
            SlItemRow(
                title = playlist.name.ifBlank { "未命名歌单" },
                symbol = MeloXSymbol.List,
                chevron = true,
                shape = shape,
                separator = separator,
                onClick = { nav.push(SlRoute.PlaylistSongs(playlist)) },
            )
        }
    }
}

@Composable
internal fun FolderPage(path: String, title: String, type: Int, nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    if (path.isEmpty()) {
        LaunchedEffect(state.isConnected) { if (state.isConnected) SyncLinkManager.refreshStorages() }
    }
    if (path.isEmpty() && state.storages.isNotEmpty()) {
        // Roots are the storage volumes reported by 1286 (new MemoryListResp or legacy MemoryInfoResp).
        // Until they arrive we fall through and let the firmware list its own root for "".
        val storages = state.storages
        SlListPage(title = title, nav = nav, subtitle = "选择存储") {
            itemsIndexed(storages) { index, storage ->
                SlStorageRow(
                    storage = storage,
                    shape = slRowShape(index, storages.size),
                    separator = index > 0,
                    onClick = { nav.push(SlRoute.Folder(storage.path, storage.name.ifBlank { storage.path.substringAfterLast('/') }, 4)) },
                )
            }
        }
        return
    }
    val paged = rememberSlPaged(path, type, state.libraryRevision) { SyncLinkManager.browse(path, it, type) }
    SlListPage(title = title, nav = nav, subtitle = path.ifBlank { null }) {
        slPagedItems(paged, emptyText = "文件夹为空") { index, item: SlPathItem, shape, separator ->
            val current = item.isFile && item.path == state.info.filepath && item.time == state.info.time
            SlItemRow(
                title = item.name.ifBlank { item.path.substringAfterLast('/') },
                symbol = if (item.isFile) MeloXSymbol.MusicNote else MeloXSymbol.Library,
                detail = if (item.isFile && item.timeEnd > item.time && item.time >= 0) slFormatTime((item.timeEnd - item.time) * 1000L) else null,
                chevron = !item.isFile,
                highlighted = current,
                shape = shape,
                separator = separator,
                onClick = {
                    if (item.isFile) {
                        SyncLinkManager.playFolderItem(paged.items, index)
                    } else {
                        // The official client browses a child with the child's own type (4 folder, 1 root).
                        nav.push(SlRoute.Folder(item.path, item.name.ifBlank { item.path.substringAfterLast('/') }, item.type.takeIf { it > 0 } ?: 4))
                    }
                },
            )
        }
    }
}

internal fun slStorageSummary(storage: SlStorage): String? {
    if (storage.total <= 0f) return null
    val used = storage.usage.coerceIn(0f, storage.total)
    return "已用 %.1f / 共 %.1f GB · 剩余 %.1f GB".format(used, storage.total, storage.total - used)
}

@Composable
private fun SlStorageRow(storage: SlStorage, shape: Shape, separator: Boolean, onClick: () -> Unit) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(onBackground.copy(alpha = 0.045f)),
    ) {
        if (separator) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(onBackground.copy(alpha = 0.10f)),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeloXSymbolIcon(MeloXSymbol.Storage, Modifier.size(24.dp), onBackground.copy(alpha = .70f), iconSize = 21.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    storage.name.ifBlank { storage.path },
                    style = MeloXTypography.body,
                    color = onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    slStorageSummary(storage) ?: storage.path,
                    style = MeloXTypography.subheadline,
                    color = onBackground.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (storage.total > 0f) {
                    val fraction = (storage.usage / storage.total).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(onBackground.copy(alpha = 0.10f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(if (fraction > 0.9f) MeloXSystemColors.Red else onBackground.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MeloXSymbolIcon(MeloXSymbol.ChevronRight, Modifier.size(20.dp), MeloXSystemColors.Red.copy(alpha = .88f), iconSize = 19.sp)
        }
    }
}
