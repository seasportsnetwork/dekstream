package com.dekstream.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.data.Stremio
import kotlinx.coroutines.launch

/** Settings > Movie & show details: the TMDB key that powers cast photos, trailers, reviews and Top 10 "this week". */
@Composable
fun AddonsScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tmdb by remember { mutableStateOf(Stremio.tmdbKey(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent, focusedTextColor = DS.Text, unfocusedTextColor = DS.Text)
    fun saveAndTest(key: String) {
        Stremio.setTmdbKey(ctx, key)
        if (key.isBlank()) { status = "Key removed. Details fall back to your playlist's info."; return }
        status = "Checking…"
        scope.launch { status = if (Stremio.testTmdbKey(key)) "✓ Key works. Cast photos, trailers and reviews are on." else "✗ TMDB didn't accept that key. Use the API Key (v3), not the long read token." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 760.dp)) {
        ScreenTitle("Movie & show details") { state.pop() }
        Text("A free TMDB key adds cast photos and character names, writers, trailers, reviews and \"Top 10 this week\" to your playlist's movies and series. " +
            "Without one, DekTV uses what your playlist provides.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        PhoneSetupCard("tmdb", onReceived = { o ->
            o.optString("key").trim().takeIf { it.isNotBlank() }?.let { tmdb = it; saveAndTest(it) }
        }, Modifier.fillMaxWidth().padding(top = 18.dp))
        OutlinedTextField(tmdb, { tmdb = it.trim() }, label = { Text("TMDB API key") }, singleLine = true, colors = colors,
            modifier = Modifier.tvTextInput().fillMaxWidth().padding(top = 16.dp))
        Text("Get one free: themoviedb.org → sign up → Settings → API → copy the \"API Key\".", color = DS.TextDim, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 16.dp)) {
            PillButton("Save and test", { saveAndTest(tmdb) }, Icons.Default.Check, primary = true)
        }
        status?.let { Text(it, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}
