package com.dekstream.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.FavTeam

/** Choose which leagues show on the Sports tab, and star favorite teams. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportsSettingsScreen(state: AppState) {
    val app = DekStreamApp.instance
    val selected by app.prefs.sportLeagues.collectAsState()
    val favs by app.prefs.favTeams.collectAsState()
    var browse by rememberSaveable { mutableStateOf("nfl") }
    val browseLeague = app.sports.league(browse) ?: app.sports.allLeagues.first()
    val teams by rememberLoad(browse) { app.sports.teams(browseLeague) }
    BackHandler { state.pop() }

    LazyVerticalGrid(
        GridCells.Adaptive(260.dp),
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text)
                }
                Text("Sports & teams", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("SHOW THESE LEAGUES") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.sports.allLeagues.forEach { l ->
                    val on = l.key in selected
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        Modifier.focusRing(shape).clip(shape).background(if (on) DS.Accent else DS.Surface)
                            .clickable { app.prefs.toggleLeague(l.key) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (on) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.padding(end = 4.dp))
                        Text(l.name, color = DS.Text, fontSize = 14.sp)
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("MY TEAMS") }
        if (favs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("No favorite teams yet. Star some below.", color = DS.TextDim) }
        }
        items(favs, key = { "f-" + it.key }) { f ->
            TeamStarRow(f.name, f.logo, app.sports.league(f.leagueKey)?.short, on = true) { app.prefs.toggleFavTeam(f) }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("ADD TEAMS") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.sports.allLeagues.forEach { l -> CategoryChip(l.short, l.key == browse) { browse = l.key } }
            }
        }
        when (val t = teams) {
            is Load.Ok -> items(t.value, key = { "t-" + browse + it.id }) { team ->
                val on = favs.any { it.leagueKey == browse && it.teamId == team.id }
                TeamStarRow(team.name, team.logo, null, on) {
                    app.prefs.toggleFavTeam(FavTeam(browse, team.id, team.name, team.abbr, team.logo))
                }
            }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { LoadView(t) {} }
        }
    }
}

@Composable
private fun TeamStarRow(name: String, logo: String?, tag: String?, on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().focusRing(shape, 1.02f).clip(shape).background(DS.Surface)
            .clickable(onClick = onToggle).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamLogo(logo, 32.dp)
        Text(name, color = DS.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f))
        tag?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp)) }
        Icon(if (on) Icons.Default.Star else Icons.Default.StarBorder, if (on) "Remove favorite" else "Add favorite",
            tint = if (on) Color(0xFFFFC107) else DS.TextDim)
    }
}
