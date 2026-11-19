package com.dekstream.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
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

@Composable
private fun SideRail(state: AppState) {
    Column(
        Modifier
            .width(84.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Appearance.palette.surface, Appearance.palette.bg)))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(36.dp, showName = false)
        Spacer(Modifier.height(18.dp))
        visibleTabs().forEach { t ->
            val selected = state.tab == t
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .padding(vertical = 3.dp)
                    .width(68.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(shape)
                    .background(
                        when {
                            focused -> Color.White
                            selected -> DS.AccentSoft
                            else -> Color.Transparent
                        }
                    )
                    .clickable { state.tab = t }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    t.icon, contentDescription = t.label,
                    tint = when { focused -> Color.Black; selected -> DS.Accent; else -> DS.TextDim },
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    t.label, fontSize = 10.sp, maxLines = 1,
                    color = when { focused -> Color.Black; selected -> DS.Text; else -> DS.TextDim },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
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
            Tab.Live, Tab.Guide, Tab.Multiview -> src.loadLive
            Tab.Movies -> src.loadMovies
            Tab.Series -> src.loadShows
            Tab.Settings -> true
            else -> true
        }
    }
}
