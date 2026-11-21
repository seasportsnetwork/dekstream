package com.dekstream.tv.ui

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.material.icons.filled.LiveTv

import androidx.compose.material.icons.filled.BarChart

import androidx.compose.foundation.lazy.LazyRow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.ChannelMatch
import com.dekstream.tv.data.EpgIndex
import com.dekstream.tv.data.FavTeam
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.SportsRepo
import com.dekstream.tv.data.TeamSide
import kotlinx.coroutines.delay

@Composable
fun GameDetailScreen(initial: Game, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    var game by remember { mutableStateOf(initial) }
    val epgStatus by app.epg.status.collectAsState()
    val hide by app.prefs.hideScores.collectAsState()
    val pinned by app.prefs.sportsChannels.collectAsState()
    val box by rememberLoad(initial.id, game.detail) { app.sports.boxScore(game) }
    val plays by rememberLoad(initial.id, game.detail) { if (game.state == "pre") emptyList() else app.sports.scoringPlays(game) }
    BackHandler { state.pop() }

    // Live score updates.
    LaunchedEffect(initial.id) {
        while (game.state != "post") { delay(45_000); game = runCatching { app.sports.refresh(game) }.getOrDefault(game) }
    }

    // Find channels fast: names + any cached guide first, then the full guide (saved on the device),
    // and only ask the provider channel-by-channel when there's no full guide. All off the main thread.
    var finishing by remember { mutableStateOf(true) }
    val matches by produceState<Load<List<ChannelMatch>>>(Load.Loading, initial.id) {
        val all = runCatching { repo.allChannels() }.getOrElse { value = Load.Err(it.message ?: "Couldn't load channels"); finishing = false; return@produceState }
        var current = SportsRepo.matchChannelsAsync(initial, all, app.epg.takeIf { it.isLoaded })
        value = Load.Ok(current)
        val idx = runCatching { repo.guideIndex() }.getOrNull()
        if (idx != null) {
            current = SportsRepo.matchChannelsAsync(initial, all, idx)
            value = Load.Ok(current)
        }
        if (idx == null || current.none { it.confirmed }) {
            val at = if (initial.isLive) System.currentTimeMillis() else initial.startMs + 15 * 60_000
            val quick = kotlinx.coroutines.coroutineScope {
                current.filter { !it.confirmed }.take(24).map { m ->
                    async {
                        val progs = runCatching { repo.fullEpg(m.channel) }.getOrDefault(emptyList())
                        val p = progs.firstOrNull { at >= it.startMs && at < it.endMs }
                        if (p != null && SportsRepo.programmeShows(initial, p.title, p.description))
                            m.copy(programme = com.dekstream.tv.data.EpgIndex.Entry(p.title, p.description, p.startMs, p.endMs), confirmed = true, score = m.score + 150)
                        else m
                    }
                }.awaitAll()
            }
            val byId = quick.associateBy { it.channel.streamId }
            current = current.map { byId[it.channel.streamId] ?: it }.sortedByDescending { it.score }
            value = Load.Ok(current)
        }
        finishing = false
    }
    var tab by rememberSaveable(initial.id) { mutableIntStateOf(0) }
    var filt by rememberSaveable(initial.id) { mutableIntStateOf(0) }

    val list = matches.valueOrNull().orEmpty()
    val confirmed = list.filter { it.confirmed }
    val guesses = list.filter { !it.confirmed }
    val best = confirmed.firstOrNull() ?: guesses.firstOrNull()

    fun play(m: ChannelMatch, from: List<ChannelMatch>) {
        app.prefs.markChannelWatched(m.channel.streamId)
        state.play(repo.liveRequest(m.channel), from.map { it.channel })
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(
            Modifier.fillMaxSize().align(Alignment.TopCenter).widthIn(max = 1100.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            item {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text)
                    }
                    Text(game.league.name, color = DS.TextDim, fontSize = 15.sp)
                }
            }
            item { Scoreboard(game, hide) }
            if (game.isLive && !hide && (game.redZone || game.situation != null || game.lastPlay != null)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(DS.Surface).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (game.redZone) { RedZonePill(); HSpacer(8.dp) }
                            game.situation?.let { Text(it, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                        }
                        game.lastPlay?.let { Text("Last play: $it", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item {
                Button(
                    onClick = { best?.let { play(it, if (it.confirmed) confirmed else guesses) } },
                    enabled = best != null,
                    colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(56.dp).focusRing(RoundedCornerShape(14.dp), 1.02f),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(
                        when {
                            best != null -> "  Watch · ${best.channel.name}"
                            matches is Load.Loading -> "  Finding channels…"
                            else -> "  No channel found"
                        },
                        fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                val extra = listOfNotNull(
                    game.broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "TV: $it" },
                    game.venue,
                ).joinToString("  ·  ")
                if (extra.isNotBlank()) Text(extra, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }

            // Tabs: Sources first, stats inside their own tab.
            item {
                Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(
                        listOf(
                            (if (list.isEmpty()) "Sources" else "Sources (${list.size})") to Icons.Default.LiveTv,
                            "Stats" to Icons.Default.BarChart,
                        ),
                        tab,
                    ) { tab = it }
                    if (finishing) {
                        CircularProgressIndicator(color = DS.Accent, modifier = Modifier.padding(start = 14.dp).size(16.dp), strokeWidth = 2.dp)
                        Text(if (list.isEmpty()) "  Finding channels…" else "  Checking your guide…", color = DS.TextDim, fontSize = 13.sp)
                    }
                }
            }
            if (tab == 0) {
                val hdPlus = { m: ChannelMatch -> SportsRepo.qualityTags(m.channel.name).any { it == "4K" || it == "FHD" } }
                val filters = listOf(
                    "All" to list.size,
                    "In your guide" to confirmed.size,
                    "FHD / 4K" to list.count(hdPlus),
                    "Pinned" to list.count { it.channel.streamId in pinned },
                )
                item {
                    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filters.size) { i ->
                            val (label, n) = filters[i]
                            Pill(if (i == 0) label else "$label · $n", filt == i, onClick = { filt = i })
                        }
                    }
                }
                val shown = when (filt) {
                    1 -> confirmed
                    2 -> list.filter(hdPlus)
                    3 -> list.filter { it.channel.streamId in pinned }
                    else -> list
                }
                val shownConfirmed = shown.filter { it.confirmed }
                val shownGuesses = shown.filter { !it.confirmed }
                if (shownConfirmed.isNotEmpty()) {
                    item { SectionLabel("CONFIRMED IN YOUR GUIDE") }
                    items(shownConfirmed, key = { "c" + it.channel.streamId }) { m ->
                        MatchRow(m, m.channel.streamId in pinned, onPin = { app.prefs.toggleSportsChannel(m.channel.streamId) }) { play(m, shownConfirmed) }
                    }
                }
                if (shownGuesses.isNotEmpty()) {
                    item { SectionLabel(if (shownConfirmed.isEmpty()) "POSSIBLE CHANNELS · NOT IN GUIDE" else "OTHER POSSIBLE CHANNELS") }
                    items(shownGuesses, key = { "g" + it.channel.streamId }) { m ->
                        MatchRow(m, m.channel.streamId in pinned, onPin = { app.prefs.toggleSportsChannel(m.channel.streamId) }) { play(m, shownGuesses) }
                    }
                }
                if (!finishing && shown.isEmpty()) {
                    item {
                        Text(
                            when {
                                list.isEmpty() -> "No channel in your guide mentions this game yet. Guides usually list games an hour or two before they start."
                                filt == 3 -> "None of your pinned channels match. Hold OK on a channel to pin it."
                                else -> "Nothing matches this filter."
                            },
                            color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
                if (epgStatus == EpgIndex.Status.Failed) {
                    item {
                        Text("Couldn't download your provider's full guide, so these are name matches only.",
                            color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            } else {
                when {
                    hide -> item { Text("Scores are hidden. Turn off \"Hide scores\" in Sports to see stats.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp)) }
                    game.state == "pre" -> item { Text("Stats show up once the game starts.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp)) }
                    else -> {
                        val pl = plays.valueOrNull().orEmpty()
                        val bs = box.valueOrNull()
                        if (pl.isNotEmpty()) item { CatchMeUpCard(game, pl) }
                        if (bs != null) item { BoxScoreCard(game, bs) }
                        if (pl.isEmpty() && bs == null) {
                            item {
                                if (box is Load.Loading || plays is Load.Loading) LoadView(box) {}
                                else Text("No stats for this game yet.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(g: Game, hide: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DS.Surface).padding(vertical = 22.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamColumn(g.away, g, Modifier.weight(1f))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (g.state != "pre" && !hide) {
                Text("${g.away.score ?: "-"}  –  ${g.home.score ?: "-"}", color = DS.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            } else {
                Text("vs", color = DS.TextDim, fontSize = 22.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (g.isLive) { LiveBadge(); HSpacer(8.dp) }
                Text(g.statusText(), color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        TeamColumn(g.home, g, Modifier.weight(1f))
    }
}

@Composable
private fun TeamColumn(t: TeamSide, g: Game, modifier: Modifier) {
    val app = DekStreamApp.instance
    val favs by app.prefs.favTeams.collectAsState()
    val on = favs.any { it.leagueKey == g.league.key && it.teamId == t.id }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(t.logo, 64.dp)
        Text(t.name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        t.record?.let { Text(it, color = DS.TextDim, fontSize = 12.sp) }
        IconButton(
            onClick = { app.prefs.toggleFavTeam(FavTeam(g.league.key, t.id, t.name, t.abbr, t.logo)) },
            modifier = Modifier.focusRing(),
        ) {
            Icon(
                if (on) Icons.Default.Star else Icons.Default.StarBorder,
                if (on) "Unfavorite ${t.name}" else "Favorite ${t.name}",
                tint = if (on) Color(0xFFFFC107) else DS.TextDim,
            )
        }
    }
}

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MatchRow(m: ChannelMatch, isPinned: Boolean, onPin: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val p = m.programme
    val now = System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRing(shape, 1.015f).clip(shape).background(DS.Surface)
            .tvClickable(onClick = onClick, onLongClick = onPin).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ChannelLogo(m.channel.icon, 48.dp)
            SportsRepo.qualityTags(m.channel.name).forEach { tag -> QualityTag(tag) }
        }
        Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.channel.name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (m.confirmed) {
                    Icon(Icons.Default.CheckCircle, "Confirmed in guide", tint = Color(0xFF34C759),
                        modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }
            if (p != null) {
                val live = now >= p.startMs && now < p.endMs
                Text(
                    (if (live) "Now · " else "${fmtTime(p.startMs)} · ") + p.title,
                    color = DS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (live) {
                    LinearProgressIndicator(
                        progress = { ((now - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f) },
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = DS.TextDim, trackColor = DS.Surface2,
                    )
                }
            } else {
                Text("No guide info", color = DS.TextDim.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        }
        IconButton(onClick = onPin, modifier = Modifier.focusRing()) {
            Icon(if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin, if (isPinned) "Unpin from Your channels" else "Pin to Your channels",
                tint = if (isPinned) DS.Accent else DS.TextDim)
        }
        Icon(Icons.Default.PlayArrow, "Play", tint = DS.Text)
    }
}

@Composable
private fun QualityTag(text: String) {
    Text(
        text, color = DS.TextDim, fontSize = 10.sp,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(DS.Surface2)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)

/** Period-by-period score and team stats side by side. */
@Composable
private fun BoxScoreCard(g: Game, bs: com.dekstream.tv.data.BoxScore) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(DS.Surface).padding(14.dp)) {
        Text("Box score", color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val n = maxOf(bs.awayLines.size, bs.homeLines.size)
        if (n > 0) {
            val label = when {
                g.league.path.startsWith("baseball") -> "Inning"
                g.league.path.startsWith("hockey") || g.league.path.startsWith("soccer") -> "Period"
                else -> "Quarter"
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(label, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(2.2f))
                for (i in 1..n) Text(if (label == "Quarter" && i > 4) "OT" else "$i", color = DS.TextDim, fontSize = 12.sp,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("T", color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
            listOf(g.away to bs.awayLines, g.home to bs.homeLines).forEach { (t, lines) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(t.logo, 20.dp)
                        Text(t.abbr.ifBlank { t.nickname }, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                    for (i in 0 until n) Text(lines.getOrNull(i) ?: "-", color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text(t.score ?: "-", color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
        if (bs.stats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(g.away.abbr, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Text("Team stats", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
                Text(g.home.abbr, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            bs.stats.forEach { (label, a, h) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(a, color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text(label, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
                    Text(h, color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** "Catch me up": the latest scoring plays, newest first. */
@Composable
private fun CatchMeUpCard(g: Game, plays: List<com.dekstream.tv.data.ScoringPlay>) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(DS.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Catch me up", color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (g.isLive) "Latest scoring" else "Scoring plays", color = DS.TextDim, fontSize = 12.sp)
        }
        plays.takeLast(8).reversed().forEach { p ->
            val team = when (p.teamId) { g.home.id -> g.home; g.away.id -> g.away; else -> null }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp)) { if (team != null) TeamLogo(team.logo, 26.dp) }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(p.text, color = DS.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(p.period, p.clock).filter { it.isNotBlank() }.joinToString(" · "), color = DS.TextDim, fontSize = 12.sp)
                }
                if (p.awayScore.isNotBlank() || p.homeScore.isNotBlank()) {
                    Text("${p.awayScore}–${p.homeScore}", color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}
