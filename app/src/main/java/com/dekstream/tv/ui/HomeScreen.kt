package com.dekstream.tv.ui

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
private data class Featured(val hero: HeroItem, val onPlay: () -> Unit, val onInfo: (() -> Unit)?)

@Composable
fun HomeScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val progress by app.prefs.progress.collectAsState()
    val favIds by app.prefs.favorites.collectAsState()
    val recentIds by app.prefs.recentChannels.collectAsState()
    val favTeams by app.prefs.favTeams.collectAsState()
    val hide by app.prefs.hideScores.collectAsState()
    val all by rememberLoad(Unit) { repo.allChannels() }
    val sports by rememberLoad(favTeams) { app.sports.favoriteGames(favTeams) }

    val showMovies = HomeLayout.shows("movies")
    val showSeries = HomeLayout.shows("series")
    val showMoods = HomeLayout.shows("moods")
    // A couple of movie and series rows to make Home feel alive.
    val movieRows by rememberLoad(showMovies) {
        if (!showMovies) return@rememberLoad emptyList()
        repo.vodCategories().take(2).map { c -> c to repo.vods(c.id).filter { it.poster != null }.take(30) }.filter { it.second.isNotEmpty() }
    }
    val seriesRows by rememberLoad(showSeries) {
        if (!showSeries) return@rememberLoad emptyList()
        repo.seriesCategories().take(2).map { c -> c to repo.series(c.id).filter { it.cover != null }.take(30) }.filter { it.second.isNotEmpty() }
    }
    // Mood / genre tiles: movie categories with the first poster as art.
    val moods by rememberLoad(showMoods) {
        if (!showMoods) return@rememberLoad emptyList()
        repo.vodCategories().take(8).mapNotNull { c ->
            val art = runCatching { repo.vods(c.id).firstOrNull { it.poster != null }?.poster }.getOrNull()
            Mood(c, cleanCategoryName(c.name), art)
        }
    }

    val channels = all.valueOrNull().orEmpty()
    val byId = channels.associateBy { it.streamId }
    val favChannels = favIds.mapNotNull { byId[it] }
    val recentChannels = recentIds.mapNotNull { byId[it] }.filter { it.streamId !in favIds }.take(20)
    val liveNow = (favChannels + recentChannels).distinctBy { it.streamId }.take(20)
    val cont = progress.values.sortedByDescending { it.updatedAt }
        .let { l -> if (showMovies || showSeries) l else l.filter { it.key.startsWith("live") } }
    val games = sports.valueOrNull().orEmpty()

    // Featured: continue watching + new movies/series with wide art (looked up in the background).
    val featured by produceState<List<Featured>>(emptyList(), movieRows, seriesRows, cont.size, liveNow.size) {
        val out = ArrayList<Featured>()
        // Live TV only (or nothing else to feature): what's on your channels.
        if (!showMovies && !showSeries) liveNow.take(5).forEach { c ->
            val now = runCatching { repo.nowNext(c).firstOrNull { it.isLive() } }.getOrNull()
            out += Featured(
                HeroItem("l-${c.streamId}", now?.title ?: c.name, c.icon,
                    meta = c.name + (now?.let { "  ·  ${fmtTime(it.startMs)} – ${fmtTime(it.endMs)}  ·  ${minutesLeft(it)} left" } ?: ""),
                    description = now?.description, badge = "On now", imageIsPoster = true),
                onPlay = { app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), liveNow) }, onInfo = null,
            )
        }
        cont.take(2).forEach { p ->
            out += Featured(
                HeroItem("c-" + p.key, p.title, p.poster, meta = p.subtitle ?: "Pick up where you left off", badge = "Continue watching",
                    imageIsPoster = true, progress = p.fraction),
                onPlay = { state.play(resumeRequest(p)) }, onInfo = null,
            )
        }
        movieRows.valueOrNull()?.firstOrNull()?.second?.take(4)?.forEach { v ->
            val info = repo.vodInfo(v)
            out += Featured(
                HeroItem("v-${v.streamId}", v.name, info?.backdrop ?: v.poster,
                    meta = listOfNotNull(info?.releaseDate?.take(4), info?.genre, info?.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                    description = info?.plot, badge = "New movie", imageIsPoster = info?.backdrop == null),
                onPlay = { state.play(repo.vodRequest(v)) }, onInfo = { state.push(Route.MovieDetail(v)) },
            )
        }
        seriesRows.valueOrNull()?.firstOrNull()?.second?.take(3)?.forEach { s ->
            out += Featured(
                HeroItem("s-${s.seriesId}", s.name, s.backdrop ?: s.cover,
                    meta = listOfNotNull(s.releaseDate?.take(4), s.genre, s.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                    description = s.plot, badge = "Series", imageIsPoster = s.backdrop == null),
                onPlay = { state.push(Route.SeriesDetail(s)) }, onInfo = { state.push(Route.SeriesDetail(s)) },
            )
        }
        value = out
    }
    var rotate by remember { mutableIntStateOf(0) }
    var holdRotate by remember { mutableLongStateOf(0L) }
    LaunchedEffect(featured.size) {
        while (true) {
            delay(9_000)
            if (System.currentTimeMillis() - holdRotate > 15_000) rotate++
        }
    }
    val current = featured.getOrNull(if (featured.isEmpty()) 0 else rotate % featured.size)

    val nextGame = games.map { it.second }.let { g -> g.firstOrNull { it.isLive } ?: g.filter { it.state == "pre" }.minByOrNull { it.startMs } }
    val onNowChannel = liveNow.firstOrNull()
    val playLive: (Channel) -> Unit = { c -> app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), liveNow) }

    BoxWithConstraints(Modifier.fillMaxSize().background(DS.Bg)) {
        val wide = maxWidth >= 720.dp
        val pad = if (wide) 32.dp else 18.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
            // Floating search + greeting
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = pad).padding(top = 18.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SearchBarPill(onClick = { state.tab = Tab.Search })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!wide) { BrandMark(28.dp, showName = false); Spacer(Modifier.width(10.dp)) }
                        Text(greeting(app.session?.profile?.name), color = DS.Text, fontSize = if (wide) 32.sp else 24.sp,
                            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!wide) app.session?.let { s ->
                            Box(Modifier.focusRing(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).clickable { state.tab = Tab.Profile }) {
                                Avatar(s.profile, 32.dp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Segmented(
                            listOf("Home" to Icons.Default.Home, "Live" to Icons.Default.LiveTv, "Guide" to Icons.Default.CalendarViewWeek),
                            selected = 0,
                        ) { i -> state.tab = when (i) { 1 -> Tab.Live; 2 -> Tab.Guide; else -> Tab.Home } }
                        if (wide) {
                            Text(statusLine(liveNow.size, games.count { it.second.isLive }, nextGame), color = DS.TextDim, fontSize = 13.sp,
                                modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PillButton("Favorites", { state.tab = Tab.Favorites }, Icons.Default.Favorite)
                            Spacer(Modifier.width(8.dp))
                            PillButton("Customize", { state.push(Route.HomeSettings) }, Icons.Default.Tune)
                        }
                    }
                    if (!wide) {
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Favorites", { state.tab = Tab.Favorites }, Icons.Default.Favorite)
                            PillButton("Multiview", { state.tab = Tab.Multiview }, Icons.Default.GridView)
                            PillButton("Customize", { state.push(Route.HomeSettings) }, Icons.Default.Tune)
                            PillButton("Settings", { state.tab = Tab.Settings }, Icons.Default.Settings)
                        }
                        Text(statusLine(liveNow.size, games.count { it.second.isLive }, nextGame), color = DS.TextDim, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }

            // Bento grid
            if (HomeLayout.shows("featured")) item {
                val feature: @Composable (Modifier) -> Unit = { m ->
                    FeatureCard(current, featured.size, if (featured.isEmpty()) 0 else rotate % featured.size, m) { holdRotate = System.currentTimeMillis() }
                }
                val upNext: @Composable (Modifier) -> Unit = { m ->
                    UpNextCard(nextGame, hide, cont.getOrNull(if (current?.hero?.badge == "Continue watching") 1 else 0), m,
                        onGame = { g -> state.push(Route.GameDetail(g)) },
                        onResume = { p -> state.play(resumeRequest(p)) },
                        onGuide = { state.tab = Tab.Guide })
                }
                val tall: @Composable (Modifier) -> Unit = { m ->
                    OnNowCard(onNowChannel, movieRows.valueOrNull()?.firstOrNull()?.second?.firstOrNull(), m,
                        onPlay = { c -> playLive(c) }, onMovie = { v -> state.push(Route.MovieDetail(v)) }, onLive = { state.tab = Tab.Live })
                }
                val tiles = listOf<@Composable (Modifier) -> Unit>(
                    { m -> StatTile("Live TV", Icons.Default.LiveTv, DS.Live, if (channels.isEmpty()) "–" else "%,d".format(channels.size),
                        "channels · ${favIds.size} favorites", onClick = { state.tab = Tab.Live }, modifier = m) },
                    { m -> StatTile("My teams", Icons.Default.SportsFootball, Color(0xFFFF9F0A),
                        if (favTeams.isEmpty()) "–" else games.size.toString(),
                        when { favTeams.isEmpty() -> "Pick your teams"; games.isEmpty() -> "No games today"
                            else -> "games · ${games.count { it.second.isLive }} live now" },
                        onClick = { state.tab = Tab.Sports }, modifier = m) },
                    if (!showMovies && !showSeries) { m -> StatTile("Favorites", Icons.Default.Favorite, Color(0xFFFF375F), favChannels.size.toString(),
                        favChannels.firstOrNull()?.let { "channels · ${it.name} and more" } ?: "Hold OK on a channel to add",
                        onClick = { state.tab = Tab.Favorites }, modifier = m) } else
                    { m -> StatTile("Watching", Icons.Default.History, Color(0xFF2D7FF9), cont.size.toString(),
                        cont.firstOrNull()?.let { "Last: ${it.title}" } ?: "Nothing in progress",
                        onClick = { cont.firstOrNull()?.let { state.play(resumeRequest(it)) } ?: run { state.tab = Tab.Movies } },
                        modifier = m, progress = cont.firstOrNull()?.fraction) },
                )
                if (wide) {
                    Row(Modifier.fillMaxWidth().height(360.dp).padding(horizontal = pad).padding(top = 18.dp)) {
                        Column(Modifier.weight(1.75f).fillMaxHeight()) {
                            feature(Modifier.fillMaxWidth().weight(1f))
                            Spacer(Modifier.height(12.dp))
                            upNext(Modifier.fillMaxWidth().height(96.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        tall(Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            tiles.forEach { it(Modifier.fillMaxWidth().weight(1f)) }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = pad).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        feature(Modifier.fillMaxWidth().height(230.dp))
                        upNext(Modifier.fillMaxWidth().height(92.dp))
                        Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            tall(Modifier.weight(1f).fillMaxHeight())
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                tiles.take(2).forEach { it(Modifier.fillMaxWidth().weight(1f)) }
                            }
                        }
                    }
                }
            }

            // Moods
            val moodList = moods.valueOrNull().orEmpty()
            if (moodList.isNotEmpty()) {
                item {
                    LazyRow(
                        Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = pad),
                    ) {
                        items(moodList, key = { it.category.id }) { m ->
                            val i = moodList.indexOf(m)
                            MoodTile(if (i % 2 == 0) "Genre" else "Mood", m.name, m.art, moodColors[i % moodColors.size],
                                onClick = { state.push(Route.VodCategory(m.category, false)) },
                                modifier = Modifier.width(if (wide) 230.dp else 190.dp).height(if (wide) 86.dp else 76.dp))
                        }
                    }
                }
            }

            if (cont.isNotEmpty() && HomeLayout.shows("continue")) {
                item { SectionHeader("Continue watching", "Pick up where you left off", modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(cont, key = { it.key }) { p ->
                            WideCard(
                                title = p.title, subtitle = p.subtitle, image = p.poster, progress = p.fraction, width = 260.dp,
                                onClick = { state.play(resumeRequest(p)) }, onLongClick = { app.prefs.removeProgress(p.key) },
                                onFocused = {},
                            )
                        }
                    }
                }
            }

            if (favChannels.isNotEmpty() && HomeLayout.shows("favorites")) {
                item { SectionHeader("Favorites", "Your channels, live now", onSeeAll = { state.tab = Tab.Favorites }, modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(favChannels, key = { "f" + it.streamId }) { c ->
                            Box(Modifier.width(280.dp)) { FavChannelTile(c, onClick = { playLive(c) }, onLongClick = null) }
                        }
                    }
                }
            } else if (favChannels.isEmpty() && HomeLayout.shows("favorites") && HomeLayout.isLiveOnly) {
                item { SectionHeader("Favorites", "Hold OK on a channel in Live TV to add it", modifier = Modifier.padding(horizontal = pad)) }
            }

            if (recentChannels.isNotEmpty() && HomeLayout.shows("recent")) {
                item { SectionHeader("Recently watched", "Channels you watched lately", onSeeAll = { state.tab = Tab.Live }, modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(recentChannels, key = { "r" + it.streamId }) { c -> LiveNowCard(c, onClick = { playLive(c) }, onFocused = {}) }
                    }
                }
            }

            if (games.isNotEmpty() && HomeLayout.shows("teams")) {
                item { SectionHeader("My teams", "Today's games", onSeeAll = { state.tab = Tab.Sports }, modifier = Modifier.padding(horizontal = pad)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(games, key = { it.first.key + it.second.id }) { (_, g) ->
                            Box(Modifier.width(340.dp)) {
                                GameRow(g, favorite = true, hideScores = hide, onClick = { state.push(Route.GameDetail(g)) }, onLongClick = {})
                            }
                        }
                    }
                }
            }

            movieRows.valueOrNull().orEmpty().forEach { (cat, list) ->
                item(key = "m-${cat.id}") {
                    SectionHeader(cleanCategoryName(cat.name), "Movies", onSeeAll = { state.push(Route.VodCategory(cat, false)) },
                        modifier = Modifier.padding(horizontal = pad))
                }
                item(key = "mr-${cat.id}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(list, key = { it.streamId }) { v -> MoviePoster(v, state, progress) {} }
                    }
                }
            }
            seriesRows.valueOrNull().orEmpty().forEach { (cat, list) ->
                item(key = "s-${cat.id}") {
                    SectionHeader(cleanCategoryName(cat.name), "Series", onSeeAll = { state.push(Route.VodCategory(cat, true)) },
                        modifier = Modifier.padding(horizontal = pad))
                }
                item(key = "sr-${cat.id}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pad)) {
                        items(list, key = { it.seriesId }) { s ->
                            PosterCard(s.name, s.cover, 140.dp, onClick = { state.push(Route.SeriesDetail(s)) })
                        }
                    }
                }
            }

            if (all is Load.Loading) item { LoadView(all) {} }
            if (cont.isEmpty() && liveNow.isEmpty() && all is Load.Ok && movieRows.valueOrNull().isNullOrEmpty()) {
                item {
                    Text("Start watching from Live TV, Movies or Series and your favorites and progress show up here. " +
                        "Long-press (hold OK) on a channel to favorite it.", color = DS.TextDim, modifier = Modifier.padding(pad))
                }
            }
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

private fun statusLine(onNow: Int, liveGames: Int, next: Game?): String {
    val parts = ArrayList<String>()
    if (liveGames > 0) parts += "$liveGames of your games ${if (liveGames == 1) "is" else "are"} live"
    else if (next != null) parts += "Next up: ${next.away.abbr} @ ${next.home.abbr} at ${fmtTime(next.startMs)}"
    if (onNow > 0) parts += "$onNow channels ready to watch"
    return parts.joinToString(" · ").ifBlank { "Everything you watch, in one place." }
}

/** The big rotating card: wide art fills it, posters sit on the right over a tinted fade. */
@Composable
private fun FeatureCard(f: Featured?, count: Int, index: Int, modifier: Modifier, onInteract: () -> Unit) {
    val hero = f?.hero
    val poster = hero?.imageIsPoster == true
    BentoCard(modifier, image = if (poster) null else hero?.image, onClick = f?.onPlay, onFocused = onInteract) {
        if (poster && hero?.badge == "On now") {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF15151A), DS.Accent.copy(alpha = 0.55f)))))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).size(130.dp).clip(RoundedCornerShape(28.dp)).background(Color(0x26FFFFFF)),
                contentAlignment = Alignment.Center) {
                hero.image?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(18.dp)) }
                    ?: Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
        } else if (poster) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF15151A), DS.Accent.copy(alpha = 0.55f)))))
            hero?.image?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(2f / 3f).padding(14.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
        if (hero == null) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF15151A), DS.Accent.copy(alpha = 0.45f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                BrandMark(34.dp, nameSize = 26)
                Text(if (HomeLayout.isLiveOnly) "Favorite a channel and what's on shows up here." else "Your channels, movies and shows.", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            return@BentoCard
        }
        hero.badge?.let {
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) {
                if (it == "On now") ArtChip("On now", Icons.Default.FiberManualRecord, color = DS.Live) else ArtChip(it, Icons.Default.AutoAwesome)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if (poster) 0.62f else 0.85f).padding(start = 20.dp, bottom = 18.dp, end = 12.dp)) {
            Text(hero.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp,
                letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hero.meta?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            hero.progress?.let { p ->
                Box(Modifier.padding(top = 8.dp).width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p).height(4.dp).background(DS.Accent))
                }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(when (hero.badge) { "Continue watching" -> "Resume"; "Series" -> "Episodes"; "On now" -> "Watch"; else -> "Play" },
                    f!!.onPlay, Icons.Default.PlayArrow, primary = true, onImage = true)
                f.onInfo?.let { CircleButton(Icons.Default.Info, it, contentDescription = "More info") }
            }
        }
        if (count > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(count) { i ->
                    Box(Modifier.size(if (i == index) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

/** Short wide card under the feature: next game, else something to resume, else the guide. */
@Composable
private fun UpNextCard(
    game: Game?, hide: Boolean, resume: ProgressEntry?, modifier: Modifier,
    onGame: (Game) -> Unit, onResume: (ProgressEntry) -> Unit, onGuide: () -> Unit,
) {
    when {
        game != null -> BentoCard(modifier, onClick = { onGame(game) }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (game.isLive) ArtChip("LIVE", color = DS.Live) else ArtChip("Up next · ${dayLabel(game.startMs)} ${fmtTime(game.startMs)}",
                        color = DS.AccentSoft, textColor = DS.Text)
                    Text("${game.away.nickname} at ${game.home.nickname}", color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(listOfNotNull(game.league.short, if (game.isLive && !hide) game.detail else null, game.broadcasts.firstOrNull()).joinToString(" · "),
                        color = DS.TextDim, fontSize = 12.sp, maxLines = 1)
                }
                listOf(game.away.logo, game.home.logo).forEach { l ->
                    AsyncImage(l, null, contentScale = ContentScale.Fit, modifier = Modifier.padding(start = 8.dp).size(44.dp))
                }
            }
        }
        resume != null -> BentoCard(modifier, onClick = { onResume(resume) }) {
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(DS.Surface2)) {
                    resume.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    ArtChip("Up next", Icons.Default.PlayArrow, color = DS.AccentSoft, textColor = DS.Text)
                    Text(resume.title, color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    resume.subtitle?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
        else -> BentoCard(modifier, onClick = onGuide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarViewWeek, null, tint = DS.Accent, modifier = Modifier.size(28.dp))
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("What's on tonight", color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Open the TV guide", color = DS.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Tall card: the first favorite channel and what's on, else a movie poster. */
@Composable
private fun OnNowCard(
    c: Channel?, movie: Vod?, modifier: Modifier,
    onPlay: (Channel) -> Unit, onMovie: (Vod) -> Unit, onLive: () -> Unit,
) {
    val repo = DekStreamApp.instance.repo
    if (c != null && repo != null) {
        val now by produceState<Program?>(null, c.streamId) { value = runCatching { repo.nowNext(c).firstOrNull { it.isLive() } }.getOrNull() }
        BentoCard(modifier, onClick = { onPlay(c) }) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DS.Accent.copy(alpha = 0.55f), Color(0xFF0E0E12)))))
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) { ArtChip("On now", Icons.Default.FiberManualRecord, color = DS.Live) }
            Box(Modifier.align(Alignment.Center).padding(bottom = 40.dp).size(96.dp).clip(RoundedCornerShape(22.dp)).background(Color(0x26FFFFFF)),
                contentAlignment = Alignment.Center) {
                if (c.icon != null) AsyncImage(c.icon, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp))
                else Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(now?.title ?: c.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                Text(c.name + (now?.let { " · ${minutesLeft(it)} left" } ?: ""), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1)
                val p = now
                if (p != null) {
                    val f = ((System.currentTimeMillis() - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(Color.White))
                    }
                }
            }
        }
    } else if (movie != null) {
        BentoCard(modifier, image = movie.poster, onClick = { onMovie(movie) }) {
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) { ArtChip("Recently added", Icons.Default.Check) }
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(movie.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Movie", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
            }
        }
    } else {
        BentoCard(modifier, onClick = onLive) {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LiveTv, null, tint = DS.Accent, modifier = Modifier.size(40.dp))
                Text("Browse Live TV", color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text("Favorite a channel to pin it here", color = DS.TextDim, fontSize = 12.sp)
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val c = java.util.Calendar.getInstance(); val today = c.get(java.util.Calendar.DAY_OF_YEAR)
    c.timeInMillis = ms
    return when (c.get(java.util.Calendar.DAY_OF_YEAR) - today) {
        0 -> "Today"; 1 -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

private fun resumeRequest(p: ProgressEntry) =
    PlayRequest(p.url, p.title, p.subtitle, isLive = false, progressKey = p.key, poster = p.poster, startPositionMs = p.positionMs)


@Composable
private fun MoviePoster(v: Vod, state: AppState, progress: Map<String, ProgressEntry>, onFocus: (Featured) -> Unit) {
    val repo = DekStreamApp.instance.repo ?: return
    var focused by remember { mutableStateOf(false) }
    // Look up wide art and plot once the poster is focused.
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        onFocus(Featured(HeroItem("v-${v.streamId}", v.name, v.poster, badge = "MOVIE", imageIsPoster = true),
            { state.play(repo.vodRequest(v)) }, { state.push(Route.MovieDetail(v)) }))
        delay(350)
        val info = repo.vodInfo(v) ?: return@LaunchedEffect
        if (focused) onFocus(Featured(
            HeroItem("v-${v.streamId}", v.name, info.backdrop ?: v.poster,
                listOfNotNull(info.releaseDate?.take(4), info.duration, info.genre, info.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                info.plot, "MOVIE", info.backdrop == null),
            { state.play(repo.vodRequest(v)) }, { state.push(Route.MovieDetail(v)) },
        ))
    }
    PosterCard(
        v.name, v.poster, 140.dp, onClick = { state.push(Route.MovieDetail(v)) },
        progress = progress["vod:${v.streamId}"]?.fraction,
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
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
