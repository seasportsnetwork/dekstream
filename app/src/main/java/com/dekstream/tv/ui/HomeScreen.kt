package com.dekstream.tv.ui

import androidx.compose.runtime.saveable.rememberSaveable

import kotlinx.coroutines.awaitAll

import kotlinx.coroutines.async

import androidx.compose.foundation.layout.widthIn

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

import androidx.compose.material.icons.filled.NotificationsNone

import androidx.compose.material.icons.filled.NotificationsActive

import androidx.compose.material.icons.filled.Schedule

import com.dekstream.tv.data.EpgIndex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SportsFootball
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.HomeLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.PlayRequest
import com.dekstream.tv.data.Program
import com.dekstream.tv.data.ProgressEntry
import com.dekstream.tv.data.Series
import com.dekstream.tv.data.Vod
import kotlinx.coroutines.delay

/** One featured slot on Home: its header art plus what pressing Play does. */
/** Guide listing as a Program (the guide index stores lighter entries). */
private fun EpgIndex.Entry.toProgram() = Program(title, desc, startMs, endMs, false)

/** Something coming up on one of your channels. */
private data class UpNext(val channel: Channel, val program: Program)

@Composable
fun HomeScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val progress by app.prefs.progress.collectAsState()
    val favIds by app.prefs.favorites.collectAsState()
    val recentIds by app.prefs.recentChannels.collectAsState()
    val favTeams by app.prefs.favTeams.collectAsState()
    val leagueKeys by app.prefs.sportLeagues.collectAsState()
    val hide by app.prefs.hideScores.collectAsState()
    val reminders by app.reminders.items.collectAsState()
    val all by rememberLoad(Unit) { repo.allChannels() }

    // Clock + a minute tick that refreshes "now" everywhere on Home.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }
    val scoreTick = tick / 60_000

    // The whole guide, from the copy saved on the device (loads in the background).
    val guide by produceState<EpgIndex?>(app.epg.takeIf { it.isLoaded }, repo.source.id) { value = runCatching { repo.guideIndex() }.getOrNull() }

    // Today's games in the leagues you follow: live first, your teams first.
    val games by rememberLoad(leagueKeys, favTeams, scoreTick) {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val ls = leagueKeys.mapNotNull { app.sports.league(it) }.filter { !it.racing }
        kotlinx.coroutines.coroutineScope {
            ls.map { l -> async { runCatching { app.sports.scoreboard(l, date) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten().filter { it.state != "post" || System.currentTimeMillis() - it.startMs < 4 * 3600_000L }
            .sortedWith(compareBy<Game>({ if (it.isLive) 0 else if (it.state == "pre") 1 else 2 }, { if (it.hasFavorite(favTeams)) 0 else 1 }, { it.startMs }))
    }

    val showMovies = HomeLayout.shows("movies")
    val showSeries = HomeLayout.shows("series")
    val showMoods = HomeLayout.shows("moods")
    val movieRows by rememberLoad(showMovies) {
        if (!showMovies) return@rememberLoad emptyList()
        repo.vodCategories().take(2).map { c -> c to repo.vods(c.id).filter { it.poster != null }.take(30) }.filter { it.second.isNotEmpty() }
    }
    val seriesRows by rememberLoad(showSeries) {
        if (!showSeries) return@rememberLoad emptyList()
        repo.seriesCategories().take(2).map { c -> c to repo.series(c.id).filter { it.cover != null }.take(30) }.filter { it.second.isNotEmpty() }
    }
    val moods by rememberLoad(showMoods) {
        if (!showMoods) return@rememberLoad emptyList()
        repo.vodCategories().take(8).mapNotNull { c ->
            val art = runCatching { repo.vods(c.id).firstOrNull { it.poster != null }?.poster }.getOrNull()
            Mood(c, cleanCategoryName(c.name), art)
        }
    }
    // Live categories for the "On now" rows.
    val catRows by rememberLoad(HomeLayout.shows("categories")) {
        if (!HomeLayout.shows("categories")) return@rememberLoad emptyList()
        repo.liveCategories().take(4).map { c -> c to repo.channels(c.id).take(20) }.filter { it.second.isNotEmpty() }
    }

    val channels = all.valueOrNull().orEmpty()
    val byId = remember(channels) { channels.associateBy { it.streamId } }
    val favChannels = favIds.mapNotNull { byId[it] }
    val lastChannel = recentIds.firstNotNullOfOrNull { byId[it] }
    val cont = if (showMovies || showSeries) progress.values.sortedByDescending { it.updatedAt } else emptyList()
    val gameList = games.valueOrNull().orEmpty()
    val idx = guide

    fun nowOf(c: Channel): Program? = idx?.nowOn(c, tick)?.toProgram()
    fun nextOf(c: Channel): Program? = idx?.programmesFor(c)?.firstOrNull { it.startMs >= tick }?.toProgram()

    // Spotlight: your team live > your team later today > biggest live game > the channel you were watching.
    val spotGame = gameList.firstOrNull { it.isLive && it.hasFavorite(favTeams) }
        ?: gameList.firstOrNull { it.state == "pre" && it.hasFavorite(favTeams) }
        ?: gameList.firstOrNull { it.isLive }
    val otherGames = gameList.filter { it.id != spotGame?.id }
    val scores = otherGames.take(3)

    // Coming up on your channels (favorites, then recent), next few hours.
    val upNext = remember(idx, favIds, recentIds, tick / 300_000, channels.size) {
        if (idx == null) emptyList() else (favIds + recentIds).distinct().mapNotNull { byId[it] }.take(40).flatMap { c ->
            idx.programmesFor(c).filter { it.startMs > tick && it.startMs < tick + 6 * 3600_000L }.take(2).map { UpNext(c, it.toProgram()) }
        }.sortedBy { it.program.startMs }.take(24)
    }
    // Keep the Shield home-screen row in step with your favorites.
    LaunchedEffect(favChannels.map { it.streamId }, idx, tick / 900_000) {
        if (favChannels.isEmpty()) return@LaunchedEffect
        val list = favChannels
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.dekstream.tv.data.TvHomeRow.update(app, list) { c -> idx?.nowOn(c, System.currentTimeMillis())?.title }
        }
    }
    val playLive: (Channel, List<Channel>) -> Unit = { c, list -> app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), list) }

    BoxWithConstraints(Modifier.fillMaxSize().background(DS.Bg)) {
        val wide = maxWidth >= 720.dp
        val pad = if (wide) 32.dp else 18.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            // ---- Header ----
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = pad).padding(top = 20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (!wide) { BrandMark(28.dp, showName = false); Spacer(Modifier.width(10.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(greeting(app.session?.profile?.name), color = DS.Text, fontSize = if (wide) 32.sp else 24.sp,
                                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(headerLine(tick, gameList, favTeams), color = DS.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(java.util.Date(tick)),
                            color = DS.Text, fontSize = if (wide) 34.sp else 24.sp, fontWeight = FontWeight.Light)
                        Text(" " + java.text.SimpleDateFormat("a", java.util.Locale.getDefault()).format(java.util.Date(tick)),
                            color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SearchBarPill(onClick = { state.tab = Tab.Search }, modifier = Modifier.weight(1f))
                        if (lastChannel != null && wide) {
                            PillButton("Resume · ${lastChannel.name}", { playLive(lastChannel, favChannels.ifEmpty { listOf(lastChannel) }) },
                                Icons.Default.History, modifier = Modifier.widthIn(max = 300.dp))
                        }
                        PillButton(if (wide) "Guide" else "", { state.tab = Tab.Guide }, Icons.Default.CalendarViewWeek)
                        PillButton(if (wide) "Customize" else "", { state.push(Route.HomeSettings) }, Icons.Default.Tune)
                    }
                    if (!wide && lastChannel != null) {
                        PillButton("Resume · ${lastChannel.name}", { playLive(lastChannel, favChannels.ifEmpty { listOf(lastChannel) }) },
                            Icons.Default.History, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }

            // ---- Spotlight + scores ----
            if (HomeLayout.shows("spotlight")) item {
                val spotlight: @Composable (Modifier) -> Unit = { m ->
                    when {
                        spotGame != null -> GameSpotlight(spotGame, hide, favTeams, m) { state.push(Route.GameDetail(spotGame)) }
                        lastChannel != null -> ChannelSpotlight(lastChannel, nowOf(lastChannel), nextOf(lastChannel), "Pick up where you left off", m,
                            onWatch = { playLive(lastChannel, favChannels.ifEmpty { listOf(lastChannel) }) }, onGuide = { state.tab = Tab.Guide })
                        favChannels.isNotEmpty() -> ChannelSpotlight(favChannels[0], nowOf(favChannels[0]), nextOf(favChannels[0]), "On your channels", m,
                            onWatch = { playLive(favChannels[0], favChannels) }, onGuide = { state.tab = Tab.Guide })
                        else -> WelcomeSpotlight(m, onLive = { state.tab = Tab.Live }, onSports = { state.tab = Tab.Sports })
                    }
                }
                val side: @Composable (Modifier) -> Unit = { m ->
                    ScoresPanel(otherGames, hide, favTeams, games is Load.Loading, leagueKeys.isEmpty(), m,
                        onGame = { g -> state.push(Route.GameDetail(g)) }, onAll = { state.tab = Tab.Sports })
                }
                if (wide) {
                    Row(Modifier.fillMaxWidth().height(320.dp).padding(horizontal = pad).padding(top = 20.dp)) {
                        spotlight(Modifier.weight(1.7f).fillMaxHeight())
                        Spacer(Modifier.width(14.dp))
                        side(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = pad).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        spotlight(Modifier.fillMaxWidth().height(240.dp))
                        if (otherGames.isNotEmpty()) side(Modifier.fillMaxWidth().height(300.dp))
                    }
                }
            }

            // ---- Your channels ----
            if (HomeLayout.shows("favorites")) {
                item {
                    SectionHeader("Your channels", if (favChannels.isEmpty()) "Hold OK on any channel in Live TV to add it" else "What's on right now",
                        onSeeAll = { state.tab = Tab.Favorites }, modifier = Modifier.padding(horizontal = pad))
                }
                item {
                    if (favChannels.isEmpty()) {
                        Row(Modifier.padding(horizontal = pad)) {
                            PillButton("Browse Live TV", { state.tab = Tab.Live }, Icons.Default.LiveTv, primary = true)
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                            items(favChannels, key = { "f" + it.streamId }) { c ->
                                Box(Modifier.width(290.dp)) { FavChannelTile(c, onClick = { playLive(c, favChannels) }, onLongClick = null, preset = nowOf(c)) }
                            }
                        }
                    }
                }
            }

            // ---- Coming up ----
            if (HomeLayout.shows("upnext") && upNext.isNotEmpty()) {
                item { SectionHeader("Coming up on your channels", "Press OK for a reminder, hold OK to watch the channel", modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(upNext, key = { "u" + it.channel.streamId + "-" + it.program.startMs }) { u ->
                            val on = reminders.any { it.streamId == u.channel.streamId && it.startMs == u.program.startMs }
                            UpNextCard(u, on,
                                onClick = {
                                    if (on) app.reminders.cancel(u.channel.streamId, u.program.startMs)
                                    else app.reminders.add(app.session?.profile?.id ?: "", u.channel, u.program)
                                },
                                onLongClick = { playLive(u.channel, favChannels.ifEmpty { listOf(u.channel) }) })
                        }
                    }
                }
            }

            // ---- On now, by category ----
            catRows.valueOrNull().orEmpty().forEach { (cat, list) ->
                item(key = "ch-" + cat.id) {
                    SectionHeader(cleanCategoryName(cat.name), "On now", onSeeAll = { state.tab = Tab.Live }, modifier = Modifier.padding(horizontal = pad))
                }
                item(key = "cr-" + cat.id) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(list, key = { "c" + cat.id + "-" + it.streamId }) { c -> ChannelNowTile(c, nowOf(c)) { playLive(c, list) } }
                    }
                }
            }

            // ---- Movies & series (only when turned on) ----
            if (cont.isNotEmpty() && HomeLayout.shows("continue")) {
                item { SectionHeader("Continue watching", "Movies and shows", modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(cont, key = { it.key }) { p ->
                            WideCard(p.title, p.subtitle, p.poster, p.fraction, 260.dp,
                                onClick = { state.play(resumeRequest(p)) }, onLongClick = { app.prefs.removeProgress(p.key) }, onFocused = {})
                        }
                    }
                }
            }
            val moodList = moods.valueOrNull().orEmpty()
            if (moodList.isNotEmpty()) {
                item { SectionHeader("Genres", modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(moodList, key = { it.category.id }) { m ->
                            val i = moodList.indexOf(m)
                            MoodTile(if (i % 2 == 0) "Genre" else "Mood", m.name, m.art, moodColors[i % moodColors.size],
                                onClick = { state.push(Route.VodCategory(m.category, false)) },
                                modifier = Modifier.width(if (wide) 230.dp else 190.dp).height(if (wide) 86.dp else 76.dp))
                        }
                    }
                }
            }
            movieRows.valueOrNull().orEmpty().forEach { (cat, list) ->
                item(key = "m-${cat.id}") {
                    SectionHeader(cleanCategoryName(cat.name), "Movies", onSeeAll = { state.push(Route.VodCategory(cat, false)) }, modifier = Modifier.padding(horizontal = pad))
                }
                item(key = "mr-${cat.id}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(list, key = { it.streamId }) { v -> MoviePoster(v, state, progress) }
                    }
                }
            }
            seriesRows.valueOrNull().orEmpty().forEach { (cat, list) ->
                item(key = "s-${cat.id}") {
                    SectionHeader(cleanCategoryName(cat.name), "Series", onSeeAll = { state.push(Route.VodCategory(cat, true)) }, modifier = Modifier.padding(horizontal = pad))
                }
                item(key = "sr-${cat.id}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(list, key = { it.seriesId }) { s -> PosterCard(s.name, s.cover, 140.dp, onClick = { state.push(Route.SeriesDetail(s)) }) }
                    }
                }
            }

            if (all is Load.Loading) item { LoadView(all) {} }
        }
    }
}

private data class Mood(val category: com.dekstream.tv.data.Category, val name: String, val art: String?)

private val moodColors = listOf(Color(0xFFFF453A), Color(0xFFFF9F0A), Color(0xFF64D2FF), Color(0xFF30D158), Color(0xFFBF5AF2), Color(0xFFFF375F))

/** "EN | Action Movies" -> "Action Movies". */
fun cleanCategoryName(raw: String): String =
    raw.substringAfterLast('|').substringAfterLast(':').replace(Regex("^\\W+"), "").trim().ifBlank { raw.trim() }

private fun greeting(name: String?): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val part = when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    return if (name.isNullOrBlank()) part else "$part, $name"
}

private fun headerLine(now: Long, games: List<Game>, favs: List<com.dekstream.tv.data.FavTeam>): String {
    val date = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date(now))
    val mineLive = games.count { it.isLive && it.hasFavorite(favs) }
    val live = games.count { it.isLive }
    val extra = when {
        mineLive > 0 -> "$mineLive of your teams ${if (mineLive == 1) "is" else "are"} playing now"
        live > 0 -> "$live live game${if (live == 1) "" else "s"}"
        else -> null
    }
    return listOfNotNull(date, extra).joinToString("  ·  ")
}

private fun countdown(ms: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((ms - now) / 60_000).coerceAtLeast(0)
    return if (m >= 60) "in ${m / 60}h ${m % 60}m" else "in ${m}m"
}

/** Big game card: logos, score, clock, Watch. */
@Composable
private fun GameSpotlight(g: Game, hide: Boolean, favs: List<com.dekstream.tv.data.FavTeam>, modifier: Modifier, onWatch: () -> Unit) {
    BentoCard(modifier, onClick = onWatch) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF101318), DS.Accent.copy(alpha = 0.5f), Color(0xFF101318)))))
        Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.isLive) ArtChip("LIVE", Icons.Default.FiberManualRecord, color = DS.Live)
            else ArtChip("Today ${fmtTime(g.startMs)} · ${countdown(g.startMs)}", Icons.Default.Schedule)
            ArtChip(if (g.hasFavorite(favs)) "Your team · ${g.league.short}" else g.league.short)
        }
        Row(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            SpotTeam(g.away, Modifier.weight(1f))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (g.state != "pre" && !hide) {
                    Text("${g.away.score ?: "-"}  –  ${g.home.score ?: "-"}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(g.detail, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(if (g.state == "pre") fmtTime(g.startMs) else "Live", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text(if (g.state == "pre") countdown(g.startMs) else "Scores hidden", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
            SpotTeam(g.home, Modifier.weight(1f))
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(if (g.isLive) "Watch now" else "Find channels", onWatch, Icons.Default.PlayArrow, primary = true, onImage = true)
            Text(g.broadcasts.take(2).joinToString(" · "), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpotTeam(t: com.dekstream.tv.data.TeamSide, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(t.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(76.dp))
        Text(t.nickname, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        t.record?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
    }
}

/** Big channel card: what's on, progress, what's next. */
@Composable
private fun ChannelSpotlight(
    c: Channel, now: Program?, next: Program?, kicker: String, modifier: Modifier, onWatch: () -> Unit, onGuide: () -> Unit,
) {
    val repo = DekStreamApp.instance.repo
    val fetched by produceState(now, c.streamId, now) { if (now == null) value = runCatching { repo?.nowNext(c)?.firstOrNull { it.isLive() } }.getOrNull() }
    val p = now ?: fetched
    BentoCard(modifier, onClick = onWatch) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF101318), DS.Accent.copy(alpha = 0.45f)))))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).size(140.dp).clip(RoundedCornerShape(30.dp)).background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center) {
            if (c.icon != null) AsyncImage(c.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(20.dp))
            else Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Box(Modifier.align(Alignment.TopStart).padding(16.dp)) { ArtChip(kicker, Icons.Default.History) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.62f).padding(start = 20.dp, bottom = 18.dp)) {
            Text(c.name, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(p?.title ?: c.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                val f = ((System.currentTimeMillis() - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(f).height(4.dp).background(Color.White))
                    }
                    Text("  ${minutesLeft(p)} left", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
            }
            next?.let { Text("Next · ${fmtTime(it.startMs)}  ${it.title}", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Watch", onWatch, Icons.Default.PlayArrow, primary = true, onImage = true)
                PillButton("Guide", onGuide, Icons.Default.CalendarViewWeek, onImage = true)
            }
        }
    }
}

@Composable
private fun WelcomeSpotlight(modifier: Modifier, onLive: () -> Unit, onSports: () -> Unit) {
    BentoCard(modifier) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF15151A), DS.Accent.copy(alpha = 0.45f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            BrandMark(34.dp, nameSize = 26)
            Text("Favorite a few channels and pick your teams — this space fills with what's on and your games.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).widthIn(max = 460.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Live TV", onLive, Icons.Default.LiveTv, primary = true, onImage = true)
                PillButton("Pick teams", onSports, Icons.Default.SportsFootball, onImage = true)
            }
        }
    }
}

/** Live and upcoming scores beside the spotlight, with league tabs. */
@Composable
private fun ScoresPanel(
    games: List<Game>, hide: Boolean, favs: List<com.dekstream.tv.data.FavTeam>, loading: Boolean, noLeagues: Boolean,
    modifier: Modifier, onGame: (Game) -> Unit, onAll: () -> Unit,
) {
    var league by rememberSaveable { mutableStateOf<String?>(null) }
    val leagues = games.map { it.league }.distinctBy { it.key }
    val shown = games.filter { league == null || it.league.key == league }
    val visible = shown.take(6)
    BentoCard(modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scores", color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                LazyRow(Modifier.weight(1f).padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { MiniTab("All", league == null) { league = null } }
                    items(leagues, key = { it.key }) { l -> MiniTab(l.short, league == l.key) { league = l.key } }
                }
                val shape = RoundedCornerShape(50)
                Row(Modifier.focusRing(shape).clip(shape).tvClickable(onAll).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (shown.size > visible.size) "+${shown.size - visible.size}" else "All", color = DS.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DS.Accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                visible.isNotEmpty() -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.chunked(2).forEach { pair ->
                        Row(Modifier.weight(1f, fill = false).heightIn(max = 76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { g -> MiniScore(g, hide, g.hasFavorite(favs), Modifier.weight(1f)) { onGame(g) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                loading -> Text("Loading scores…", color = DS.TextDim, fontSize = 13.sp)
                noLeagues -> Text("Choose the leagues you follow in Sports.", color = DS.TextDim, fontSize = 13.sp)
                else -> Text("No more games today.", color = DS.TextDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MiniTab(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(label, color = if (on) DS.OnInvert else DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.focusRing(shape, 1.05f).clip(shape).background(if (on) DS.Invert else Color.Transparent)
            .tvClickable(onClick).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Compact two-line score card. */
@Composable
private fun MiniScore(g: Game, hide: Boolean, mine: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.focusRing(shape, 1.03f).clip(shape).background(if (DS.Light) Color(0xFFF2F2F5) else Color(0x12FFFFFF))
            .tvClickable(onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            listOf(g.away, g.home).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(t.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(18.dp))
                    Text(" " + t.abbr, color = DS.Text, fontSize = 13.sp, fontWeight = if (mine) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.width(52.dp), maxLines = 1)
                    if (g.state != "pre" && !hide) Text(t.score ?: "-", color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (g.isLive) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(DS.Live))
            Text(when { g.isLive -> g.detail; g.state == "pre" -> fmtTime(g.startMs); else -> "Final" },
                color = if (g.isLive) DS.Live else DS.TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(max = 70.dp))
        }
    }
}

/** A programme coming up on one of your channels; OK toggles a reminder. */
@Composable
private fun UpNextCard(u: UpNext, reminded: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    BentoCard(Modifier.width(250.dp).height(118.dp), onClick = onClick, onLongClick = onLongClick) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(u.program.startMs), color = DS.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("  ${countdown(u.program.startMs)}", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(if (reminded) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, if (reminded) "Reminder set" else "Set reminder",
                    tint = if (reminded) DS.Accent else DS.TextDim, modifier = Modifier.size(18.dp))
            }
            Text(u.program.title, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(u.channel.icon, 20.dp)
                Text("  " + u.channel.name, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compact channel tile for the category rows (guide data only, no network per tile). */
@Composable
private fun ChannelNowTile(c: Channel, now: Program?, onClick: () -> Unit) {
    BentoCard(Modifier.width(240.dp).height(104.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (DS.Light) Color(0xFFF0F0F3) else Color(0x14FFFFFF)),
                    contentAlignment = Alignment.Center) {
                    if (c.icon != null) AsyncImage(c.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp))
                    else Icon(Icons.Default.LiveTv, null, tint = DS.TextDim, modifier = Modifier.size(20.dp))
                }
                Text(c.name, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(now?.title ?: "Live", color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                val f = ((System.currentTimeMillis() - now.startMs).toFloat() / (now.endMs - now.startMs)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(DS.Line)) {
                    Box(Modifier.fillMaxWidth(f).height(3.dp).background(DS.Accent))
                }
            }
        }
    }
}

private fun resumeRequest(p: ProgressEntry) =
    PlayRequest(p.url, p.title, p.subtitle, isLive = false, progressKey = p.key, poster = p.poster, startPositionMs = p.positionMs)


@Composable
private fun MoviePoster(v: Vod, state: AppState, progress: Map<String, ProgressEntry>) {
    PosterCard(
        v.name, v.poster, 140.dp, onClick = { state.push(Route.MovieDetail(v)) },
        progress = progress["vod:${v.streamId}"]?.fraction,
    )
}

/** Landscape card with progress (Continue watching). */
@Composable
private fun WideCard(
    title: String, subtitle: String?, image: String?, progress: Float, width: Dp,
    onClick: () -> Unit, onLongClick: () -> Unit, onFocused: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(width).onFocusChanged { if (it.hasFocus) onFocused() }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(shape, 1.05f).clip(shape).background(DS.Surface2)
                .tvClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (image != null) {
                AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC000000))))
            }
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0x66000000)).padding(6.dp))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(DS.Accent))
            }
        }
        Text(title, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        subtitle?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Channel tile with what's on now and how far in. */
@Composable
private fun LiveNowCard(c: Channel, onClick: () -> Unit, onFocused: (Program?) -> Unit) {
    val repo = DekStreamApp.instance.repo ?: return
    val now by produceState<Program?>(null, c.streamId) { value = repo.nowNext(c).firstOrNull { it.isLive() } }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(250.dp).onFocusChanged { if (it.hasFocus) onFocused(now) }
            .focusRing(shape, 1.05f).clip(shape)
            .background(Brush.linearGradient(listOf(DS.Surface2, DS.Surface)))
            .tvClickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.icon, 44.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(c.name, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(DS.Live))
                    Text(" LIVE", color = DS.Live, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(now?.title ?: "No guide info", color = DS.Text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
        val p = now
        val f = if (p != null) ((System.currentTimeMillis() - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f) else 0f
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(f).height(3.dp).background(DS.Accent))
        }
        Text(p?.let { "${minutesLeft(it)} left" } ?: " ", color = DS.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Suppress("unused") private fun unusedSeries(s: Series) = s
