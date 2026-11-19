package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HOURS_BACK = 3
private const val HOURS_AHEAD = 8
private val DP_PER_MIN = 5.dp
private val ROW_H = 64.dp
private val CHANNEL_COL = 170.dp

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))

@Composable
fun GuideScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val cats = rememberLiveCategories()
    val version by app.prefs.customVersion.collectAsState()
    val all by rememberLoad(version) { repo.allChannels() }
    var catMenu by remember { mutableStateOf<com.dekstream.tv.data.Category?>(null) }
    var chMenu by remember { mutableStateOf<Channel?>(null) }
    catMenu?.let { c -> CategoryOptionsDialog(c, "l:", onDismiss = { catMenu = null }) }
    chMenu?.let { c -> ChannelOptionsDialog(c, onDismiss = { chMenu = null }) }
    val favs by app.prefs.favorites.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Pair<Channel, Program>?>(null) }

    // Window start, rounded down to the half hour.
    val windowStart = remember {
        val now = System.currentTimeMillis()
        (now - HOURS_BACK * 3600_000L) / 1_800_000L * 1_800_000L
    }
    val windowEnd = windowStart + (HOURS_BACK + HOURS_AHEAD) * 3600_000L
    val density = LocalDensity.current
    val pxPerMin = with(density) { DP_PER_MIN.toPx() }
    val nowOffsetPx = ((System.currentTimeMillis() - windowStart) / 60000f) * pxPerMin
    var scrollX by remember { mutableFloatStateOf((nowOffsetPx - with(density) { 60.dp.toPx() }).coerceAtLeast(0f)) }
    val totalPx = ((windowEnd - windowStart) / 60000f) * pxPerMin

    LoadView(cats) { catList ->
        val catId = selected ?: if (favs.isNotEmpty()) FAV_ID else catList.getOrNull(2)?.id ?: ALL_ID
        Column(Modifier.fillMaxSize().background(Appearance.guideBrush)) {
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = rowSpacing) {
                items(catList, key = { it.id }) { c -> CategoryChip(c.name, c.id == catId, onLongClick = { catMenu = c }) { selected = c.id } }
            }
            LoadView(all) { list ->
                val channels = filterChannels(list, catId, favs)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewportPx = with(density) { (maxWidth - CHANNEL_COL).toPx() }
                    val maxScroll = (totalPx - viewportPx).coerceAtLeast(0f)
                    val drag = rememberDraggableState { d -> scrollX = (scrollX - d).coerceIn(0f, maxScroll) }
                    Column(Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal)) {
                        // Time ruler
                        Row(Modifier.fillMaxWidth().height(28.dp)) {
                            Box(Modifier.width(CHANNEL_COL))
                            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                var t = windowStart
                                while (t < windowEnd) {
                                    val x = ((t - windowStart) / 60000f) * pxPerMin - scrollX
                                    Text(
                                        fmtTime(t), color = DS.TextDim, fontSize = 12.sp,
                                        modifier = Modifier.offset { IntOffset(x.roundToInt() + 6, 4) },
                                    )
                                    t += 1_800_000L
                                }
                                // "now" marker
                                Box(
                                    Modifier.offset { IntOffset((nowOffsetPx - scrollX).roundToInt(), 0) }
                                        .width(2.dp).fillMaxHeight().background(DS.Accent),
                                )
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(channels, key = { it.streamId }) { c ->
                                GuideRow(
                                    c, windowStart, windowEnd, pxPerMin, scrollX, viewportPx,
                                    onScrollTo = { scrollX = it.coerceIn(0f, maxScroll) },
                                    onChannel = {
                                        app.prefs.markChannelWatched(c.streamId)
                                        state.play(repo.liveRequest(c), channels)
                                    },
                                    onProgram = { p -> picked = c to p },
                                    onChannelMenu = { chMenu = c },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val allReminders by app.reminders.items.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    picked?.let { (c, p) ->
        val now = System.currentTimeMillis()
        val canCatchUp = c.hasArchive && p.endMs <= now
        AlertDialog(
            onDismissRequest = { picked = null },
            containerColor = DS.Surface,
            title = { Text(p.title, color = DS.Text) },
            text = {
                Column {
                    Text("${c.name} · ${fmtTime(p.startMs)} – ${fmtTime(p.endMs)}" + if (p.isLive(now)) " · ${minutesLeft(p)} left" else "",
                        color = DS.TextDim, fontSize = 13.sp)
                    if (p.description.isNotBlank()) Text(p.description, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    if (p.endMs <= now && !c.hasArchive) Text("This channel doesn't offer catch-up.", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                when {
                    p.isLive(now) -> TextButton(onClick = {
                        picked = null
                        app.prefs.markChannelWatched(c.streamId)
                        state.play(repo.liveRequest(c))
                    }) { Text("Watch live", color = DS.Accent) }
                    canCatchUp -> TextButton(onClick = {
                        picked = null
                        repo.catchupRequest(c, p)?.let { state.play(it) }
                    }) { Text("Watch from start", color = DS.Accent) }
                    p.startMs > now -> {
                        val on = allReminders.any { it.streamId == c.streamId && it.startMs == p.startMs }
                        TextButton(onClick = {
                            if (on) app.reminders.cancel(c.streamId, p.startMs)
                            else {
                                app.reminders.add(app.session?.profile?.id ?: "", c, p)
                                (context as? MainActivity)?.askNotificationPermission()
                            }
                            picked = null
                        }) { Text(if (on) "Cancel reminder" else "Remind me", color = DS.Accent) }
                    }
                    else -> {}
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Close", color = DS.TextDim) } },
        )
    }
}

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    c: Channel,
    windowStart: Long,
    windowEnd: Long,
    pxPerMin: Float,
    scrollX: Float,
    viewportPx: Float,
    onScrollTo: (Float) -> Unit,
    onChannel: () -> Unit,
    onProgram: (Program) -> Unit,
    onChannelMenu: () -> Unit,
) {
    val repo = DekStreamApp.instance.repo ?: return
    val programs by produceState<List<Program>?>(null, c.streamId) {
        value = repo.fullEpg(c).filter { it.endMs > windowStart && it.startMs < windowEnd }
    }
    val density = LocalDensity.current
    val now = System.currentTimeMillis()
    val reminders by DekStreamApp.instance.reminders.items.collectAsState()
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(vertical = 2.dp)) {
        // Channel cell
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.width(CHANNEL_COL).fillMaxHeight().padding(end = 4.dp)
                .focusRing(shape, 1.02f).clip(shape).background(DS.Surface)
                .tvClickable(onClick = onChannel, onLongClick = onChannelMenu).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(c.icon, 40.dp)
            Text(
                c.name, color = DS.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Program strip
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val list = programs
            if (list == null) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(DS.Surface.copy(alpha = 0.4f)))
            } else if (list.isEmpty()) {
                Text("No guide information", color = DS.TextDim, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            } else {
                fillGaps(list, windowStart, windowEnd).forEach { (p, isGap) ->
                    val start = maxOf(p.startMs, windowStart)
                    val end = minOf(p.endMs, windowEnd)
                    val x = ((start - windowStart) / 60000f) * pxPerMin
                    val w = ((end - start) / 60000f) * pxPerMin
                    val live = p.isLive(now)
                    val past = p.endMs <= now
                    val shape2 = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .offset { IntOffset((x - scrollX).roundToInt(), 0) }
                            .width(with(density) { (w - 3f).coerceAtLeast(1f).toDp() })
                            .fillMaxHeight()
                            .onFocusChanged { f ->
                                // keep the focused programme on screen while navigating with a remote
                                if (f.isFocused) {
                                    if (x < scrollX) onScrollTo(x - 20f)
                                    else if (x + w > scrollX + viewportPx) {
                                        onScrollTo(if (w > viewportPx) x - 20f else x + w - viewportPx + 20f)
                                    }
                                }
                            }
                            .focusRing(shape2, 1.02f)
                            .clip(shape2)
                            .background(
                                when {
                                    isGap -> DS.Surface.copy(alpha = 0.3f)
                                    live -> DS.AccentSoft
                                    past -> DS.Surface.copy(alpha = 0.55f)
                                    else -> DS.Surface
                                }
                            )
                            .clickable { if (isGap) onChannel() else onProgram(p) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (past && c.hasArchive && !isGap) {
                                    Icon(Icons.Default.History, null, tint = DS.TextDim, modifier = Modifier.width(14.dp).padding(end = 2.dp))
                                }
                                if (!isGap && reminders.any { it.streamId == c.streamId && it.startMs == p.startMs }) {
                                    Icon(Icons.Default.NotificationsActive, "Reminder set", tint = DS.Accent, modifier = Modifier.width(16.dp).padding(end = 3.dp))
                                }
                                Text(
                                    p.title, color = if (past) DS.TextDim else DS.Text, fontSize = 13.sp,
                                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!isGap) Text(
                                if (live) "${minutesLeft(p)} left · ends ${fmtTime(p.endMs)}" else "${fmtTime(p.startMs)} – ${fmtTime(p.endMs)}",
                                color = if (live) DS.Text.copy(alpha = 0.8f) else DS.TextDim, fontSize = 11.sp, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Pads the guide row so gaps (and missing ends) show as "No information" blocks instead of empty space. */
private fun fillGaps(list: List<Program>, from: Long, to: Long): List<Pair<Program, Boolean>> {
    val out = ArrayList<Pair<Program, Boolean>>()
    var cursor = from
    for (p in list.sortedBy { it.startMs }) {
        if (p.startMs - cursor >= 5 * 60_000) out += Program("No information", "", cursor, p.startMs, false) to true
        if (p.endMs > cursor) { out += p to false; cursor = maxOf(cursor, p.endMs) }
    }
    if (to - cursor >= 5 * 60_000) out += Program("No information", "", cursor, to, false) to true
    return out
}
