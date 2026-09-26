package com.lladlam.melox.core.synclink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lladlam.melox.ui.synclink.SyncLinkActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a SyncLink session exists. Without it, pausing the DAP leaves MeloX
 * with no foreground service; once backgrounded the app gets frozen (cached-app freezer / Doze),
 * heartbeats stop, the DAP drops us and the user comes back to "心跳超时".
 *
 * Holds a partial wake lock (and a Wi-Fi lock for LAN sessions) only while a session is active.
 */
class SyncLinkConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(SyncLinkManager.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "startForeground failed", started.exceptionOrNull())
            stopSelf()
            return
        }
        scope.launch {
            SyncLinkManager.state
                .map { Triple(it.connection, it.device?.name, it.info.displayTitle.takeIf { _ -> it.hasTrack }) }
                .distinctUntilChanged()
                .collect {
                    val s = SyncLinkManager.state.value
                    updateLocks(s)
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s)) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            SyncLinkManager.disconnect()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateLocks(s: SyncLinkState) {
        // Failed = waiting to reconnect; keep the CPU awake so the retry timer actually fires.
        val active = s.connection != SlConnection.Idle
        if (active) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeloX:SyncLink")
                    ?.apply { setReferenceCounted(false); runCatching { acquire() } }
            }
            if (s.device?.bluetooth == false && wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MeloX:SyncLink")
                    ?.apply { setReferenceCounted(false); runCatching { acquire() } }
            }
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "山灵 SyncLink 连接", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(s: SyncLinkState): Notification {
        val open = PendingIntent.getActivity(
            this,
            1901,
            Intent(this, SyncLinkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1902,
            Intent(this, SyncLinkConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val name = s.device?.name ?: "SyncLink"
        val title = when (s.connection) {
            SlConnection.Connected -> "已连接 $name"
            SlConnection.Connecting -> "正在连接 $name…"
            SlConnection.Failed -> "$name 连接中断，正在重试"
            SlConnection.Idle -> "SyncLink"
        }
        val text = if (s.hasTrack) s.info.displayTitle else "保持后台连接，避免心跳超时"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "断开", disconnect)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncLinkService"
        private const val CHANNEL_ID = "melox_synclink_connection"
        private const val NOTIFICATION_ID = 0x5117
        private const val ACTION_DISCONNECT = "com.lladlam.melox.synclink.DISCONNECT"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, SyncLinkConnectionService::class.java))
            }.onFailure { Log.w(TAG, "cannot start keep-alive service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncLinkConnectionService::class.java)) }
        }
    }
}
