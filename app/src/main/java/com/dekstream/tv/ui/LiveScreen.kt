package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val repo = DekStreamApp.instance.repo
    val cats by rememberLoad(Unit) { repo!!.liveCategories() }
    return when (val c = cats) {
        is Load.Ok -> Load.Ok(listOf(Category(FAV_ID, "★ Favorites"), Category(ALL_ID, "All Channels")) + c.value)
        else -> c
    }
}

fun filterChannels(all: List<Channel>, catId: String, favs: Set<Int>): List<Channel> = when (catId) {
    FAV_ID -> all.filter { it.streamId in favs }
    ALL_ID -> all
    else -> all.filter { it.categoryId == catId }
}

@Composable
fun LiveScreen(state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val cats = rememberLiveCategories()
    val all by rememberLoad(Unit) { repo.allChannels() }
    val favs by app.prefs.favorites.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

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
                            CategoryItem(c.name, c.id == catId) { selected = c.id }
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
                            CategoryChip(c.name, c.id == catId) { selected = c.id }
                        }
                    }
                    LoadView(all) { list -> ChannelList(filterChannels(list, catId, favs), state) }
                }
            }
        }
    }
}

@Composable
fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        name,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(if (selected) DS.AccentSoft else DS.Bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = if (selected) DS.Text else DS.TextDim,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CategoryChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        name,
        modifier = Modifier
            .focusRing(shape)
            .clip(shape)
            .background(if (selected) DS.Accent else DS.Surface)
            .clickable(onClick = onClick)
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
    if (channels.isEmpty()) {
        Text("No channels here yet. Long-press any channel to add it to Favorites.", color = DS.TextDim, modifier = Modifier.padding(24.dp))
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
                onLongClick = { app.prefs.toggleFavorite(c.streamId) },
            )
        }
    }
}
