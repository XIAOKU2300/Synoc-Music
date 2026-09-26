package com.lladlam.melox.ui.synclink

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lladlam.melox.core.synclink.SyncLinkManager
import com.lladlam.melox.playback.MeloXPlaybackService
import com.lladlam.melox.ui.MeloXPredictiveBackPage
import com.lladlam.melox.ui.prepareMeloXPagePredictiveBack
import com.lladlam.melox.ui.theme.MeloXTheme

/**
 * Shanling SyncLink remote: discovery, now playing, the DAP's whole library, queue, lyrics,
 * device information and the safe subset of device settings.
 */
class SyncLinkActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncLinkManager.init(this)
        // Bind the playback service so the MediaSession can take over the DAP (notification,
        // lock screen, headset buttons) as soon as a device connects.
        controllerFuture = runCatching {
            MediaController.Builder(this, SessionToken(this, ComponentName(this, MeloXPlaybackService::class.java))).buildAsync()
        }.getOrNull()
        enableEdgeToEdge()
        prepareMeloXPagePredictiveBack()
        setContent {
            MeloXPredictiveBackPage(onBack = ::finish) {
                MeloXTheme {
                    SyncLinkRoot(onExit = ::finish)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the background: reconnect at once instead of waiting out the backoff.
        SyncLinkManager.resumeIfNeeded()
    }

    override fun onDestroy() {
        controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        controllerFuture = null
        super.onDestroy()
    }

    companion object {
        /** Page to open on top of Home; routes aren't Parcelable, so it is handed over in-process. */
        @Volatile private var pendingRoute: SlRoute? = null

        internal fun takePendingRoute(): SlRoute? = pendingRoute.also { pendingRoute = null }

        fun launch(context: Context) = launch(context, null)

        internal fun launch(context: Context, route: SlRoute?) {
            pendingRoute = route
            context.startActivity(
                Intent(context, SyncLinkActivity::class.java)
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}

@Composable
private fun SyncLinkRoot(onExit: () -> Unit) {
    val context = LocalContext.current
    val stack = remember {
        mutableStateListOf<SlRoute>(SlRoute.Home).apply { SyncLinkActivity.takePendingRoute()?.let(::add) }
    }
    val navigator = remember(stack) {
        object : SlNavigator {
            override fun push(route: SlRoute) { stack.add(route) }
            override fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }
            override fun popToHome() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
        }
    }
    BackHandler(enabled = stack.size > 1) { navigator.back() }
    LaunchedEffect(Unit) {
        SyncLinkManager.prompts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    SyncLinkPages(stack.last(), navigator)
}
