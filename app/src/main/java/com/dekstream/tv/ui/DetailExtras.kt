package com.dekstream.tv.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.dekstream.tv.data.CastMember
import com.dekstream.tv.data.Review
import com.dekstream.tv.data.Stremio
import com.dekstream.tv.data.TitleExtras
import com.dekstream.tv.data.Trailer

/** Summary that shows 4 lines with "Show more" (OK expands, again collapses). */
@Composable
fun ExpandableText(text: String, color: Color = DS.Text, modifier: Modifier = Modifier) {
    var open by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(text, color = color, fontSize = 15.sp, lineHeight = 22.sp, maxLines = if (open) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!open) overflows = it.hasVisualOverflow })
        if (overflows || open) {
            val shape = RoundedCornerShape(50)
            Row(Modifier.padding(top = 6.dp).focusRing(shape).clip(shape).tvClickable({ open = !open }).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "Show less" else "Show more", color = DS.TextDim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = DS.TextDim, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** What a detail page knows before TMDB answers (from Cinemeta / the provider). */
data class BasicCredits(val cast: List<String>, val director: String?, val writers: List<String>, val trailer: String?)

/**
 * Director / writers, trailers, cast (photos + characters with a TMDB key, initials without) and reviews.
 * type = "movie" or "series".
 */
@Composable
fun DetailExtras(
    type: String, tmdbId: String?, imdbId: String?, title: String, year: String?, basic: BasicCredits,
    onAddKey: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val hasKey = remember { Stremio.tmdbKey(ctx).isNotBlank() }
    var extras by remember(title, imdbId) { mutableStateOf<TitleExtras?>(null) }
    LaunchedEffect(title, imdbId, tmdbId) { if (hasKey) extras = Stremio.extras(ctx, type, tmdbId, imdbId, title, year) }
    val e = extras
    var playing by remember { mutableStateOf<Trailer?>(null) }
    var reading by remember { mutableStateOf<Review?>(null) }

    val directors = e?.directors?.takeIf { it.isNotEmpty() } ?: listOfNotNull(basic.director)
    val writers = e?.writers?.takeIf { it.isNotEmpty() } ?: basic.writers
    val cast = e?.cast?.takeIf { it.isNotEmpty() } ?: basic.cast.map { CastMember(it, null, null) }
    val trailers = e?.trailers?.takeIf { it.isNotEmpty() } ?: listOfNotNull(basic.trailer?.let { Trailer("Trailer", it) })

    Column(modifier) {
        if (directors.isNotEmpty()) CreditLine(if (type == "movie") "Director" else "Created by", directors.joinToString(", "))
        if (writers.isNotEmpty()) CreditLine(if (writers.size == 1) "Writer" else "Writers", writers.joinToString(", "))

        if (trailers.isNotEmpty()) {
            ExtrasLabel(if (trailers.size == 1) "Trailer" else "Trailers & teasers")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(trailers, key = { it.youtubeId }) { t -> TrailerCard(t) { playing = t } }
            }
        }
        if (cast.isNotEmpty()) {
            ExtrasLabel("Cast")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(cast.take(20), key = { it.name + (it.character ?: "") }) { CastCircle(it) }
            }
        }
        val reviews = e?.reviews.orEmpty()
        if (reviews.isNotEmpty()) {
            ExtrasLabel("Reviews")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(reviews, key = { it.author + it.created }) { r -> ReviewCard(r) { reading = r } }
            }
        }
        if (!hasKey) {
            val shape = RoundedCornerShape(50)
            Row(Modifier.padding(top = 18.dp).focusRing(shape).clip(shape).border(1.dp, DS.Line, shape).tvClickable(onAddKey)
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Add a free TMDB key in Settings › Addons for cast photos, trailers and reviews", color = DS.TextDim, fontSize = 13.sp)
            }
        }
    }
    playing?.let { TrailerPlayer(it) { playing = null } }
    reading?.let { ReviewDialog(it) { reading = null } }
}

@Composable
private fun CreditLine(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text("$label:  ", color = DS.TextDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = DS.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExtrasLabel(t: String) {
    Text(t, color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
}

@Composable
private fun CastCircle(c: CastMember) {
    Column(Modifier.width(118.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(104.dp).focusRing(CircleShape, 1.06f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(DS.Surface2, DS.Card))).tvClickable({}),
            contentAlignment = Alignment.Center,
        ) {
            if (c.photo != null) AsyncImage(c.photo, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(c.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                color = DS.TextDim, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Text(c.name, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        c.character?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun TrailerCard(t: Trailer, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(260.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(shape, 1.05f).clip(shape).background(DS.Surface2).tvClickable(onClick),
            contentAlignment = Alignment.Center) {
            AsyncImage("https://img.youtube.com/vi/${t.youtubeId}/hqdefault.jpg", t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play trailer", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Text(t.name, color = DS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ReviewCard(r: Review, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(420.dp).height(196.dp).focusRing(shape, 1.03f).clip(shape).background(DS.Card).border(1.dp, DS.Line, shape)
        .tvClickable(onClick).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.author, color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            r.rating?.let {
                Text("${if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)}/10", color = DS.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(DS.Surface2).padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
        Text(r.content, color = DS.TextDim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ReviewDialog(r: Review, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(20.dp)).background(DS.Surface2).padding(26.dp)) {
            Text(r.author + (r.rating?.let { "  ·  ${"%.0f".format(it)}/10" } ?: ""), color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            r.created?.let { Text(it, color = DS.TextDim, fontSize = 12.sp) }
            // Focusable so the remote's Up/Down scroll it.
            Column(Modifier.padding(top = 12.dp).height(360.dp).focusRequester(focus).focusRing(RoundedCornerShape(8.dp)).tvClickable(onClose)
                .verticalScroll(rememberScrollState())) {
                Text(r.content, color = DS.Text, fontSize = 15.sp, lineHeight = 23.sp)
            }
            Text("Back to close", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Trailer in DekTV's own full-screen player (YouTube's embedded player in a web view — the YouTube app is
 * never opened). OK pauses/plays, Left/Right skip 10 s, Back closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPlayer(t: Trailer, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
                .androidx_onKey { key ->
                    val js = when (key) {
                        "ok" -> "toggle()"; "left" -> "skip(-10)"; "right" -> "skip(10)"; else -> null
                    }
                    if (js != null) web?.evaluateJavascript(js, null)
                    js != null
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        isFocusable = false
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            // Never leave the trailer page (no jumping out to YouTube).
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
                        }
                        val id = t.youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                        val html = """
                            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style></head>
                            <body><div id="p"></div>
                            <script src="https://www.youtube.com/iframe_api"></script>
                            <script>
                              var player;
                              function onYouTubeIframeAPIReady(){
                                player=new YT.Player('p',{videoId:'$id',host:'https://www.youtube-nocookie.com',
                                  playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3,origin:'https://seasportsnetwork.github.io'},
                                  events:{onReady:function(e){e.target.playVideo();}}});
                              }
                              function toggle(){ if(!player||!player.getPlayerState) return; player.getPlayerState()==1?player.pauseVideo():player.playVideo(); }
                              function skip(s){ if(player&&player.getCurrentTime) player.seekTo(Math.max(0,player.getCurrentTime()+s),true); }
                            </script></body></html>
                        """.trimIndent()
                        // An https page address so YouTube accepts the embed.
                        loadDataWithBaseURL("https://seasportsnetwork.github.io/", html, "text/html", "utf-8", null)
                        web = this
                    }
                },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
            Text(t.name, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
        }
    }
}

/** D-pad keys for the trailer: "ok", "left", "right" (focusable so the box receives them). */
private fun Modifier.androidx_onKey(handle: (String) -> Boolean): Modifier = this
    .onPreviewKeyEvent { ev ->
        if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (ev.key) {
            androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter,
            androidx.compose.ui.input.key.Key.MediaPlayPause, androidx.compose.ui.input.key.Key.Spacebar -> handle("ok")
            androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.MediaRewind -> handle("left")
            androidx.compose.ui.input.key.Key.DirectionRight, androidx.compose.ui.input.key.Key.MediaFastForward -> handle("right")
            else -> false
        }
    }
    .focusable()
