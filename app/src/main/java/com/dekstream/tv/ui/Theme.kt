package com.dekstream.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Look & feel choices (per profile). Compose observes these, so changes apply instantly. */
object Appearance {
    data class Palette(val name: String, val bg: Color, val surface: Color, val surface2: Color)

    val accents = listOf(
        "Red" to Color(0xFFE50914), "Amber" to Color(0xFFFFB020), "Blue" to Color(0xFF2D7FF9),
        "Green" to Color(0xFF30D158), "Purple" to Color(0xFFBF5AF2), "Teal" to Color(0xFF00C7BE), "Pink" to Color(0xFFFF375F),
    )
    val palettes = listOf(
        Palette("Dark", Color(0xFF0B0D12), Color(0xFF151922), Color(0xFF1E2430)),
        Palette("Midnight", Color(0xFF070B1A), Color(0xFF10172F), Color(0xFF19223F)),
        Palette("Black", Color(0xFF000000), Color(0xFF0F0F11), Color(0xFF1B1B1F)),
        Palette("Slate", Color(0xFF14171C), Color(0xFF1F242C), Color(0xFF2A313B)),
    )
    /** How the focused item is highlighted when using a remote. */
    val selections = listOf("White outline", "Accent outline", "Glow")
    val guideBackgrounds = listOf(
        "Original" to listOf(Color.Transparent, Color.Transparent),
        "Midnight" to listOf(Color(0xFF0A1030), Color(0xFF05070F)),
        "Aurora" to listOf(Color(0xFF06302A), Color(0xFF14123A)),
        "Ember" to listOf(Color(0xFF3A1206), Color(0xFF120806)),
        "Forest" to listOf(Color(0xFF0C2A14), Color(0xFF060E08)),
        "Rose" to listOf(Color(0xFF3A0C22), Color(0xFF12060C)),
        "Lavender" to listOf(Color(0xFF231A44), Color(0xFF0C0A18)),
    )

    var accentIdx by mutableIntStateOf(0)
    var paletteIdx by mutableIntStateOf(0)
    var selectionIdx by mutableIntStateOf(0)
    var guideBgIdx by mutableIntStateOf(0)
    /** 0.55 (see-through panels) .. 1.0 (solid). */
    var panelAlpha by mutableFloatStateOf(1f)

    val palette get() = palettes.getOrElse(paletteIdx) { palettes[0] }
    val accent get() = accents.getOrElse(accentIdx) { accents[0] }.second
    val guideBrush: Brush get() = Brush.verticalGradient(guideBackgrounds.getOrElse(guideBgIdx) { guideBackgrounds[0] }.second)
}

object DS {
    val Bg: Color get() = Appearance.palette.bg
    val Surface: Color get() = Appearance.palette.surface.copy(alpha = Appearance.panelAlpha)
    val Surface2: Color get() = Appearance.palette.surface2.copy(alpha = (Appearance.panelAlpha + 0.1f).coerceAtMost(1f))
    val Accent: Color get() = Appearance.accent
    val AccentSoft: Color get() = Appearance.accent.copy(alpha = 0.22f)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFF9AA3B2)
    val Live = Color(0xFFFF3B30)
    val Focus: Color get() = if (Appearance.selectionIdx == 1) Appearance.accent else Color.White
}

@Composable
fun DekStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DS.Accent,
            onPrimary = Color.White,
            secondary = DS.Accent,
            background = DS.Bg,
            surface = Appearance.palette.surface,
            surfaceVariant = Appearance.palette.surface2,
            onBackground = DS.Text,
            onSurface = DS.Text,
            onSurfaceVariant = DS.TextDim,
        ),
        content = content,
    )
}

/**
 * Visible focus for remote (D-pad) navigation: outline or glow + slight zoom.
 * Harmless on touch screens, where nothing takes focus until tapped.
 */
fun Modifier.focusRing(shape: Shape = RectangleShape, scale: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    val glow = Appearance.selectionIdx == 2
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(
            when {
                !focused -> Modifier
                glow -> Modifier.background(DS.Accent.copy(alpha = 0.35f), shape).border(1.dp, DS.Accent, shape)
                else -> Modifier.border(2.dp, DS.Focus, shape)
            }
        )
}
