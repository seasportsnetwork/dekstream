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
            Row(Modifier.fillMaxSize()) {
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

@Composable
private fun SideRail(state: AppState) {
    val collapsed = Appearance.sidebarCollapsed
    val visible = visibleTabs()
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(if (collapsed) 76.dp else 228.dp)
                .fillMaxHeight()
                .background(if (DS.Light) Color(0xFFFAFAFB) else Appearance.palette.surface.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            // Brand + collapse toggle
            Row(Modifier.fillMaxWidth().padding(start = if (collapsed) 0.dp else 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start) {
                if (!collapsed) {
                    BrandMark(26.dp, nameSize = 18)
                    Spacer(Modifier.weight(1f))
                }
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.size(32.dp).focusRing(shape).clip(shape).background(DS.Card).border(1.dp, DS.Line, shape)
                        .clickable { Appearance.sidebarCollapsed = !collapsed },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        "Toggle sidebar", tint = DS.TextDim, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val app0 = com.dekstream.tv.DekStreamApp.instance
                app0.mini?.let { m ->
                    NowPlayingItem(m.first.title, collapsed) { app0.mini = null; state.play(m.first, m.second) }
                }
                sidebarGroups.forEach { (label, tabs) ->
                    val shown = tabs.filter { it in visible }
                    if (shown.isEmpty()) return@forEach
                    if (label != null) {
                        if (collapsed) Box(Modifier.padding(vertical = 8.dp).align(Alignment.CenterHorizontally).width(24.dp).height(1.dp).background(DS.Line))
                        else Text(label, color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp))
                    }
                    shown.forEach { t -> SidebarItem(t, state.tab == t, collapsed) { state.tab = t } }
                }
            }
            // Profile card at the bottom
            com.dekstream.tv.DekStreamApp.instance.session?.let { s ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).onFocusChanged { focused = it.isFocused }
                        .clip(shape).background(if (focused) DS.Invert else if (state.tab == Tab.Profile) DS.Card else Color.Transparent)
                        .clickable { state.tab = Tab.Profile }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                ) {
                    Avatar(s.profile, 34.dp)
                    if (!collapsed) {
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(s.profile.name, color = if (focused) DS.OnInvert else DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(s.source.name, color = if (focused) DS.OnInvert.copy(alpha = 0.7f) else DS.TextDim, fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.UnfoldMore, null, tint = if (focused) DS.OnInvert else DS.TextDim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(DS.Line))
    }
}

@Composable
private fun SidebarItem(t: Tab, selected: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .padding(vertical = 1.dp)
            .then(if (collapsed) Modifier.size(48.dp) else Modifier.fillMaxWidth())
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
            .padding(horizontal = if (collapsed) 0.dp else 10.dp, vertical = if (collapsed) 0.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            t.icon, contentDescription = t.label,
            tint = when { focused -> DS.OnInvert; selected -> DS.Accent; else -> DS.TextDim },
            modifier = Modifier.size(20.dp),
        )
        if (!collapsed) {
            Text(
                t.label, fontSize = 14.sp, maxLines = 1,
                color = when { focused -> DS.OnInvert; else -> DS.Text },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
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
    val session = com.dekstream.tv.DekStreamApp.instance.session ?: return Tab.entries
    val src = session.source
    return Tab.entries.filter { t ->
        when (t) {
            Tab.Live, Tab.Guide, Tab.Multiview, Tab.Favorites -> src.loadLive
            Tab.Movies -> src.loadMovies && com.dekstream.tv.data.Stremio.iptvVod(com.dekstream.tv.DekStreamApp.instance)
            Tab.Series -> src.loadShows && com.dekstream.tv.data.Stremio.iptvVod(com.dekstream.tv.DekStreamApp.instance)
            Tab.Discover -> !session.profile.kids
            Tab.Settings -> true
            else -> true
        }
    }
}


@Composable
private fun NowPlayingItem(title: String, collapsed: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(bottom = 8.dp).then(if (collapsed) Modifier.size(48.dp) else Modifier.fillMaxWidth())
            .onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(if (focused) DS.Invert else DS.Live.copy(alpha = 0.18f))
            .clickable(onClick = onClick).padding(horizontal = if (collapsed) 0.dp else 10.dp, vertical = if (collapsed) 0.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(Icons.Default.PlayCircle, "Now playing", tint = if (focused) DS.OnInvert else DS.Live, modifier = Modifier.size(20.dp))
        if (!collapsed) Column(Modifier.padding(start = 12.dp)) {
            Text("Now playing", color = if (focused) DS.OnInvert else DS.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(title, color = if (focused) DS.OnInvert.copy(alpha = 0.7f) else DS.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
