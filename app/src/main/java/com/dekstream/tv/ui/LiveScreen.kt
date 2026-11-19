package com.dekstream.tv.ui

import androidx.compose.foundation.background
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
        is Load.Ok -> Load.Ok(listOf(Category(FAV_ID, "★ Favorites")) + groups + listOf(Category(ALL_ID, "All Channels")) +
            c.value.filter { allow == null || it.id in allow })
        else -> c
    }
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

    catMenu?.let { c -> CategoryOptionsDialog(c, "l:", onDismiss = { catMenu = null }) }
    LoadView(cats) { catList ->
        val catId = selected ?: if (favs.isNotEmpty()) FAV_ID else catList.getOrNull(2)?.id ?: ALL_ID
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 640.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.width(240.dp).fillMaxHeight().background(DS.Bg),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        items(catList, key = { it.id }) { c ->
                            CategoryItem(c.name, c.id == catId, onLongClick = { catMenu = c }) { selected = c.id }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LoadView(all) { list -> ChannelList(filterChannels(list, catId, favs), state) }
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
fun ChannelList(channels: List<Channel>, state: AppState) {
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
            ChannelRowItem(
                name = c.name, icon = c.icon, number = c.num.takeIf { it > 0 },
                now = now?.title, next = next?.title, nowProgress = prog,
                favorite = c.streamId in favs,
                onClick = {
                    app.prefs.markChannelWatched(c.streamId)
                    state.play(repo.liveRequest(c), channels)
                },
                onLongClick = { menuFor = c },
            )
        }
    }
}
