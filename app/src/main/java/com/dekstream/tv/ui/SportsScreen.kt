package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.SportsRepo
import com.dekstream.tv.data.TeamSide
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE MMM d · h:mm a", Locale.getDefault())

@Composable
fun SportsScreen(state: AppState) {
    val app = DekStreamApp.instance
    var refresh by remember { mutableIntStateOf(0) }
    var league by rememberSaveable { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Game?>(null) }

    // Auto-refresh scores every 60 s while this tab is open.
    LaunchedEffect(Unit) { while (true) { delay(60_000); refresh++ } }

    val mine by rememberLoad(refresh) { app.sports.myTeamGames() }
    val (leagueName, leaguePath) = app.sports.leagues[league]
    val board by rememberLoad(league, refresh) { app.sports.scoreboard(leagueName, leaguePath) }

    LazyVerticalGrid(
        GridCells.Adaptive(300.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sports", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = DS.TextDim)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("My Teams") }
        when (val m = mine) {
            is Load.Ok -> if (m.value.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("No upcoming games for your teams.", color = DS.TextDim) }
            } else {
                items(m.value, key = { "mine-" + it.second.id }) { (team, g) ->
                    GameCard(g, width = Dp.Unspecified, label = team.label, onClick = { picked = g })
                }
            }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { LoadView(m) {} }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionTitle("Today")
                LazyRow(horizontalArrangement = rowSpacing) {
                    items(app.sports.leagues.size) { idx ->
                        CategoryChip(app.sports.leagues[idx].first, idx == league) { league = idx }
                    }
                }
            }
        }
        when (val b = board) {
            is Load.Ok -> if (b.value.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("No $leagueName games today.", color = DS.TextDim) }
            } else {
                items(b.value, key = { "b-" + it.id }) { g -> GameCard(g, width = Dp.Unspecified, onClick = { picked = g }) }
            }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { LoadView(b) {} }
        }
    }

    picked?.let { g -> GameChannelPicker(g, state, onDismiss = { picked = null }) }
}

@Composable
fun GameCard(g: Game, width: Dp, onClick: () -> Unit, label: String? = null) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier.fillMaxWidth())
            .focusRing(shape, 1.03f)
            .clip(shape)
            .background(DS.Surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label?.let { "${g.league} · $it" } ?: g.league, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (g.isLive) LiveBadge()
        }
        TeamLine(g.away, g, Modifier.padding(top = 8.dp))
        TeamLine(g.home, g, Modifier.padding(top = 6.dp))
        val status = when (g.state) {
            "pre" -> if (g.startMs > 0) dayFmt.format(Date(g.startMs)) else g.detail
            else -> g.detail
        }
        Text(status, color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (g.broadcasts.isNotEmpty()) {
            Text("📺 " + g.broadcasts.joinToString(", "), color = DS.TextDim, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun TeamLine(t: TeamSide, g: Game, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp)) {
            if (t.logo != null) AsyncImage(t.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Text(t.name, color = DS.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (g.state != "pre" && t.score != null) {
            Text(t.score, color = DS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Finds channels that likely carry the game and lets you pick one. */
@Composable
private fun GameChannelPicker(g: Game, state: AppState, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val matches by rememberLoad(g.id) { SportsRepo.matchChannels(g, repo.allChannels()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DS.Surface,
        title = { Text("${g.away.nickname} @ ${g.home.nickname}", color = DS.Text) },
        text = {
            LoadView(matches) { list ->
                if (list.isEmpty()) {
                    Text("Couldn't find a channel for this game. Try Search with the network name" +
                        (g.broadcasts.firstOrNull()?.let { " ($it)" } ?: "") + ".", color = DS.TextDim)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list, key = { it.streamId }) { c ->
                            val shape = RoundedCornerShape(8.dp)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).focusRing(shape, 1.02f).clip(shape)
                                    .background(DS.Surface2)
                                    .clickable {
                                        onDismiss()
                                        app.prefs.markChannelWatched(c.streamId)
                                        state.play(repo.liveRequest(c), list)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChannelLogo(c.icon, 36.dp)
                                Text(c.name, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = DS.TextDim) } },
    )
}
