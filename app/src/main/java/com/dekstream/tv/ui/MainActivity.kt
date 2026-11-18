package com.dekstream.tv.ui

import android.app.PictureInPictureParams
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dekstream.tv.DekStreamApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DekStreamTheme {
                Box(Modifier.fillMaxSize().background(DS.Bg)) { Root() }
            }
        }
    }

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
        else -> key(session.key) { SignedIn() }
    }
}

@Composable
private fun SignedIn() {
    val state = remember { AppState() }
    val top = state.stack.lastOrNull()

    BackHandler(enabled = top != null || state.tab != Tab.Home) {
        if (!state.pop()) state.tab = Tab.Home
    }

    when (top) {
        null -> Shell(state)
        is Route.Player -> PlayerScreen(top.request, top.zapList, onExit = { state.pop() })
        is Route.MovieDetail -> MovieDetailScreen(top.vod, state)
        is Route.SeriesDetail -> SeriesDetailScreen(top.series, state)
        is Route.VodCategory -> VodCategoryScreen(top.category, top.isSeries, state)
        is Route.GameDetail -> GameDetailScreen(top.game, state)
        Route.SportsSettings -> SportsSettingsScreen(state)
        is Route.EditSource -> SourceEditorScreen(existing = top.source, firstRun = false, onDone = { state.pop() }, onCancel = { state.pop() })
    }
}
