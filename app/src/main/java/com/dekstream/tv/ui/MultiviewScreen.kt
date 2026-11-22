package com.dekstream.tv.ui

import androidx.compose.material.icons.filled.Scoreboard

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
fun MultiviewScreen(initial: List<Channel> = emptyList(), games: List<com.dekstream.tv.data.Game> = emptyList(), onExit: (() -> Unit)? = null) {
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
    // One-connection plans: HLS tiles, extra logins, and score cards for games that can't get a stream.
    val ctx = LocalContext.current
    val mvPrefs = remember { ctx.getSharedPreferences("multiview", android.content.Context.MODE_PRIVATE) }
    var hlsTiles by remember { mutableStateOf(mvPrefs.getBoolean("hls_" + repo.source.id, true)) }
    val limited = remember { mutableStateListOf(false, false, false, false) }
    val limitWhy = remember { mutableStateListOf<String?>(null, null, null, null) }
    val scoreCard = remember { mutableStateListOf(false, false, false, false) }
    val account by rememberLoad(Unit) { runCatching { repo.provider.verify() }.getOrNull() }
    val maxConns = account.valueOrNull()?.maxConnections ?: 0
    val logins = 1 + repo.source.extraLogins.size
    // Plan reports N streams per login: only that many tiles connect; the rest show what's on and swap in on OK.
    // (Some providers drop your first stream when a second one connects, so don't even try.)
    val streamSlots = if (maxConns > 0) maxConns * logins else 4
    fun playingTile(except: Int): Int? =
        (listOf(audio) + (0 until 4).toList()).firstOrNull { it != except && slots[it] != null && !limited[it] && !scoreCard[it] && it < streamSlots }
    fun swapIn(i: Int) {
        val j = playingTile(i) ?: run { limited[i] = false; return }
        val t = slots[j]; slots[j] = slots[i]; slots[i] = t
        limited[j] = false; limitWhy[j] = null
        limited[i] = true
        audio = j
    }

    if (onExit != null) BackHandler { onExit() }

    // Sports multiview: the sound jumps to whichever game just scored.
    var followAction by remember { mutableStateOf(games.isNotEmpty()) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(games, followAction) {
        if (games.isEmpty() || !followAction) return@LaunchedEffect
        val last = HashMap<String, String>()
        var current = games
        while (true) {
            current = current.map { g -> runCatching { app.sports.refresh(g) }.getOrDefault(g) }
            current.forEachIndexed { i, g ->
                val total = "${g.home.score}-${g.away.score}"
                val before = last[g.id]
                last[g.id] = total
                if (before != null && before != total && i < 4 && slots[i] != null && audio != i) {
                    audio = i
                    actionNote = if (app.prefs.hideScores.value) "Sound moved to ${g.away.abbr} @ ${g.home.abbr}"
                        else "Score in ${g.away.abbr} @ ${g.home.abbr} · ${g.away.score}–${g.home.score}"
                }
            }
            kotlinx.coroutines.delay(30_000)
        }
    }
    LaunchedEffect(actionNote) { if (actionNote != null) { kotlinx.coroutines.delay(5_000); actionNote = null } }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); if (slots.any { it != null }) app.session?.settings?.addWatchMinute() } }

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
            CategoryChip(if (hlsTiles) "One-connection mode: on" else "One-connection mode: off", hlsTiles) {
                hlsTiles = !hlsTiles; mvPrefs.edit().putBoolean("hls_" + repo.source.id, hlsTiles).apply()
                for (i in 0 until 4) limited[i] = false
            }
            HSpacer(6.dp)
            if (games.isNotEmpty()) {
                CategoryChip(if (followAction) "Sound follows the action: on" else "Sound follows the action: off", followAction) { followAction = !followAction }
                HSpacer(6.dp)
            }
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
        actionNote?.let { Text("🔊 $it", color = DS.Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)) }

        val tile: @Composable (Int, Modifier) -> Unit = { i, m ->
            Box(m.padding(3.dp)) {
                val game = games.getOrNull(i)
                if (scoreCard[i] && game != null) {
                    ScoreCardTile(game, swapping = swapFrom == i, onSelect = { tileClick(i) }, onVideo = { scoreCard[i] = false; limited[i] = false })
                } else MultiTile(
                    channel = slots[i], hasAudio = audio == i, swapping = swapFrom == i,
                    onSelect = { if (limited[i] || i >= streamSlots) swapIn(i) else tileClick(i) },
                    onChange = { pickFor = i }, onClear = { slots[i] = null; limited[i] = false },
                    // Wait for the plan's stream count before connecting anything beyond the first tile.
                    slot = i, hls = hlsTiles, limited = limited[i] || i >= streamSlots || (i > 0 && account is Load.Loading),
                    onLimited = { why -> limited[i] = true; limitWhy[i] = why },
                    canScore = game != null, onScore = { scoreCard[i] = true },
                    why = limitWhy[i], oneStream = maxConns > 0 && streamSlots < 2,
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
        Text("Tap a tile to hear it · long-press to change channel · " + when {
                maxConns > 0 -> "your plan allows $maxConns stream${if (maxConns == 1) "" else "s"}" +
                    (if (logins > 1) " per login · $logins logins" else "") +
                    (if (maxConns * logins < 2) " — one-connection mode and score cards help here" else "")
                else -> "more channels need more connections, or try one-connection mode"
            },
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

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.tvClickable(onClick = onClick, onLongClick = onLongClick)

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiTile(
    channel: Channel?,
    hasAudio: Boolean,
    swapping: Boolean,
    onSelect: () -> Unit,
    onChange: () -> Unit,
    onClear: () -> Unit,
    slot: Int = 0,
    hls: Boolean = false,
    limited: Boolean = false,
    onLimited: (String) -> Unit = {},
    canScore: Boolean = false,
    onScore: () -> Unit = {},
    why: String? = null,
    oneStream: Boolean = false,
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
            .tvClickable(onClick = onSelect, onLongClick = onChange),
    ) {
        if (channel == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Add, null, tint = DS.TextDim, modifier = Modifier.size(32.dp))
                Text("Add channel", color = DS.TextDim, fontSize = 13.sp)
            }
        } else {
            if (!limited) TilePlayer(channel, hasAudio, slot, hls, onLimited)
            else WaitingTile(channel, why, oneStream, canScore, onScore)
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
private fun TilePlayer(channel: Channel, hasAudio: Boolean, slot: Int = 0, hlsWanted: Boolean = false, onLimited: (String) -> Unit = {}) {
    val context = LocalContext.current
    val repo = DekStreamApp.instance.repo ?: return
    val player = remember(channel.streamId) { buildPlayer(context, live = true, small = true) }
    // If the provider doesn't serve HLS (404/400), fall back to TS once before calling it refused.
    var hls by remember(channel.streamId) { mutableStateOf(hlsWanted) }
    LaunchedEffect(channel.streamId, hls) {
        val src = repo.source
        var url = repo.liveRequest(channel).url
        if (src.type == com.dekstream.tv.data.SourceType.XTREAM) {
            // Tiles after the first use the playlist's extra logins, when there are any.
            src.extraLogins.getOrNull(slot - 1)?.let { login ->
                val (u, p) = login.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                url = url.replace("/live/${src.username}/${src.password}/", "/live/$u/$p/")
            }
            // HLS fetches short segments instead of holding one long connection open.
            if (hls) url = url.replace(Regex("\\.ts$"), ".m3u8")
        }
        // Stagger starts so the server doesn't see several brand-new connections at the same moment.
        kotlinx.coroutines.delay(slot * 1_500L)
        url = runCatching { repo.provider.resolve(url) }.getOrDefault(url)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val code = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (hls && (code == 404 || code == 400 || code == 405)) { hls = false; return }
                onLimited(if (code != null) "HTTP $code" else error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(hasAudio) { player.volume = if (hasAudio) 1f else 0f }
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

/** A tile that isn't streaming: what's on, why, and OK to bring it into the playing tile. */
@Composable
private fun WaitingTile(channel: Channel, why: String?, oneStream: Boolean, canScore: Boolean, onScore: () -> Unit) {
    val repo = DekStreamApp.instance.repo
    val now by androidx.compose.runtime.produceState<com.dekstream.tv.data.Program?>(null, channel.streamId) {
        value = runCatching { repo?.nowNext(channel)?.firstOrNull { it.isLive() } }.getOrNull()
    }
    Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ChannelLogo(channel.icon, 56.dp)
        Text(now?.title ?: channel.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            modifier = Modifier.padding(top = 8.dp))
        now?.let { Text("${minutesLeft(it)} left", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp) }
        Text(
            when {
                oneStream -> "Your plan streams one channel at a time · press OK to watch this one here"
                why != null -> "Couldn't start ($why) · press OK to watch this one here instead"
                else -> "Press OK to watch this one here"
            },
            color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        if (canScore) PillButton("Show live score here", onScore, Icons.Default.Scoreboard, onImage = true)
    }
}

/** A live score card in place of a stream (for games your plan can't stream at the same time). */
@Composable
private fun ScoreCardTile(game: com.dekstream.tv.data.Game, swapping: Boolean, onSelect: () -> Unit, onVideo: () -> Unit) {
    val app = DekStreamApp.instance
    val hide by app.prefs.hideScores.collectAsState()
    val g by androidx.compose.runtime.produceState(game, game.id) {
        while (true) { value = runCatching { app.sports.refresh(value) }.getOrDefault(value); kotlinx.coroutines.delay(30_000) }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxSize().focusRing(shape, 1.02f).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF101318), DS.Accent.copy(alpha = 0.45f))))
            .then(if (swapping) Modifier.border(3.dp, Color(0xFFFFC107), shape) else Modifier)
            .tvClickable(onClick = onSelect, onLongClick = onVideo),
    ) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(g.away to Alignment.Start, g.home to Alignment.End).forEachIndexed { idx, (t, _) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    coil.compose.AsyncImage(t.logo, null, modifier = Modifier.size(54.dp))
                    Text(t.abbr, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (!hide && g.state != "pre") Text(t.score ?: "-", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                if (idx == 0) Text(if (g.isLive) g.detail else if (g.state == "pre") fmtTime(g.startMs) else "Final",
                    color = if (g.isLive) Color(0xFFFF6B60) else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Live score · hold OK to try the stream again", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
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
