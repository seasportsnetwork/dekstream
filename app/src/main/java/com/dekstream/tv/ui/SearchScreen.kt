package com.dekstream.tv.ui

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.Row

import androidx.compose.material.icons.filled.Mic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    var q by rememberSaveable { mutableStateOf("") }
    val favs by app.prefs.favorites.collectAsState()
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    menuFor?.let { c -> ChannelOptionsDialog(c, onDismiss = { menuFor = null }) }
    val channels by produceState<List<Channel>>(emptyList(), q) {
        delay(250) // debounce typing
        value = runCatching { repo.searchChannels(q) }.getOrDefault(emptyList())
    }
    // Guide programmes ("On TV"), using the whole-guide index.
    val guide by rememberLoad(repo.source.id) { repo.guideIndex() }
    val programmes by produceState<List<Pair<Channel, com.dekstream.tv.data.EpgIndex.Entry>>>(emptyList(), q, guide) {
        delay(300)
        val idx = guide.valueOrNull() ?: run { value = emptyList(); return@produceState }
        val byEpg = repo.allChannels().filter { it.epgChannelId != null }.groupBy { it.epgChannelId!!.lowercase() }
        value = idx.search(q).mapNotNull { (id, e) -> byEpg[id]?.firstOrNull()?.let { it to e } }
    }
    val movies = repo.searchLoadedVod(q)
    val shows = repo.searchLoadedSeries(q)

    // Voice search: Android's speech recognizer (the Shield remote's mic works here too).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { q = it }
    }
    val canVoice = remember {
        ctx.packageManager.queryIntentActivities(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    fun listen() {
        runCatching {
            voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel, show, team or movie"))
        }
    }
    // Opened by the mic/search key or "voice" from elsewhere: start listening right away.
    LaunchedEffect(DekStreamApp.instance.voiceSearchRequested) {
        if (DekStreamApp.instance.voiceSearchRequested && canVoice) { DekStreamApp.instance.voiceSearchRequested = false; listen() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search channels, movies, series") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent),
                modifier = Modifier.weight(1f),
            )
            if (canVoice) {
                Spacer(Modifier.width(10.dp))
                PillButton("Voice", { listen() }, Icons.Default.Mic, primary = true)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (movies.isNotEmpty()) {
                item { SectionTitle("Movies") }
                item {
                    LazyRow(horizontalArrangement = rowSpacing) {
                        items(movies, key = { it.streamId }) { v ->
                            PosterCard(v.name, v.poster, 120.dp, onClick = { state.push(Route.MovieDetail(v)) })
                        }
                    }
                }
            }
            if (shows.isNotEmpty()) {
                item { SectionTitle("Series") }
                item {
                    LazyRow(horizontalArrangement = rowSpacing) {
                        items(shows, key = { it.seriesId }) { s ->
                            PosterCard(s.name, s.cover, 120.dp, onClick = { state.push(Route.SeriesDetail(s)) })
                        }
                    }
                }
            }
            if (programmes.isNotEmpty()) {
                item { SectionTitle("On TV") }
                items(programmes, key = { "p" + it.first.streamId + "-" + it.second.startMs }) { (c, e) ->
                    val now = System.currentTimeMillis()
                    val live = now >= e.startMs && now < e.endMs
                    ChannelRowItem(
                        name = e.title, icon = c.icon, number = null,
                        now = (if (live) "Now" else gameTime(e.startMs)) + " · " + c.name,
                        next = null, nowProgress = null, favorite = false,
                        onClick = {
                            if (live) { app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c)) }
                            else {
                                app.reminders.add(app.session?.profile?.id ?: "", c,
                                    com.dekstream.tv.data.Program(e.title, e.desc, e.startMs, e.endMs, false))
                            }
                        },
                        onLongClick = {},
                    )
                }
                item { Text("Tap something on now to watch it, or something later to set a reminder.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            }
            if (channels.isNotEmpty()) {
                item { SectionTitle("Channels") }
                items(channels, key = { it.streamId }) { c ->
                    ChannelRowItem(
                        name = c.name, icon = c.icon, number = null, now = null, next = null, nowProgress = null,
                        favorite = c.streamId in favs,
                        onClick = {
                            app.prefs.markChannelWatched(c.streamId)
                            state.play(repo.liveRequest(c), channels)
                        },
                        onLongClick = { menuFor = c },
                    )
                }
            }
            if (q.length >= 2 && channels.isEmpty() && movies.isEmpty() && shows.isEmpty() && programmes.isEmpty()) {
                item { Text("No matches yet. Movie and series search covers the categories you've opened.", color = DS.TextDim, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
