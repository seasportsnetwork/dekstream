package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.PlayRequest

@Composable
fun HomeScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val progress by app.prefs.progress.collectAsState()
    val favIds by app.prefs.favorites.collectAsState()
    val recentIds by app.prefs.recentChannels.collectAsState()
    val all by rememberLoad(Unit) { repo.allChannels() }
    val favTeams by app.prefs.favTeams.collectAsState()
    val sports by rememberLoad(favTeams) { app.sports.favoriteGames(favTeams) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dek", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Stream", color = DS.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Shortcuts (mainly for the phone layout, where these tabs aren't in the bottom bar)
        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Tab.Search, Tab.Multiview, Tab.Settings).forEach { t ->
                    val shape = RoundedCornerShape(20.dp)
                    Row(
                        Modifier.focusRing(shape).clip(shape).background(DS.Surface)
                            .clickable { state.tab = t }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon, null, tint = DS.TextDim, modifier = Modifier.size(18.dp))
                        Text(" ${t.label}", color = DS.Text, fontSize = 14.sp)
                    }
                }
            }
        }

        // My teams
        val games = sports.valueOrNull().orEmpty()
        if (games.isNotEmpty()) {
            item { SectionTitle("My Teams") }
            item {
                LazyRow(horizontalArrangement = rowSpacing) {
                    items(games, key = { it.first.key + it.second.id }) { (_, g) ->
                        Box(Modifier.width(340.dp)) {
                            GameRow(g, favorite = true, onClick = { state.push(Route.GameDetail(g)) }, onLongClick = {})
                        }
                    }
                }
            }
        }

        val cont = progress.values.sortedByDescending { it.updatedAt }
        if (cont.isNotEmpty()) {
            item { SectionTitle("Continue Watching") }
            item {
                LazyRow(horizontalArrangement = rowSpacing) {
                    items(cont, key = { it.key }) { p ->
                        PosterCard(
                            title = p.title, subtitle = p.subtitle, image = p.poster, width = 130.dp,
                            progress = p.fraction,
                            onClick = {
                                state.play(PlayRequest(p.url, p.title, p.subtitle, isLive = false,
                                    progressKey = p.key, poster = p.poster, startPositionMs = p.positionMs))
                            },
                            onLongClick = { app.prefs.removeProgress(p.key) },
                        )
                    }
                }
            }
        }

        val channels = all.valueOrNull().orEmpty()
        val byId = channels.associateBy { it.streamId }
        val favs = favIds.mapNotNull { byId[it] }
        val recent = recentIds.mapNotNull { byId[it] }

        if (favs.isNotEmpty()) {
            item { SectionTitle("Favorite Channels") }
            item { ChannelStrip(favs, state) }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recently Watched") }
            item { ChannelStrip(recent, state) }
        }
        if (all is Load.Loading) item { LoadView(all) {} }
        if (all is Load.Err) item { LoadView(all) {} }
        if (favs.isEmpty() && recent.isEmpty() && cont.isEmpty() && all is Load.Ok) {
            item {
                Text(
                    "Start watching from Live TV, Movies or Series. Your favorites and progress will show up here. " +
                        "Long-press a channel to favorite it.",
                    color = DS.TextDim, modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChannelStrip(list: List<com.dekstream.tv.data.Channel>, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    LazyRow(horizontalArrangement = rowSpacing) {
        items(list, key = { it.streamId }) { c ->
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.width(120.dp).focusRing(shape).clip(shape).background(DS.Surface)
                    .clickable {
                        app.prefs.markChannelWatched(c.streamId)
                        state.play(repo.liveRequest(c), list)
                    }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChannelLogo(c.icon, 64.dp)
                Text(c.name, color = DS.Text, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
