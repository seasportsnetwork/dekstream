package com.dekstream.tv.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel

/** Up to four live channels at once. Tap a tile to hear it; long-press to change its channel. */
@Composable
fun MultiviewScreen() {
    val slots = remember { mutableStateListOf<Channel?>(null, null, null, null) }
    var audio by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "Multiview · tap a tile to hear it, long-press to change channel",
            color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
        )
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    Box(Modifier.weight(1f).fillMaxSize().padding(4.dp)) {
                        MultiTile(
                            channel = slots[i],
                            hasAudio = audio == i,
                            onSelect = { if (slots[i] == null) pickFor = i else audio = i },
                            onChange = { pickFor = i },
                            onClear = { slots[i] = null },
                        )
                    }
                }
            }
        }
    }

    pickFor?.let { idx ->
        ChannelPickerDialog(
            onPick = { c -> slots[idx] = c; audio = idx; pickFor = null },
            onDismiss = { pickFor = null },
        )
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiTile(
    channel: Channel?,
    hasAudio: Boolean,
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
            .background(Color.Black)
            .then(if (hasAudio && channel != null) Modifier.border(3.dp, DS.Accent, shape) else Modifier)
            .combinedClickable(onClick = onSelect, onLongClick = onChange),
    ) {
        if (channel == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, null, tint = DS.TextDim)
                Text("Add channel", color = DS.TextDim, fontSize = 13.sp)
            }
        } else {
            TilePlayer(channel, hasAudio)
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasAudio) Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = DS.Accent, modifier = Modifier.padding(end = 6.dp))
                Text(channel.name, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Remove", tint = Color.White) }
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
        player.setMediaItem(MediaItem.fromUri(repo.liveRequest(channel).url))
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
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search channels") },
                    modifier = Modifier.fillMaxWidth())
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
