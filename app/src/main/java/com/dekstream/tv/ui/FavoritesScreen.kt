package com.dekstream.tv.ui

import androidx.compose.runtime.mutableStateOf

import androidx.compose.material.icons.automirrored.filled.ArrowForward

import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.SwapHoriz

import androidx.compose.material.icons.filled.Check

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.HomeLayout
import com.dekstream.tv.data.Program

/** Favorite channels and teams in one place. */
@Composable
fun FavoritesScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val favIds by app.prefs.favorites.collectAsState()
    val favTeams by app.prefs.favTeams.collectAsState()
    val all by rememberLoad(Unit) { repo.allChannels() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val byId = all.valueOrNull().orEmpty().associateBy { it.streamId }
    val favs = favIds.distinct().mapNotNull { byId[it] }
    val myList by app.prefs.myList.collectAsState()
    val librarySort by app.prefs.librarySort.collectAsState()
    val progress by app.prefs.progress.collectAsState()

    LazyVerticalGrid(
        GridCells.Adaptive(if (tab == 2) 150.dp else 270.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 22.dp)) {
                Text("Favorites", color = DS.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    when (tab) {
                        0 -> "${favs.size} channel${if (favs.size == 1) "" else "s"} · " +
                            (if (editing) "move channels with the arrows" else "hold OK on one to remove it")
                        1 -> "${favTeams.size} team${if (favTeams.size == 1) "" else "s"}"
                        else -> "${myList.size} saved · hold OK on one for options"
                    },
                    color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf("Channels" to Icons.Default.LiveTv, "Teams" to Icons.Default.SportsFootball,
                        "My Library" to Icons.Default.Bookmark), tab) { tab = it }
                    if (tab == 0 && favs.size > 1) {
                        Spacer(Modifier.width(12.dp))
                        PillButton(if (editing) "Done" else "Reorder", { editing = !editing }, if (editing) Icons.Default.Check else Icons.Default.SwapHoriz, primary = editing)
                    }
                }
            }
        }
        if (tab == 0) {
            if (all is Load.Loading) item(span = { GridItemSpan(maxLineSpan) }) { LoadView(all) {} }
            else if (favs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFavorites("No favorite channels yet", "In Live TV or the Guide, hold OK (or long-press) on a channel to add it here.",
                    "Browse Live TV") { state.tab = Tab.Live }
            }
            else items(favs.distinctBy { it.streamId }, key = { it.streamId }) { c ->
                if (editing) {
                    val i = favs.indexOf(c)
                    BentoCard(Modifier.fillMaxWidth().height(132.dp)) {
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = DS.Accent, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(34.dp))
                                ChannelLogo(c.icon, 36.dp)
                                Text("  " + c.name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircleButton(Icons.AutoMirrored.Filled.ArrowBack, { app.prefs.moveFavorite(c.streamId, -1) }, onImage = false, contentDescription = "Move earlier")
                                CircleButton(Icons.AutoMirrored.Filled.ArrowForward, { app.prefs.moveFavorite(c.streamId, 1) }, onImage = false, contentDescription = "Move later")
                                CircleButton(Icons.Default.Close, { app.prefs.toggleFavorite(c.streamId) }, onImage = false, contentDescription = "Remove")
                            }
                        }
                    }
                } else FavChannelTile(c,
                    onClick = { app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), favs) },
                    onLongClick = { app.prefs.toggleFavorite(c.streamId) })
            }
        } else if (tab == 2) {
            if (myList.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Sort", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 9.dp, end = 4.dp)) }
                    items(com.dekstream.tv.data.LibrarySort.names.size) { i ->
                        Pill(com.dekstream.tv.data.LibrarySort.names[i], librarySort == i, onClick = { app.prefs.setLibrarySort(i) })
                    }
                }
            }
            if (myList.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFavorites("Nothing saved yet", "Hold OK on any movie or series poster and choose Add to My Library, or press My Library on its page.",
                    "Browse movies") { state.tab = Tab.Movies }
            }
            else items(com.dekstream.tv.data.LibrarySort.apply(myList, librarySort), key = { it.kind + it.id }) { t ->
                PosterCard(com.dekstream.tv.data.Top10.displayName(t.name), t.poster, 150.dp,
                    onClick = { if (t.kind == "movie") state.push(Route.MovieDetail(t.toVod())) else state.push(Route.SeriesDetail(t.toSeries())) },
                    progress = if (t.kind == "movie") progress["vod:${t.id}"]?.fraction else null,
                    subtitle = if (t.kind == "movie") "Movie" else "Series",
                    onLongClick = { state.titleMenu = t })
            }
        } else {
            if (favTeams.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFavorites("No favorite teams yet", "Pick the teams you follow and their games show up on Home and Sports.",
                    "Choose teams") { state.push(Route.SportsSettings) }
            }
            else {
                items(favTeams, key = { it.key }) { t ->
                    BentoCard(Modifier.fillMaxWidth().height(88.dp), onClick = { state.tab = Tab.Sports }) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(t.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp))
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(t.name, color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.sports.league(t.leagueKey)?.short ?: t.abbr, color = DS.TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row { PillButton("Manage teams", { state.push(Route.SportsSettings) }, Icons.Default.Tune) }
                }
            }
        }
    }
}

@Composable
private fun EmptyFavorites(title: String, body: String, action: String, onAction: () -> Unit) {
    BentoCard(Modifier.fillMaxWidth().height(200.dp)) {
        Column(Modifier.align(Alignment.Center).padding(20.dp).widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Favorite, null, tint = DS.Accent, modifier = Modifier.size(34.dp))
            Text(title, color = DS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(body, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            PillButton(action, onAction, primary = true)
        }
    }
}

/** Channel card: logo, what's on and how far in. */
@Composable
fun FavChannelTile(c: Channel, onClick: () -> Unit, onLongClick: (() -> Unit)?, preset: Program? = null) {
    val repo = DekStreamApp.instance.repo
    val now by produceState(preset, c.streamId, preset) {
        if (preset == null) value = runCatching { repo?.nowNext(c)?.firstOrNull { it.isLive() } }.getOrNull()
    }
    BentoCard(Modifier.fillMaxWidth().height(132.dp), onClick = onClick, onLongClick = onLongClick) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(if (DS.Light) Color(0xFFF0F0F3) else Color(0x14FFFFFF)),
                    contentAlignment = Alignment.Center) {
                    if (c.icon != null) AsyncImage(c.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
                    else Icon(Icons.Default.LiveTv, null, tint = DS.TextDim)
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(c.name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(DS.Live))
                        Text(" LIVE" + (if (c.num > 0) "  ·  CH ${c.num}" else ""), color = DS.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Icon(Icons.Default.Favorite, null, tint = DS.Accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(now?.title ?: "No guide info", color = DS.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = now
            val f = if (p != null) ((System.currentTimeMillis() - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f) else 0f
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(DS.Line)) {
                    Box(Modifier.fillMaxWidth(f).height(3.dp).background(DS.Accent))
                }
                Text(p?.let { "  ${minutesLeft(it)} left" } ?: "", color = DS.TextDim, fontSize = 11.sp)
            }
        }
    }
}

/** Choose what Home shows. */
@Composable
fun HomeLayoutScreen(state: AppState) {
    val app = DekStreamApp.instance
    val st = app.session?.settings ?: return
    fun set(h: Set<String>) { HomeLayout.hidden = h; st.homeHidden = h }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 680.dp)) {
        ScreenTitle("Home screen") { state.pop() }
        Text("Home is built for Live TV. Turn on Movies and Series here if you want them on Home too — they still have their own tabs either way.",
            color = DS.TextDim, fontSize = 14.sp)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Live TV only", HomeLayout.hidden == HomeLayout.liveOnlyHidden, onClick = { set(HomeLayout.liveOnlyHidden) }, icon = Icons.Default.LiveTv)
            Pill("Live TV + Movies & Series", HomeLayout.hidden.isEmpty(), onClick = { set(emptySet()) })
            Pill("Sports fan", HomeLayout.hidden == setOf("moods", "movies", "series", "continue", "categories"),
                onClick = { set(setOf("moods", "movies", "series", "continue", "categories")) }, icon = Icons.Default.SportsFootball)
        }
        Spacer(Modifier.height(18.dp))
        BentoCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                HomeLayout.sections.forEach { (key, label) ->
                    val on = HomeLayout.shows(key)
                    val toggle = { set(if (on) HomeLayout.hidden + key else HomeLayout.hidden - key) }
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp).focusRing(shape, 1.01f).clip(shape)
                            .tvClickable(toggle).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = DS.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Switch(on, onCheckedChange = { toggle() },
                            colors = SwitchDefaults.colors(checkedTrackColor = DS.Accent, checkedThumbColor = Color.White))
                    }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
