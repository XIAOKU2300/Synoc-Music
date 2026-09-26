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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lladlam.melox.core.synclink.SlAlbum
import com.lladlam.melox.core.synclink.SlArtist
import com.lladlam.melox.core.synclink.SlGenre
import com.lladlam.melox.core.synclink.SlPage
import com.lladlam.melox.core.synclink.SlPlaylist
import com.lladlam.melox.ui.glass.MeloXIosTopBar
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------------------------

internal enum class SlSongSource(val title: String) { All("全部歌曲"), Hr("Hi-Res 音乐"), Favorite("我的收藏"), Recent("最近播放") }

internal sealed interface SlRoute {
    data object Home : SlRoute
    data class Songs(val source: SlSongSource) : SlRoute
    data class AlbumSongs(val album: SlAlbum) : SlRoute
    data class ArtistSongs(val artist: SlArtist) : SlRoute
    data class ArtistDetail(val artist: SlArtist) : SlRoute
    data class GenreAlbums(val genre: SlGenre) : SlRoute
    data class GenreAlbumSongs(val genre: SlGenre, val album: SlAlbum) : SlRoute
    data class PlaylistSongs(val playlist: SlPlaylist) : SlRoute
    data object Albums : SlRoute
    data object Artists : SlRoute
    data object Genres : SlRoute
    data object Playlists : SlRoute
    data class Folder(val path: String, val title: String, val type: Int = 4) : SlRoute
    data object Search : SlRoute
    data object Queue : SlRoute
    data object Lyrics : SlRoute
    data object Device : SlRoute
    data object Settings : SlRoute
    data object MetaDownload : SlRoute
}

internal interface SlNavigator {
    fun push(route: SlRoute)
    fun back()
    fun popToHome()
}

@Composable
internal fun SyncLinkPages(route: SlRoute, nav: SlNavigator) {
    when (route) {
        SlRoute.Home -> SyncLinkHomePage(nav)
        is SlRoute.Songs -> SongSourcePage(route.source, nav)
        is SlRoute.AlbumSongs -> AlbumSongsPage(route.album, nav)
        is SlRoute.ArtistSongs -> ArtistSongsPage(route.artist, nav)
        is SlRoute.ArtistDetail -> ArtistDetailPage(route.artist, nav)
        is SlRoute.GenreAlbums -> GenreAlbumsPage(route.genre, nav)
        is SlRoute.GenreAlbumSongs -> GenreAlbumSongsPage(route.genre, route.album, nav)
        is SlRoute.PlaylistSongs -> PlaylistSongsPage(route.playlist, nav)
        SlRoute.Albums -> AlbumsPage(nav)
        SlRoute.Artists -> ArtistsPage(nav)
        SlRoute.Genres -> GenresPage(nav)
        SlRoute.Playlists -> PlaylistsPage(nav)
        is SlRoute.Folder -> FolderPage(route.path, route.title, route.type, nav)
        SlRoute.Search -> SearchPage(nav)
        SlRoute.Queue -> QueuePage(nav)
        SlRoute.Lyrics -> LyricsPage(nav)
        SlRoute.Device -> DevicePage(nav)
        SlRoute.Settings -> SettingsPage(nav)
        SlRoute.MetaDownload -> MetaDownloadPage(nav)
    }
}

// ---------------------------------------------------------------------------------------------
// Paging
// ---------------------------------------------------------------------------------------------

internal class SlPaged<T>(
    private val scope: CoroutineScope,
    private val loader: suspend (offset: Int) -> SlPage<T>?,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var total by mutableIntStateOf(-1)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val endReached: Boolean get() = total >= 0 && items.size >= total

    fun loadMore() {
        if (loading || endReached || error != null) return
        loading = true
        scope.launch {
            val page = runCatching { loader(items.size) }.getOrNull()
            if (page == null) {
                error = "加载失败，请检查与设备的连接"
            } else {
                items = items + page.items
                // Guard against firmware that reports a larger total than it can page through.
                total = if (page.items.isEmpty()) items.size else maxOf(page.total, items.size)
            }
            loading = false
        }
    }

    fun retry() {
        error = null
        loadMore()
    }
}

@Composable
internal fun <T> rememberSlPaged(vararg keys: Any?, loader: suspend (offset: Int) -> SlPage<T>?): SlPaged<T> {
    val scope = rememberCoroutineScope()
    return remember(*keys) { SlPaged(scope, loader) }
}

internal fun <T> LazyListScope.slPagedItems(
    paged: SlPaged<T>,
    emptyText: String = "暂无内容",
    row: @Composable (index: Int, item: T, shape: Shape, separator: Boolean) -> Unit,
) {
    val items = paged.items
    itemsIndexed(items) { index, item ->
        row(index, item, slRowShape(index, items.size, paged.endReached), index > 0)
    }
    item { SlPagedFooter(paged, emptyText) }
}

@Composable
private fun <T> SlPagedFooter(paged: SlPaged<T>, emptyText: String) {
    val error = paged.error
    when {
        error != null -> SlHint(error + "（点此重试）", Modifier.clickable { paged.retry() })
        !paged.endReached -> {
            LaunchedEffect(paged, paged.items.size) { paged.loadMore() }
            SlHint("正在加载…")
        }
        paged.items.isEmpty() -> SlHint(emptyText)
        else -> SlHint("共 ${paged.total} 项")
    }
}

internal fun slRowShape(index: Int, size: Int, complete: Boolean = true): Shape {
    val radius = 22.dp
    val first = index == 0
    val last = complete && index == size - 1
    return when {
        first && last -> RoundedCornerShape(radius)
        first -> RoundedCornerShape(topStart = radius, topEnd = radius)
        last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        else -> RectangleShape
    }
}

// ---------------------------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------------------------

@Composable
internal fun SlTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    MeloXIosTopBar(
        title = title,
        subtitle = subtitle,
        contentPadding = PaddingValues(horizontal = 0.dp),
        navigation = {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                MeloXSymbolIcon(MeloXSymbol.ChevronLeft, Modifier.size(28.dp), MaterialTheme.colorScheme.onBackground, iconSize = 24.sp)
            }
        },
        actions = { actions() },
    )
}

@Composable
internal fun SlListPage(
    title: String,
    nav: SlNavigator,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp),
    ) {
        item {
            SlTopBar(title, nav::back, subtitle, actions)
            Spacer(Modifier.height(18.dp))
        }
        content()
    }
}

@Composable
internal fun SlSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .48f),
    )
}

@Composable
internal fun SlHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        style = MeloXTypography.subheadline,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .45f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** Single-line list row used by every library list; long titles ellipsize instead of clipping. */
@Composable
internal fun SlItemRow(
    title: String,
    shape: Shape,
    separator: Boolean,
    subtitle: String? = null,
    detail: String? = null,
    symbol: MeloXSymbol? = null,
    leadingText: String? = null,
    highlighted: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val separatorColor = onSurface.copy(alpha = 0.10f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.045f)),
    ) {
        if (separator) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(separatorColor),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (subtitle.isNullOrBlank()) 54.dp else 64.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = if (highlighted) MeloXSystemColors.Red else onSurface.copy(alpha = .70f)
            if (symbol != null) {
                MeloXSymbolIcon(symbol, Modifier.size(24.dp), accent, iconSize = 21.sp)
                Spacer(Modifier.width(12.dp))
            } else if (leadingText != null) {
                Text(
                    leadingText,
                    modifier = Modifier.width(34.dp),
                    style = MeloXTypography.caption,
                    color = if (highlighted) MeloXSystemColors.Red else onSurface.copy(alpha = .40f),
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MeloXTypography.body,
                    color = if (highlighted) MeloXSystemColors.Red else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MeloXTypography.subheadline,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(detail, style = MeloXTypography.subheadline, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f), maxLines = 1)
            }
            if (chevron) {
                Spacer(Modifier.width(8.dp))
                MeloXSymbolIcon(MeloXSymbol.ChevronRight, Modifier.size(20.dp), MeloXSystemColors.Red.copy(alpha = .88f), iconSize = 19.sp)
            }
        }
    }
}

internal fun slFormatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun slSubtitle(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

internal val SlDimmed: @Composable () -> Color = { MaterialTheme.colorScheme.onBackground.copy(alpha = .55f) }
