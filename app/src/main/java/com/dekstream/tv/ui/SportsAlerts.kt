package com.dekstream.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.SportsRepo
import kotlinx.coroutines.delay

/** A heads-up about one of your teams' games. */
data class SportsAlert(val key: String, val title: String, val message: String, val game: Game, val at: Long = System.currentTimeMillis())

private data class Snap(val state: String, val home: String?, val away: String?)

/**
 * Watches your favorite teams' games in the background (every 45 s) and raises an alert when a game
 * starts, a team scores (or takes the lead), or it ends. Scores are left out when "hide scores" is on.
 */
@Composable
fun SportsAlertWatcher() {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    if (session.profile.kids) return
    LaunchedEffect(session.key) {
        val snaps = HashMap<String, Snap>()
        val lastAlert = HashMap<String, Long>()
        var first = true
        while (true) {
            val favs = session.prefs.favTeams.value
            val hide = session.prefs.hideScores.value
            val games = runCatching { app.sports.favoriteGames(favs) }.getOrDefault(emptyList()).map { it.second }.distinctBy { it.id }
            for (g in games) {
                val prev = snaps[g.id]
                val now = Snap(g.state, g.home.score, g.away.score)
                snaps[g.id] = now
                if (first || prev == null) continue
                val matchup = "${g.away.nickname.ifBlank { g.away.abbr }} @ ${g.home.nickname.ifBlank { g.home.abbr }}"
                val scoreLine = "${g.away.abbr} ${g.away.score ?: "-"} – ${g.home.score ?: "-"} ${g.home.abbr} · ${g.detail}"
                val alert: SportsAlert? = when {
                    prev.state == "pre" && now.state == "in" ->
                        SportsAlert(g.id + "start", "Game on", "$matchup has started", g)
                    prev.state == "in" && now.state == "post" && !hide ->
                        SportsAlert(g.id + "final", "Final", scoreLine, g)
                    now.state == "in" && !hide && (prev.home != now.home || prev.away != now.away) -> {
                        val ph = prev.home?.toDoubleOrNull() ?: 0.0; val pa = prev.away?.toDoubleOrNull() ?: 0.0
                        val nh = now.home?.toDoubleOrNull() ?: 0.0; val na = now.away?.toDoubleOrNull() ?: 0.0
                        val leadChange = (ph - pa) * (nh - na) < 0 || (ph == pa && nh != na)
                        val scorer = if (nh - ph >= na - pa) g.home else g.away
                        // Every basket would be too much: big moments always, otherwise at most every 4 min.
                        val big = leadChange || g.league.path.startsWith("football") || g.league.path.startsWith("hockey") ||
                            g.league.path.startsWith("soccer") || g.league.path.startsWith("baseball")
                        val recent = System.currentTimeMillis() - (lastAlert[g.id] ?: 0L) < 4 * 60_000
                        if (big || !recent) SportsAlert(
                            g.id + "s" + nh + "-" + na,
                            if (leadChange) "${scorer.nickname.ifBlank { scorer.abbr }} take the lead" else "${scorer.nickname.ifBlank { scorer.abbr }} score",
                            scoreLine, g,
                        ) else null
                    }
                    else -> null
                }
                if (alert != null) {
                    lastAlert[g.id] = System.currentTimeMillis()
                    app.alert = alert
                }
            }
            first = false
            delay(45_000)
        }
    }
    // Alerts disappear on their own.
    val a = app.alert
    LaunchedEffect(a?.key) { if (a != null) { delay(14_000); if (app.alert?.key == a.key) app.alert = null } }
}

/** Finds the best channel for an alert's game and plays it (replacing what's on, if watching). */
suspend fun watchAlert(state: AppState, a: SportsAlert) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    app.alert = null
    val chans = repo.allChannels()
    val idx = repo.guideIndex()
    val best = SportsRepo.matchChannels(a.game, chans, idx).firstOrNull()?.channel
    if (best == null) { state.push(Route.GameDetail(a.game)); return }
    app.prefs.markChannelWatched(best.streamId)
    state.playReplacing(repo.liveRequest(best))
}

/** Card in the bottom-right corner, over whatever is on screen. */
@Composable
fun BoxScope.SportsAlertCard(state: AppState, onWatch: (SportsAlert) -> Unit) {
    val app = DekStreamApp.instance
    val a = app.alert
    AnimatedVisibility(
        visible = a != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
    ) {
        val alert = a ?: return@AnimatedVisibility
        val g = alert.game
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xF21E2430), Color(0xF2151922))))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, tint = DS.Accent, modifier = Modifier.size(18.dp))
                Text("  ${g.league.short}", color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f))
                if (g.isLive) LiveBadge()
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(g.away.logo, 34.dp)
                TeamLogo(g.home.logo, 34.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(alert.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(alert.message, color = DS.Text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onWatch(alert) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.focusRing(RoundedCornerShape(10.dp)),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(" Watch", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { app.alert = null }) { Text("Dismiss", color = DS.TextDim) }
                Box(Modifier.weight(1f))
                if (state.stack.lastOrNull() is Route.Player) Text("OK to watch", color = DS.TextDim, fontSize = 11.sp)
            }
        }
    }
}
