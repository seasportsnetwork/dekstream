package com.dekstream.tv.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dekstream.tv.DekStreamApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            TvScale {
                DekStreamTheme {
                    Box(Modifier.fillMaxSize().background(DS.Bg)) { Root() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getIntExtra(EXTRA_PLAY_STREAM, -1) ?: -1
        if (id >= 0) DekStreamApp.instance.pendingPlayStream = id
        if (com.dekstream.tv.BuildConfig.DEBUG) handleTestIntent(i)
    }

    /**
     * Debug builds only, for the automated emulator test (ci/smoke.sh):
     * test_m3u = set up a test profile with this playlist; test_tab = open this tab; test_palette = palette index.
     */
    private fun handleTestIntent(i: Intent?) {
        val app = DekStreamApp.instance
        i?.getStringExtra("test_m3u")?.let { url ->
            if (app.store.profiles.value.isEmpty()) {
                val src = com.dekstream.tv.data.Source(name = "Test playlist", type = com.dekstream.tv.data.SourceType.M3U_URL, url = url,
                    epgUrl = i.getStringExtra("test_epg") ?: "")
                app.store.saveSource(src)
                val p = com.dekstream.tv.data.Profile(name = "Tester", color = com.dekstream.tv.data.Profile.Colors[0], sourceId = src.id)
                app.store.saveProfile(p)
                app.start(p)
            } else if (app.session == null) app.store.profiles.value.firstOrNull()?.let { app.start(it) }
        }
        i?.getStringExtra("test_game")?.let { app.pendingTestGame = it }
        i?.getStringExtra("test_tab")?.let { t -> runCatching { Tab.valueOf(t) }.getOrNull()?.let { app.pendingTab = it } }
        if (i?.getBooleanExtra("test_update", false) == true) {
            com.dekstream.tv.data.Updater.available = com.dekstream.tv.data.UpdateInfo("v9.9.9", "9.9.9",
                "Sports day picker\nAdult content filter\nFavorites screen", "", 0)
            com.dekstream.tv.data.Updater.dismissed = false
        }
        i?.getIntExtra("test_palette", -1)?.takeIf { it >= 0 }?.let { Appearance.paletteIdx = it }
    }

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    companion object { const val EXTRA_PLAY_STREAM = "play_stream" }

    /** Pressing Home while watching drops the video into a picture-in-picture window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (DekStreamApp.instance.playerActive) enterPip()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        DekStreamApp.instance.inPip = isInPictureInPictureMode
    }
}

@Composable
private fun Root() {
    val app = DekStreamApp.instance
    val profiles by app.store.profiles.collectAsState()
    val session = app.session
    val pending = app.pendingProfile

    when {
        profiles.isEmpty() -> FirstProfileScreen()
        pending != null -> SourceEditorScreen(existing = null, firstRun = true, onDone = {}, onCancel = { app.switchProfile() })
        session == null -> ProfilePickerScreen()
        else -> key(session.key) { KidsGate { SignedIn() } }
    }
}

@Composable
private fun SignedIn() {
    val app = DekStreamApp.instance
    val state = remember { AppState() }
    val top = state.stack.lastOrNull()

    UpdatePrompt()

    // Debug test hook: jump to a tab.
    val pendingTab = app.pendingTab
    LaunchedEffect(pendingTab) {
        val t = pendingTab ?: return@LaunchedEffect
        app.pendingTab = null
        state.stack.clear(); state.tab = t
    }

    // Debug test hook: open the first game of a league (test_game=nba).
    val testGame = app.pendingTestGame
    LaunchedEffect(testGame) {
        val key = testGame ?: return@LaunchedEffect
        app.pendingTestGame = null
        val l = app.sports.league(key) ?: return@LaunchedEffect
        val game = run {
            var found: com.dekstream.tv.data.Game? = null
            for (d in 0..6) {
                val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR, d)
                found = app.sports.scoreboard(l, java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(c.time)).firstOrNull()
                if (found != null) break
            }
            found
        } ?: return@LaunchedEffect
        state.stack.clear(); state.tab = Tab.Sports
        state.push(Route.GameDetail(game))
    }

    // Open a channel from a reminder notification.
    val pending = app.pendingPlayStream
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        app.pendingPlayStream = null
        val repo = app.repo ?: return@LaunchedEffect
        runCatching { repo.allChannels().firstOrNull { it.streamId == id } }.getOrNull()?.let {
            app.prefs.markChannelWatched(it.streamId)
            state.play(repo.liveRequest(it))
        }
    }

    BackHandler(enabled = top != null || state.tab != Tab.Home) {
        if (!state.pop()) state.tab = Tab.Home
    }

    // Sports heads-ups over every screen.
    SportsAlertWatcher()
    val alertScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(app.alertWatchRequested) {
        if (app.alertWatchRequested) { app.alertWatchRequested = false; app.alert?.let { watchAlert(state, it) } }
    }

    state.lockedPending?.let { p ->
        ParentGate(requireSetup = false, reason = "${p.request.title} is locked.", onDismiss = { state.lockedPending = null }) {
            state.lockedPending = null
            state.push(p)
        }
    }

    Box(Modifier.fillMaxSize()) {
    when (top) {
        null -> Shell(state)
        is Route.Player -> PlayerScreen(top.request, top.zapList, onExit = { state.pop() })
        is Route.MovieDetail -> MovieDetailScreen(top.vod, state)
        is Route.SeriesDetail -> SeriesDetailScreen(top.series, state)
        is Route.VodCategory -> VodCategoryScreen(top.category, top.isSeries, state)
        is Route.GameDetail -> GameDetailScreen(top.game, state)
        Route.SportsSettings -> SportsSettingsScreen(state)
        is Route.Multiview -> MultiviewScreen(top.channels, top.games, onExit = { state.pop() })
        is Route.KidsRules -> KidsRulesScreen(top.profileId, state)
        Route.AppearanceSettings -> AppearanceScreen(state)
        Route.HomeSettings -> HomeLayoutScreen(state)
        Route.ChannelManager -> ChannelManagerScreen(state)
        is Route.EditSource -> SourceEditorScreen(existing = top.source, firstRun = false, onDone = { state.pop() }, onCancel = { state.pop() })
    }
    SportsAlertCard(state) { a -> alertScope.launch { watchAlert(state, a) } }
    }
}

/** Locks a kids profile when paused, at bedtime, or out of time (re-checked every 30 s). */
@Composable
private fun KidsGate(content: @Composable () -> Unit) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    if (!session.profile.kids) { content(); return }
    var lock by remember { mutableStateOf(session.settings.lockReason()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(30_000); lock = session.settings.lockReason() }
    }
    val l = lock
    if (l != null) KidsLockScreen(l, session.settings, session.profile.name, onUnlocked = { lock = session.settings.lockReason() })
    else content()
}

/**
 * TVs report a small layout width (the Shield: 960 dp at 1080p), so a layout made for tablets looks
 * zoomed in. On TV we lay out as if the screen were ~1400 dp wide, the scale TV apps are designed for.
 */
@Composable
private fun TvScale(content: @Composable () -> Unit) {
    val app = DekStreamApp.instance
    val base = androidx.compose.ui.platform.LocalDensity.current
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val scale = if (app.isTv && widthDp in 1..1399) widthDp / 1400f else 1f
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * scale, base.fontScale),
    ) { content() }
}
