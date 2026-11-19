package com.dekstream.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import com.dekstream.tv.data.SportsRepo
import kotlinx.coroutines.launch
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
    val repo = app.repo
    val favs by app.prefs.favTeams.collectAsState()
    val leagueKeys by app.prefs.sportLeagues.collectAsState()
    val hide by app.prefs.hideScores.collectAsState()
    val pinnedIds by app.prefs.sportsChannels.collectAsState()
    val leagues = leagueKeys.mapNotNull { app.sports.league(it) }
    val teamLeagues = leagues.filter { !it.racing }
    var refresh by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) } // null = all leagues
    var starFor by remember { mutableStateOf<Game?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Game>() }
    var launching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { while (true) { delay(60_000); refresh++ } }
    LaunchedEffect(Unit) { repo?.let { r -> runCatching { r.guideIndex() } } }

    val yesterday = remember {
        java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() - 86_400_000L))
    }
    val mine by rememberLoad(favs, refresh) { app.sports.favoriteGames(favs) }
    val boards by rememberLoad(leagueKeys, refresh) {
        coroutineScope {
            teamLeagues.map { l ->
                async { Triple(l, app.sports.scoreboard(l), app.sports.scoreboard(l, yesterday)) }
            }.awaitAll()
        }
    }
    val f1 by rememberLoad(leagueKeys.contains("f1"), refresh) { if (leagueKeys.contains("f1")) app.sports.f1Weekends() else emptyList() }
    val allChannels by rememberLoad(repo?.source?.id) { repo?.allChannels().orEmpty() }
    val pinned = allChannels.valueOrNull().orEmpty().let { list -> pinnedIds.mapNotNull { id -> list.firstOrNull { it.streamId == id } } }

    val open: (Game) -> Unit = { g ->
        if (selecting) {
            if (selected.any { it.id == g.id }) selected.removeAll { it.id == g.id } else if (selected.size < 4) selected += g
        } else state.push(Route.GameDetail(g))
    }

    // Sports multiview: best channel for each chosen game, then open them side by side.
    fun launchMultiview() {
        val r = repo ?: return
        launching = true
        scope.launch {
            val chans = r.allChannels()
            val idx = r.guideIndex()
            val pairs = selected.mapNotNull { g -> SportsRepo.matchChannels(g, chans, idx).firstOrNull()?.channel?.let { it to g } }
            launching = false
            if (pairs.isNotEmpty()) {
                selecting = false; selected.clear()
                state.push(Route.Multiview(pairs.map { it.first }, pairs.map { it.second }))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(340.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (selecting) 96.dp else 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sports", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { app.prefs.setHideScores(!hide) }, modifier = Modifier.focusRing()) {
                        Icon(if (hide) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (hide) "Show scores" else "Hide scores",
                            tint = if (hide) DS.Accent else DS.TextDim)
                    }
                    IconButton(onClick = { selecting = !selecting; selected.clear() }, modifier = Modifier.focusRing()) {
                        Icon(Icons.Default.GridView, "Watch several games", tint = if (selecting) DS.Accent else DS.TextDim)
                    }
                    IconButton(onClick = { refresh++ }, modifier = Modifier.focusRing()) { Icon(Icons.Default.Refresh, "Refresh", tint = DS.TextDim) }
                    IconButton(onClick = { state.push(Route.SportsSettings) }, modifier = Modifier.focusRing()) {
                        Icon(Icons.Default.Tune, "Choose sports and teams", tint = DS.TextDim)
                    }
                }
            }
            if (selecting) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Pick up to 4 games to watch at once.", color = DS.Accent, fontSize = 14.sp)
                }
            }

            // My teams
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionLabel("MY TEAMS")
                    when (val m = mine) {
                        is Load.Ok -> if (favs.isEmpty()) {
                            Text("Star a team to follow it. Long-press any game, or tap the sliders icon above.", color = DS.TextDim, fontSize = 14.sp)
                        } else if (m.value.isEmpty()) {
                            Text("No upcoming games for your teams.", color = DS.TextDim, fontSize = 14.sp)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(m.value, key = { "fav-" + it.first.key + it.second.id }) { (team, g) ->
                                    FavTeamCard(team, g, hide, selected.any { it.id == g.id }, onClick = { open(g) }, onLongClick = { starFor = g })
                                }
                            }
                        }
                        else -> LoadView(m) {}
                    }
                }
            }

            // Your channels
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionLabel("YOUR CHANNELS")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(pinned, key = { "pin-" + it.streamId }) { c ->
                            PinnedChannelCard(c,
                                onClick = { r -> app.prefs.markChannelWatched(c.streamId); state.play(r.liveRequest(c), pinned) },
                                onLongClick = { app.prefs.toggleSportsChannel(c.streamId) })
                        }
                        item {
                            val shape = RoundedCornerShape(14.dp)
                            Column(
                                Modifier.width(120.dp).height(96.dp).focusRing(shape, 1.04f).clip(shape).background(DS.Surface)
                                    .clickable { picking = true },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Add, null, tint = DS.TextDim)
                                Text("Add channel", color = DS.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                    if (pinned.isEmpty()) Text("Pin the sports channels you use most. Long-press one to unpin it.", color = DS.TextDim, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }

            // F1
            val weekends = f1.valueOrNull().orEmpty()
            if (leagueKeys.contains("f1") && (filter == null || filter == "f1") && weekends.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("FORMULA 1") }
                items(weekends, key = { "f1-" + it.id }) { w -> F1WeekendCard(w) }
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
                    if (live.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("LIVE NOW") }
                        items(live, key = { "live-" + it.id }) { g ->
                            GameRow(g, g.hasFavorite(favs), hide, selected.any { it.id == g.id }, onClick = { open(g) }, onLongClick = { starFor = g })
                        }
                    }
                    groups.forEach { (l, today, yday) ->
                        val later = today.filter { !it.isLive }
                        if (later.isEmpty() && yday.isEmpty()) return@forEach
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h-" + l.key) { SectionLabel(l.name.uppercase()) }
                        if (later.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "t-" + l.key) { SubLabel("Today") }
                            items(later, key = { l.key + "-t-" + it.id }) { g ->
                                GameRow(g, g.hasFavorite(favs), hide, selected.any { it.id == g.id }, onClick = { open(g) }, onLongClick = { starFor = g })
                            }
                        }
                        if (yday.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "y-" + l.key) { SubLabel("Yesterday") }
                            items(yday, key = { l.key + "-y-" + it.id }) { g ->
                                GameRow(g, g.hasFavorite(favs), hide, false, onClick = { if (!selecting) state.push(Route.GameDetail(g)) }, onLongClick = { starFor = g })
                            }
                        }
                    }
                    if (teamLeagues.isEmpty() && !leagueKeys.contains("f1")) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("No sports selected. Tap the sliders icon to choose which leagues to show.", color = DS.TextDim)
                        }
                    }
                }
                else -> item(span = { GridItemSpan(maxLineSpan) }) { LoadView(b) {} }
            }
        }

        // Sports multiview bar
        if (selecting) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(DS.Surface).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.size} of 4 games", color = DS.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selecting = false; selected.clear() }) { Text("Cancel", color = DS.TextDim) }
                Button(
                    onClick = { launchMultiview() }, enabled = selected.isNotEmpty() && !launching,
                    colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                    modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                ) { Text(if (launching) "Finding channels…" else "Watch together") }
            }
        }
    }

    starFor?.let { g -> FavoriteTeamDialog(g, onDismiss = { starFor = null }) }
    if (picking) ChannelPickerDialog(onPick = { c -> if (c.streamId !in pinnedIds) app.prefs.toggleSportsChannel(c.streamId); picking = false }, onDismiss = { picking = false })
}

@Composable
private fun SubLabel(text: String) {
    Text(text, color = DS.TextDim.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, start = 2.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedChannelCard(c: com.dekstream.tv.data.Channel, onClick: (com.dekstream.tv.data.Repo) -> Unit, onLongClick: () -> Unit) {
    val repo = DekStreamApp.instance.repo ?: return
    val now by produceState<com.dekstream.tv.data.Program?>(null, c.streamId) { value = repo.nowNext(c).firstOrNull { it.isLive() } }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(200.dp).height(96.dp).focusRing(shape, 1.04f).clip(shape).background(DS.Surface)
            .tvClickable(onClick = { onClick(repo) }, onLongClick = onLongClick).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.icon, 36.dp)
            Text(c.name, color = DS.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
        }
        Text(now?.let { "Now · ${it.title}" } ?: "No guide info", color = DS.TextDim, fontSize = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun F1WeekendCard(w: com.dekstream.tv.data.RaceWeekend) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DS.Surface).padding(14.dp)) {
        Text(w.name, color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        w.circuit?.let { Text(it, color = DS.TextDim, fontSize = 12.sp) }
        Spacer(Modifier.size(8.dp))
        w.sessions.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (s.state == "in") { LiveBadge(); HSpacer(8.dp) }
                Text(
                    when (s.state) {
                        "pre" -> gameTime(s.startMs)
                        "post" -> s.leader?.let { "Won by $it" } ?: "Finished"
                        else -> s.detail
                    },
                    color = if (s.state == "in") DS.Live else DS.TextDim, fontSize = 13.sp,
                )
            }
        }
    }
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
fun GameRow(g: Game, favorite: Boolean, hideScores: Boolean = false, selected: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(if (selected) DS.AccentSoft else DS.Surface)
            .then(if (selected) Modifier.border(2.dp, DS.Accent, shape) else Modifier)
            .tvClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TeamScoreLine(g.away, g, winner = !hideScores && isWinner(g, g.away, g.home), hideScores)
            Spacer(Modifier.size(8.dp))
            TeamScoreLine(g.home, g, winner = !hideScores && isWinner(g, g.home, g.away), hideScores)
            if (g.isLive && !hideScores && (g.redZone || g.situation != null)) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (g.redZone) RedZonePill()
                    g.situation?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = if (g.redZone) 8.dp else 0.dp)) }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp).size(1.dp, 44.dp).background(DS.Surface2))
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
            if (g.isLive) LiveBadge()
            Text(
                if (hideScores && g.state == "post") "Final" else g.statusText(), color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 12.sp,
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
private fun TeamScoreLine(t: TeamSide, g: Game, winner: Boolean, hideScores: Boolean = false) {
    val dim = g.state == "post" && !winner && !hideScores
    val hasBall = g.isLive && g.possessionId != null && g.possessionId == t.id
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t.logo, 26.dp)
        Text(
            t.name, color = if (dim) DS.TextDim else DS.Text, fontSize = 15.sp,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
        )
        t.record?.let { Text("  $it", color = DS.TextDim, fontSize = 12.sp, maxLines = 1) }
        if (hasBall && !hideScores) Text("  ●", color = Color(0xFFFFC107), fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        if (g.state != "pre" && t.score != null) {
            Text(if (hideScores) "–" else t.score, color = if (dim) DS.TextDim else DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavTeamCard(team: FavTeam, g: Game, hideScores: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val isHome = g.home.id == team.teamId
    val me = if (isHome) g.home else g.away
    val opp = if (isHome) g.away else g.home
    Row(
        Modifier.width(300.dp).focusRing(shape, 1.03f).clip(shape).background(if (selected) DS.AccentSoft else DS.Surface)
            .then(if (selected) Modifier.border(2.dp, DS.Accent, shape) else Modifier)
            .tvClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp),
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
        if (g.state != "pre" && me.score != null && !hideScores) {
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

@Composable
fun RedZonePill() {
    Text(
        "RED ZONE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFD7263D)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
