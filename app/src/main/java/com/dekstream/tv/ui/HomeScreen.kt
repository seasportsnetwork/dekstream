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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    // A couple of movie and series rows to make Home feel alive.
    val movieRows by rememberLoad(Unit) {
        repo.vodCategories().take(2).map { c -> c.name to repo.vods(c.id).filter { it.poster != null }.take(30) }.filter { it.second.isNotEmpty() }
    }
    val seriesRows by rememberLoad(Unit) {
        repo.seriesCategories().take(2).map { c -> c.name to repo.series(c.id).filter { it.cover != null }.take(30) }.filter { it.second.isNotEmpty() }
    }

    val channels = all.valueOrNull().orEmpty()
    val byId = channels.associateBy { it.streamId }
    val liveNow = (favIds.mapNotNull { byId[it] } + recentIds.mapNotNull { byId[it] }).distinctBy { it.streamId }.take(20)
    val cont = progress.values.sortedByDescending { it.updatedAt }

    // Featured: continue watching + new movies/series with wide art (looked up in the background).
    val featured by produceState<List<Featured>>(emptyList(), movieRows, seriesRows, cont.size) {
        val out = ArrayList<Featured>()
        cont.take(3).forEach { p ->
            out += Featured(
                HeroItem("c-" + p.key, p.title, p.poster, meta = p.subtitle ?: "Continue watching", badge = "CONTINUE",
                    imageIsPoster = true, progress = p.fraction),
                onPlay = { state.play(resumeRequest(p)) }, onInfo = null,
            )
        }
        movieRows.valueOrNull()?.firstOrNull()?.second?.take(5)?.forEach { v ->
            val info = repo.vodInfo(v)
            out += Featured(
                HeroItem("v-${v.streamId}", v.name, info?.backdrop ?: v.poster,
                    meta = listOfNotNull(info?.releaseDate?.take(4), info?.genre, info?.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                    description = info?.plot, badge = "MOVIE", imageIsPoster = info?.backdrop == null),
                onPlay = { state.play(repo.vodRequest(v)) }, onInfo = { state.push(Route.MovieDetail(v)) },
            )
        }
        seriesRows.valueOrNull()?.firstOrNull()?.second?.take(3)?.forEach { s ->
            out += Featured(
                HeroItem("s-${s.seriesId}", s.name, s.backdrop ?: s.cover,
                    meta = listOfNotNull(s.releaseDate?.take(4), s.genre, s.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                    description = s.plot, badge = "SERIES", imageIsPoster = s.backdrop == null),
                onPlay = { state.push(Route.SeriesDetail(s)) }, onInfo = { state.push(Route.SeriesDetail(s)) },
            )
        }
        value = out
    }

    // Header follows focus; when nothing is focused it rotates through the featured titles.
    var focusedHero by remember { mutableStateOf<Featured?>(null) }
    var rotate by remember { mutableIntStateOf(0) }
    var lastFocus by remember { mutableLongStateOf(0L) }
    LaunchedEffect(featured.size) {
        while (true) {
            delay(8_000)
            if (System.currentTimeMillis() - lastFocus > 12_000) { focusedHero = null; rotate++ }
        }
    }
    val current = focusedHero ?: featured.getOrNull(if (featured.isEmpty()) 0 else rotate % featured.size)
    fun focus(f: Featured) { focusedHero = f; lastFocus = System.currentTimeMillis() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroH = (maxHeight * 0.52f).coerceIn(260.dp, 460.dp)
        HeroBackdrop(current?.hero, Modifier.fillMaxWidth().height(heroH + 80.dp))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // Header: brand + profile, then featured info and buttons
            item {
                Column(Modifier.fillMaxWidth().height(heroH).padding(start = 28.dp, end = 28.dp, top = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(32.dp, nameSize = 20)
                        Spacer(Modifier.weight(1f))
                        app.session?.let { s ->
                            Row(
                                Modifier.focusRing(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                                    .clickable { state.tab = Tab.Profile }.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(s.profile.name, color = DS.Text.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                                Avatar(s.profile, 30.dp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    HeroInfo(current?.hero) {
                        current?.let { f ->
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = f.onPlay,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                                    modifier = Modifier.focusRing(RoundedCornerShape(10.dp)).onFocusChanged { if (it.isFocused) lastFocus = System.currentTimeMillis() },
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                                    Text(if (f.hero.badge == "CONTINUE") " Resume" else if (f.hero.badge == "SERIES") " Episodes" else " Play",
                                        fontWeight = FontWeight.SemiBold)
                                }
                                f.onInfo?.let { info ->
                                    Button(
                                        onClick = info,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.18f), contentColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        modifier = Modifier.focusRing(RoundedCornerShape(10.dp)),
                                    ) {
                                        Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                                        Text(" More info")
                                    }
                                }
                            }
                        }
                    }
                    // Featured dots
                    if (featured.size > 1 && focusedHero == null) {
                        Row(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            featured.indices.forEach { i ->
                                val on = i == rotate % featured.size
                                Box(Modifier.size(if (on) 18.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (on) Color.White else Color.White.copy(alpha = 0.35f)))
                            }
                        }
                    } else Spacer(Modifier.height(26.dp))
                }
            }

            // Shortcuts on narrow screens (these tabs aren't in the bottom bar)
            item {
                Row(Modifier.padding(start = 28.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Tab.Search, Tab.Multiview, Tab.Profile, Tab.Settings).forEach { t ->
                        val shape = RoundedCornerShape(18.dp)
                        Row(
                            Modifier.focusRing(shape).clip(shape).background(DS.Surface.copy(alpha = 0.85f))
                                .clickable { state.tab = t }.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(t.icon, null, tint = DS.TextDim, modifier = Modifier.size(16.dp))
                            Text(" ${t.label}", color = DS.Text, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (cont.isNotEmpty()) {
                item { RowTitle("Continue watching") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                        items(cont, key = { it.key }) { p ->
                            WideCard(
                                title = p.title, subtitle = p.subtitle, image = p.poster, progress = p.fraction, width = 260.dp,
                                onClick = { state.play(resumeRequest(p)) }, onLongClick = { app.prefs.removeProgress(p.key) },
                                onFocused = {
                                    focus(Featured(HeroItem("c-" + p.key, p.title, p.poster, p.subtitle, null, "CONTINUE", true, p.fraction),
                                        { state.play(resumeRequest(p)) }, null))
                                },
                            )
                        }
                    }
                }
            }

            if (liveNow.isNotEmpty()) {
                item { RowTitle("Live now · your channels") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                        items(liveNow, key = { it.streamId }) { c ->
                            LiveNowCard(c, onClick = {
                                app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), liveNow)
                            }, onFocused = { prog ->
                                focus(Featured(
                                    HeroItem("l-${c.streamId}", prog?.title ?: c.name, c.icon,
                                        meta = c.name + (prog?.let { "  ·  ${fmtTime(it.startMs)} – ${fmtTime(it.endMs)}  ·  ${minutesLeft(it)} left" } ?: ""),
                                        description = prog?.description, badge = "LIVE", imageIsPoster = true),
                                    { app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), liveNow) }, null,
                                ))
                            })
                        }
                    }
                }
            }

            val games = sports.valueOrNull().orEmpty()
            if (games.isNotEmpty()) {
                item { RowTitle("My teams") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                        items(games, key = { it.first.key + it.second.id }) { (_, g) ->
                            Box(Modifier.width(340.dp)) {
                                GameRow(g, favorite = true, hideScores = hide, onClick = { state.push(Route.GameDetail(g)) }, onLongClick = {})
                            }
                        }
                    }
                }
            }

            movieRows.valueOrNull().orEmpty().forEach { (name, list) ->
                item(key = "m-$name") { RowTitle(name) }
                item(key = "mr-$name") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                        items(list, key = { it.streamId }) { v -> MoviePoster(v, state, progress) { f -> focus(f) } }
                    }
                }
            }
            seriesRows.valueOrNull().orEmpty().forEach { (name, list) ->
                item(key = "s-$name") { RowTitle(name) }
                item(key = "sr-$name") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                        items(list, key = { it.seriesId }) { s ->
                            PosterCard(
                                s.name, s.cover, 140.dp, onClick = { state.push(Route.SeriesDetail(s)) },
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) focus(Featured(
                                        HeroItem("s-${s.seriesId}", s.name, s.backdrop ?: s.cover,
                                            listOfNotNull(s.releaseDate?.take(4), s.genre, s.rating?.let { r -> "★ $r" }).joinToString("  ·  ").ifBlank { null },
                                            s.plot, "SERIES", s.backdrop == null),
                                        { state.push(Route.SeriesDetail(s)) }, { state.push(Route.SeriesDetail(s)) },
                                    ))
                                },
                            )
                        }
                    }
                }
            }

            if (all is Load.Loading) item { LoadView(all) {} }
            if (cont.isEmpty() && liveNow.isEmpty() && all is Load.Ok && movieRows.valueOrNull().isNullOrEmpty()) {
                item {
                    Text("Start watching from Live TV, Movies or Series and your favorites and progress show up here. " +
                        "Long-press (hold OK) on a channel to favorite it.", color = DS.TextDim, modifier = Modifier.padding(28.dp))
                }
            }
        }
    }
}

private fun resumeRequest(p: ProgressEntry) =
    PlayRequest(p.url, p.title, p.subtitle, isLive = false, progressKey = p.key, poster = p.poster, startPositionMs = p.positionMs)

@Composable
private fun RowTitle(text: String) {
    Text(text, color = DS.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, top = 22.dp, bottom = 10.dp))
}

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
    val shape = RoundedCornerShape(12.dp)
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
