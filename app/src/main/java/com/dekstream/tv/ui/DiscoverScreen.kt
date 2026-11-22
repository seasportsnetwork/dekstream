package com.dekstream.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.MetaPreview
import com.dekstream.tv.data.Stremio
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** One row on Discover. */
private data class DRow(val key: String, val title: String, val items: List<MetaPreview>, val style: Int = 0) // 0 poster, 1 top 10, 2 wide

private val genres = listOf("Action", "Comedy", "Sci-Fi", "Thriller", "Animation", "Drama", "Crime", "Horror", "Family", "Documentary")

/**
 * Netflix-style Discover: the focused title fills the top of the screen (backdrop, logo, details),
 * rows scroll underneath. Popular, Top 10, new releases, new episodes, coming soon and genres.
 */
@Composable
fun DiscoverScreen(state: AppState) {
    val app = DekStreamApp.instance
    val ctx = LocalContext.current
    var section by rememberSaveable { mutableStateOf(0) } // 0 Home, 1 Movies, 2 Series, 3 My list, 4 Collections
    val hasCollections = Stremio.collectionsUrl(ctx).isNotBlank()
    LaunchedEffect(app.pendingTestSection) { app.pendingTestSection?.let { section = it; app.pendingTestSection = null } }
    val progress by app.prefs.progress.collectAsState()
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()

    val rows by rememberLoad(section) {
        coroutineScope {
            when (section) {
                1 -> {
                    val pop = async { Stremio.catalog("movie", "top") }
                    val new = async { Stremio.catalog("movie", "year", year) }
                    val best = async { Stremio.catalog("movie", "imdbRating") }
                    val soon = async { Stremio.upcomingMovies(ctx) }
                    val g = genres.map { gn -> async { DRow("mg$gn", gn, Stremio.catalog("movie", "top", gn)) } }
                    listOf(DRow("mp", "Popular movies", pop.await()), DRow("mt", "Top 10 movies today", pop.await().take(10), 1),
                        DRow("mn", "New in $year", new.await()), DRow("ms", "Coming soon to theaters", soon.await()),
                        DRow("mr", "Top rated", best.await())) + g.map { it.await() }
                }
                2 -> {
                    val pop = async { Stremio.catalog("series", "top") }
                    val eps = async { Stremio.newEpisodes() }
                    val prem = async { Stremio.premieres() }
                    val new = async { Stremio.catalog("series", "year", year) }
                    val best = async { Stremio.catalog("series", "imdbRating") }
                    val g = genres.map { gn -> async { DRow("sg$gn", gn, Stremio.catalog("series", "top", gn)) } }
                    listOf(DRow("sp", "Popular shows", pop.await()), DRow("st", "Top 10 shows today", pop.await().take(10), 1),
                        DRow("se", "New episodes", eps.await(), 2), DRow("sc", "Premieres coming up", prem.await(), 2),
                        DRow("sn", "New in $year", new.await()), DRow("sr", "Top rated", best.await())) + g.map { it.await() }
                }
                3 -> listOf(DRow("ml", "My list", Stremio.watchlistItems(ctx)))
                4 -> emptyList()
                else -> {
                    val mov = async { Stremio.catalog("movie", "top") }
                    val ser = async { Stremio.catalog("series", "top") }
                    val eps = async { Stremio.newEpisodes() }
                    val prem = async { Stremio.premieres() }
                    val newM = async { Stremio.catalog("movie", "year", year) }
                    val soon = async { Stremio.upcomingMovies(ctx) }
                    val act = async { Stremio.catalog("movie", "top", "Action") }
                    val com = async { Stremio.catalog("movie", "top", "Comedy") }
                    val ani = async { Stremio.catalog("series", "top", "Animation") }
                    val crime = async { Stremio.catalog("series", "top", "Crime") }
                    val trending = (mov.await().take(12) zip ser.await().take(12)).flatMap { listOf(it.first, it.second) }
                    listOf(
                        DRow("ht", "Trending now", trending),
                        DRow("hm", "Top 10 movies today", mov.await().take(10), 1),
                        DRow("he", "New episodes", eps.await(), 2),
                        DRow("hs", "Top 10 shows today", ser.await().take(10), 1),
                        DRow("hn", "New movies", newM.await()),
                        DRow("hc", "Coming soon", (prem.await() + soon.await()).take(30), 2),
                        DRow("ha", "Action movies", act.await()),
                        DRow("hco", "Comedies", com.await()),
                        DRow("hcr", "Crime shows", crime.await()),
                        DRow("han", "Animated shows", ani.await()),
                    )
                }
            }.filter { it.items.isNotEmpty() }
        }
    }
    val cont = progress.values.filter { it.key.startsWith("st:") }.sortedByDescending { it.updatedAt }
    val list = rows.valueOrNull().orEmpty()

    // The hero follows focus (after a short pause), or cycles the first row until something is focused.
    var focusedItem by remember { mutableStateOf<MetaPreview?>(null) }
    var heroItem by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(focusedItem) { focusedItem?.let { delay(350); heroItem = it } }
    LaunchedEffect(list.firstOrNull()?.key) {
        val first = list.firstOrNull()?.items.orEmpty().filter { it.background != null }.take(6)
        var i = 0
        while (first.isNotEmpty() && focusedItem == null) { heroItem = first[i % first.size]; i++; delay(9_000) }
    }
    val hero = heroItem ?: list.firstOrNull()?.items?.firstOrNull()
    fun open(m: MetaPreview) = state.push(Route.Title(m))
    if (section == 4 && !hasCollections) section = 0

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        val wide = maxWidth >= 720.dp
        val heroH = if (wide) maxHeight * 0.48f else 260.dp
        val imgStart = maxWidth * 0.3f
        // Backdrop
        Crossfade(hero?.background ?: hero?.poster, label = "backdrop") { img ->
            Box(Modifier.fillMaxWidth().height(heroH + 120.dp)) {
                if (img != null) AsyncImage(img, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (wide) Modifier.padding(start = imgStart) else Modifier))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    if (wide) listOf(Color(0xFF0A0A0C), Color(0xFF0A0A0C), Color(0xCC0A0A0C), Color.Transparent) else listOf(Color(0x990A0A0C), Color(0x660A0A0C)))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xFF0A0A0C))))
            }
        }
        Column(Modifier.fillMaxSize()) {
            // Top bar: sections
            Row(Modifier.padding(start = 32.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Discover", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 10.dp))
                (listOf("Home", "Movies", "Series", "My list") + if (hasCollections) listOf("Collections") else emptyList())
                    .forEachIndexed { i, l -> Pill(l, section == i, onClick = { section = i; focusedItem = null }) }
            }
            // Hero info
            Box(Modifier.fillMaxWidth().height(heroH - 56.dp).padding(start = 32.dp, end = 32.dp)) {
                hero?.let {
                    if (it.type == "folder") FolderHero(it, Modifier.align(Alignment.BottomStart).widthIn(max = 620.dp))
                    else HeroInfo(it, Modifier.align(Alignment.BottomStart).widthIn(max = 620.dp), onPlay = { open(it) }, onList = { Stremio.toggleWatchlist(ctx, it) })
                }
            }
            if (section == 4) { CollectionRows(state) { f -> focusedItem = f }; return@Column }
            // Rows
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                if (cont.isNotEmpty() && section != 3) {
                    item(key = "cont") {
                        RowTitle("Continue watching")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 32.dp)) {
                            items(cont, key = { it.key }) { p ->
                                WideTile(p.title, p.subtitle, p.poster, p.fraction, onFocus = {},
                                    onClick = { state.play(com.dekstream.tv.data.PlayRequest(p.url, p.title, p.subtitle, false, p.key, p.poster, startPositionMs = p.positionMs)) })
                            }
                        }
                    }
                }
                if (section == 3) {
                    val items = list.firstOrNull()?.items.orEmpty()
                    item {
                        if (items.isEmpty()) Text("Nothing here yet. Open a title and choose My list to save it.", color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(32.dp))
                        else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 32.dp)) {
                            items(items, key = { it.type + it.id }) { m -> PosterTile(m, 150.dp, onFocus = { focusedItem = m }) { open(m) } }
                        }
                    }
                } else list.forEach { r ->
                    item(key = r.key) {
                        RowTitle(r.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(if (r.style == 1) 4.dp else 12.dp), contentPadding = PaddingValues(horizontal = 32.dp)) {
                            itemsIndexed(r.items, key = { _, m -> r.key + m.type + m.id }) { i, m ->
                                when (r.style) {
                                    1 -> TopTenTile(i + 1, m, onFocus = { focusedItem = m }) { open(m) }
                                    2 -> WideTile(m.name, m.note, m.background ?: m.poster, null, onFocus = { focusedItem = m }) { open(m) }
                                    else -> PosterTile(m, 140.dp, onFocus = { focusedItem = m }) { open(m) }
                                }
                            }
                        }
                    }
                }
                if (rows is Load.Loading) item { LoadView(rows) {} }
                if (rows is Load.Err) item { Text("Couldn't load the catalog. Check your internet connection.", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(32.dp)) }
            }
        }
    }
}

@Composable
private fun HeroInfo(m: MetaPreview, modifier: Modifier, onPlay: () -> Unit, onList: () -> Unit) {
    val ctx = LocalContext.current
    var listed by remember(m.id) { mutableStateOf(Stremio.inWatchlist(ctx, m)) }
    Column(modifier.padding(bottom = 12.dp)) {
        if (m.logo != null) AsyncImage(m.logo, m.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.height(90.dp).widthIn(max = 420.dp))
        else Text(m.name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.note != null) Text(m.note, color = Color(0xFF46D369), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            m.rating?.let {
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                Text(" $it", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
            }
            m.year?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp)) }
            Text(if (m.type == "series") "Series" else "Movie", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            if (m.genres.isNotEmpty()) Text("   " + m.genres.take(3).joinToString(" • "), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
        }
        m.description?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (m.upcoming) "Details" else "Play", onPlay, if (m.upcoming) Icons.Default.Info else Icons.Default.PlayArrow, primary = true, onImage = true)
            if (!m.upcoming) PillButton(if (listed) "On my list" else "My list", { onList(); listed = !listed }, if (listed) Icons.Default.Check else Icons.Default.Add, onImage = true)
        }
    }
}

@Composable
private fun RowTitle(t: String) {
    Text(t, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 10.dp))
}

@Composable
private fun PosterTile(m: MetaPreview, width: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.width(width).aspectRatio(2f / 3f).focusRing(shape, 1.08f).onFocusGained(onFocus).clip(shape)
            .background(Color(0xFF1C1C22)).tvClickable(onClick),
    ) {
        AsyncImage(m.poster, m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        m.note?.let {
            Text(it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCCE50914)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
}

/** Netflix-style Top 10: a big outlined number with the poster beside it. */
@Composable
private fun TopTenTile(n: Int, m: MetaPreview, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (n == 10) 104.dp else 64.dp).height(200.dp)) {
            Text("$n", color = Color(0xFF3A3A44), fontSize = 150.sp, fontWeight = FontWeight.Black, letterSpacing = (-12).sp,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xFF9A9AA4), blurRadius = 2f)),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 30.dp))
        }
        Box(Modifier.width(134.dp).aspectRatio(2f / 3f).focusRing(shape, 1.08f).onFocusGained(onFocus).clip(shape)
            .background(Color(0xFF1C1C22)).tvClickable(onClick)) {
            AsyncImage(m.poster, m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun WideTile(title: String, note: String?, image: String?, progress: Float?, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(280.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(shape, 1.06f).onFocusGained(onFocus).clip(shape)
            .background(Color(0xFF1C1C22)).tvClickable(onClick)) {
            AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, color = Color(0xFF46D369), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFFE50914)))
            }
        }
    }
}



/** Collections section: each group is a row of folder tiles with the collection's artwork. */
@Composable
private fun CollectionRows(state: AppState, onFocus: (MetaPreview) -> Unit) {
    val ctx = LocalContext.current
    val groups by rememberLoad(Stremio.collectionsUrl(ctx)) { com.dekstream.tv.data.Collections.groups(ctx) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (Stremio.tmdbKey(ctx).isBlank()) item {
            Text("Most of this collection is built from TMDB. Add a free TMDB key in Settings → Addons & Discover to fill the folders.",
                color = Color(0xFFFFC107), fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, top = 8.dp))
        }
        when (val g = groups) {
            is Load.Loading -> item { LoadView(g) {} }
            is Load.Err -> item { Text("Couldn't load the collection: ${g.message}", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(32.dp)) }
            is Load.Ok -> g.value.forEach { grp ->
                item(key = "g" + grp.id + grp.title) {
                    RowTitle(grp.title)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 32.dp)) {
                        items(grp.folders, key = { grp.id + it.id }) { f ->
                            FolderTile(f, onFocus = {
                                onFocus(MetaPreview(f.id, "folder", f.title, f.cover, f.backdrop ?: f.cover, f.logo, grp.title, null, null, emptyList(),
                                    note = "${f.sources.size} list${if (f.sources.size == 1) "" else "s"}"))
                            }) { state.push(Route.Folder(f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderTile(f: com.dekstream.tv.data.CFolder, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val (w, ratio) = when (f.shape.uppercase()) { "LANDSCAPE", "WIDE" -> 260.dp to 16f / 9f; "SQUARE" -> 160.dp to 1f; else -> 140.dp to 2f / 3f }
    Column(Modifier.width(w)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).focusRing(shape, 1.06f).onFocusGained(onFocus).clip(shape)
            .background(Color(0xFF1C1C22)).tvClickable(onClick)) {
            AsyncImage(f.cover ?: f.backdrop, f.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (f.cover == null && f.logo != null) AsyncImage(f.logo, f.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(20.dp))
        }
        if (!f.hideTitle) Text(f.title, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FolderHero(m: MetaPreview, modifier: Modifier) {
    Column(modifier.padding(bottom = 12.dp)) {
        if (m.logo != null) AsyncImage(m.logo, m.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.height(90.dp).widthIn(max = 420.dp))
        else Text(m.name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
        Text(listOfNotNull(m.description, m.note).joinToString(" · "), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Text("Press OK to open", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A collection folder: its artwork on top, one row per list. */
@Composable
fun FolderScreen(f: com.dekstream.tv.data.CFolder, state: AppState) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        AsyncImage(f.backdrop ?: f.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(420.dp))
        Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(0.3f to Color(0x330A0A0C), 1f to Color(0xFF0A0A0C))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 32.dp)) {
                    if (f.logo != null) AsyncImage(f.logo, f.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart).height(110.dp).widthIn(max = 460.dp))
                    else Text(f.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
            f.sources.forEachIndexed { i, src ->
                item(key = "s$i") {
                    val list by rememberLoad(f.id + i) { com.dekstream.tv.data.Collections.items(ctx, src) }
                    RowTitle(src.title)
                    when (val l = list) {
                        is Load.Ok -> {
                            val items = l.value
                            if (items == null) Text(com.dekstream.tv.data.Collections.unavailableReason(ctx, src), color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                                modifier = Modifier.padding(start = 32.dp))
                            else if (items.isEmpty()) Text("Nothing here right now.", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp))
                            else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 32.dp)) {
                                items(items, key = { it.type + it.id }) { m -> PosterTile(m, 140.dp, onFocus = {}) { state.push(Route.Title(m)) } }
                            }
                        }
                        else -> Box(Modifier.padding(start = 32.dp).height(60.dp)) { LoadView(l) {} }
                    }
                }
            }
        }
    }
}
