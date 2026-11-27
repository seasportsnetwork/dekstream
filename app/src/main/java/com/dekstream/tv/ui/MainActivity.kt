package com.dekstream.tv.ui

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import androidx.compose.material3.Text

import androidx.compose.foundation.layout.padding

import androidx.compose.ui.Alignment

import androidx.compose.ui.graphics.Color

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.runtime.mutableLongStateOf

import androidx.compose.foundation.layout.offset

import androidx.compose.ui.input.key.onPreviewKeyEvent

import android.Manifest
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            TvScale {
                DekStreamTheme {
                    // Landing on a text box never opens the keyboard: any keyboard request waits here
                    // until OK is pressed on the box (TvKeyboard.allowed), then goes through.
                    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                    androidx.compose.ui.platform.InterceptPlatformTextInput(
                        interceptor = { request, next ->
                            androidx.compose.runtime.snapshotFlow { TvKeyboard.allowed }.first { it }
                            next.startInputMethod(request)
                        },
                    ) {
                        Box(Modifier.fillMaxSize().background(DS.Bg)) { Root() }
                    }
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
        val gid = i?.getStringExtra(EXTRA_GAME_ID)
        val gl = i?.getStringExtra(EXTRA_GAME_LEAGUE)
        if (gid != null && gl != null) DekStreamApp.instance.pendingGame = Triple(gl, gid, i.getLongExtra(EXTRA_GAME_START, System.currentTimeMillis()))
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
        i?.getStringExtra("test_play")?.let { app.pendingTestPlay = it }
        if (i?.getBooleanExtra("test_multiview", false) == true) app.pendingTestRoute = "multiview"
        if (i?.getBooleanExtra("test_title", false) == true) app.pendingTestRoute = "title"
        i?.getStringExtra("test_collections")?.let { com.dekstream.tv.data.Stremio.setCollectionsUrl(app, it); app.pendingTestSection = 4 }
        i?.getIntExtra("test_sync", -1)?.takeIf { it >= 0 }?.let { DekStreamAppRef.syncMode = it; app.pendingTestRoute = "sync" }
        i?.getIntExtra("test_fav", 0)?.takeIf { it > 0 }?.let { app.pendingTestFav = it }
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

    /** Search / mic key on the remote: open Search and start listening. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH || keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            val app = DekStreamApp.instance
            if (app.session != null && !app.playerActive) {
                app.voiceSearchRequested = true
                app.pendingTab = Tab.Search
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_PLAY_STREAM = "play_stream"
        const val EXTRA_GAME_LEAGUE = "game_league"
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_GAME_START = "game_start"
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

    // Debug test hook: play the first channel whose name contains test_play, full screen.
    val testPlay = app.pendingTestPlay
    LaunchedEffect(testPlay) {
        val q = testPlay ?: return@LaunchedEffect
        val r = app.repo
        val all = runCatching { r?.allChannels() }.getOrNull().orEmpty()
        val c = all.firstOrNull { it.name.contains(q, ignoreCase = true) } ?: all.firstOrNull()
        if (c != null && r != null) { android.util.Log.i("DekTV", "SOAK playing ${c.name}"); state.play(r.liveRequest(c), all.take(50)) }
        app.pendingTestPlay = null
    }

    // Debug test hook: open a screen.
    val testRoute = app.pendingTestRoute
    LaunchedEffect(testRoute) {
        if (testRoute == "sync") { state.stack.clear(); state.tab = Tab.Settings; state.push(Route.Sync) }
        if (testRoute == "title") {
            state.push(Route.Title(com.dekstream.tv.data.MetaPreview("tt0903747", "series", "Breaking Bad", null, null, null, null, null, null, emptyList())))
        }
        if (testRoute == "multiview") {
            val chans = runCatching { app.repo?.allChannels() }.getOrNull().orEmpty().take(3)
            state.push(Route.Multiview(chans, emptyList()))
        }
        app.pendingTestRoute = null
    }

    // Debug test hook: favorite the first N channels.
    val testFav = app.pendingTestFav
    LaunchedEffect(testFav) {
        val n = testFav ?: return@LaunchedEffect
        val chans = runCatching { app.repo?.allChannels() }.getOrNull().orEmpty().take(n)
        chans.forEach { c -> if (c.streamId !in app.prefs.favorites.value) app.prefs.toggleFavorite(c.streamId) }
        chans.firstOrNull()?.let { app.prefs.markChannelWatched(it.streamId) }
        app.pendingTestFav = null
    }

    // Debug test hook: open the first game of a league (test_game=nba).
    val testGame = app.pendingTestGame
    LaunchedEffect(testGame) {
        val key = testGame ?: return@LaunchedEffect
        val l = app.sports.league(key) ?: run { app.pendingTestGame = null; return@LaunchedEffect }
        val game = run {
            var found: com.dekstream.tv.data.Game? = null
            for (d in 0..6) {
                val c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR, d)
                found = app.sports.scoreboard(l, java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(c.time)).firstOrNull()
                if (found != null) break
            }
            found
        } ?: run { app.pendingTestGame = null; return@LaunchedEffect }
        state.stack.clear(); state.tab = Tab.Sports
        state.push(Route.GameDetail(game))
        app.pendingTestGame = null // cleared last: clearing first would cancel this effect
    }

    // "Watch" on a game notification: find the game and play its best channel.
    val pendingGame = app.pendingGame
    LaunchedEffect(pendingGame) {
        val (lk, gid, start) = pendingGame ?: return@LaunchedEffect
        val league = app.sports.league(lk)
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(start))
        val g = league?.let { l -> runCatching { app.sports.scoreboard(l, date) }.getOrDefault(emptyList()).firstOrNull { it.id == gid } }
        if (g == null) { state.tab = Tab.Sports }
        else watchAlert(state, SportsAlert(gid + "open", "Watch", "", g))
        app.pendingGame = null // last: clearing first would cancel this effect
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
    // On Home with nothing open, Back once shows a hint; Back again within 2 s closes DekTV.
    var exitArmedAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    BackHandler(enabled = top == null && state.tab == Tab.Home) {
        val now = System.currentTimeMillis()
        if (now - exitArmedAt < 2_000) activity?.finish()
        else {
            exitArmedAt = now
            android.widget.Toast.makeText(activity, "Press Back again to exit DekTV", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Sports heads-ups over every screen.
    SportsAlertWatcher()
    state.titleMenu?.let { TitleMenu(it, state) { state.titleMenu = null } }
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

    // Start-up choice (once per launch): Home, the Guide, or straight into the last channel.
    LaunchedEffect(app.session?.key) {
        if (app.startupHandled || app.session == null) return@LaunchedEffect
        app.startupHandled = true
        when (com.dekstream.tv.data.PlaybackPrefs.startOn) {
            "guide" -> state.tab = Tab.Guide
            "last" -> app.prefs.recentChannels.value.firstOrNull()?.let { app.pendingPlayStream = it }
        }
    }

    // Burn-in protection: dim after 3 idle minutes (not while video plays) and nudge the layout a few pixels now and then.
    val pp = com.dekstream.tv.data.PlaybackPrefs
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    var shift by remember { mutableIntStateOf(0) }
    val videoOn = top is Route.Player || top is Route.Multiview
    LaunchedEffect(lastInput, videoOn, pp.burnIn) {
        dimmed = false
        if (!pp.burnIn || videoOn) return@LaunchedEffect
        kotlinx.coroutines.delay(3 * 60_000L); dimmed = true
    }
    LaunchedEffect(pp.burnIn) { while (pp.burnIn) { kotlinx.coroutines.delay(3 * 60_000L); shift = (shift + 1) % 4 } }
    val offsets = listOf(0 to 0, 2 to 1, -1 to 2, 1 to -2)

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
            lastInput = System.currentTimeMillis()
            if (dimmed) { dimmed = false; true } else false // the first key only wakes the screen
        },
    ) {
    Box(Modifier.fillMaxSize().then(
        if (pp.burnIn && !videoOn) Modifier.offset(offsets[shift].first.dp, offsets[shift].second.dp) else Modifier,
    )) {
    val saver = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    androidx.compose.runtime.LaunchedEffect(FocusMemory.restore) { if (FocusMemory.restore != null) { kotlinx.coroutines.delay(4000); FocusMemory.restore = null } }
    saver.SaveableStateProvider(if (top == null) "shell" else "r${state.stack.size}:${top.hashCode()}") {
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
        Route.Sync -> SyncScreen(state)
        Route.PlaybackSettings -> PlaybackSettingsScreen(state)
        Route.Addons -> AddonsScreen(state)
        is Route.Title -> TitleScreen(top.meta, state)
        is Route.Folder -> FolderScreen(top.folder, state)
        Route.ChannelManager -> ChannelManagerScreen(state)
        is Route.EditSource -> SourceEditorScreen(existing = top.source, firstRun = false, onDone = { state.pop() }, onCancel = { state.pop() })
    }
    }
    }
    SportsAlertCard(state) { a -> alertScope.launch { watchAlert(state, a) } }
    if (dimmed) Box(Modifier.fillMaxSize().background(Color(0xD9000000))) {
        Text("Press any button", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp,
            modifier = Modifier.align(listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.BottomStart)[shift]).padding(40.dp))
    }
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
