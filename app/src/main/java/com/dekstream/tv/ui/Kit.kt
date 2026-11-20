package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/*
 * The DekTV design kit: big rounded cards, pill chips and buttons, section headers with "See all",
 * bold mood tiles and a floating search bar. Everything takes remote focus.
 */

/** Small rounded label on top of artwork ("New season", "Live", "Continue"). */
@Composable
fun ArtChip(text: String, icon: ImageVector? = null, color: Color = Color(0xB3000000), textColor: Color = Color.White) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = textColor, modifier = Modifier.size(13.dp)); Spacer(Modifier.size(5.dp)) }
        Text(text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Filter chip: filled when selected. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.focusRing(shape, 1.04f).clip(shape)
            .background(if (selected) DS.Invert else DS.Card)
            .border(1.dp, if (selected) Color.Transparent else DS.Line, shape)
            .tvClickable(onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) DS.OnInvert else DS.TextDim, modifier = Modifier.size(15.dp)); Spacer(Modifier.size(6.dp))
        }
        Text(text, color = if (selected) DS.OnInvert else DS.Text, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Segmented control (e.g. Home | Sports). */
@Composable
fun Segmented(options: List<Pair<String, ImageVector?>>, selected: Int, onSelect: (Int) -> Unit) {
    val outer = RoundedCornerShape(50)
    Row(Modifier.clip(outer).background(DS.Card).border(1.dp, DS.Line, outer).padding(3.dp)) {
        options.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.focusRing(shape, 1.03f).clip(shape)
                    .background(if (on) (if (DS.Light) Color(0xFFF0F0F3) else Appearance.palette.surface2) else Color.Transparent)
                    .tvClickable({ onSelect(i) }).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = if (on) DS.Text else DS.TextDim, modifier = Modifier.size(15.dp)); Spacer(Modifier.size(6.dp))
                }
                Text(label, color = if (on) DS.Text else DS.TextDim, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Rounded action button. primary = white/black filled; otherwise an outlined card pill. */
@Composable
fun PillButton(
    text: String, onClick: () -> Unit, icon: ImageVector? = null, primary: Boolean = false,
    onImage: Boolean = false, modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val bg = when { primary && onImage -> Color.White; primary -> DS.Invert; onImage -> Color(0x40FFFFFF); else -> DS.Card }
    val fg = when { primary && onImage -> Color(0xFF111111); primary -> DS.OnInvert; onImage -> Color.White; else -> DS.Text }
    Row(
        modifier.focusRing(shape, 1.05f).clip(shape).background(bg)
            .then(if (!primary && !onImage) Modifier.border(1.dp, DS.Line, shape) else Modifier)
            .tvClickable(onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(7.dp)) }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Round icon-only button (add, info, mute). */
@Composable
fun CircleButton(icon: ImageVector, onClick: () -> Unit, onImage: Boolean = true, contentDescription: String? = null) {
    Box(
        Modifier.size(40.dp).focusRing(CircleShape, 1.08f).clip(CircleShape)
            .background(if (onImage) Color(0x40FFFFFF) else DS.Card)
            .border(1.dp, if (onImage) Color(0x66FFFFFF) else DS.Line, CircleShape)
            .tvClickable(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = if (onImage) Color.White else DS.Text, modifier = Modifier.size(20.dp)) }
}

/** Row header: bold title, optional subtitle, "See all ›" on the right. */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, onSeeAll: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DS.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onSeeAll != null) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.focusRing(shape).clip(shape).tvClickable(onSeeAll).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", color = DS.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DS.Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Bento card: rounded, hairline border, optional artwork with a dark fade so white text reads on top.
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    image: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    fade: Boolean = image != null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(DS.R)
    Box(
        modifier
            .then(if (DS.Light) Modifier.shadow(6.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000)) else Modifier)
            .focusRing(shape, 1.025f)
            .then(if (onFocused != null) Modifier.onFocusGained(onFocused) else Modifier)
            .clip(shape)
            .background(if (image != null) Color(0xFF111111) else DS.Card)
            .border(1.dp, DS.Line, shape)
            .then(if (onClick != null) Modifier.tvClickable(onClick, onLongClick) else Modifier),
    ) {
        if (image != null) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (fade) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        }
        content()
    }
}

/** Stat tile: colored icon badge + label, a big number and a line under it. */
@Composable
fun StatTile(
    label: String, icon: ImageVector, tint: Color, value: String, caption: String?,
    onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float? = null,
) {
    BentoCard(modifier, onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(tint), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Text(label, color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DS.TextDim, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(value, color = DS.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, color = DS.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress != null) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(DS.Line)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(tint))
                }
            }
        }
    }
}

/** Genre / mood tile: tiny colored kicker and a huge condensed uppercase name over dimmed art. */
@Composable
fun MoodTile(kicker: String, name: String, image: String?, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BentoCard(modifier, image = image, onClick = onClick, fade = false) {
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color(0xF0101010), Color(0xB3101010), Color(0x40101010)))
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp, end = 10.dp)) {
            Text(kicker.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(name.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 26.sp,
                letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Floating search bar. */
@Composable
fun SearchBarPill(onClick: () -> Unit, hint: String = "Channels, movies and shows", modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth()
            .shadow(if (DS.Light) 8.dp else 0.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .focusRing(shape, 1.02f).clip(shape).background(DS.Card).border(1.dp, DS.Line, shape)
            .tvClickable(onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(Icons.Default.Search, null, tint = DS.TextDim, modifier = Modifier.size(18.dp))
        Text(hint, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
    }
}

fun Modifier.onFocusGained(block: () -> Unit): Modifier =
    this.onFocusChanged { if (it.hasFocus) block() }
