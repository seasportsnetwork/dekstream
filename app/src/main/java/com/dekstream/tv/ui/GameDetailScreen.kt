package com.dekstream.tv.ui

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
    BackHandler { state.pop() }

    // Live score updates.
    LaunchedEffect(initial.id) {
        while (game.state != "post") { delay(45_000); game = runCatching { app.sports.refresh(game) }.getOrDefault(game) }
    }

    // Check every channel's guide (one xmltv download, cached 30 min), then rank.
    val matches by rememberLoad(initial.id) {
        val all = repo.allChannels()
        val idx = repo.guideIndex()
        SportsRepo.matchChannels(initial, all, idx)
    }

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

            if (matches is Load.Loading) {
                item {
                    Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = DS.Accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            if (epgStatus == EpgIndex.Status.Loading) "  Checking the guide on all your channels…" else "  Finding channels…",
                            color = DS.TextDim, fontSize = 14.sp,
                        )
                    }
                }
            }
            if (!hide && game.state != "pre") {
                val bs = box.valueOrNull()
                if (bs != null) item { BoxScoreCard(game, bs) }
            }
            if (confirmed.isNotEmpty()) {
                item { SectionLabel("WATCH ON · CONFIRMED IN YOUR GUIDE") }
                items(confirmed, key = { "c" + it.channel.streamId }) { m -> MatchRow(m, m.channel.streamId in pinned, onPin = { app.prefs.toggleSportsChannel(m.channel.streamId) }) { play(m, confirmed) } }
            }
            if (guesses.isNotEmpty()) {
                item { SectionLabel(if (confirmed.isEmpty()) "POSSIBLE CHANNELS · NOT IN GUIDE" else "OTHER POSSIBLE CHANNELS") }
                items(guesses, key = { "g" + it.channel.streamId }) { m -> MatchRow(m, m.channel.streamId in pinned, onPin = { app.prefs.toggleSportsChannel(m.channel.streamId) }) { play(m, guesses) } }
            }
            if (matches is Load.Ok && list.isEmpty()) {
                item {
                    Text(
                        "No channel in your guide mentions this game yet. Guides usually list games an hour or two before they start.",
                        color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            if (epgStatus == EpgIndex.Status.Failed) {
                item {
                    Text("Couldn't download your provider's full guide, so these are name matches only.",
                        color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
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
            .combinedClickable(onClick = onClick, onLongClick = onPin).padding(12.dp),
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
