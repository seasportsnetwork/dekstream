package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Autorenew
import com.dekstream.tv.data.Updater
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.BuildConfig
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.SourceType

@Composable
fun SettingsScreen(state: AppState) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    var format by remember { mutableStateOf(app.prefs.liveFormat) }
    var pinStep by remember { mutableStateOf(0) } // 1 = confirm old, 2 = choose new
    var adultAsk by remember { mutableStateOf(false) }
    fun setAdult(on: Boolean) {
        session.settings.showAdult = on
        com.dekstream.tv.data.ContentFilter.showAdult = on
        app.start(session.profile) // reload lists with the new filter
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 640.dp)) {
        Text("Settings", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

        if (session.source.type == SourceType.XTREAM) {
            SectionTitle("Live TV stream format")
            Card {
                Text("Try HLS if channels stutter or won't start with MPEG-TS.", color = DS.TextDim, fontSize = 13.sp)
                Row(Modifier.padding(top = 10.dp)) {
                    listOf("ts" to "MPEG-TS (.ts)", "m3u8" to "HLS (.m3u8)").forEach { (v, label) ->
                        CategoryChip(label, format == v) { format = v; app.prefs.liveFormat = v }
                        HSpacer(10.dp)
                    }
                }
            }
        }

        SectionTitle("Look & family")
        Card {
            NavRow(Icons.Default.Palette, "Appearance", "Accent, background, selection style, transparency, guide background") {
                state.push(Route.AppearanceSettings)
            }
            NavRow(Icons.Default.Dashboard, "Home screen", "Live TV first. Add Movies and Series to Home, or pick your own mix") {
                state.push(Route.HomeSettings)
            }
            if (!session.profile.kids) {
                val hasPin = app.store.parentPin.isNotEmpty()
                NavRow(Icons.Default.Lock, if (hasPin) "Change parent PIN" else "Set a parent PIN",
                    "Protects kids settings and locked channels") { pinStep = if (hasPin) 1 else 2 }
            }
        }

        if (!session.profile.kids) {
            SectionTitle("Content")
            Card {
                val on = com.dekstream.tv.data.ContentFilter.showAdult
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().focusRing(shape, 1.02f).clip(shape)
                        .clickable { if (on) setAdult(false) else adultAsk = true }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.NoAdultContent, null, tint = DS.Accent)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("Show adult (XXX) content", color = DS.Text, fontSize = 15.sp)
                        Text(if (on) "Adult channels, categories, movies and series are visible"
                            else "Hidden everywhere: Live TV, Guide, Movies, Series, Search and Home",
                            color = DS.TextDim, fontSize = 12.sp)
                    }
                    Switch(on, onCheckedChange = { if (it) adultAsk = true else setAdult(false) },
                        colors = SwitchDefaults.colors(checkedTrackColor = DS.Accent, checkedThumbColor = Color.White))
                }
            }
        }

        SectionTitle("Tips")
        Card {
            Text("• Long-press a channel to add or remove it from Favorites.", color = DS.Text, fontSize = 14.sp)
            Text("• On the Shield, press ▲ / ▼ while watching to change channels.", color = DS.Text, fontSize = 14.sp)
            Text("• Long-press something in Continue Watching to remove it.", color = DS.Text, fontSize = 14.sp)
            Text("• Add more playlists and profiles from the Profile tab.", color = DS.Text, fontSize = 14.sp)
        }

        SectionTitle("About")
        Card {
            BrandMark(32.dp, nameSize = 18)
            InfoLine("Version", BuildConfig.VERSION_NAME)
        }
        SectionTitle("Updates")
        Card {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            NavRow(Icons.Default.SystemUpdate, "Check for updates",
                Updater.status ?: "Current version v${BuildConfig.VERSION_NAME}") {
                scope.launch { if (Updater.check(ctx, manual = true) != null) Updater.dismissed = false }
            }
            var auto by remember { mutableStateOf(Updater.autoCheck(ctx)) }
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.fillMaxWidth().focusRing(shape, 1.02f).clip(shape)
                    .clickable { auto = !auto; Updater.setAutoCheck(ctx, auto) }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Autorenew, null, tint = DS.Accent)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("Check automatically", color = DS.Text, fontSize = 15.sp)
                    Text("Look for a new version each time DekTV opens", color = DS.TextDim, fontSize = 12.sp)
                }
                Switch(auto, onCheckedChange = { auto = it; Updater.setAutoCheck(ctx, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = DS.Accent, checkedThumbColor = Color.White))
            }
        }
        val crashFile = java.io.File(app.filesDir, "last_crash.txt")
        var crash by remember { mutableStateOf(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()) }
        crash?.let { text ->
            SectionTitle("Last crash")
            Card {
                Text(text.lines().take(18).joinToString("\n"), color = DS.TextDim, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Row(Modifier.padding(top = 10.dp)) {
                    CategoryChip("Clear", false) { crashFile.delete(); crash = null }
                }
            }
        }
    }
    if (adultAsk) {
        ParentGate(requireSetup = false, reason = "Show adult content on this profile.", onDismiss = { adultAsk = false }) {
            adultAsk = false; setAdult(true)
        }
    }
    when (pinStep) {
        1 -> PinDialog("Current parent PIN", app.store.parentPin, onDismiss = { pinStep = 0 }) { pinStep = 2 }
        2 -> PinDialog("New parent PIN", null, onDismiss = { pinStep = 0 }) { app.store.parentPin = it; pinStep = 0 }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DS.Surface).padding(16.dp)) { content() }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRing(shape, 1.02f).clip(shape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = DS.Accent)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = DS.Text, fontSize = 15.sp)
            Text(subtitle, color = DS.TextDim, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = DS.TextDim)
    }
}
