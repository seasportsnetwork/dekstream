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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    val cats by rememberLoad(isSeries) { if (isSeries) repo.seriesCategories() else repo.vodCategories() }
    LoadView(cats) { list ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 0.dp, top = 8.dp, bottom = 24.dp)) {
            item {
                Text(if (isSeries) "Series" else "Movies", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(list, key = { it.id }) { cat -> CategoryRow(cat, isSeries, state) }
        }
    }
}

@Composable
private fun CategoryRow(cat: Category, isSeries: Boolean, state: AppState) {
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
                    items(list.take(40), key = { it.seriesId }) { s ->
                        PosterCard(s.name, s.cover, 130.dp, onClick = { state.push(Route.SeriesDetail(s)) })
                    }
                }
            }
        } else {
            val items by rememberLoad(cat.id) { repo.vods(cat.id) }
            LoadView(items, Modifier.height(220.dp)) { list ->
                LazyRow(horizontalArrangement = rowSpacing, contentPadding = PaddingValues(end = 20.dp)) {
                    items(list.take(40), key = { it.streamId }) { v ->
                        PosterCard(
                            v.name, v.poster, 130.dp,
                            progress = progress["vod:${v.streamId}"]?.fraction,
                            onClick = { state.push(Route.MovieDetail(v)) },
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
                    items(list, key = { it.seriesId }) { s ->
                        PosterCard(s.name, s.cover, 130.dp, onClick = { state.push(Route.SeriesDetail(s)) })
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
                    items(list, key = { it.streamId }) { v ->
                        PosterCard(v.name, v.poster, 130.dp, progress = progress["vod:${v.streamId}"]?.fraction,
                            onClick = { state.push(Route.MovieDetail(v)) })
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
    val progress by app.prefs.progress.collectAsState()
    val resume = progress["vod:${v.streamId}"]
    BackHandler { state.pop() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box {
                Hero(info.valueOrNull()?.backdrop ?: v.poster) {
                    Text(v.name, color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.safeDrawingPadding()) { BackHeader("", state) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                val i = info.valueOrNull()
                val meta = listOfNotNull(i?.releaseDate?.take(4), i?.duration, i?.genre, i?.rating?.let { "★ $it" }).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = DS.TextDim, fontSize = 14.sp)
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { state.play(repo.vodRequest(v)) },
                        colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                        modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (resume != null) " Resume" else " Play")
                    }
                    if (resume != null) {
                        Button(
                            onClick = { state.play(repo.vodRequest(v).copy(startPositionMs = 0)) },
                            colors = ButtonDefaults.buttonColors(containerColor = DS.Surface2),
                            modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                        ) { Text("Start over") }
                    }
                }
                i?.plot?.let { Text(it, color = DS.Text, fontSize = 15.sp, lineHeight = 22.sp) }
                i?.cast?.let { Text("Cast: $it", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                i?.director?.let { Text("Director: $it", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (info is Load.Loading) LoadView(info) {}
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(s: Series, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val info by rememberLoad(s.seriesId) { repo.seriesInfo(s) }
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
                (i?.plot ?: s.plot)?.let { Text(it, color = DS.Text, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (info is Load.Loading || info is Load.Err) LoadView(info) {}
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
