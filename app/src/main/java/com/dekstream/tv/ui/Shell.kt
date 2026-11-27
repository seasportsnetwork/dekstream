package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wide screens (tablet landscape, TV): a side rail.
 * Narrow screens (phone / tablet portrait): a bottom bar with the main tabs.
 */
@Composable
fun Shell(state: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            if (Appearance.sidebarMode == 2) {
                // Auto-hide: the page keeps the slim strip's width; the opened menu slides over it.
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Spacer(Modifier.width(RAIL_SLIM + 1.dp))
                        Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(state) }
                    }
                    SideRail(state, overlay = true)
                }
            } else Row(Modifier.fillMaxSize()) {
                SideRail(state)
                Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(state) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(state) }
                BottomBar(state)
            }
        }
    }
}

@Composable
private fun TabContent(state: AppState) {
    when (state.tab) {
        Tab.Home -> HomeScreen(state)
        Tab.Live -> LiveScreen(state)
        Tab.Favorites -> FavoritesScreen(state)
        Tab.Discover -> DiscoverScreen(state)
        Tab.Guide -> GuideScreen(state)
        Tab.Movies -> VodBrowseScreen(state, isSeries = false)
        Tab.Series -> VodBrowseScreen(state, isSeries = true)
        Tab.Sports -> SportsScreen(state)
        Tab.Multiview -> MultiviewScreen()
        Tab.Search -> SearchScreen(state)
        Tab.Profile -> ProfileScreen(state)
        Tab.Settings -> SettingsScreen(state)
    }
}

/** Sidebar groups, HolyArrs style: unlabeled top group, then labeled sections. */
private val sidebarGroups: List<Pair<String?, List<Tab>>> = listOf(
    null to listOf(Tab.Home, Tab.Search),
    "Watch" to listOf(Tab.Live, Tab.Favorites, Tab.Guide, Tab.Sports, Tab.Multiview),
    "Library" to listOf(Tab.Discover, Tab.Movies, Tab.Series),
    "Manage" to listOf(Tab.Settings),
)

private val RAIL_SLIM = 76.dp
private val RAIL_OPEN = 228.dp

@Composable
private fun SideRail(state: AppState, overlay: Boolean = false) {
    var railFocused by remember { mutableStateOf(false) }
    val open = when (Appearance.sidebarMode) {
        1 -> false
        2 -> railFocused
        else -> !(state.tab == Tab.Discover && !railFocused) // Discover runs near full screen
    }
    // Smooth slide: width eases between the slim strip and the open menu; labels fade in after it widens.
    val ease = androidx.compose.animation.core.FastOutSlowInEasing
    val width by androidx.compose.animation.core.animateDpAsState(if (open) RAIL_OPEN else RAIL_SLIM,
        androidx.compose.animation.core.tween(280, easing = ease), label = "rail")
    val labels by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f,
        androidx.compose.animation.core.tween(if (open) 220 else 120, delayMillis = if (open) 80 else 0, easing = ease), label = "labels")
    val scrim by androidx.compose.animation.core.animateFloatAsState(if (overlay && open) 1f else 0f,
        androidx.compose.animation.core.tween(280, easing = ease), label = "scrim")
    val visible = visibleTabs()
    Box(Modifier.fillMaxHeight()) {
        // Soft shade over the page while the overlay menu is open.
        if (scrim > 0.01f) Box(Modifier.fillMaxHeight().width(RAIL_OPEN + 220.dp).graphicsLayer { alpha = scrim }
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Row(Modifier.fillMaxHeight()) {
            Column(
                Modifier
                    .onFocusChanged { railFocused = it.hasFocus }
                    .width(width)
                    .fillMaxHeight()
                    .background(
                        if (DS.Light) Color(0xFFFAFAFB)
                        else if (overlay) Appearance.palette.surface.copy(alpha = 0.97f)
                        else Appearance.palette.surface.copy(alpha = 0.55f)
                    )
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                // Brand + collapse toggle
                Row(Modifier.fillMaxWidth().height(40.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (labels > 0.5f) {
                        Box(Modifier.graphicsLayer { alpha = (labels - 0.5f) * 2 }.padding(start = 6.dp)) { BrandMark(26.dp, nameSize = 18) }
                        Spacer(Modifier.weight(1f))
                    } else Spacer(Modifier.width(10.dp))
                    if (Appearance.sidebarMode != 2) {
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            Modifier.size(32.dp).focusRing(shape).clip(shape).background(DS.Card).border(1.dp, DS.Line, shape)
                                .clickable { Appearance.sidebarCollapsed = open },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (open) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                "Toggle sidebar", tint = DS.TextDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    sidebarGroups.forEach { (label, tabs) ->
                        val shown = tabs.filter { it in visible }
                        if (shown.isEmpty()) return@forEach
                        if (label != null) {
                            Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.BottomStart) {
                                if (labels > 0.5f) Text(label, color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                                    modifier = Modifier.graphicsLayer { alpha = (labels - 0.5f) * 2 }.padding(start = 10.dp, bottom = 6.dp))
                                else Box(Modifier.padding(start = 14.dp, bottom = 12.dp).width(24.dp).height(1.dp).background(DS.Line))
                            }
                        }
                        shown.forEach { t -> SidebarItem(t, state.tab == t, labels) { state.tab = t } }
                    }
                }
                // Profile card at the bottom
                com.dekstream.tv.DekStreamApp.instance.session?.let { s ->
                    var focused by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).onFocusChanged { focused = it.isFocused }
                            .clip(shape).background(if (focused) DS.Invert else if (state.tab == Tab.Profile) DS.Card else Color.Transparent)
                            .clickable { state.tab = Tab.Profile }.padding(start = 9.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(s.profile, 34.dp)
                        if (labels > 0.01f) Row(Modifier.weight(1f).graphicsLayer { alpha = labels }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(s.profile.name, color = if (focused) DS.OnInvert else DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, softWrap = false)
                                Text(s.source.name, color = if (focused) DS.OnInvert.copy(alpha = 0.7f) else DS.TextDim, fontSize = 12.sp, maxLines = 1,
                                    softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.UnfoldMore, null, tint = if (focused) DS.OnInvert else DS.TextDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(DS.Line))
        }
    }
}

@Composable
private fun SidebarItem(t: Tab, selected: Boolean, labels: Float, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // Same layout open or closed (icon never moves), so the slide stays smooth; the label just fades.
    Row(
        Modifier
            .padding(vertical = 1.dp)
            .fillMaxWidth()
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> DS.Invert
                    selected -> if (DS.Light) Color(0xFFEDEDF0) else Appearance.palette.surface2
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            t.icon, contentDescription = t.label,
            tint = when { focused -> DS.OnInvert; selected -> DS.Accent; else -> DS.TextDim },
            modifier = Modifier.size(20.dp),
        )
        if (labels > 0.01f) {
            Text(
                t.label, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                color = when { focused -> DS.OnInvert; else -> DS.Text },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 14.dp).graphicsLayer { alpha = labels },
            )
        }
    }
}

private val bottomTabs = listOf(Tab.Home, Tab.Live, Tab.Guide, Tab.Movies, Tab.Series, Tab.Sports)

@Composable
private fun BottomBar(state: AppState) {
    // The less common tabs (Multiview, Search, Settings) live behind "More" on Home.
    NavigationBar(containerColor = DS.Surface) {
        bottomTabs.filter { it in visibleTabs() }.forEach { t ->
            NavigationBarItem(
                selected = state.tab == t,
                onClick = { state.tab = t },
                icon = { Icon(t.icon, contentDescription = t.label) },
                label = { Text(t.label, fontSize = 10.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DS.Accent,
                    selectedTextColor = DS.Text,
                    unselectedIconColor = DS.TextDim,
                    unselectedTextColor = DS.TextDim,
                    indicatorColor = DS.AccentSoft,
                ),
            )
        }
    }
}

/** Tabs for this playlist and profile (e.g. no Movies tab if the playlist doesn't load movies). */
fun visibleTabs(): List<Tab> {
    val session = com.dekstream.tv.DekStreamApp.instance.session
        ?: return Tab.entries.filter { it != Tab.Multiview || com.dekstream.tv.data.PlaybackPrefs.multiview }
    val src = session.source
    return Tab.entries.filter { t ->
        when (t) {
            Tab.Multiview -> src.loadLive && com.dekstream.tv.data.PlaybackPrefs.multiview
            Tab.Live, Tab.Guide, Tab.Favorites -> src.loadLive
            Tab.Movies -> src.loadMovies
            Tab.Series -> src.loadShows
            Tab.Discover -> false // addons are off for now; Discover's titles play through addons
            Tab.Settings -> true
            else -> true
        }
    }
}
