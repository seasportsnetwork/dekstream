package com.dekstream.tv.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Channel
import kotlinx.coroutines.delay

/** What the big header shows: whatever is focused or featured. */
data class HeroItem(
    val key: String,
    val title: String,
    val image: String?,
    val meta: String? = null,
    val description: String? = null,
    val badge: String? = null,
    /** Wide art fills the header; posters are shown smaller on the right instead. */
    val imageIsPoster: Boolean = false,
    val progress: Float? = null,
)

/** Full-bleed artwork that crossfades as the focused item changes, darkened toward the content. */
@Composable
fun HeroBackdrop(item: HeroItem?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Crossfade(targetState = item?.image to item?.imageIsPoster, animationSpec = tween(500), label = "hero") { (img, poster) ->
            Box(Modifier.fillMaxSize()) {
                if (img != null) {
                    AsyncImage(
                        model = img, contentDescription = null, contentScale = ContentScale.Crop,
                        alpha = if (poster == true) 0.35f else 0.9f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Readability: dark from the left (text side) and from the bottom (rows).
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to DS.Bg, 0.45f to DS.Bg.copy(alpha = 0.75f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to DS.Bg)))
    }
}

/** Title, meta line, description; animates with the item. */
@Composable
fun HeroInfo(item: HeroItem?, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Crossfade(targetState = item, animationSpec = tween(300), label = "heroInfo", modifier = modifier) { it ->
        if (it == null) return@Crossfade
        Column(Modifier.widthIn(max = 620.dp)) {
            it.badge?.let { b ->
                Text(
                    b, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (b == "LIVE") DS.Live else DS.Accent)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Text(
                it.title, color = DS.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            it.meta?.let { m -> Text(m, color = DS.Text.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
            it.progress?.let { p ->
                Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.5f).padding(end = 1.dp)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 0.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFFFFF)).padding(1.5.dp))
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(DS.Accent).padding(1.5.dp))
                }
            }
            it.description?.takeIf { d -> d.isNotBlank() }?.let { d ->
                Text(d, color = DS.TextDim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp))
            }
            extra()
        }
    }
}

/**
 * Small live player for previews (Live TV pane, Guide header). Waits briefly before tuning so
 * scrolling through channels doesn't start a stream for each one.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePreview(channel: Channel?, modifier: Modifier = Modifier, muted: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val context = LocalContext.current
    val repo = DekStreamApp.instance.repo
    var active by remember { mutableStateOf<Channel?>(null) }
    var buffering by remember { mutableStateOf(false) }
    val player = remember { buildPlayer(context, live = true) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(channel?.streamId) {
        player.stop()
        active = null
        val c = channel ?: return@LaunchedEffect
        delay(700)
        val r = repo ?: return@LaunchedEffect
        val prefs = DekStreamApp.instance.session?.prefs
        if (prefs?.isChannelLocked(c) == true) return@LaunchedEffect
        val url = runCatching { r.provider.resolve(r.liveRequest(c).url) }.getOrNull() ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.playWhenReady = true
        active = c
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (active == null && channel != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChannelLogo(channel.icon, 72.dp) }
        }
        if (buffering && active != null) CircularProgressIndicator(color = DS.Accent, modifier = Modifier.align(Alignment.Center))
        content()
    }
}

@Suppress("unused") private val h = Modifier.fillMaxHeight()
@Suppress("unused") private val r = Row::class
