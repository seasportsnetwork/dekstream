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
fun Shell(state: AppState, onSignOut: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(state)
                Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(state, onSignOut) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(state, onSignOut) }
                BottomBar(state)
            }
        }
    }
}

@Composable
private fun TabContent(state: AppState, onSignOut: () -> Unit) {
    when (state.tab) {
        Tab.Home -> HomeScreen(state)
        Tab.Live -> LiveScreen(state)
        Tab.Guide -> GuideScreen(state)
        Tab.Movies -> VodBrowseScreen(state, isSeries = false)
        Tab.Series -> VodBrowseScreen(state, isSeries = true)
        Tab.Sports -> SportsScreen(state)
        Tab.Multiview -> MultiviewScreen()
        Tab.Search -> SearchScreen(state)
        Tab.Settings -> SettingsScreen(onSignOut)
    }
}

@Composable
private fun SideRail(state: AppState) {
    Column(
        Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(DS.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dek", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Stream", color = DS.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Tab.entries.forEach { t ->
            val selected = state.tab == t
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .focusRing(shape, 1.03f)
                    .clip(shape)
                    .background(if (selected) DS.AccentSoft else DS.Surface)
                    .clickable { state.tab = t }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, contentDescription = null, tint = if (selected) DS.Accent else DS.TextDim, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.label, color = if (selected) DS.Text else DS.TextDim, fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
        bottomTabs.forEach { t ->
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
