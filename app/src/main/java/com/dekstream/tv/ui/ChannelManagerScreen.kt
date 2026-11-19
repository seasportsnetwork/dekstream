package com.dekstream.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp

/** Everything you've hidden, locked, renamed, grouped or matched on this playlist, in one place. */
@Composable
fun ChannelManagerScreen(state: AppState) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    val prefs = session.prefs
    val repo = session.repo
    val version by prefs.customVersion.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Hidden") }
    var editGroup by remember { mutableStateOf<String?>(null) }
    var gate by remember { mutableStateOf<(() -> Unit)?>(null) }
    val channels by rememberLoad(Unit) { repo.rawChannels() }
    val cats by rememberLoad(Unit) {
        repo.rawLiveCategories().map { "l:" + it.id to it.name } + repo.rawVodCategories().map { "v:" + it.id to "Movies · " + it.name } +
            repo.rawSeriesCategories().map { "s:" + it.id to "Series · " + it.name }
    }
    val byId = channels.valueOrNull().orEmpty().associateBy { it.streamId }
    val catName = cats.valueOrNull().orEmpty().toMap()
    BackHandler { state.pop() }

    fun guarded(action: () -> Unit) { gate = action }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).widthIn(max = 860.dp)) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.pop() }, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text) }
            Column {
                Text("Channels & groups", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${session.profile.name} · ${session.source.name}", color = DS.TextDim, fontSize = 13.sp)
            }
        }
        LazyRow(horizontalArrangement = rowSpacing, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(listOf("Hidden", "Locked", "Renamed", "Groups", "Guide matches")) { t -> CategoryChip(t, tab == t) { tab = t } }
        }
        @Suppress("UNUSED_EXPRESSION") version
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when (tab) {
                "Hidden" -> {
                    val ch = prefs.hiddenChannels.toList(); val ca = prefs.hiddenCats.toList()
                    if (ch.isEmpty() && ca.isEmpty()) item { Empty("Nothing hidden. Long-press a channel or category to hide it.") }
                    items(ca, key = { "hc$it" }) { k -> ManageRow(catName[k] ?: k, "Category", "Show") { guarded { prefs.setCatHidden(k, false) } } }
                    items(ch, key = { "h$it" }) { id -> ManageRow(byId[id]?.name ?: "Channel $id", "Channel", "Show") { guarded { prefs.setHidden(id, false) } } }
                }
                "Locked" -> {
                    val ch = prefs.lockedChannels.toList(); val ca = prefs.lockedCats.toList()
                    if (ch.isEmpty() && ca.isEmpty()) item { Empty("Nothing locked. Long-press a channel or category to lock it with the parent PIN.") }
                    items(ca, key = { "lc$it" }) { k -> ManageRow(catName[k] ?: k, "Category", "Unlock") { guarded { prefs.setCatLocked(k, false) } } }
                    items(ch, key = { "l$it" }) { id -> ManageRow(byId[id]?.name ?: "Channel $id", "Channel", "Unlock") { guarded { prefs.setLocked(id, false) } } }
                }
                "Renamed" -> {
                    val ch = prefs.channelNames.entries.toList(); val nums = prefs.channelNumbers.entries.toList(); val ca = prefs.catNames.entries.toList()
                    if (ch.isEmpty() && nums.isEmpty() && ca.isEmpty()) item { Empty("No renamed channels, numbers or categories.") }
                    items(ca, key = { "rc" + it.key }) { (k, v) -> ManageRow(v, "Was: ${catName[k] ?: k}", "Reset") { prefs.renameCategory(k, null) } }
                    items(ch, key = { "r" + it.key }) { (id, v) -> ManageRow(v, "Was: ${byId[id]?.name ?: id}", "Reset") { prefs.renameChannel(id, null) } }
                    items(nums, key = { "n" + it.key }) { (id, n) -> ManageRow("${byId[id]?.name ?: id} → $n", "Custom number (was ${byId[id]?.num ?: "-"})", "Reset") { prefs.renumberChannel(id, null) } }
                }
                "Groups" -> {
                    if (prefs.groups.isEmpty()) item { Empty("No groups yet. Long-press a channel → Add to a group → New group.") }
                    items(prefs.groups, key = { "g" + it.id }) { g -> ManageRow(g.name, "${g.channels.size} channels", "Edit") { editGroup = g.id } }
                }
                "Guide matches" -> {
                    val ov = prefs.epgOverrides.entries.toList()
                    if (ov.isEmpty()) item { Empty("No manual guide matches. Long-press a channel → Match guide.") }
                    items(ov, key = { "o" + it.key }) { (id, epg) ->
                        ManageRow(byId[id]?.name ?: "Channel $id", "Guide: $epg", "Remove") { prefs.setEpgOverride(id, null); repo.clearEpgCache() }
                    }
                }
            }
        }
    }
    editGroup?.let { GroupEditDialog(it, onDismiss = { editGroup = null }) }
    gate?.let { action ->
        ParentGate(requireSetup = false, reason = "Parent PIN needed.", onDismiss = { gate = null }) { gate = null; action() }
    }
}

@Composable
private fun Empty(text: String) = Text(text, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))

@Composable
private fun ManageRow(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(DS.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DS.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onAction, modifier = Modifier.focusRing(RoundedCornerShape(16.dp))) { Text(action, color = DS.Accent) }
    }
}
