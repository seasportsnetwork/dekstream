package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** Long-press menu: artwork + title on the left, actions on the right. TV-style: solid dark sheet, no outlines,
 *  the highlighted action fills white. [progress] (0..1) shows a bar under the title. */
@Composable
fun ActionSheet(title: String, subtitle: String?, image: String?, actions: List<MenuAction>, onDismiss: () -> Unit, progress: Float? = null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    val sheet = androidx.compose.ui.graphics.lerp(Appearance.palette.bg, androidx.compose.ui.graphics.Color.White, if (DS.Light) 0f else 0.07f)
    Dialog(onDismissRequest = onDismiss) {
        Row(
            Modifier.widthIn(min = 560.dp, max = 680.dp)
                .shadow(32.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp)).background(sheet)
                .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f), RoundedCornerShape(26.dp))
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(148.dp).height(222.dp).shadow(12.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(DS.Card))
            Column(Modifier.weight(1f)) {
                Text(title, color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (progress != null && progress > 0f) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(DS.Accent))
                }
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions.forEachIndexed { i, a -> SheetAction(a, if (i == 0) Modifier.focusRequester(first) else Modifier) { onDismiss(); a.onClick() } }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(a: MenuAction, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val bg by androidx.compose.animation.animateColorAsState(
        if (focused) DS.Invert else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f), androidx.compose.animation.core.tween(120), label = "row")
    val fg = if (focused) DS.OnInvert else DS.Text
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().height(50.dp).onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
            .tvClickable(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(a.icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(a.label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp))
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
    ActionSheet(com.dekstream.tv.data.Top10.displayName(p.title), listOfNotNull(p.subtitle, left?.let { "$it min left" }).joinToString(" · ").ifBlank { null }, p.poster, listOf(
        MenuAction("Resume", Icons.Default.PlayArrow, onResume),
        MenuAction("Start over", Icons.Default.Replay, onStartOver),
        MenuAction("Remove from row", Icons.Default.Delete) { app.prefs.removeProgress(p.key) },
    ), onDismiss, progress = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null)
}
