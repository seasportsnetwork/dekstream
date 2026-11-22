package com.dekstream.tv.ui

import androidx.compose.foundation.border

import androidx.compose.runtime.collectAsState

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
/**
 * Memory matters: every buffered second of video lives in the app's memory. Live TV keeps at most
 * 30 s ahead (15 s in multiview) and, only for HLS (which can actually rewind), 60 s behind.
 * The old 5-minute back buffer filled memory at HD bitrates and crashed after a few minutes.
 */
fun buildPlayer(context: android.content.Context, live: Boolean, keepBehindMs: Int = 0, small: Boolean = false): ExoPlayer {
    val app = DekStreamApp.instance
    val dataSource = OkHttpDataSource.Factory(app.http).setUserAgent(XtreamApi.USER_AGENT)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (small) 5_000 else if (live) 8_000 else 15_000,
            if (small) 15_000 else if (live) 30_000 else 60_000,
            1_500,
            3_000,
        )
        .apply { if (keepBehindMs > 0) setBackBuffer(keepBehindMs, true) }
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
}

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill (crop)",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

fun fmtDuration(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, zapList: List<Channel>, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val app = DekStreamApp.instance
    val repo = app.repo
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var current by remember { mutableStateOf(request) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    /** What the stream actually is: "1080p", "4K", plus extras like "60fps", "HDR", "HEVC". */
    var quality by remember { mutableStateOf<List<String>>(emptyList()) }
    // Self-healing: errors on this feed, whether it has started, feeds already tried, on-screen notice.
    var errorsHere by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var loadStartedAt by remember { mutableLongStateOf(0L) }
    val tried = remember { mutableSetOf<Int>() }
    var notice by remember { mutableStateOf<String?>(null) }
    var failoverRequest by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }         // bump to keep controls up
    var showChannels by remember { mutableStateOf(false) }
    /** Guide strip over the video: index into zapList being browsed (null = closed). */
    var browseIdx by remember { mutableStateOf<Int?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    val allChannels by produceState(emptyList<Channel>()) { value = runCatching { repo?.allChannels() }.getOrNull().orEmpty() }
    val recentIds by app.prefs.recentChannels.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var resizeIdx by remember { mutableIntStateOf(app.channelMemory.resize(request.channel?.streamId)) }
    /** The channel before this one (Previous channel button / LAST key). */
    var prevChannel by remember { mutableStateOf<Channel?>(null) }
    var audioApplied by remember { mutableStateOf<String?>(null) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isLiveWindow by remember { mutableStateOf(false) }
    var behindLive by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val pip = app.inPip

    // Keep 60 s behind live so HLS live TV can be paused and rewound (TS can't seek, so nothing is kept).
    val player = remember {
        val hls = request.url.contains(".m3u8", ignoreCase = true)
        buildPlayer(context, request.isLive, keepBehindMs = if (request.isLive && hls) 60_000 else 0)
    }

    DisposableEffect(Unit) {
        app.playerActive = true
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            app.playerActive = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Pause when the app goes to the background (but keep playing in picture-in-picture).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) player.pause()
            if (e == Lifecycle.Event.ON_START && current.isLive) player.play()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    val ch = current.channel
                    if (!started && current.isLive && ch != null) app.health.recordStart(ch.streamId, System.currentTimeMillis() - loadStartedAt)
                    started = true
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying || player.playWhenReady }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { quality = qualityOf(player) }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                quality = qualityOf(player)
                // Put back the audio track picked last time on this channel (once per load).
                val ch = current.channel ?: return
                if (audioApplied == current.url) return
                val want = app.channelMemory.audio(ch.streamId) ?: run { audioApplied = current.url; return }
                for (g in tracks.groups) {
                    if (g.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val hit = (want.first != null && f.language == want.first) || (want.second.isNotBlank() && f.label == want.second)
                        if (hit && g.isTrackSupported(i)) {
                            audioApplied = current.url
                            if (!g.isTrackSelected(i)) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                            return
                        }
                    }
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                if (current.isLive) {
                    errorsHere++
                    if (errorsHere >= 2) failoverRequest = "stopped working"
                    else scope.launch {
                        delay(2_000)
                        player.seekToDefaultPosition(); player.prepare(); player.play()
                    }
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Load whenever the item changes (zapping, start over, back to live).
    LaunchedEffect(current.url) {
        // Best version: a better-working copy of this channel was played before, so use it.
        val ch0 = current.channel
        if (ch0 != null && current.isLive && com.dekstream.tv.data.PlaybackPrefs.bestVersion && ch0.streamId !in tried) {
            val best = com.dekstream.tv.data.PlaybackPrefs.best(com.dekstream.tv.data.StreamHealth.baseName(ch0.name))
            if (best != null && best.first != ch0.streamId) {
                val alt = runCatching { repo?.allChannels() }.getOrNull()?.firstOrNull { it.streamId == best.first }
                if (alt != null && app.session?.prefs?.isChannelLocked(alt) != true && repo != null) {
                    tried += ch0.streamId
                    notice = "Playing ${alt.name} · the version of this channel that works best"
                    current = repo.liveRequest(alt)
                    return@LaunchedEffect
                }
            }
        }
        error = null
        quality = emptyList()
        errorsHere = 0; started = false; loadStartedAt = System.currentTimeMillis()
        val url = runCatching { repo?.provider?.resolve(current.url) ?: current.url }.getOrDefault(current.url)
        player.setMediaItem(MediaItem.fromUri(url), current.startPositionMs)
        player.prepare()
        player.playWhenReady = true
        touch++
    }

    // ---- Self-healing streams ----
    fun failover(reason: String) {
        val ch = current.channel ?: return
        val r = repo ?: return
        if (!current.isLive) return
        app.health.recordFailure(ch.streamId)
        com.dekstream.tv.data.PlaybackPrefs.forgetBest(com.dekstream.tv.data.StreamHealth.baseName(ch.name), ch.streamId)
        tried += ch.streamId
        scope.launch {
            val next = runCatching { r.alternatesFor(ch, app.health) }.getOrDefault(emptyList())
                .firstOrNull { it.streamId !in tried && app.session?.prefs?.isChannelLocked(it) != true }
            if (next != null) {
                notice = "${ch.name} $reason · switched to ${next.name}"
                current = r.liveRequest(next)
            } else {
                notice = "${ch.name} $reason and no backup feed was found"
            }
        }
    }
    LaunchedEffect(failoverRequest) { failoverRequest?.let { failover(it); failoverRequest = null } }
    // Frozen for 10 s after it was playing, or never started within 15 s: try a backup feed.
    LaunchedEffect(buffering, current.url, started) {
        if (current.isLive && buffering && started) { delay(10_000); if (buffering) failover("froze") }
    }
    LaunchedEffect(current.url) {
        if (current.isLive) { delay(15_000); if (!started) failover("wouldn't start") }
    }
    // Played smoothly for 90 s: remember this copy (and its resolution) as a good version of the channel.
    LaunchedEffect(current.url, started) {
        val ch = current.channel ?: return@LaunchedEffect
        if (!current.isLive || !started) return@LaunchedEffect
        delay(90_000)
        val hgt = player.videoFormat?.let { minOf(it.width, it.height) } ?: 0
        if (!buffering && error == null && hgt > 0) com.dekstream.tv.data.PlaybackPrefs.recordGood(com.dekstream.tv.data.StreamHealth.baseName(ch.name), ch.streamId, hgt)
    }
    LaunchedEffect(notice) { if (notice != null) { delay(6_000); notice = null } }

    // Position ticker for the seek bar and live-edge offset.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration
            isLiveWindow = player.isCurrentMediaItemLive
            behindLive = if (isLiveWindow && duration > 0) (duration - position).coerceAtLeast(0) else 0
            delay(500)
        }
    }

    fun saveProgress() {
        val key = current.progressKey ?: return
        val pos = player.currentPosition; val dur = player.duration
        if (dur <= 0 || pos < 10_000) return
        app.prefs.saveProgress(ProgressEntry(key, current.title, current.subtitle, current.poster, current.url, pos, dur, System.currentTimeMillis()))
    }
    LaunchedEffect(current.progressKey) { while (current.progressKey != null) { delay(10_000); saveProgress() } }
    DisposableEffect(Unit) { onDispose { saveProgress(); player.release() } }

    // Count viewing time (for the profile's weekly totals and kids limits).
    LaunchedEffect(player) {
        while (true) { delay(60_000); if (player.isPlaying) app.session?.settings?.addWatchMinute() }
    }

    // Auto-hide controls 5 s after the last interaction.
    LaunchedEffect(touch, controls, showOptions, showChannels) {
        if (controls && !showOptions && !showChannels) { delay(5_000); controls = false }
    }
    LaunchedEffect(controls) { if (!controls) runCatching { rootFocus.requestFocus() } }

    // Sleep timer.
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            player.pause(); onExit()
        }
    }

    fun switchTo(c: Channel) {
        val r = repo ?: return
        app.prefs.markChannelWatched(c.streamId)
        current.channel?.let { if (it.streamId != c.streamId) prevChannel = it }
        resizeIdx = app.channelMemory.resize(c.streamId)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO).build()
        tried.clear()
        current = r.liveRequest(c)
        showChannels = false; showRecent = false; browseIdx = null
    }

    fun zap(delta: Int) {
        val ch = current.channel ?: return
        if (zapList.size < 2) return
        val idx = zapList.indexOfFirst { it.streamId == ch.streamId }.coerceAtLeast(0)
        switchTo(zapList[(idx + delta + zapList.size) % zapList.size])
    }

    // Number-key jumps: type digits, wait a moment, jump to that channel number.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null) {
            val target = zapList.firstOrNull { it.num == n } ?: repo?.allChannels()?.firstOrNull { it.num == n }
            if (target != null) switchTo(target)
        }
    }

    val epg by produceState<List<Program>>(emptyList(), current.channel?.streamId, current.isLive) {
        val ch = current.channel
        value = if (ch != null && repo != null) repo.nowNext(ch) else emptyList()
    }
    val nowProg = epg.firstOrNull { it.isLive() }
    val nextProg = epg.firstOrNull { it.startMs > System.currentTimeMillis() }

    fun startOver() {
        val ch = current.channel ?: return
        val p = nowProg ?: return
        repo?.catchupRequest(ch, p)?.let { current = it }
    }
    fun backToLive() { current.channel?.let { switchTo(it) } }

    fun showControls() { controls = true; touch++ }
    LaunchedEffect(browseIdx) { if (browseIdx != null) { delay(8_000); browseIdx = null } }
    // Frame rate matching: ask the TV for a refresh rate that fits the stream (60 for sports, 24 for movies).
    val fps = player.videoFormat?.frameRate?.takeIf { quality.isNotEmpty() } ?: -1f
    LaunchedEffect(fps, com.dekstream.tv.data.PlaybackPrefs.matchFrameRate, pip) {
        val act = activity ?: return@LaunchedEffect
        if (com.dekstream.tv.data.PlaybackPrefs.matchFrameRate && fps > 0 && !pip) matchFrameRate(act, fps) else resetFrameRate(act)
    }
    DisposableEffect(Unit) { onDispose { activity?.let { resetFrameRate(it) } } }

    BackHandler {
        when {
            app.alert != null -> app.alert = null
            showOptions -> showOptions = false
            showChannels -> showChannels = false
            showRecent -> showRecent = false
            browseIdx != null -> browseIdx = null
            controls && !pip -> controls = false
            current.isLive && com.dekstream.tv.data.PlaybackPrefs.backToMini && !pip -> { app.mini = current to zapList; onExit() }
            else -> onExit()
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(controls) { if (controls) { delay(50); runCatching { playFocus.requestFocus() } } }
    LaunchedEffect(pip) { playerView?.resizeMode = if (pip) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeModes[resizeIdx].first }

    val canStartOver = current.isLive && current.channel?.hasArchive == true && nowProg != null
    val isCatchup = !current.isLive && current.channel != null

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                touch++
                // Digits jump channels from anywhere.
                val digit = when (k.keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k.keyCode - KeyEvent.KEYCODE_0
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                if (digit >= 0 && current.channel != null) { digits = (digits + digit).takeLast(4); return@onPreviewKeyEvent true }
                when (k.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play(); showControls(); return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showOptions = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> { prevChannel?.let { switchTo(it) }; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekForward(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBack(); return@onPreviewKeyEvent true }
                }
                // A sports alert is showing: OK watches that game.
                if (app.alert != null && !showOptions && !showChannels &&
                    (k.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    app.alertWatchRequested = true; return@onPreviewKeyEvent true
                }
                // Guide strip open: up/down browse, OK switches, left/right close.
                browseIdx?.let { bi ->
                    when (k.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { browseIdx = (bi - 1 + zapList.size) % zapList.size; return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { browseIdx = (bi + 1) % zapList.size; return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { zapList.getOrNull(bi)?.let { switchTo(it) }; browseIdx = null; return@onPreviewKeyEvent true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { browseIdx = null; return@onPreviewKeyEvent true }
                    }
                }
                if (controls || showOptions || showChannels || showRecent) return@onPreviewKeyEvent false
                // Controls hidden: remote shortcuts.
                when (k.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (k.repeatCount > 0) showOptions = true else showControls(); true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val dir = if (k.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        when {
                            !current.isLive -> showControls()
                            com.dekstream.tv.data.PlaybackPrefs.upDown == "guide" && zapList.size > 1 -> {
                                val cur = zapList.indexOfFirst { it.streamId == current.channel?.streamId }.coerceAtLeast(0)
                                browseIdx = (cur + dir + zapList.size) % zapList.size
                            }
                            else -> zap(dir)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when {
                            !current.isLive -> player.seekBack()
                            recentIds.size >= 2 -> showRecent = true
                            zapList.size > 1 -> showChannels = true
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (current.isLive) showControls() else player.seekForward(); true }
                    else -> false
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { if (controls) controls = false else showControls() }, onDoubleTap = { activity?.enterPip() }) },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = false
                    resizeMode = resizeModes[resizeIdx].first
                    isFocusable = false
                    keepScreenOn = true
                    playerView = this
                }
            },
            update = {
                it.resizeMode = if (pip) AspectRatioFrameLayout.RESIZE_MODE_FIT else resizeModes[resizeIdx].first
                applyCaptionStyle(it)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) CircularProgressIndicator(color = DS.Accent, modifier = Modifier.align(Alignment.Center))

        if (pip) return@Box

        error?.let {
            Text(
                "Playback problem: $it" + if (current.isLive) " · retrying…" else "",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(top = 80.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC000000)).padding(16.dp),
            )
        }

        // Typed channel number.
        if (digits.isNotEmpty()) {
            Text(
                digits, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }

        // Self-healing notice
        if (!pip) ScoreTicker(Modifier.align(Alignment.TopEnd).padding(top = if (controls) 76.dp else 20.dp, end = 20.dp))

        AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xE6151922)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AutoFixHigh, null, tint = DS.Accent, modifier = Modifier.size(18.dp))
                Text("  " + (notice ?: ""), color = Color.White, fontSize = 14.sp)
            }
        }

        // ---------- Controls overlay ----------
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onExit() }
                    current.channel?.let { ch -> HSpacer(12.dp); ChannelLogo(ch.icon, 44.dp) }
                    Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(current.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (current.isLive) { HSpacer(10.dp); LiveBadge() }
                            quality.forEachIndexed { i, q -> HSpacer(if (i == 0) 10.dp else 6.dp); QualityBadge(q, primary = i == 0) }
                        }
                        val sub = if (current.isLive) nowProg?.let { "${it.title} · ${minutesLeft(it)} left" } else current.subtitle
                        sub?.let { Text(it, color = DS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (sleepAt > 0) {
                        val mins = ((sleepAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                        Text("Sleep in ${mins}m", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x55FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp))
                        HSpacer(8.dp)
                    }
                    CtrlButton(Icons.Default.PictureInPictureAlt, "Picture-in-picture") { activity?.enterPip() }
                }

                // Bottom bar
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    if (!current.isLive || isLiveWindow) {
                        SeekBar(
                            position = position, duration = duration,
                            onSeek = { player.seekTo(it); touch++ },
                            onStep = { d -> if (d > 0) player.seekForward() else player.seekBack(); touch++ },
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(fmtDuration(position), color = DS.TextDim, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (isLiveWindow) (if (behindLive > 15_000) "-" + fmtDuration(behindLive) else "LIVE") else fmtDuration(duration),
                                color = if (isLiveWindow && behindLive <= 15_000) DS.Live else DS.TextDim, fontSize = 12.sp,
                            )
                        }
                    } else if (nowProg != null) {
                        // Plain live stream: show programme progress instead of a seek bar.
                        val f = ((System.currentTimeMillis() - nowProg.startMs).toFloat() / (nowProg.endMs - nowProg.startMs)).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF))) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(DS.Accent))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(fmtTime(nowProg.startMs), color = DS.TextDim, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            nextProg?.let { Text("Next: ${it.title} · ${fmtTime(it.startMs)}", color = DS.TextDim, fontSize = 12.sp, maxLines = 1) }
                            Spacer(Modifier.weight(1f))
                            Text(fmtTime(nowProg.endMs), color = DS.TextDim, fontSize = 12.sp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (current.isLive && zapList.size > 1) CtrlButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Channels") { showChannels = true }
                        prevChannel?.let { pc -> if (current.isLive) PillButton(Icons.Default.SwapHoriz, "Back to ${pc.name.take(18)}") { switchTo(pc) } }
                        CtrlButton(Icons.Default.Replay10, "Back 10 seconds") { player.seekBack(); touch++ }
                        CtrlButton(
                            if (playing && player.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (player.playWhenReady) "Pause" else "Play",
                            big = true, focus = playFocus,
                        ) {
                            if (player.playWhenReady) {
                                player.pause()
                                if (current.isLive && !player.isCurrentMediaItemSeekable)
                                    notice = "This stream can't be paused or rewound. In Settings, set Live TV stream format to HLS to pause live TV."
                            } else player.play()
                            playing = player.playWhenReady; touch++
                        }
                        CtrlButton(Icons.Default.Forward10, "Forward 10 seconds") { player.seekForward(); touch++ }
                        if (isLiveWindow && behindLive > 15_000) PillButton(Icons.Default.LiveTv, "Go live") { player.seekToDefaultPosition(); touch++ }
                        if (canStartOver) PillButton(Icons.Default.History, "Start over") { startOver() }
                        if (isCatchup) PillButton(Icons.Default.LiveTv, "Back to live") { backToLive() }
                        Spacer(Modifier.weight(1f))
                        CtrlButton(Icons.Default.Subtitles, "Audio & subtitles") { showOptions = true }
                        CtrlButton(Icons.Default.Bedtime, "Sleep timer") { showOptions = true }
                        CtrlButton(Icons.Default.AspectRatio, "Picture size: ${resizeModes[resizeIdx].second}") {
                            resizeIdx = (resizeIdx + 1) % resizeModes.size; app.channelMemory.setResize(current.channel?.streamId, resizeIdx); touch++
                        }
                    }
                }
            }
        }

        // ---------- Channel list ----------
        AnimatedVisibility(
            showChannels,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight(),
        ) {
            ChannelPanel(zapList, current.channel?.streamId, onPick = { switchTo(it) })
        }

        AnimatedVisibility(
            showRecent, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val byId = remember(allChannels) { allChannels.associateBy { it.streamId } }
            ChannelPanel(recentIds.mapNotNull { byId[it] }.take(10), current.channel?.streamId, title = "Recent channels") { switchTo(it) }
        }
        browseIdx?.let { bi -> zapList.getOrNull(bi)?.let { c -> GuideStrip(c, zapList, bi, Modifier.align(Alignment.BottomCenter)) } }

        if (showOptions) {
            PlayerOptionsDialog(
                player = player,
                sleepAt = sleepAt,
                programmeEnd = nowProg?.endMs,
                onSleep = { sleepAt = it },
                resizeIdx = resizeIdx,
                onResize = { resizeIdx = it; app.channelMemory.setResize(current.channel?.streamId, it) },
                onDismiss = { showOptions = false; touch++ },
                onAudioPicked = { lang, label -> current.channel?.let { app.channelMemory.setAudio(it.streamId, lang, label) } },
            )
        }
    }
}

fun minutesLeft(p: Program): String {
    val m = ((p.endMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

@Composable
private fun CtrlButton(icon: ImageVector, label: String, big: Boolean = false, focus: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val size = if (big) 50.dp else 38.dp
    Box(
        Modifier
            .size(size)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    big -> Color.White.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (focused) Color.Black else Color.White.copy(alpha = 0.92f), modifier = Modifier.size(if (big) 28.dp else 22.dp))
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.height(32.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(16.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(16.dp))
        Text("  $label", color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Seek bar that works with touch (tap/drag) and with the remote (left/right when focused). */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit, onStep: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onSeek((o.x / widthPx * duration).toLong()) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(DS.Accent))
        }
        Box(
            Modifier.fillMaxWidth(f.coerceAtLeast(0.0001f)).height(24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.size(if (focused) 18.dp else 12.dp).clip(CircleShape).background(if (focused) Color.White else DS.Accent))
        }
    }
}

@Composable
private fun ChannelPanel(channels: List<Channel>, currentId: Int?, title: String = "Channels", onPick: (Channel) -> Unit) {
    val repo = DekStreamApp.instance.repo
    val startIdx = channels.indexOfFirst { it.streamId == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 3).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxHeight().widthIn(max = 420.dp).width(380.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF20B0D12), Color(0xD90B0D12))))
            .padding(vertical = 18.dp),
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(state = listState) {
            itemsIndexed(channels, key = { _, c -> c.streamId }) { i, c ->
                val now by produceState<Program?>(null, c.streamId) { value = repo?.nowNext(c)?.firstOrNull { it.isLive() } }
                val selected = c.streamId == currentId
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                        .then(if (i == startIdx) Modifier.focusRequester(first) else Modifier)
                        .focusRing(shape, 1.02f).clip(shape)
                        .background(if (selected) DS.AccentSoft else Color.Transparent)
                        .clickable { onPick(c) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (c.num > 0) "${c.num}" else "", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.width(34.dp))
                    ChannelLogo(c.icon, 36.dp)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(c.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        now?.let { Text(it.title, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Suppress("unused") private val noRipple = MutableInteractionSource()
@Suppress("unused") private val h = Modifier.heightIn(0.dp)


/** Resolution + extras from the playing video track. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun qualityOf(p: androidx.media3.exoplayer.ExoPlayer): List<String> {
    val f = p.videoFormat ?: return emptyList()
    val w = maxOf(f.width, f.height); val h = minOf(f.width, f.height)
    if (h <= 0) return emptyList()
    val res = when {
        h >= 2000 || w >= 3800 -> "4K"
        h >= 1400 -> "1440p"
        h >= 1000 || w >= 1900 -> "1080p"
        h >= 700 || w >= 1260 -> "720p"
        h >= 540 -> "576p"
        else -> "SD"
    }
    val out = arrayListOf(res)
    val fps = f.frameRate
    if (fps >= 47f) out += "${kotlin.math.round(fps).toInt()}fps"
    val tr = f.colorInfo?.colorTransfer
    if (tr == androidx.media3.common.C.COLOR_TRANSFER_ST2084 || tr == androidx.media3.common.C.COLOR_TRANSFER_HLG) out += "HDR"
    val mime = f.sampleMimeType ?: ""
    if (mime.contains("hevc") || mime.contains("dolby")) out += if (mime.contains("dolby")) "DV" else "HEVC"
    return out
}

@Composable
private fun QualityBadge(text: String, primary: Boolean) {
    val bg = when {
        !primary -> Color(0x33FFFFFF)
        text == "4K" -> Color(0xFFFFB300)
        text == "1080p" || text == "1440p" -> Color(0xFF2D7FF9)
        text == "720p" -> Color(0xFF34C759)
        else -> Color(0xFF8E8E93)
    }
    Text(text, color = if (primary && text == "4K") Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp))
}


/** Your teams' live games in the corner while you watch (Sports settings → Score ticker). */
@Composable
private fun ScoreTicker(modifier: Modifier) {
    val app = DekStreamApp.instance
    val ctx = LocalContext.current
    if (!com.dekstream.tv.data.GameAlarms.tickerOn(ctx)) return
    val favs by app.prefs.favTeams.collectAsState()
    val hide by app.prefs.hideScores.collectAsState()
    val games by produceState(emptyList<com.dekstream.tv.data.Game>(), favs) {
        while (true) {
            value = runCatching { app.sports.favoriteGames(favs) }.getOrDefault(emptyList()).map { it.second }
                .distinctBy { it.id }.filter { it.isLive }.take(3)
            delay(60_000)
        }
    }
    if (games.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        games.forEach { g ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC0B0B0F)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFF3B30)))
                Text("  ${g.away.abbr}" + (if (hide) "" else " ${g.away.score ?: "-"}") + "  ${g.home.abbr}" + (if (hide) "" else " ${g.home.score ?: "-"}"),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("  ${g.detail}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}


/** Bottom strip while browsing channels over the video: what's on now and next. OK switches. */
@Composable
private fun GuideStrip(c: Channel, list: List<Channel>, idx: Int, modifier: Modifier) {
    val repo = DekStreamApp.instance.repo
    val epg by produceState(emptyList<Program>(), c.streamId) { value = runCatching { repo?.nowNext(c) }.getOrNull().orEmpty() }
    val now = epg.firstOrNull { it.isLive() }
    val next = epg.firstOrNull { it.startMs > System.currentTimeMillis() }
    val prevC = list.getOrNull((idx - 1 + list.size) % list.size)
    val nextC = list.getOrNull((idx + 1) % list.size)
    Column(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 28.dp),
    ) {
        prevC?.let { Text("▲  ${it.name}", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, maxLines = 1) }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.icon, 64.dp)
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Text((if (c.num > 0) "${c.num}  " else "") + c.name, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(now?.title ?: "No guide information", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    val f = ((System.currentTimeMillis() - now.startMs).toFloat() / (now.endMs - now.startMs)).coerceIn(0f, 1f)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                            Box(Modifier.fillMaxWidth(f).height(4.dp).background(DS.Accent))
                        }
                        Text("  ${minutesLeft(now)} left", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
                next?.let { Text("Next · ${fmtTime(it.startMs)}  ${it.title}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)) }
            }
            Text("OK to watch", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 14.dp, vertical = 7.dp))
        }
        nextC?.let { Text("▼  ${it.name}", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp, maxLines = 1) }
    }
}

/** Caption size, background and position from Settings > Playback & screen. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun applyCaptionStyle(v: PlayerView) {
    val sv = v.subtitleView ?: return
    val pp = com.dekstream.tv.data.PlaybackPrefs
    val bg = when (pp.captionBg) { 0 -> android.graphics.Color.TRANSPARENT; 2 -> android.graphics.Color.BLACK; else -> 0x99000000.toInt() }
    sv.setStyle(androidx.media3.ui.CaptionStyleCompat(
        android.graphics.Color.WHITE, bg, android.graphics.Color.TRANSPARENT,
        if (pp.captionBg == 0) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
        android.graphics.Color.BLACK, null,
    ))
    sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * listOf(0.8f, 1f, 1.3f, 1.6f)[pp.captionSize.coerceIn(0, 3)])
    sv.setBottomPaddingFraction(if (pp.captionHigh) 0.2f else androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
}

/** Pick the display mode at the current resolution whose refresh rate fits the video best. */
private fun matchFrameRate(act: android.app.Activity, fps: Float) {
    runCatching {
        val display = act.window.decorView.display ?: return
        val cur = display.mode
        val modes = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        fun fit(rate: Float): Float { val r = rate / fps; val n = kotlin.math.round(r); return if (n < 1f) 99f else kotlin.math.abs(r - n) + (n - 1) * 0.01f }
        val best = modes.minByOrNull { fit(it.refreshRate) } ?: return
        if (fit(best.refreshRate) > 0.02f) return
        val lp = act.window.attributes
        if (lp.preferredDisplayModeId != best.modeId) { lp.preferredDisplayModeId = best.modeId; act.window.attributes = lp }
    }
}

private fun resetFrameRate(act: android.app.Activity) {
    runCatching {
        val lp = act.window.attributes
        if (lp.preferredDisplayModeId != 0) { lp.preferredDisplayModeId = 0; act.window.attributes = lp }
    }
}


/** Live TV in a corner while browsing. OK = full screen again, hold OK = close. */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(req: PlayRequest, modifier: Modifier, onExpand: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = DekStreamApp.instance.repo
    val player = remember(req.url) { buildPlayer(context, live = true, small = true) }
    LaunchedEffect(req.url) {
        val url = runCatching { repo?.provider?.resolve(req.url) ?: req.url }.getOrDefault(req.url)
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.width(400.dp).height(225.dp).focusRing(shape, 1.04f).clip(shape).background(Color.Black)
            .border(1.dp, Color(0x33FFFFFF), shape).tvClickable(onClick = onExpand, onLongClick = onClose),
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false; isFocusable = false; keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(req.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text("OK full screen · hold to close", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
        }
    }
}
