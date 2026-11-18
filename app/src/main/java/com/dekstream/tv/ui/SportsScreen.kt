package com.dekstream.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.FavTeam
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.TeamSide
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val clockFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())

/** "7:30 PM", "Tomorrow 1:00 PM", "Sun Nov 23 4:25 PM" */
fun gameTime(ms: Long): String {
    if (ms <= 0) return ""
    val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR); val year = c.get(Calendar.YEAR)
    c.timeInMillis = ms
    val sameYear = c.get(Calendar.YEAR) == year
    return when {
        sameYear && c.get(Calendar.DAY_OF_YEAR) == today -> clockFmt.format(Date(ms))
        sameYear && c.get(Calendar.DAY_OF_YEAR) == today + 1 -> "Tomorrow " + clockFmt.format(Date(ms))
        else -> dayFmt.format(Date(ms)) + " " + clockFmt.format(Date(ms))
    }
}

fun Game.statusText(): String = when (state) {
    "pre" -> gameTime(startMs).ifBlank { detail }
    else -> detail
}

fun Game.hasFavorite(favs: List<FavTeam>) =
    favs.any { it.leagueKey == league.key && (it.teamId == home.id || it.teamId == away.id) }

@Composable
fun SportsScreen(state: AppState) {
    val app = DekStreamApp.instance
    val favs by app.prefs.favTeams.collectAsState()
    val leagueKeys by app.prefs.sportLeagues.collectAsState()
    val leagues = leagueKeys.mapNotNull { app.sports.league(it) }
    var refresh by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) } // null = all leagues
    var starFor by remember { mutableStateOf<Game?>(null) }

    LaunchedEffect(Unit) { while (true) { delay(60_000); refresh++ } }
    // Start fetching the full guide in the background so game pages open fast.
    LaunchedEffect(Unit) { app.repo?.let { r -> runCatching { r.guideIndex() } } }

    val mine by rememberLoad(favs, refresh) { app.sports.favoriteGames(favs) }
    val boards by rememberLoad(leagueKeys, refresh) {
        coroutineScope { leagues.map { l -> async { l to app.sports.scoreboard(l) } }.awaitAll() }
    }

    val open: (Game) -> Unit = { state.push(Route.GameDetail(it)) }

    LazyVerticalGrid(
        GridCells.Adaptive(340.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sports", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, modifier = Modifier.focusRing()) { Icon(Icons.Default.Refresh, "Refresh", tint = DS.TextDim) }
                IconButton(onClick = { state.push(Route.SportsSettings) }, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Tune, "Choose sports and teams", tint = DS.TextDim)
                }
            }
        }

        // My teams strip
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionLabel("MY TEAMS")
                when (val m = mine) {
                    is Load.Ok -> if (favs.isEmpty()) {
                        Text("Star a team to follow it. Long-press any game, or tap ⚙ above.", color = DS.TextDim, fontSize = 14.sp)
                    } else if (m.value.isEmpty()) {
                        Text("No upcoming games for your teams.", color = DS.TextDim, fontSize = 14.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(m.value, key = { "fav-" + it.first.key + it.second.id }) { (team, g) ->
                                FavTeamCard(team, g, onClick = { open(g) }, onLongClick = { starFor = g })
                            }
                        }
                    }
                    else -> LoadView(m) {}
                }
            }
        }

        // League filter
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip("All", filter == null) { filter = null } }
                items(leagues, key = { it.key }) { l -> CategoryChip(l.short, filter == l.key) { filter = l.key } }
            }
        }

        when (val b = boards) {
            is Load.Ok -> {
                val groups = b.value.filter { filter == null || it.first.key == filter }
                val live = groups.flatMap { it.second }.filter { it.isLive }
                if (filter == null && live.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("LIVE NOW") }
                    items(live, key = { "live-" + it.id }) { g ->
                        GameRow(g, g.hasFavorite(favs), onClick = { open(g) }, onLongClick = { starFor = g })
                    }
                }
                groups.forEach { (l, games) ->
                    val list = if (filter == null) games.filter { !it.isLive } else games
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-" + l.key) {
                        SectionLabel(l.name.uppercase() + if (games.isEmpty()) " · NO GAMES TODAY" else "")
                    }
                    items(list, key = { l.key + "-" + it.id }) { g ->
                        GameRow(g, g.hasFavorite(favs), onClick = { open(g) }, onLongClick = { starFor = g })
                    }
                }
                if (leagues.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("No sports selected. Tap ⚙ to choose which leagues to show.", color = DS.TextDim)
                    }
                }
            }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { LoadView(b) {} }
        }
    }

    starFor?.let { g -> FavoriteTeamDialog(g, onDismiss = { starFor = null }) }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 2.dp),
    )
}

@Composable
fun TeamLogo(url: String?, size: Dp) {
    Box(Modifier.size(size)) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** Compact scoreboard row: two team lines on the left, status on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameRow(g: Game, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(DS.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TeamScoreLine(g.away, g, winner = isWinner(g, g.away, g.home))
            Spacer(Modifier.size(8.dp))
            TeamScoreLine(g.home, g, winner = isWinner(g, g.home, g.away))
        }
        Box(Modifier.padding(horizontal = 12.dp).size(1.dp, 44.dp).background(DS.Surface2))
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
            if (g.isLive) LiveBadge()
            Text(
                g.statusText(), color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 12.sp,
                maxLines = 2, modifier = Modifier.padding(top = 4.dp),
            )
            if (favorite) Icon(Icons.Default.Star, "Favorite team", tint = Color(0xFFFFC107), modifier = Modifier.padding(top = 4.dp).size(14.dp))
        }
    }
}

private fun isWinner(g: Game, me: TeamSide, other: TeamSide): Boolean {
    if (g.state != "post") return false
    val a = me.score?.toDoubleOrNull() ?: return false
    val b = other.score?.toDoubleOrNull() ?: return false
    return a > b
}

@Composable
private fun TeamScoreLine(t: TeamSide, g: Game, winner: Boolean) {
    val dim = g.state == "post" && !winner
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t.logo, 26.dp)
        Text(
            t.name, color = if (dim) DS.TextDim else DS.Text, fontSize = 15.sp,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
        )
        t.record?.let { Text("  $it", color = DS.TextDim, fontSize = 12.sp, maxLines = 1) }
        Spacer(Modifier.weight(1f))
        if (g.state != "pre" && t.score != null) {
            Text(t.score, color = if (dim) DS.TextDim else DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavTeamCard(team: FavTeam, g: Game, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val isHome = g.home.id == team.teamId
    val me = if (isHome) g.home else g.away
    val opp = if (isHome) g.away else g.home
    Row(
        Modifier.width(300.dp).focusRing(shape, 1.03f).clip(shape).background(DS.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(me.logo ?: team.logo, 40.dp)
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(team.name.substringAfterLast(' '), color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text((if (isHome) "vs " else "@ ") + opp.name, color = DS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (g.isLive) { LiveBadge(); HSpacer(6.dp) }
                Text(g.statusText(), color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (g.state != "pre" && me.score != null) {
            Text("${me.score}-${opp.score ?: ""}", color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Long-press a game: star either team. */
@Composable
fun FavoriteTeamDialog(g: Game, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val favs by app.prefs.favTeams.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DS.Surface,
        title = { Text("Favorite teams", color = DS.Text) },
        text = {
            Column {
                listOf(g.away, g.home).forEach { t ->
                    val on = favs.any { it.leagueKey == g.league.key && it.teamId == t.id }
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRing(shape, 1.02f).clip(shape)
                            .background(DS.Surface2)
                            .clickable { app.prefs.toggleFavTeam(FavTeam(g.league.key, t.id, t.name, t.abbr, t.logo)) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamLogo(t.logo, 32.dp)
                        Text(t.name, color = DS.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Icon(
                            if (on) Icons.Default.Star else Icons.Default.StarBorder, null,
                            tint = if (on) Color(0xFFFFC107) else DS.TextDim,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = DS.Accent) } },
    )
}
