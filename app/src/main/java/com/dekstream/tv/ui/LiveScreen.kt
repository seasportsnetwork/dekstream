package com.dekstream.tv.ui

import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.ui.graphics.Brush

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Category
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.Program

const val FAV_ID = "__fav__"
const val ALL_ID = "__all__"

/** Category list with Favorites and All Channels pinned on top. */
@Composable
fun rememberLiveCategories(): Load<List<Category>> {
    val app = DekStreamApp.instance
    val repo = app.repo
    val version by app.prefs.customVersion.collectAsState()
    val cats by rememberLoad(version) { repo!!.liveCategories() }
    val allow = kidsAllowed("l:")
    val groups = remember(version) { if (allow == null) app.prefs.groups.map { Category("g:" + it.id, "▸ " + it.name) } else emptyList() }
    return when (val c = cats) {
        is Load.Ok -> Load.Ok(orderCategories(listOf(Category(FAV_ID, "★ Favorites")) + groups + listOf(Category(ALL_ID, "All Channels")) +
            c.value.filter { allow == null || it.id in allow }, app.prefs.catOrder))
        else -> c
    }
}

/** Order key for a Live TV category in the combined list. */
fun liveCatKey(c: Category) = if (c.id.startsWith("__") || c.id.startsWith("g:")) c.id else "l:" + c.id

/** Your saved order first, then everything else in its original order. */
fun orderCategories(list: List<Category>, order: List<String>): List<Category> {
    if (order.isEmpty()) return list
    val pos = order.withIndex().associate { it.value to it.index }
    val (known, rest) = list.partition { liveCatKey(it) in pos }
    return known.sortedBy { pos[liveCatKey(it)] } + rest
}

fun filterChannels(all: List<Channel>, catId: String, favs: Set<Int>): List<Channel> {
    val allow = kidsAllowedNow("l:")
    val visible = if (allow == null) all else all.filter { it.categoryId in allow }
    return when {
        catId == FAV_ID -> visible.filter { it.streamId in favs }
        catId == ALL_ID -> visible
        catId.startsWith("g:") -> {
            val g = DekStreamApp.instance.session?.prefs?.groups?.firstOrNull { it.id == catId.removePrefix("g:") }
            val byId = visible.associateBy { it.streamId }
            g?.channels?.mapNotNull { byId[it] }.orEmpty()
        }
        else -> visible.filter { it.categoryId == catId }
    }
}

/** For kids profiles with an allowlist: allowed category ids of this type ("l:", "v:", "s:"); null = no restriction. */
fun kidsAllowedNow(prefix: String): Set<String>? {
    val s = DekStreamApp.instance.session ?: return null
    if (!s.profile.kids) return null
    val all = s.settings.allowed(s.source.id)
    if (all.isEmpty()) return null
    return all.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
}

@Composable
fun kidsAllowed(prefix: String): Set<String>? = remember(DekStreamApp.instance.session?.key) { kidsAllowedNow(prefix) }

@Composable
fun LiveScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val cats = rememberLiveCategories()
    val version by app.prefs.customVersion.collectAsState()
    val all by rememberLoad(version) { repo.allChannels() }
    var catMenu by remember { mutableStateOf<Category?>(null) }
    val favs by app.prefs.favorites.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<Channel?>(null) }
    // Video only starts when you press OK / tap; moving through the list just shows the info.
    var playing by remember { mutableStateOf<Channel?>(null) }

    catMenu?.let { c -> CategoryOptionsDialog(c, "l:", onDismiss = { catMenu = null }, order = cats.valueOrNull()?.map(::liveCatKey)) }
    LoadView(cats) { catList ->
        val catId = selected ?: if (favs.isNotEmpty()) FAV_ID else catList.getOrNull(2)?.id ?: ALL_ID
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 640.dp
            if (wide) {
                val showPreview = maxWidth >= 900.dp
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.width(210.dp).fillMaxHeight().background(DS.Bg),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        item {
                            Text("Live TV", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp))
                        }
                        items(catList, key = { it.id }) { c ->
                            CategoryItem(c.name, c.id == catId, onLongClick = { catMenu = c }) { selected = c.id }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LoadView(all) { list ->
                            val shown = filterChannels(list, catId, favs)
                            if (showPreview && previewing == null) shown.firstOrNull()?.let { previewing = it }
                            ChannelList(
                                shown, state,
                                selectedId = if (showPreview) previewing?.streamId else null,
                                onSelect = if (showPreview) { c -> previewing = c } else null,
                                playingId = playing?.streamId,
                                onPreview = if (showPreview) { c -> previewing = c; playing = c } else null,
                            )
                        }
                    }
                    if (showPreview) {
                        PreviewPane(previewing, playing, { playing = it }, all.valueOrNull().orEmpty().let { filterChannels(it, catId, favs) }, state,
                            Modifier.weight(1.1f).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = rowSpacing) {
                        items(catList, key = { it.id }) { c ->
                            CategoryChip(c.name, c.id == catId, onLongClick = { catMenu = c }) { selected = c.id }
                        }
                    }
                    LoadView(all) { list -> ChannelList(filterChannels(list, catId, favs), state) }
                }
            }
        }
    }
}

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryItem(name: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        name,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(if (selected) DS.AccentSoft else DS.Bg)
            .tvClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = if (selected) DS.Text else DS.TextDim,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryChip(name: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        name,
        modifier = Modifier
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) DS.Accent else DS.Surface)
            .tvClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = DS.Text,
        fontSize = 13.sp,
        maxLines = 1,
    )
}

@Composable
fun ChannelList(
    channels: List<Channel>, state: AppState, selectedId: Int? = null, onSelect: ((Channel) -> Unit)? = null,
    playingId: Int? = null, onPreview: ((Channel) -> Unit)? = null,
) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val favs by app.prefs.favorites.collectAsState()
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    menuFor?.let { c -> ChannelOptionsDialog(c, onDismiss = { menuFor = null }) }
    if (channels.isEmpty()) {
        Text("No channels here yet. Long-press any channel for options like Favorites and groups.", color = DS.TextDim, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        items(channels, key = { it.streamId }) { c ->
            val epg by produceState<List<Program>>(emptyList(), c.streamId) { value = repo.nowNext(c) }
            val now = epg.firstOrNull { it.isLive() }
            val next = epg.firstOrNull { it.startMs > System.currentTimeMillis() }
            val prog = now?.let {
                ((System.currentTimeMillis() - it.startMs).toFloat() / (it.endMs - it.startMs)).coerceIn(0f, 1f)
            }
            val isSel = selectedId == c.streamId
            ChannelRowItem(
                name = c.name, icon = c.icon, number = c.num.takeIf { it > 0 },
                now = now?.title, next = next?.title, nowProgress = prog,
                favorite = c.streamId in favs,
                selected = isSel,
                onClick = {
                    // With a preview pane: first OK / tap plays it in the preview, the second goes full screen.
                    if (onPreview != null && playingId != c.streamId) onPreview(c)
                    else {
                        app.prefs.markChannelWatched(c.streamId)
                        state.play(repo.liveRequest(c), channels)
                    }
                },
                onLongClick = { menuFor = c },
                modifier = if (onSelect != null) Modifier.onFocusChanged { if (it.hasFocus) onSelect(c) } else Modifier,
            )
        }
    }
}

/** Right-hand pane: small live picture of the highlighted channel plus now/next. */
@Composable
private fun PreviewPane(c: Channel?, playing: Channel?, onPlay: (Channel) -> Unit, list: List<Channel>, state: AppState, modifier: Modifier) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val epg by produceState<List<Program>>(emptyList(), c?.streamId) { value = c?.let { repo.nowNext(it) }.orEmpty() }
    val now = epg.firstOrNull { it.isLive() }
    val next = epg.firstOrNull { it.startMs > System.currentTimeMillis() }
    Column(modifier.padding(start = 6.dp, end = 20.dp, top = 16.dp)) {
        LivePreview(playing, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (c != null && playing == null) {
                Column(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DS.Accent.copy(alpha = 0.35f), Color(0xFF0B0B0F))))
                        .clickable { onPlay(c) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    ChannelLogo(c.icon, 72.dp)
                    Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(" Press OK to preview", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            // Browsing other channels keeps this one playing; OK on another channel switches.
            if (c != null && playing != null && playing.streamId != c.streamId) {
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Playing ${playing.name}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    Text("OK on ${c.name.take(22)} to switch", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
                }
            }
            if (c != null && playing != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(DS.Live))
                    Text("  PREVIEW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
        if (c == null) { Text("Pick a channel", color = DS.TextDim, modifier = Modifier.padding(top = 16.dp)); return@Column }
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.icon, 40.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(c.name, color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.num > 0) Text("Channel ${c.num}", color = DS.TextDim, fontSize = 12.sp)
            }
        }
        if (now != null) {
            Text(now.title, color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp))
            Text("${fmtTime(now.startMs)} – ${fmtTime(now.endMs)}  ·  ${minutesLeft(now)} left", color = DS.TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            val f = ((System.currentTimeMillis() - now.startMs).toFloat() / (now.endMs - now.startMs)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(DS.Surface2)) {
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(DS.Accent))
            }
            if (now.description.isNotBlank()) {
                Text(now.description, color = DS.TextDim, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp))
            }
        } else {
            Text("No guide information", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
        }
        next?.let {
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DS.Surface).padding(10.dp)) {
                Text("NEXT", color = DS.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                Text("${fmtTime(it.startMs)}  ${it.title}", color = DS.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(
            onClick = { app.prefs.markChannelWatched(c.streamId); state.play(repo.liveRequest(c), list) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(top = 16.dp).focusRing(RoundedCornerShape(10.dp)),
        ) {
            Icon(Icons.Default.Fullscreen, null, modifier = Modifier.size(20.dp))
            Text(" Watch full screen", fontWeight = FontWeight.SemiBold)
        }
    }
}
