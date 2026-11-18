package com.dekstream.tv.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.PlayRequest
import com.dekstream.tv.data.Program
import com.dekstream.tv.data.ProgressEntry
import com.dekstream.tv.data.XtreamApi
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
fun buildPlayer(context: android.content.Context, live: Boolean): ExoPlayer {
    val app = DekStreamApp.instance
    val dataSource = OkHttpDataSource.Factory(app.http).setUserAgent(XtreamApi.USER_AGENT)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (live) 8_000 else 15_000,   // min buffer
            if (live) 30_000 else 60_000,  // max buffer
            1_500,                         // buffer before starting playback
            3_000,                         // buffer after a rebuffer
        )
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .setLoadControl(loadControl)
        .build()
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, zapList: List<Channel>, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = DekStreamApp.instance
    val repo = app.repo
    var current by remember { mutableStateOf(request) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var showInfo by remember { mutableIntStateOf(0) } // bump to show the channel banner
    var bannerVisible by remember { mutableStateOf(true) }
    var controllerVisible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val player = remember { buildPlayer(context, request.isLive) }

    // Full screen + keep screen on while playing.
    DisposableEffect(Unit) {
        val act = context as? Activity
        val window = act?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Listener: errors, buffering, auto-retry for live streams.
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
                if (current.isLive) {
                    // Live streams often hiccup: retry from the live edge.
                    player.prepare()
                    player.play()
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // (Re)load when the item changes (channel zapping swaps `current`).
    LaunchedEffect(current.url) {
        error = null
        player.setMediaItem(MediaItem.fromUri(current.url), current.startPositionMs)
        player.prepare()
        player.playWhenReady = true
        showInfo++
    }

    // Save resume position every 10 s and on exit.
    fun saveProgress() {
        val key = current.progressKey ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0 || pos < 10_000) return
        app.prefs.saveProgress(ProgressEntry(key, current.title, current.subtitle, current.poster, current.url, pos, dur, System.currentTimeMillis()))
    }
    LaunchedEffect(current.progressKey) {
        while (current.progressKey != null) { delay(10_000); saveProgress() }
    }
    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            player.release()
        }
    }

    // Channel banner auto-hides after 5 s.
    LaunchedEffect(showInfo) { bannerVisible = true; delay(5_000); bannerVisible = false }

    fun zap(delta: Int) {
        val ch = current.channel ?: return
        if (zapList.size < 2 || repo == null) return
        val idx = zapList.indexOfFirst { it.streamId == ch.streamId }
        if (idx < 0) return
        val next = zapList[(idx + delta + zapList.size) % zapList.size]
        app.prefs.markChannelWatched(next.streamId)
        current = repo.liveRequest(next)
    }

    BackHandler { onExit() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                // With the control bar hidden, up/down changes channel on live TV.
                if (!controllerVisible && current.isLive && current.channel != null) {
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_INFO -> { showInfo++; return@onPreviewKeyEvent true }
                    }
                }
                false
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controllerVisible = v == android.view.View.VISIBLE
                    })
                    if (request.isLive) {
                        setShowFastForwardButton(false)
                        setShowRewindButton(false)
                    }
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            CircularProgressIndicator(color = DS.Accent, modifier = Modifier.align(Alignment.Center))
        }

        error?.let {
            Text(
                "Playback problem: $it" + if (current.isLive) " · retrying…" else "",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000)).padding(16.dp),
            )
        }

        // On-screen back button (touch screens); shows with the controls or the channel banner.
        AnimatedVisibility(
            visible = controllerVisible || bannerVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x99000000)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }

        AnimatedVisibility(
            visible = bannerVisible && !controllerVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
        ) {
            InfoBanner(current, hasZap = zapList.size > 1)
        }
    }
}

@Composable
private fun InfoBanner(req: PlayRequest, hasZap: Boolean) {
    val repo = DekStreamApp.instance.repo
    val ch = req.channel
    val epg by produceState<List<Program>>(emptyList(), ch?.streamId) {
        if (ch != null && repo != null) value = repo.nowNext(ch.streamId)
    }
    val now = epg.firstOrNull { it.isLive() }
    val next = epg.firstOrNull { it.startMs > System.currentTimeMillis() }
    Row(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ch != null) ChannelLogo(ch.icon, 56.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(req.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (req.isLive) { HSpacer(10.dp); LiveBadge() }
            }
            req.subtitle?.let { Text(it, color = DS.TextDim, fontSize = 14.sp) }
            now?.let { Text("Now: ${it.title}  (${fmtTime(it.startMs)} – ${fmtTime(it.endMs)})", color = Color.White, fontSize = 14.sp) }
            next?.let { Text("Next: ${it.title}  ${fmtTime(it.startMs)}", color = DS.TextDim, fontSize = 13.sp) }
            if (hasZap && req.isLive) Text("▲ ▼ change channel", color = DS.TextDim, fontSize = 12.sp)
        }
    }
}
