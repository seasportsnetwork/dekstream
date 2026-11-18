package com.dekstream.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Result of a suspend load, for use in composables. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

@Suppress("UNCHECKED_CAST")
fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ok<T>)?.value

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = try {
            Load.Ok(block())
        } catch (e: Exception) {
            Load.Err(e.message ?: "Something went wrong")
        }
    }

@Composable
fun <T> LoadView(
    state: Load<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is Load.Loading -> Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = DS.Accent)
        }
        is Load.Err -> Column(
            modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, color = DS.TextDim)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again", color = DS.Accent) }
        }
        is Load.Ok -> content(state.value)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        color = DS.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    image: String?,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
    wide: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .focusRing(shape)
                .clip(shape)
                .background(DS.Surface2)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    title,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    color = DS.TextDim,
                    fontSize = 13.sp,
                    maxLines = 4,
                )
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = DS.Accent,
                    trackColor = Color(0x66000000),
                )
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            color = DS.Text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                modifier = Modifier.padding(horizontal = 2.dp),
                color = DS.TextDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ChannelLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(8.dp)).background(DS.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Icon(Icons.Default.Tv, contentDescription = null, tint = DS.TextDim)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRowItem(
    name: String,
    icon: String?,
    number: Int?,
    now: String?,
    next: String?,
    nowProgress: Float?,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .focusRing(shape, 1.02f)
            .clip(shape)
            .background(DS.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null && number > 0) {
            Text("$number", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.width(40.dp))
        }
        ChannelLogo(icon, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false),
                )
                if (favorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, contentDescription = "Favorite", tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                }
            }
            if (now != null) {
                Text(now, color = DS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nowProgress != null) {
                    LinearProgressIndicator(
                        progress = { nowProgress },
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = DS.Accent,
                        trackColor = DS.Surface2,
                    )
                }
            }
            if (next != null) {
                Text("Next: $next", color = DS.TextDim.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        "LIVE",
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(DS.Live).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, DS.Bg))

@Composable
fun HSpacer(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpacer(h: Dp) = Spacer(Modifier.height(h))

val rowSpacing = Arrangement.spacedBy(14.dp)
