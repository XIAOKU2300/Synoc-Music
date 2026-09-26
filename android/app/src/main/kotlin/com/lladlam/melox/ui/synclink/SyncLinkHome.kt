package com.lladlam.melox.ui.synclink

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.lladlam.melox.core.synclink.SyncLinkBluetooth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lladlam.melox.core.synclink.SlConnection
import com.lladlam.melox.core.synclink.SlControl
import com.lladlam.melox.core.synclink.SlDevice
import com.lladlam.melox.core.synclink.SlMode
import com.lladlam.melox.core.synclink.SyncLinkDiscovery
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.core.synclink.SyncLinkMeta
import com.lladlam.melox.core.synclink.SyncLinkState
import com.lladlam.melox.core.synclink.slDeviceModelName
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassDialog
import com.lladlam.melox.ui.glass.MeloXGlassTextField
import com.lladlam.melox.ui.glass.MeloXGlassToggle
import com.lladlam.melox.ui.glass.MeloXIosGroupedList
import com.lladlam.melox.ui.glass.MeloXIosListIcon
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.glass.MeloXShapes
import com.lladlam.melox.ui.glass.MeloXSymbol
import com.lladlam.melox.ui.glass.MeloXSymbolIcon
import com.lladlam.melox.ui.glass.MeloXSymbolVariant
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.glass.MeloXTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import java.io.File

@Composable
internal fun SyncLinkHomePage(nav: SlNavigator) {
    val state by SyncLinkManager.state.collectAsState()
    val remote by SyncLinkManager.remoteRoute.collectAsState()
    val subtitle = when {
        state.isConnected -> slSubtitle(state.device?.name, slDeviceModelName(state.deviceType))
        state.connection == SlConnection.Connecting -> "正在连接 ${state.device?.name.orEmpty()}…"
        else -> "未连接"
    }
    SlListPage(title = "山灵 SyncLink", nav = nav, subtitle = subtitle) {
        if (!state.isConnected) {
            item { SlConnectSection(state) }
        } else {
            item {
                NowPlayingCard(state)
                Spacer(Modifier.height(22.dp))
            }
            item {
                MeloXIosGroupedList {
                    SlNavRow("歌词", MeloXSymbol.Lyrics, first = true) { nav.push(SlRoute.Lyrics) }
                    val queueDetail = if (state.queueTotal > 0) "${(state.queueIndex + 1).coerceAtLeast(1)}/${state.queueTotal}" else null
                    SlNavRow("播放队列", MeloXSymbol.Queue, detail = queueDetail) { nav.push(SlRoute.Queue) }
                    val batch = SyncLinkMeta.batch.collectAsState().value
                    SlNavRow(
                        "封面与歌词下载",
                        MeloXSymbol.Download,
                        detail = if (batch.running && batch.total > 0) "${batch.done}/${batch.total}" else null,
                    ) { nav.push(SlRoute.MetaDownload) }
                }
                Spacer(Modifier.height(22.dp))
            }
            item {
                SlSectionLabel("资料库")
                MeloXIosGroupedList {
                    SlNavRow(SlSongSource.All.title, MeloXSymbol.MusicNote, first = true) { nav.push(SlRoute.Songs(SlSongSource.All)) }
                    SlNavRow(SlSongSource.Hr.title, MeloXSymbol.Sparkles) { nav.push(SlRoute.Songs(SlSongSource.Hr)) }
                    SlNavRow(SlSongSource.Favorite.title, MeloXSymbol.Heart) { nav.push(SlRoute.Songs(SlSongSource.Favorite)) }
                    SlNavRow(SlSongSource.Recent.title, MeloXSymbol.Clock) { nav.push(SlRoute.Songs(SlSongSource.Recent)) }
                    SlNavRow("专辑", MeloXSymbol.Library) { nav.push(SlRoute.Albums) }
                    SlNavRow("艺术家", MeloXSymbol.Person) { nav.push(SlRoute.Artists) }
                    SlNavRow("流派", MeloXSymbol.RadioWaves) { nav.push(SlRoute.Genres) }
                    SlNavRow("歌单", MeloXSymbol.List) { nav.push(SlRoute.Playlists) }
                    SlNavRow("文件夹", MeloXSymbol.Storage) { nav.push(SlRoute.Folder("", "文件夹")) }
                    SlNavRow("搜索", MeloXSymbol.Search) { nav.push(SlRoute.Search) }
                }
                Spacer(Modifier.height(22.dp))
            }
            item {
                SlSectionLabel("设备")
                MeloXIosGroupedList {
                    val battery = state.battery?.let { "${it.level}%" }
                    SlNavRow("设备信息与存储", MeloXSymbol.Info, detail = battery, first = true) { nav.push(SlRoute.Device) }
                    SlNavRow("设备设置", MeloXSymbol.Settings) { nav.push(SlRoute.Settings) }
                    val scanning = state.scanningCount
                    SlNavRow(
                        "更新曲库",
                        MeloXSymbol.Refresh,
                        detail = scanning?.let { "扫描中 $it" },
                        chevron = false,
                    ) { SyncLinkManager.startLibraryScan() }
                    SlNavRow("刷新状态", MeloXSymbol.Refresh, chevron = false) { SyncLinkManager.refreshAll() }
                    MeloXIosListRow(
                        title = "通知栏与锁屏控制此设备",
                        subtitle = "关闭后，MeloX 的媒体通知回到本机播放",
                        leading = { MeloXIosListIcon(MeloXSymbol.Devices) },
                        trailing = {
                            MeloXGlassToggle(
                                checked = remote,
                                onCheckedChange = { if (it) SyncLinkManager.claimRoute() else SyncLinkManager.releaseRoute() },
                            )
                        },
                    )
                    MeloXIosListRow(
                        title = "断开连接",
                        leading = { MeloXIosListIcon(MeloXSymbol.Xmark, tint = MeloXSystemColors.Red) },
                        chevronTint = androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { SyncLinkManager.disconnect() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SlNavRow(
    title: String,
    symbol: MeloXSymbol,
    detail: String? = null,
    first: Boolean = false,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    MeloXIosListRow(
        title = title,
        detail = detail,
        leading = { MeloXIosListIcon(symbol) },
        chevronTint = if (chevron) MeloXSystemColors.Red.copy(alpha = 0.88f) else androidx.compose.ui.graphics.Color.Transparent,
        onClick = onClick,
        showTopSeparator = !first,
    )
}

// ---------------------------------------------------------------------------------------------
// Connect
// ---------------------------------------------------------------------------------------------

@Composable
private fun SlConnectSection(state: SyncLinkState) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<SlDevice>() }
    var scanRound by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val last = remember(state.connection) { SyncLinkManager.lastDevice(context) }
    val connecting = state.connection == SlConnection.Connecting

    var btRound by remember { mutableIntStateOf(0) }
    var btAsked by remember { mutableStateOf(false) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btAsked = true
        btRound++
    }
    val btConnectOk = remember(btRound) { SyncLinkBluetooth.canConnect(context) }
    val btScanOk = remember(btRound) { SyncLinkBluetooth.canScan(context) }
    val btEnabled = remember(btRound) { SyncLinkBluetooth.isEnabled(context) }
    val locationOn = remember(btRound) { SyncLinkBluetooth.locationServiceOn(context) }
    val paired = remember(btRound) { SyncLinkBluetooth.paired(context) }
    val nearby = remember { mutableStateListOf<SlDevice>() }
    var btScanning by remember { mutableStateOf(false) }
    // Re-read permission / adapter state when coming back from system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) btRound++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val requestBt: () -> Unit = {
        val activity = context as? Activity
        val missing = SyncLinkBluetooth.permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        // After a "don't ask again" denial the launcher returns instantly; send the user to app settings.
        val permanentlyDenied = btAsked && activity != null && missing.isNotEmpty() &&
            missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (permanentlyDenied) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            btLauncher.launch(SyncLinkBluetooth.permissions)
        }
    }

    LaunchedEffect(scanRound, btScanOk, btEnabled) {
        if (!btScanOk || !btEnabled) return@LaunchedEffect
        btScanning = true
        SyncLinkBluetooth.discover(context)
            .catch { }
            .collect { d -> if (paired.none { it.host == d.host } && nearby.none { it.host == d.host }) nearby.add(d) }
        btScanning = false
    }

    LaunchedEffect(scanRound) {
        scanning = true
        SyncLinkDiscovery.scan(context)
            .catch { }
            .collect { d -> if (found.none { it.host == d.host && it.port == d.port }) found.add(d) }
        scanning = false
    }

    Column {
        val error = state.error
        if (state.connection == SlConnection.Failed && error != null) {
            Text(
                "连接失败：$error",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                style = MeloXTypography.subheadline,
                color = MeloXSystemColors.Red,
            )
        }
        val deviceRow: @Composable (SlDevice, Int, String) -> Unit = { device, index, sub ->
            MeloXIosListRow(
                title = device.name,
                subtitle = sub,
                leading = { MeloXIosListIcon(MeloXSymbol.Devices) },
                detail = if (connecting && state.device?.host == device.host) "连接中…" else null,
                onClick = { if (!connecting) SyncLinkManager.connect(device) },
                showTopSeparator = index > 0,
            )
        }
        SlSectionLabel(if (btScanning) "蓝牙 · 正在搜索附近的设备…" else "蓝牙")
        MeloXIosGroupedList {
            when {
                !btConnectOk || !btScanOk -> MeloXIosListRow(
                    title = "允许访问附近的设备和位置信息",
                    subtitle = "与山灵官方 App 相同：蓝牙搜索需要“附近的设备”和“位置信息”权限",
                    leading = { MeloXIosListIcon(MeloXSymbol.Devices) },
                    onClick = requestBt,
                    showTopSeparator = false,
                )
                !btEnabled -> MeloXIosListRow(
                    title = "蓝牙未开启",
                    subtitle = "点此开启蓝牙",
                    leading = { MeloXIosListIcon(MeloXSymbol.Devices) },
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    showTopSeparator = false,
                )
                else -> {
                    var index = 0
                    if (!locationOn) {
                        MeloXIosListRow(
                            title = "系统定位未开启",
                            subtitle = "Android 11 及以下搜索蓝牙需要开启定位，点此前往设置",
                            leading = { MeloXIosListIcon(MeloXSymbol.Info, tint = MeloXSystemColors.Red) },
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            showTopSeparator = false,
                        )
                        index++
                    }
                    paired.forEach { deviceRow(it, index++, "已配对 · ${it.host}") }
                    with(SyncLinkBluetooth) { nearby.filter { n -> paired.none { it.host == n.host } }.sortedForDisplay() }
                        .forEach { deviceRow(it, index++, "附近 · ${it.host} · 点击将配对并连接") }
                    if (index == 0) {
                        MeloXIosListRow(
                            title = if (btScanning) "搜索中…" else "未发现蓝牙设备",
                            subtitle = "请在播放器上开启 SyncLink 并保持蓝牙可被发现",
                            leading = { MeloXIosListIcon(MeloXSymbol.RadioWaves) },
                            showTopSeparator = false,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        SlSectionLabel(if (scanning) "正在搜索局域网内的设备…" else "局域网（Wi-Fi）")
        MeloXIosGroupedList {
            if (found.isEmpty()) {
                MeloXIosListRow(
                    title = if (scanning) "搜索中…" else "未发现设备",
                    leading = { MeloXIosListIcon(MeloXSymbol.RadioWaves) },
                    showTopSeparator = false,
                )
            }
            found.forEachIndexed { index, device ->
                MeloXIosListRow(
                    title = device.name,
                    subtitle = device.addressLabel,
                    leading = { MeloXIosListIcon(MeloXSymbol.Devices) },
                    detail = if (connecting && state.device?.host == device.host) "连接中…" else null,
                    onClick = { if (!connecting) SyncLinkManager.connect(device) },
                    showTopSeparator = index > 0,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        MeloXIosGroupedList {
            SlNavRow("重新搜索", MeloXSymbol.Refresh, first = true, chevron = false) {
                btRound++
                if (!scanning) {
                    found.clear()
                    nearby.clear()
                    scanRound++
                }
            }
            SlNavRow("手动输入 IP 地址", MeloXSymbol.Plus) { manual = true }
            if (last != null) {
                MeloXIosListRow(
                    title = "上次连接：${last.name}",
                    subtitle = last.addressLabel,
                    leading = { MeloXIosListIcon(MeloXSymbol.Clock) },
                    onClick = { if (!connecting) SyncLinkManager.connect(last) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "请在山灵播放器上开启 SyncLink（被控端）。蓝牙：先在系统蓝牙设置里与播放器配对；Wi-Fi：手机与播放器连接同一个网络。",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MeloXTypography.subheadline,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
        )
    }

    if (manual) {
        SlManualDialog(
            initialHost = last?.takeUnless { it.bluetooth }?.host.orEmpty(),
            onDismiss = { manual = false },
            onConnect = { host, port ->
                manual = false
                SyncLinkManager.connect(SlDevice(host, host, port))
            },
        )
    }
}

@Composable
private fun SlManualDialog(initialHost: String, onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(SyncLinkDiscovery.PORT.toString()) }
    val portValue = port.toIntOrNull()
    val valid = host.isNotBlank() && portValue != null && portValue in 1..65535
    MeloXGlassDialog(visible = true, onDismiss = onDismiss) {
        Text("连接到设备", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        MeloXGlassTextField(
            value = host,
            onValueChange = { host = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("IP 地址，例如 192.168.1.20") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(10.dp))
        MeloXGlassTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("端口") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MeloXGlassButton(onClick = onDismiss, modifier = Modifier.weight(1f), style = MeloXGlassButtonStyle.Plain) {
                Text("取消", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            MeloXGlassButton(
                onClick = { if (portValue != null && valid) onConnect(host, portValue) },
                modifier = Modifier.weight(1f),
                enabled = valid,
                style = MeloXGlassButtonStyle.BorderedProminent,
            ) {
                Text("连接", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Now playing
// ---------------------------------------------------------------------------------------------

@Composable
private fun NowPlayingCard(state: SyncLinkState) {
    val info = state.info
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
        now = SystemClock.elapsedRealtime()
    }
    MeloXIosGroupedList {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlCover(state.coverPath, Modifier.size(96.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (state.hasTrack) info.displayTitle else "未在播放",
                        style = MeloXTypography.headline,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.hasTrack) {
                        Text(
                            slSubtitle(info.artist, info.album).ifBlank { "未知艺术家" },
                            style = MeloXTypography.subheadline,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val format = state.qualityLabel
                        if (format.isNotBlank()) {
                            Text(format, style = MeloXTypography.caption, color = MeloXSystemColors.Red.copy(alpha = .9f), maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SlProgress(state, now)
            Spacer(Modifier.height(6.dp))
            SlTransportRow(state)
            Spacer(Modifier.height(10.dp))
            SlVolumeRow(state)
            if (state.remotePaused) {
                SlCardNote("设备端已暂停 SyncLink 控制")
            }
            state.scanningCount?.let { SlCardNote("设备正在更新曲库，已扫描 $it 首") }
        }
    }
}

@Composable
private fun SlCardNote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 10.dp),
        style = MeloXTypography.caption,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f),
    )
}

@Composable
internal fun SlCover(path: String?, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(MeloXShapes.card)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .07f)),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            MeloXSymbolIcon(MeloXSymbol.MusicNote, Modifier.size(36.dp), MaterialTheme.colorScheme.onBackground.copy(alpha = .35f), iconSize = 32.sp)
        }
    }
}

@Composable
private fun SlProgress(state: SyncLinkState, now: Long) {
    val duration = state.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = state.positionMs(now)
    val value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Slider(
        value = value,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { if (duration > 0) SyncLinkManager.seekTo((it * duration).toLong()) }
            dragging = null
        },
        enabled = state.hasTrack && duration > 0,
        colors = SliderDefaults.colors(thumbColor = MeloXSystemColors.Red, activeTrackColor = MeloXSystemColors.Red),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = dragging?.let { (it * duration).toLong() } ?: position
        Text(slFormatTime(shown), style = MeloXTypography.caption, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f))
        Spacer(Modifier.weight(1f))
        Text(
            if (duration > 0) slFormatTime(duration) else "--:--",
            style = MeloXTypography.caption,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .55f),
        )
    }
}

@Composable
private fun SlTransportRow(state: SyncLinkState) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val modeSymbol = when (state.mode) {
            SlMode.REPEAT_ALL -> MeloXSymbol.Repeat
            SlMode.REPEAT_ONE -> MeloXSymbol.RepeatOne
            SlMode.RANDOM -> MeloXSymbol.Shuffle
            else -> MeloXSymbol.List
        }
        val modeLabel = when (state.mode) {
            SlMode.REPEAT_ALL -> "列表循环"
            SlMode.REPEAT_ONE -> "单曲循环"
            SlMode.RANDOM -> "随机播放"
            else -> "顺序播放"
        }
        SlRoundButton(modeSymbol, modeLabel, size = 44, tint = if (state.mode == SlMode.NORMAL) onBg.copy(alpha = .7f) else MeloXSystemColors.Red) {
            SyncLinkManager.cycleMode()
        }
        SlRoundButton(MeloXSymbol.Previous, "上一首", size = 52, tint = onBg) { SyncLinkManager.previous() }
        SlRoundButton(
            if (state.playStatus == SlControl.PLAY) MeloXSymbol.Pause else MeloXSymbol.Play,
            if (state.playStatus == SlControl.PLAY) "暂停" else "播放",
            size = 64,
            tint = androidx.compose.ui.graphics.Color.White,
            background = MeloXSystemColors.Red,
        ) { SyncLinkManager.togglePlay() }
        SlRoundButton(MeloXSymbol.Next, "下一首", size = 52, tint = onBg) { SyncLinkManager.next() }
        val favored = state.info.favor == 1
        SlRoundButton(
            MeloXSymbol.Heart,
            if (favored) "取消收藏" else "收藏",
            size = 44,
            tint = if (favored) MeloXSystemColors.Red else onBg.copy(alpha = .7f),
            fill = favored,
            enabled = state.hasTrack,
        ) { SyncLinkManager.toggleFavorite() }
    }
}

@Composable
private fun SlRoundButton(
    symbol: MeloXSymbol,
    label: String,
    size: Int,
    tint: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    fill: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MeloXSymbolIcon(
            symbol,
            Modifier.size((size * 0.5f).dp),
            if (enabled) tint else tint.copy(alpha = .3f),
            variant = if (fill || background != androidx.compose.ui.graphics.Color.Transparent) MeloXSymbolVariant.Fill else MeloXSymbolVariant.Regular,
            iconSize = (size * 0.46f).sp,
            contentDescription = label,
        )
    }
}

@Composable
private fun SlVolumeRow(state: SyncLinkState) {
    val v = state.volume
    val lo = v.lo == 1
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = v.max.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val muted = v.mute == 1
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = !lo, role = Role.Button) { SyncLinkManager.toggleMute() },
            contentAlignment = Alignment.Center,
        ) {
            MeloXSymbolIcon(
                MeloXSymbol.Volume,
                Modifier.size(22.dp),
                if (muted) MeloXSystemColors.Red else MaterialTheme.colorScheme.onBackground.copy(alpha = if (lo) .3f else .7f),
                iconSize = 20.sp,
                contentDescription = if (muted) "取消静音" else "静音",
            )
        }
        Spacer(Modifier.width(6.dp))
        val current = dragging ?: v.cur.toFloat()
        Slider(
            value = current.coerceIn(0f, max.toFloat()),
            onValueChange = {
                val prev = (dragging ?: v.cur.toFloat()).toInt()
                dragging = it
                // Stream integer steps while dragging so the DAP follows the finger.
                if (it.toInt() != prev) SyncLinkManager.setVolume(it.toInt())
            },
            onValueChangeFinished = {
                dragging?.let { SyncLinkManager.setVolume(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..max.toFloat(),
            enabled = !lo && v.max > 0,
            colors = SliderDefaults.colors(thumbColor = MeloXSystemColors.Red, activeTrackColor = MeloXSystemColors.Red),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                lo -> "LO"
                muted -> "静音"
                else -> current.toInt().toString()
            },
            modifier = Modifier.width(40.dp),
            style = MeloXTypography.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f),
            textAlign = TextAlign.End,
        )
    }
}
