package com.dekstream.tv.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.MultiLayout

/**
 * Several live channels at once. Layouts: 2 side by side, 1 big + 2 small, or a 2x2 grid.
 * Tap a tile for its sound, long-press to change its channel, swap tiles, save up to 8 layouts.
 */
@Composable
fun MultiviewScreen(initial: List<Channel> = emptyList(), onExit: (() -> Unit)? = null) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val slots = remember { mutableStateListOf<Channel?>().apply { repeat(4) { i -> add(initial.getOrNull(i)) } } }
    var grid by remember { mutableIntStateOf(if (initial.size in 1..2) 2 else 4) }
    var audio by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<Int?>(null) }
    var swapFrom by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val layouts by app.prefs.layouts.collectAsState()
    val all by rememberLoad(Unit) { repo.allChannels() }

    if (onExit != null) BackHandler { onExit() }

    fun tileClick(i: Int) {
        val from = swapFrom
        when {
            from != null -> {
                if (from != i) { val t = slots[from]; slots[from] = slots[i]; slots[i] = t; if (audio == from) audio = i else if (audio == i) audio = from }
                swapFrom = null
            }
            slots[i] == null -> pickFor = i
            else -> audio = i
        }
    }

    fun load(l: MultiLayout) {
        val byId = all.valueOrNull().orEmpty().associateBy { it.streamId }
        for (i in 0 until 4) slots[i] = l.channels.getOrNull(i)?.let { byId[it] }
        grid = l.grid; audio = 0
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(6.dp)) {
        // Toolbar
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onExit != null) IconButton(onClick = onExit, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text) }
            Text("Multiview", color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            HSpacer(14.dp)
            listOf(2 to "2", 3 to "1+2", 4 to "4").forEach { (g, label) -> CategoryChip(label, grid == g) { grid = g }; HSpacer(6.dp) }
            HSpacer(6.dp)
            IconButton(onClick = { swapFrom = if (swapFrom == null) audio else null }, modifier = Modifier.focusRing()) {
                Icon(Icons.Default.SwapHoriz, "Swap tiles", tint = if (swapFrom != null) DS.Accent else DS.TextDim)
            }
            IconButton(onClick = { saving = true }, modifier = Modifier.focusRing()) { Icon(Icons.Default.BookmarkAdd, "Save layout", tint = DS.TextDim) }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(layouts, key = { it.name }) { l ->
                    val shape = RoundedCornerShape(16.dp)
                    Text(
                        l.name, color = DS.Text, fontSize = 13.sp,
                        modifier = Modifier.focusRing(shape).clip(shape).background(DS.Surface)
                            .combinedClickableCompat(onClick = { load(l) }, onLongClick = { app.prefs.deleteLayout(l.name) })
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (swapFrom != null) Text("Swap: pick the tile to trade places with.", color = DS.Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))

        val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
            Box(m.padding(3.dp)) {
                MultiTile(
                    channel = slots[i], hasAudio = audio == i, swapping = swapFrom == i,
                    onSelect = { tileClick(i) }, onChange = { pickFor = i }, onClear = { slots[i] = null },
                )
            }
        }
        when (grid) {
            2 -> Row(Modifier.weight(1f).fillMaxWidth()) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
            3 -> Row(Modifier.weight(1f).fillMaxWidth()) {
                tile(0, Modifier.weight(2f).fillMaxSize())
                Column(Modifier.weight(1f).fillMaxSize()) { tile(1, Modifier.weight(1f).fillMaxWidth()); tile(2, Modifier.weight(1f).fillMaxWidth()) }
            }
            else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.weight(1f).fillMaxWidth()) { tile(0, Modifier.weight(1f).fillMaxSize()); tile(1, Modifier.weight(1f).fillMaxSize()) }
                Row(Modifier.weight(1f).fillMaxWidth()) { tile(2, Modifier.weight(1f).fillMaxSize()); tile(3, Modifier.weight(1f).fillMaxSize()) }
            }
        }
        Text("Tap a tile to hear it · long-press to change channel · needs a plan that allows several connections",
            color = DS.TextDim, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
    }

    pickFor?.let { idx ->
        ChannelPickerDialog(onPick = { c -> slots[idx] = c; audio = idx; pickFor = null }, onDismiss = { pickFor = null })
    }
    if (saving) {
        var name by remember { mutableStateOf("Layout ${layouts.size + 1}") }
        AlertDialog(
            onDismissRequest = { saving = false },
            containerColor = DS.Surface,
            title = { Text("Save layout", color = DS.Text) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Name") })
                    Text("Keeps up to 8 layouts. Long-press a saved layout to delete it.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    app.prefs.saveLayout(MultiLayout(name.trim(), grid, slots.map { it?.streamId ?: -1 }))
                    saving = false
                }) { Text("Save", color = DS.Accent) }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel", color = DS.TextDim) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiTile(
    channel: Channel?,
    hasAudio: Boolean,
    swapping: Boolean,
    onSelect: () -> Unit,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxSize()
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(DS.Surface.copy(alpha = 0.4f))
            .then(
                when {
                    swapping -> Modifier.border(3.dp, Color(0xFFFFC107), shape)
                    hasAudio && channel != null -> Modifier.border(3.dp, DS.Accent, shape)
                    else -> Modifier
                }
            )
            .combinedClickable(onClick = onSelect, onLongClick = onChange),
    ) {
        if (channel == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, null, tint = DS.TextDim, modifier = Modifier.size(32.dp))
                Text("Add channel", color = DS.TextDim, fontSize = 13.sp)
            }
        } else {
            TilePlayer(channel, hasAudio)
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasAudio) Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = DS.Accent, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(channel.name, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TilePlayer(channel: Channel, hasAudio: Boolean) {
    val context = LocalContext.current
    val repo = DekStreamApp.instance.repo ?: return
    val player = remember(channel.streamId) { buildPlayer(context, live = true) }
    LaunchedEffect(channel.streamId) {
        val url = runCatching { repo.provider.resolve(repo.liveRequest(channel).url) }.getOrDefault(repo.liveRequest(channel).url)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(hasAudio) { player.volume = if (hasAudio) 1f else 0f }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Search-as-you-type channel picker (favorites first). */
@Composable
fun ChannelPickerDialog(onPick: (Channel) -> Unit, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    var q by remember { mutableStateOf("") }
    val all by rememberLoad(Unit) { repo.allChannels() }
    val favs by app.prefs.favorites.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DS.Surface,
        title = { Text("Choose a channel", color = DS.Text) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search channels") }, modifier = Modifier.fillMaxWidth())
                LoadView(all) { list ->
                    val n = q.trim().lowercase()
                    val shown = (if (n.isEmpty()) list.filter { it.streamId in favs } + list.filter { it.streamId !in favs }
                        else list.filter { it.name.lowercase().contains(n) }).take(150)
                    LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                        items(shown, key = { it.streamId }) { c ->
                            val shape = RoundedCornerShape(8.dp)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRing(shape, 1.02f).clip(shape)
                                    .background(DS.Surface2).clickable { onPick(c) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ChannelLogo(c.icon, 32.dp)
                                Text(c.name, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DS.TextDim) } },
    )
}

@Suppress("unused") private fun RowScope.noop() {}
@Suppress("unused") private fun ColumnScope.noop2() {}
