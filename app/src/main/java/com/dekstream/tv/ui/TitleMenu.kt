package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.ProgressEntry
import com.dekstream.tv.data.SavedTitle

data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Long-press menu: artwork + title on the left, actions on the right. */
@Composable
fun ActionSheet(title: String, subtitle: String?, image: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Row(Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(22.dp)).background(DS.Surface2).padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(12.dp)).background(DS.Card))
            Column(Modifier.weight(1f)) {
                Text(title, color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { i, a ->
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                .focusRing(shape, 1.02f).clip(shape).background(DS.Card)
                                .tvClickable({ onDismiss(); a.onClick() }).padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(a.icon, null, tint = DS.Text, modifier = Modifier.size(20.dp))
                            Text(a.label, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Long-press menu for a movie or series poster: Play/Open, add to or remove from My list. */
@Composable
fun TitleMenu(t: SavedTitle, state: AppState, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return onDismiss()
    val on = app.prefs.inMyList(t.kind, t.id)
    val actions = buildList {
        if (t.kind == "movie") add(MenuAction("Play", Icons.Default.PlayArrow) { state.play(repo.vodRequest(t.toVod())) })
        add(MenuAction(if (t.kind == "movie") "Details" else "Open", Icons.Default.Info) {
            if (t.kind == "movie") state.push(Route.MovieDetail(t.toVod())) else state.push(Route.SeriesDetail(t.toSeries()))
        })
        add(MenuAction(if (on) "Remove from My Library" else "Add to My Library", if (on) Icons.Default.Check else Icons.Default.Add) {
            val now = app.prefs.toggleMyList(t)
            android.widget.Toast.makeText(app, if (now) "Added to My Library" else "Removed from My Library", android.widget.Toast.LENGTH_SHORT).show()
        })
    }
    ActionSheet(com.dekstream.tv.data.Top10.displayName(t.name), if (t.kind == "movie") "Movie" else "Series", t.poster, actions, onDismiss)
}

/** Long-press menu for a Continue watching card. */
@Composable
fun ContinueMenu(p: ProgressEntry, onResume: () -> Unit, onStartOver: () -> Unit, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val left = if (p.durationMs > 0) ((p.durationMs - p.positionMs) / 60000).coerceAtLeast(1) else null
    ActionSheet(p.title, listOfNotNull(p.subtitle, left?.let { "$it min left" }).joinToString(" · ").ifBlank { null }, p.poster, listOf(
        MenuAction("Resume", Icons.Default.PlayArrow, onResume),
        MenuAction("Start over", Icons.Default.Replay, onStartOver),
        MenuAction("Remove from Continue watching", Icons.Default.Delete) { app.prefs.removeProgress(p.key) },
    ), onDismiss)
}
