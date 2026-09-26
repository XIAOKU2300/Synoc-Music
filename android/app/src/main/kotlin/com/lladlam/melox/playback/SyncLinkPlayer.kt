package com.lladlam.melox.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lladlam.melox.core.synclink.SlControl
import com.lladlam.melox.core.synclink.SlMode
import com.lladlam.melox.core.synclink.SlSong
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.core.synclink.SyncLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Media3 facade over a SyncLink session so the MediaSession (notification, lock screen,
 * Bluetooth/Auto controllers and the in-app mini player) drives the Shanling DAP.
 *
 * Any attempt to load local media items hands the session back to MeloX's own ExoPlayer via
 * [onLocalPlaybackRequested]; the DAP keeps its state untouched.
 */
@OptIn(UnstableApi::class)
class SyncLinkPlayer(
    private val onLocalPlaybackRequested: (items: List<MediaItem>, startIndex: Int, startPositionMs: Long, append: Boolean) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Declared before init: Main.immediate may call getState() synchronously from the collectors below.
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlayingAt = 0L

    init {
        scope.launch {
            // Position ticks are read live through the PositionSupplier; only rebuild on real changes.
            SyncLinkManager.state
                .map { it.copy(positionSec = 0, positionAt = 0L) to (it.positionSec / 5) }
                .distinctUntilChanged()
                .collect { invalidateState() }
        }
        scope.launch { SyncLinkManager.queue.collect { invalidateState() } }
    }

    override fun getState(): State {
        val s = SyncLinkManager.state.value
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_PREPARE,
                COMMAND_RELEASE,
                COMMAND_GET_DEVICE_VOLUME,
            )
            .apply {
                if (s.volume.lo != 1 && s.volume.max > 0) {
                    addAll(
                        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    )
                }
            }
            .build()
        val maxVolume = s.volume.max.coerceAtLeast(0)
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(maxVolume).build())
            .setDeviceVolume(s.volume.cur.coerceIn(0, maxVolume))
            .setIsDeviceMuted(s.volume.mute == 1)
            .setRepeatMode(
                when (s.mode) {
                    SlMode.REPEAT_ONE -> REPEAT_MODE_ONE
                    SlMode.REPEAT_ALL -> REPEAT_MODE_ALL
                    else -> REPEAT_MODE_OFF
                },
            )
            .setShuffleModeEnabled(s.mode == SlMode.RANDOM)
            .setPlayWhenReady(reportedPlaying(s), PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
        // Keep the last track through reconnects: an IDLE/empty session removes the media
        // notification, and a background app may not promote it back to the foreground.
        if (s.info.isEmpty || !s.holdsSession) {
            return builder.setPlaybackState(STATE_IDLE).setPlaylist(emptyList()).build()
        }
        val (playlist, index) = playlist(s)
        return builder
            .setPlaybackState(STATE_READY)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { SyncLinkManager.state.value.positionMs() }
            .build()
    }

    /**
     * The DAP reports STOP for a moment when a track runs out and the next one starts. Passing that
     * through flips the session to "paused", which demotes the playback service; from the background
     * it can then fail to come back and the island/notification freezes on the old song. A STOP within
     * [STOP_GRACE_MS] of playing is therefore still reported as playing (a user pause is PAUSE).
     */
    private fun reportedPlaying(s: SyncLinkState): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (s.isPlaying) {
            lastPlayingAt = now
            return true
        }
        val sinceMs = now - lastPlayingAt
        if (s.playStatus == SlControl.STOP && s.isConnected && lastPlayingAt > 0L && sinceMs < STOP_GRACE_MS) {
            handler.postDelayed({ invalidateState() }, STOP_GRACE_MS - sinceMs + 50L)
            return true
        }
        return false
    }

    private var cachedQueue: List<SlSong>? = null
    private var cachedQueueItems: List<MediaItemData> = emptyList()

    /**
     * The DAP's real play queue is exposed as the timeline. Besides showing the queue, this is
     * what keeps "next" enabled: BasePlayer silently ignores seekToNext on a one-item timeline.
     */
    private fun playlist(s: SyncLinkState): Pair<List<MediaItemData>, Int> {
        val queue = SyncLinkManager.queue.value
        val index = s.queueIndex
        val current = itemData(s, "synclink:$index:")
        if (queue.isEmpty() || index !in queue.indices) return listOf(current) to 0
        if (queue !== cachedQueue) {
            cachedQueue = queue
            cachedQueueItems = queue.mapIndexed { i, song -> songData(song, i) }
        }
        val items = ArrayList(cachedQueueItems)
        items[index] = current
        return items to index
    }

    private fun songData(song: SlSong, index: Int): MediaItemData {
        val key = "synclink:$index:" + song.filepath + "#" + song.time
        val metadata = MediaMetadata.Builder()
            .setTitle(song.displayTitle)
            .setArtist(song.artist.ifBlank { null })
            .setAlbumTitle(song.album.ifBlank { null })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val durationMs = song.durationSec * 1000L
        return MediaItemData.Builder(key)
            .setMediaItem(MediaItem.Builder().setMediaId(key).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
    }

    private fun itemData(s: SyncLinkState, prefix: String): MediaItemData {
        val info = s.info
        val key = prefix + info.filepath.ifBlank { "${info.title}|${info.artist}|${info.album}" } + "#" + info.time
        val metadata = MediaMetadata.Builder()
            .setTitle(info.displayTitle)
            .setArtist(info.artist.ifBlank { null })
            .setAlbumTitle(info.album.ifBlank { null })
            .setAlbumArtist(info.artist.ifBlank { null })
            .setGenre(info.genre.ifBlank { null })
            .setComposer(info.composer.ifBlank { null })
            .setReleaseYear(info.year.takeIf { it > 0 })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply { putBoolean(EXTRA_SYNCLINK, true) })
            .apply {
                s.coverPath?.takeIf { s.coverKey.isNotEmpty() }?.let { setArtworkUri(Uri.fromFile(File(it))) }
            }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(key)
            .setMediaMetadata(metadata)
            .build()
        val durationMs = s.durationMs
        return MediaItemData.Builder(key)
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(durationMs > 0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) SyncLinkManager.play() else SyncLinkManager.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        SyncLinkManager.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        SyncLinkManager.setMode(
            when (repeatMode) {
                REPEAT_MODE_ONE -> SlMode.REPEAT_ONE
                REPEAT_MODE_ALL -> SlMode.REPEAT_ALL
                else -> SlMode.NORMAL
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        SyncLinkManager.setMode(if (shuffleModeEnabled) SlMode.RANDOM else SlMode.REPEAT_ALL)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = SyncLinkManager.state.value.queueIndex
        when {
            seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> SyncLinkManager.next()
            seekCommand == COMMAND_SEEK_TO_PREVIOUS || seekCommand == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> SyncLinkManager.previous()
            mediaItemIndex >= 0 && mediaItemIndex != current && SyncLinkManager.queue.value.size > 1 ->
                SyncLinkManager.playQueueIndex(mediaItemIndex)
            else -> SyncLinkManager.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        SyncLinkManager.setVolume(deviceVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        SyncLinkManager.setVolume(SyncLinkManager.state.value.volume.cur + 1)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        SyncLinkManager.setVolume(SyncLinkManager.state.value.volume.cur - 1)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        SyncLinkManager.setVolume(SyncLinkManager.state.value.volume.cur, muted)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (mediaItems.isNotEmpty()) onLocalPlaybackRequested(ArrayList(mediaItems), startIndex, startPositionMs, false)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (mediaItems.isNotEmpty()) onLocalPlaybackRequested(ArrayList(mediaItems), index, C.TIME_UNSET, true)
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    companion object {
        const val EXTRA_SYNCLINK = "melox.synclink.remote"
        private const val STOP_GRACE_MS = 4_000L
    }
}
