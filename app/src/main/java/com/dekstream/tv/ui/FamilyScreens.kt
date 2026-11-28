package com.dekstream.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.ProfileSettings

/** Parent-only: time limit, bedtime and allowed categories for one kids profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KidsRulesScreen(profileId: String, state: AppState) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val kid = app.store.profile(profileId) ?: return
    val st = remember(profileId) { ProfileSettings(app, profileId) }
    var limit by remember { mutableIntStateOf(st.dailyLimitMin) }
    var bedStart by remember { mutableIntStateOf(st.bedtimeStart) }
    var bedEnd by remember { mutableIntStateOf(st.bedtimeEnd) }
    val srcId = repo.source.id
    var allowed by remember { mutableStateOf(st.allowed(srcId)) }
    val live by rememberLoad(srcId) { repo.liveCategories() }
    val movies by rememberLoad(srcId) { repo.vodCategories() }
    val shows by rememberLoad(srcId) { repo.seriesCategories() }
    BackHandler { state.pop() }

    fun toggle(key: String) {
        allowed = if (key in allowed) allowed - key else allowed + key
        st.setAllowed(srcId, allowed)
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 860.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text) }
            Avatar(kid, 40.dp)
            Text("  ${kid.name}'s rules", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        SectionLabel("DAILY TIME LIMIT")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 30, 60, 90, 120, 180, 240).forEach { m ->
                CategoryChip(if (m == 0) "No limit" else if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) " 30m" else ""}", limit == m) {
                    limit = m; st.dailyLimitMin = m
                }
            }
        }

        SectionLabel("BEDTIME")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryChip("Off", bedStart < 0) { bedStart = -1; st.bedtimeStart = -1 }
            listOf(19 * 60, 19 * 60 + 30, 20 * 60, 20 * 60 + 30, 21 * 60, 21 * 60 + 30, 22 * 60).forEach { m ->
                CategoryChip(fmtClock(m), bedStart == m) { bedStart = m; st.bedtimeStart = m }
            }
        }
        if (bedStart >= 0) {
            Text("Until", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60).forEach { m ->
                    CategoryChip(fmtClock(m), bedEnd == m) { bedEnd = m; st.bedtimeEnd = m }
                }
            }
        }

        SectionLabel("ALLOWED CATEGORIES · ${repo.source.name.uppercase()}")
        Text(
            if (allowed.isEmpty()) "Nothing selected means everything on this playlist is allowed. Pick categories to limit what ${kid.name} sees."
            else "${kid.name} only sees the ${allowed.size} selected categories on this playlist.",
            color = DS.TextDim, fontSize = 13.sp,
        )
        if (allowed.isNotEmpty()) {
            OutlinedButton(onClick = { allowed = emptySet(); st.setAllowed(srcId, emptySet()) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Allow everything", color = DS.Text)
            }
        }
        listOf(Triple("Live TV", "l:", live), Triple("Movies", "v:", movies), Triple("Series", "s:", shows)).forEach { (title, prefix, load) ->
            val cats = load.valueOrNull().orEmpty()
            if (cats.isEmpty()) return@forEach
            Text(title, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    val key = prefix + c.id
                    val on = key in allowed
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier.focusRing(shape).clip(shape).background(if (on) DS.AccentSoft else DS.Surface)
                            .clickable { toggle(key) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (on) Icon(Icons.Default.Check, null, tint = DS.Accent, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                        Text(c.name, color = DS.Text, fontSize = 13.sp)
                    }
                }
            }
        }

        SectionLabel("VIEWING · LAST 7 DAYS")
        ViewingChart(st)
    }
}

/** Shown over a kids profile when it's paused, bedtime, or out of time. "Ask a parent" grants more with the parent PIN. */
@Composable
fun KidsLockScreen(lock: ProfileSettings.Lock, settings: ProfileSettings, name: String, onUnlocked: () -> Unit) {
    val app = DekStreamApp.instance
    var asking by remember { mutableStateOf<Int?>(null) }
    var resumeAsk by remember { mutableStateOf(false) }
    val (icon, title, body) = when (lock) {
        ProfileSettings.Lock.Paused -> Triple(Icons.Default.PauseCircle, "TV is paused", "A parent paused $name's TV.")
        ProfileSettings.Lock.Bedtime -> Triple(Icons.Default.Bedtime, "It's bedtime", "Time to rest, $name. TV is back tomorrow.")
        ProfileSettings.Lock.TimeUp -> Triple(Icons.Default.HourglassBottom, "That's all for today", "$name has used today's TV time.")
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF14102A), DS.Bg))).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp).widthIn(max = 520.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(DS.AccentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = DS.Accent, modifier = Modifier.size(56.dp))
            }
            Text(title, color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text(body, color = DS.TextDim, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            if (lock == ProfileSettings.Lock.Paused) {
                Button(
                    onClick = { resumeAsk = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                    modifier = Modifier.padding(top = 28.dp).focusRing(RoundedCornerShape(24.dp)),
                ) { Text("Parent: resume") }
            } else {
                Text("Ask a parent for more time", color = DS.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        Button(
                            onClick = { asking = m },
                            colors = ButtonDefaults.buttonColors(containerColor = DS.Surface2),
                            modifier = Modifier.focusRing(RoundedCornerShape(24.dp)),
                        ) { Text(if (m < 60) "+$m min" else "+1 hour", color = DS.Text) }
                    }
                }
            }
            OutlinedButton(onClick = { app.switchProfile() }, modifier = Modifier.padding(top = 18.dp).focusRing(RoundedCornerShape(24.dp))) {
                Text("Switch profile", color = DS.Text)
            }
        }
    }
    asking?.let { m ->
        ParentGate(requireSetup = false, reason = "Give $name $m more minutes today.", onDismiss = { asking = null }) {
            asking = null
            // Extra time counts from now: cover what's been used past the limit, plus the grant.
            val over = (settings.minutesToday() - settings.dailyLimitMin - settings.bonusToday).coerceAtLeast(0)
            settings.bonusToday = settings.bonusToday + over + m
            onUnlocked()
        }
    }
    if (resumeAsk) {
        ParentGate(requireSetup = false, reason = "Resume $name's TV.", onDismiss = { resumeAsk = false }) {
            resumeAsk = false; settings.paused = false; onUnlocked()
        }
    }
}

/** Look & feel for this profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(state: AppState) {
    val app = DekStreamApp.instance
    val st = app.session?.settings ?: return
    var alpha by remember { mutableFloatStateOf(Appearance.panelAlpha) }
    BackHandler { state.pop() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 760.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text) }
            Text("Appearance", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("Saved for this profile.", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))

        SectionLabel("ACCENT COLOR")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Appearance.accents.forEachIndexed { i, (name, c) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(44.dp).focusRing(CircleShape).clip(CircleShape).background(c)
                            .clickable { Appearance.accentIdx = i; st.accentIdx = i },
                        contentAlignment = Alignment.Center,
                    ) { if (Appearance.accentIdx == i) Icon(Icons.Default.Check, null, tint = Color.White) }
                    Text(name, color = DS.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        SectionLabel("BACKGROUND")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Appearance.palettes.forEachIndexed { i, p ->
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.focusRing(shape).clip(shape).background(p.bg)
                        .then(if (Appearance.paletteIdx == i) Modifier.background(DS.AccentSoft) else Modifier)
                        .clickable { Appearance.paletteIdx = i; st.paletteIdx = i }.padding(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(28.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(p.surface))
                        Box(Modifier.size(28.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(p.surface2))
                    }
                    Text(p.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        SectionLabel("SELECTION STYLE (REMOTE)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.selections.forEachIndexed { i, name ->
                CategoryChip(name, Appearance.selectionIdx == i) { Appearance.selectionIdx = i; st.selectionIdx = i }
            }
        }

        SectionLabel("SIDE MENU")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Appearance.sidebarModes.forEachIndexed { i, name ->
                CategoryChip(name, Appearance.sidebarMode == i) { Appearance.sidebarMode = i; st.sidebarMode = i }
            }
        }
        Text("Auto-hide gives the page the whole screen; press Left at the left edge (or tap the edge) and the menu slides out.",
            color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        SectionLabel("PANEL TRANSPARENCY")
        Slider(
            value = alpha, onValueChange = { alpha = it; Appearance.panelAlpha = it },
            onValueChangeFinished = { st.panelAlpha = alpha },
            valueRange = 0.55f..1f,
            colors = SliderDefaults.colors(thumbColor = DS.Accent, activeTrackColor = DS.Accent),
            modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp), 1.0f),
        )
        Text(if (alpha >= 0.98f) "Solid" else "${((1 - alpha) * 100).toInt()}% see-through", color = DS.TextDim, fontSize = 12.sp)

        SectionLabel("GUIDE BACKGROUND")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Appearance.guideBackgrounds.forEachIndexed { i, (name, colors) ->
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.size(104.dp, 64.dp).focusRing(shape).clip(shape)
                        .background(if (i == 0) Brush.verticalGradient(listOf(DS.Surface, DS.Bg)) else Brush.verticalGradient(colors))
                        .clickable { Appearance.guideBgIdx = i; st.guideBgIdx = i },
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(name, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                    if (Appearance.guideBgIdx == i) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                }
            }
        }
    }
}

@Suppress("unused") private val fill = Modifier.fillMaxWidth()
