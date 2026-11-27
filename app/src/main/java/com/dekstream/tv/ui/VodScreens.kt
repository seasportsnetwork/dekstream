package com.dekstream.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Category
import com.dekstream.tv.data.Series
import com.dekstream.tv.data.Vod

/** Netflix-style rows: one row per provider category, loaded as it scrolls into view. */
@Composable
fun VodBrowseScreen(state: AppState, isSeries: Boolean) {
    val repo = DekStreamApp.instance.repo ?: return
    val allow = kidsAllowed(if (isSeries) "s:" else "v:")
    val cats by rememberLoad(isSeries) {
        (if (isSeries) repo.seriesCategories() else repo.vodCategories()).filter { allow == null || it.id in allow }
    }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    LoadView(cats) { list ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroH = (maxHeight * 0.42f).coerceIn(220.dp, 380.dp)
            HeroBackdrop(hero, Modifier.fillMaxWidth().height(heroH + 70.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 0.dp, bottom = 24.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().height(heroH).padding(top = 18.dp, end = 24.dp)) {
                        Text(if (isSeries) "Series" else "Movies", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        HeroInfo(hero ?: HeroItem("none", if (isSeries) "Find your next show" else "Find something to watch", null,
                            description = "Move across the posters to see details here.", imageIsPoster = true))
                        Spacer(Modifier.height(10.dp))
                    }
                }
                items(list, key = { it.id }) { cat -> CategoryRow(cat, isSeries, state) { hero = it } }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: Category, isSeries: Boolean, state: AppState, onHero: (HeroItem) -> Unit) {
    val repo = DekStreamApp.instance.repo ?: return
    val progress by DekStreamApp.instance.prefs.progress.collectAsState()
    Column {
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.padding(top = 14.dp, bottom = 8.dp).focusRing(shape).clip(shape)
                .clickable { state.push(Route.VodCategory(cat, isSeries)) }.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cat.name, color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.ChevronRight, contentDescription = "See all", tint = DS.TextDim)
        }
        if (isSeries) {
            val items by rememberLoad(cat.id) { repo.series(cat.id) }
            LoadView(items, Modifier.height(220.dp)) { list ->
                LazyRow(horizontalArrangement = rowSpacing, contentPadding = PaddingValues(end = 20.dp)) {
                    items(list.distinctBy { it.seriesId }.take(40), key = { it.seriesId }) { s ->
                        PosterCard(
                            com.dekstream.tv.data.Top10.displayName(s.name), s.cover, 130.dp, onClick = { state.push(Route.SeriesDetail(s)) },
                            onLongClick = { state.titleMenu = com.dekstream.tv.data.SavedTitle.of(s) },
                            modifier = Modifier.onFocusChanged {
                                if (it.hasFocus) onHero(HeroItem("s-${s.seriesId}", s.name, s.backdrop ?: s.cover,
                                    listOfNotNull(s.releaseDate?.take(4), s.genre, s.rating?.let { r -> "★ $r" }).joinToString("  ·  ").ifBlank { null },
                                    s.plot, null, s.backdrop == null))
                            },
                        )
                    }
                }
            }
        } else {
            val items by rememberLoad(cat.id) { repo.vods(cat.id) }
            LoadView(items, Modifier.height(220.dp)) { list ->
                LazyRow(horizontalArrangement = rowSpacing, contentPadding = PaddingValues(end = 20.dp)) {
                    items(list.distinctBy { it.streamId }.take(40), key = { it.streamId }) { v ->
                        var focused by remember { mutableStateOf(false) }
                        LaunchedEffect(focused) {
                            if (!focused) return@LaunchedEffect
                            onHero(HeroItem("v-${v.streamId}", v.name, v.poster, imageIsPoster = true))
                            kotlinx.coroutines.delay(350)
                            val info = repo.vodInfo(v) ?: return@LaunchedEffect
                            if (focused) onHero(HeroItem("v-${v.streamId}", v.name, info.backdrop ?: v.poster,
                                listOfNotNull(info.releaseDate?.take(4), info.duration, info.genre, info.rating?.let { "★ $it" }).joinToString("  ·  ").ifBlank { null },
                                info.plot, null, info.backdrop == null))
                        }
                        PosterCard(
                            com.dekstream.tv.data.Top10.displayName(v.name), v.poster, 130.dp,
                            progress = progress["vod:${v.streamId}"]?.fraction,
                            onClick = { state.push(Route.MovieDetail(v)) },
                            onLongClick = { state.titleMenu = com.dekstream.tv.data.SavedTitle.of(v) },
                            modifier = Modifier.onFocusChanged { focused = it.hasFocus },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackHeader(title: String, state: AppState) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DS.Text)
        }
        Text(title, color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Full grid for one category. */
@Composable
fun VodCategoryScreen(cat: Category, isSeries: Boolean, state: AppState) {
    val repo = DekStreamApp.instance.repo ?: return
    val progress by DekStreamApp.instance.prefs.progress.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackHeader(cat.name, state)
        if (isSeries) {
            val items by rememberLoad(cat.id) { repo.series(cat.id) }
            LoadView(items) { list ->
                LazyVerticalGrid(
                    GridCells.Adaptive(130.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list.distinctBy { it.seriesId }, key = { it.seriesId }) { s ->
                        PosterCard(com.dekstream.tv.data.Top10.displayName(s.name), s.cover, 130.dp, onClick = { state.push(Route.SeriesDetail(s)) },
                            onLongClick = { state.titleMenu = com.dekstream.tv.data.SavedTitle.of(s) })
                    }
                }
            }
        } else {
            val items by rememberLoad(cat.id) { repo.vods(cat.id) }
            LoadView(items) { list ->
                LazyVerticalGrid(
                    GridCells.Adaptive(130.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list.distinctBy { it.streamId }, key = { it.streamId }) { v ->
                        PosterCard(com.dekstream.tv.data.Top10.displayName(v.name), v.poster, 130.dp, progress = progress["vod:${v.streamId}"]?.fraction,
                            onClick = { state.push(Route.MovieDetail(v)) }, onLongClick = { state.titleMenu = com.dekstream.tv.data.SavedTitle.of(v) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(image: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        if (image != null) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), DS.Bg))))
        Box(Modifier.align(Alignment.BottomStart).padding(20.dp)) { content() }
    }
}

@Composable
fun MovieDetailScreen(v: Vod, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val info by rememberLoad(v.streamId) { repo.vodInfo(v) }
    // Full details (rating, cast, artwork) from Cinemeta, matched by title + year.
    var meta by remember(v.streamId) { mutableStateOf<com.dekstream.tv.data.MetaDetail?>(null) }
    LaunchedEffect(v.streamId) { meta = runCatching { com.dekstream.tv.data.Stremio.lookup("movie", v.name) }.getOrNull() }
    val progress by app.prefs.progress.collectAsState()
    val resume = progress["vod:${v.streamId}"]
    BackHandler { state.pop() }
    val i = info.valueOrNull()
    val p = meta?.preview
    val backdrop = p?.background ?: i?.backdrop ?: p?.poster ?: v.poster
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(DS.Bg)) {
        if (backdrop != null) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(DS.Bg, DS.Bg.copy(alpha = 0.85f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, DS.Bg))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 40.dp)) {
            item { Box(Modifier.safeDrawingPadding()) { BackHeader("", state) } }
            item {
                Column(Modifier.fillMaxWidth(0.6f).padding(top = 40.dp)) {
                    if (p?.logo != null) {
                        AsyncImage(p.logo, p.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                            modifier = Modifier.height(110.dp).fillMaxWidth())
                    } else {
                        Text(p?.name ?: v.name, color = DS.Text, fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    // Rating · year · runtime · genres
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val rating = p?.rating ?: i?.rating
                        if (!rating.isNullOrBlank() && rating != "0") {
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF5C518)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                Text("IMDb $rating", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val bits = listOfNotNull(p?.year ?: i?.releaseDate?.take(4), meta?.runtime ?: i?.duration)
                        if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), color = DS.Text, fontSize = 15.sp)
                    }
                    val genres = p?.genres?.takeIf { it.isNotEmpty() }?.take(4) ?: i?.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                    if (genres.isNotEmpty()) {
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            genres.forEach { g ->
                                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, DS.Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                                    Text(g, color = DS.TextDim, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { state.play(repo.vodRequest(v)) },
                            colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                            modifier = Modifier.focusRequester(playFocus).focusRing(RoundedCornerShape(24.dp)),
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Text(if (resume != null) " Resume" else " Play")
                        }
                        val myList by app.prefs.myList.collectAsState()
                        val saved = myList.any { it.kind == "movie" && it.id == v.streamId }
                        Button(
                            onClick = { app.prefs.toggleMyList(com.dekstream.tv.data.SavedTitle.of(v)) },
                            colors = ButtonDefaults.buttonColors(containerColor = DS.Surface2),
                            modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null)
                            Text(if (saved) " On my list" else " My list")
                        }
                        if (resume != null) {
                            Button(
                                onClick = { state.play(repo.vodRequest(v).copy(startPositionMs = 0)) },
                                colors = ButtonDefaults.buttonColors(containerColor = DS.Surface2),
                                modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                            ) { Text("Start over") }
                        }
                    }
                    if (resume != null && resume.durationMs > 0) {
                        val left = ((resume.durationMs - resume.positionMs) / 60000).coerceAtLeast(1)
                        Text("$left min left", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    val plot = p?.description?.takeIf { it.isNotBlank() } ?: i?.plot
                    plot?.let { ExpandableText(it) }
                    if (info is Load.Loading && meta == null) LoadView(info) {}
                }
            }
            item(key = "extras") {
                val castNames = meta?.cast?.takeIf { it.isNotEmpty() } ?: i?.cast?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                DetailExtras(
                    "movie", meta?.tmdbId, p?.id?.takeIf { it.startsWith("tt") }, p?.name ?: com.dekstream.tv.data.Top10.displayName(v.name),
                    p?.year ?: i?.releaseDate?.take(4),
                    BasicCredits(castNames, meta?.director ?: i?.director, meta?.writers.orEmpty(), meta?.trailer),
                    onAddKey = { state.push(Route.Addons) }, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(s: Series, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val info by rememberLoad(s.seriesId) { repo.seriesInfo(s) }
    var smeta by remember(s.seriesId) { mutableStateOf<com.dekstream.tv.data.MetaDetail?>(null) }
    LaunchedEffect(s.seriesId) { smeta = runCatching { com.dekstream.tv.data.Stremio.lookup("series", s.name) }.getOrNull() }
    val progress by app.prefs.progress.collectAsState()
    var season by rememberSaveable { mutableIntStateOf(-1) }
    BackHandler { state.pop() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box {
                Hero(info.valueOrNull()?.backdrop ?: s.cover) {
                    Text(s.name, color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.safeDrawingPadding()) { BackHeader("", state) }
            }
        }
        val i = info.valueOrNull()
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                val meta = listOfNotNull(s.releaseDate?.take(4), i?.genre ?: s.genre, s.rating?.let { "★ $it" }).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = DS.TextDim, fontSize = 14.sp)
                val myList by app.prefs.myList.collectAsState()
                val saved = myList.any { it.kind == "series" && it.id == s.seriesId }
                Row(Modifier.padding(top = 12.dp)) {
                    PillButton(if (saved) "On my list" else "My list", { app.prefs.toggleMyList(com.dekstream.tv.data.SavedTitle.of(s)) },
                        if (saved) Icons.Default.Check else Icons.Default.Add)
                }
                (smeta?.preview?.description?.takeIf { it.isNotBlank() } ?: i?.plot ?: s.plot)?.let { ExpandableText(it, modifier = Modifier.padding(top = 10.dp)) }
                if (info is Load.Loading || info is Load.Err) LoadView(info) {}
                val castNames = smeta?.cast?.takeIf { it.isNotEmpty() } ?: i?.cast?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                DetailExtras(
                    "series", smeta?.tmdbId, smeta?.preview?.id?.takeIf { it.startsWith("tt") },
                    smeta?.preview?.name ?: com.dekstream.tv.data.Top10.displayName(s.name), smeta?.preview?.year?.take(4) ?: s.releaseDate?.take(4),
                    BasicCredits(castNames, smeta?.director, smeta?.writers.orEmpty(), smeta?.trailer),
                    onAddKey = { state.push(Route.Addons) }, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (i != null && i.seasons.isNotEmpty()) {
            val seasonNums = i.seasons.keys.toList()
            val current = if (season in seasonNums) season else seasonNums.first()
            item {
                LazyRow(contentPadding = PaddingValues(20.dp), horizontalArrangement = rowSpacing) {
                    items(seasonNums) { n -> CategoryChip("Season $n", n == current) { season = n } }
                }
            }
            items(i.seasons[current].orEmpty(), key = { it.id }) { e ->
                val p = progress["ep:${e.id}"]
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                        .focusRing(shape, 1.02f).clip(shape).background(DS.Surface)
                        .clickable { state.play(repo.episodeRequest(s, e)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(DS.Surface2)) {
                        if (e.image != null) AsyncImage(e.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                        if (p != null) {
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction).height(4.dp).background(DS.Accent))
                        }
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("${e.episodeNum}. ${e.title}", color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.duration?.let { Text(it, color = DS.TextDim, fontSize = 12.sp) }
                        e.plot?.let { Text(it, color = DS.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
