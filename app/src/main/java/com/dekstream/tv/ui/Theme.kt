package com.dekstream.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

object DS {
    val Bg = Color(0xFF0B0D12)
    val Surface = Color(0xFF151922)
    val Surface2 = Color(0xFF1E2430)
    val Accent = Color(0xFFE50914)
    val AccentSoft = Color(0x33E50914)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9AA3B2)
    val Live = Color(0xFFFF3B30)
    val Focus = Color(0xFFFFFFFF)
}

@Composable
fun DekStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DS.Accent,
            onPrimary = Color.White,
            secondary = DS.Accent,
            background = DS.Bg,
            surface = DS.Surface,
            surfaceVariant = DS.Surface2,
            onBackground = DS.Text,
            onSurface = DS.Text,
            onSurfaceVariant = DS.TextDim,
        ),
        content = content,
    )
}

/**
 * Visible focus for remote (D-pad) navigation: white outline + slight zoom.
 * Harmless on touch screens, where nothing takes focus until tapped.
 */
fun Modifier.focusRing(shape: Shape = RectangleShape, scale: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (focused) Modifier.border(2.dp, DS.Focus, shape) else Modifier)
}
