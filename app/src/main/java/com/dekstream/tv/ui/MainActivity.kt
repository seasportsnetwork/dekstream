package com.dekstream.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
}

@Composable
private fun Root() {
    val app = DekStreamApp.instance
    var signedIn by remember { mutableStateOf(app.repo != null) }
    if (!signedIn) {
        LoginScreen(onSignedIn = { signedIn = true })
        return
    }
    val state = remember { AppState() }
    val top = state.stack.lastOrNull()

    BackHandler(enabled = top != null || state.tab != Tab.Home) {
        if (!state.pop()) state.tab = Tab.Home
    }

    when (top) {
        null -> Shell(state, onSignOut = {
            app.signOut()
            signedIn = false
        })
        is Route.Player -> PlayerScreen(top.request, top.zapList, onExit = { state.pop() })
        is Route.MovieDetail -> MovieDetailScreen(top.vod, state)
        is Route.SeriesDetail -> SeriesDetailScreen(top.series, state)
        is Route.VodCategory -> VodCategoryScreen(top.category, top.isSeries, state)
    }
}
