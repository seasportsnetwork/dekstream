package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Episode2
import com.dekstream.tv.data.MetaPreview
import com.dekstream.tv.data.PlayRequest
import com.dekstream.tv.data.StreamItem
import com.dekstream.tv.data.Stremio
import kotlinx.coroutines.launch

/** Title page: backdrop, details, seasons and episodes, and sources from your addons (Usenet Ultimate). */
@Composable
fun TitleScreen(initial: MetaPreview, state: AppState) {
    val app = DekStreamApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // TMDB titles (collections) are looked up by IMDb id, which Cinemeta and your addons use.
    val detail by rememberLoad(initial.id) {
        val imdb = if (initial.id.startsWith("tmdb:")) com.dekstream.tv.data.Collections.imdbFor(ctx, initial) else if (initial.upcoming) null else initial.id
        if (imdb == null) null else Stremio.meta(initial.type, imdb)
    }
    val m = detail.valueOrNull()?.preview ?: initial
    val progress by app.prefs.progress.collectAsState()
    var listed by remember { mutableStateOf(Stremio.inWatchlist(ctx, initial)) }
    // Sources panel: which video ("tt1" or "tt1:2:5") and its label.
    var sourcesFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val eps = detail.valueOrNull()?.episodes.orEmpty().filter { it.season > 0 }
    val seasons = eps.map { it.season }.distinct()
    var season by rememberSaveable(initial.id) { mutableStateOf<Int?>(null) }
    val curSeason = season ?: seasons.lastOrNull { s -> eps.any { it.season == s && (it.released ?: 0) < System.currentTimeMillis() } } ?: seasons.firstOrNull()
    val nextUp = eps.firstOrNull { e -> progress["st:${initial.type}:${e.id}"].let { it == null || it.fraction < 0.9f } && (e.released ?: 0) < System.currentTimeMillis() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        AsyncImage(m.background ?: m.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(520.dp))
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.horizontalGradient(listOf(Color(0xF20A0A0C), Color(0xB30A0A0C), Color(0x330A0A0C)))))
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xFF0A0A0C))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 40.dp)) {
            item {
                Column(Modifier.widthIn(max = 680.dp)) {
                    if (m.logo != null) AsyncImage(m.logo, m.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.height(110.dp).widthIn(max = 460.dp))
                    else Text(m.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        m.rating?.let { Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp)); Text(" $it   ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                        listOfNotNull(m.year, detail.valueOrNull()?.runtime, m.note, if (seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size == 1) "" else "s"}" else null)
                            .forEach { Text("$it   ", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
                    }
                    if (m.genres.isNotEmpty()) Text(m.genres.joinToString(" • "), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    m.description?.let { Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 14.dp)) }
                    detail.valueOrNull()?.let { d ->
                        if (d.cast.isNotEmpty()) Text("Starring: " + d.cast.take(5).joinToString(", "), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        d.director?.let { Text("Director: $it", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp) }
                    }
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (m.upcoming || m.id.startsWith("tmdb:")) Text(if (detail is Load.Loading) "Loading…" else m.note ?: "Not available yet",
                            color = Color(0xFF46D369), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        else if (m.type == "movie") {
                            val p = progress["st:movie:${m.id}"]
                            PillButton(if (p != null && p.fraction in 0.02f..0.95f) "Resume" else "Play", {
                                if (p != null && p.fraction in 0.02f..0.95f) state.play(PlayRequest(p.url, p.title, p.subtitle, false, p.key, p.poster, startPositionMs = p.positionMs))
                                else sourcesFor = m.id to m.name
                            }, Icons.Default.PlayArrow, primary = true, onImage = true)
                            PillButton("Sources", { sourcesFor = m.id to m.name }, onImage = true)
                        } else nextUp?.let { e ->
                            PillButton("Play S${e.season} E${e.episode}", { sourcesFor = e.id to "${m.name} · S${e.season} E${e.episode}" }, Icons.Default.PlayArrow, primary = true, onImage = true)
                        }
                        if (!m.upcoming) PillButton(if (listed) "On my list" else "My list", { Stremio.toggleWatchlist(ctx, m); listed = !listed },
                            if (listed) Icons.Default.Check else Icons.Default.Add, onImage = true)
                    }
                }
            }
            if (detail is Load.Loading) item { CircularProgressIndicator(color = DS.Accent, modifier = Modifier.padding(top = 30.dp).size(28.dp)) }
            if (seasons.isNotEmpty()) {
                item {
                    LazyRow(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s -> Pill("Season $s", s == curSeason, onClick = { season = s }) }
                    }
                }
                items(eps.filter { it.season == curSeason }, key = { it.id }) { e ->
                    EpisodeRow(e, progress["st:${initial.type}:${e.id}"]?.fraction) { sourcesFor = e.id to "${m.name} · S${e.season} E${e.episode}" }
                }
            }
        }
        sourcesFor?.let { (vid, label) ->
            SourcesPanel(m.type, vid, label, Modifier.align(Alignment.CenterEnd), onDismiss = { sourcesFor = null }) { s ->
                sourcesFor = null
                val key = "st:${m.type}:$vid"
                val epTitle = eps.firstOrNull { it.id == vid }?.title
                state.play(PlayRequest(s.url, m.name, if (epTitle != null) label.substringAfter(" · ") + " · " + epTitle else null, false, key, m.poster))
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = sourcesFor != null) { sourcesFor = null }
    @Suppress("UNUSED_VARIABLE") val unused = scope
}

@Composable
private fun EpisodeRow(e: Episode2, watched: Float?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val out = (e.released ?: 0) < System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().widthIn(max = 980.dp).padding(top = 10.dp).focusRing(shape, 1.01f).clip(shape)
            .background(Color(0x14FFFFFF)).tvClickable(onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C22))) {
            AsyncImage(e.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (watched != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(watched).height(4.dp).background(Color(0xFFE50914)))
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text("${e.episode}. ${e.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.released?.let { Text((if (out) "" else "Coming ") + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(it)),
                color = if (out) Color.White.copy(alpha = 0.5f) else Color(0xFF46D369), fontSize = 12.sp) }
            e.overview?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Right-hand panel: sources from your addons, best first. */
@Composable
private fun SourcesPanel(type: String, videoId: String, label: String, modifier: Modifier, onDismiss: () -> Unit, onPick: (StreamItem) -> Unit) {
    val ctx = LocalContext.current
    val streams by rememberLoad(videoId) { Stremio.streams(ctx, type, videoId) }
    val rank = { s: StreamItem -> (when (s.quality) { "4K" -> 4; "1080p" -> 3; "720p" -> 2; else -> 1 }) * 10 + (if ("DV" in s.tags) 2 else 0) + (if ("HDR" in s.tags) 1 else 0) }
    Column(
        modifier.fillMaxSize().widthIn(max = 560.dp).width(540.dp).background(Brush.horizontalGradient(listOf(Color(0xE60A0A0C), Color(0xFA0A0A0C))))
            .padding(24.dp),
    ) {
        Text("Sources", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
        when (val s = streams) {
            is Load.Loading -> Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = DS.Accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("   Searching your addons…", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            }
            is Load.Err -> Text("Couldn't reach your addons: ${s.message}", color = DS.Live, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
            is Load.Ok -> {
                val list = s.value.sortedByDescending(rank)
                if (list.isEmpty()) Text("No sources found. Check your addons in Settings → Addons & Discover.", color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                LazyColumn(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.url }) { st ->
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().focusRing(shape, 1.02f).clip(shape).background(Color(0x1AFFFFFF)).tvClickable({ onPick(st) }).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(st.quality ?: "SD", color = if (st.quality == "4K") Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.width(54.dp).clip(RoundedCornerShape(6.dp))
                                    .background(when (st.quality) { "4K" -> Color(0xFFFFB300); "1080p" -> Color(0xFF2D7FF9); "720p" -> Color(0xFF34C759); else -> Color(0xFF8E8E93) })
                                    .padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (st.tags + listOfNotNull(st.size)).forEach { t ->
                                        Text(t, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(end = 6.dp).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                    Text(st.addon, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1)
                                }
                                Text(st.title.ifBlank { st.name }, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val d = onDismiss
}
