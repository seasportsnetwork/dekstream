package com.dekstream.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Genres
import com.dekstream.tv.data.SavedTitle
import com.dekstream.tv.data.Top10

/** A genre from Home: the playlist's movies and series in it (most popular first with a TMDB key). */
@Composable
fun GenreScreen(name: String, state: AppState) {
    val app = DekStreamApp.instance
    val ctx = LocalContext.current
    val repo = app.repo ?: return
    val g = Genres.byName(name) ?: return
    val res by rememberLoad(name) { Genres.titles(ctx, repo, g) }
    val progress by app.prefs.progress.collectAsState()
    BackHandler { state.pop() }
    val r = res.valueOrNull()
    LazyVerticalGrid(
        GridCells.Adaptive(140.dp), Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenTitle(name) { state.pop() }
                Text(
                    when {
                        r == null -> "Finding $name in your playlist…"
                        r.fromTmdb -> "Most popular first · from your playlist"
                        else -> "From your playlist's ${name.lowercase()} categories" +
                            if (com.dekstream.tv.data.Stremio.tmdbKey(ctx).isBlank()) " · add a TMDB key for a fuller list" else ""
                    }, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (r == null) item(span = { GridItemSpan(maxLineSpan) }) { CircularProgressIndicator(color = DS.Accent, modifier = Modifier.padding(24.dp)) }
        else if (r.movies.isEmpty() && r.series.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Nothing in your playlist for $name yet.", color = DS.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
        } else {
            if (r.movies.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Movies", color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                items(r.movies, key = { "m" + it.streamId }) { v ->
                    PosterCard(Top10.displayName(v.name), v.poster, 140.dp, progress = progress["vod:${v.streamId}"]?.fraction,
                        onClick = { state.push(Route.MovieDetail(v)) }, onLongClick = { state.titleMenu = SavedTitle.of(v) })
                }
            }
            if (r.series.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Series", color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp)) }
                items(r.series, key = { "s" + it.seriesId }) { s ->
                    PosterCard(Top10.displayName(s.name), s.cover, 140.dp,
                        onClick = { state.push(Route.SeriesDetail(s)) }, onLongClick = { state.titleMenu = SavedTitle.of(s) })
                }
            }
        }
    }
}
