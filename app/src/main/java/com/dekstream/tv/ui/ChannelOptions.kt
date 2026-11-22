package com.dekstream.tv.ui

import androidx.compose.material.icons.filled.KeyboardArrowDown

import androidx.compose.material.icons.filled.KeyboardArrowUp

import androidx.compose.material.icons.filled.VerticalAlignBottom

import androidx.compose.material.icons.filled.VerticalAlignTop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Category
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.CustomGroup

@Composable
private fun OptionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRing(shape, 1.02f).clip(shape).background(DS.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = DS.Accent, modifier = Modifier.size(20.dp))
        Text("   $label", color = DS.Text, fontSize = 15.sp)
    }
}

/** Long-press a channel: favorite, groups, rename, number, hide, lock, guide match, pin to Sports. */
@Composable
fun ChannelOptionsDialog(c: Channel, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    val prefs = session.prefs
    val favs by prefs.favorites.collectAsState()
    val pinned by prefs.sportsChannels.collectAsState()
    var step by remember { mutableStateOf("menu") } // menu, rename, number, group, guide, gate-lock, gate-hide
    val kids = session.profile.kids

    when (step) {
        "rename" -> TextInputDialog("Rename channel", prefs.channelNames[c.streamId] ?: c.name, "Leave empty to use the original name",
            onDismiss = onDismiss) { prefs.renameChannel(c.streamId, it); onDismiss() }
        "number" -> TextInputDialog("Channel number", c.num.takeIf { it > 0 }?.toString() ?: "", "Used for number-key jumps. Empty = original.",
            numeric = true, onDismiss = onDismiss) { prefs.renumberChannel(c.streamId, it.toIntOrNull()); onDismiss() }
        "group" -> GroupPickerDialog(c, onDismiss)
        "guide" -> MatchGuideDialog(c, onDismiss)
        "gate-lock" -> ParentGate(reason = if (prefs.isChannelLocked(c)) "Unlock ${c.name}" else "Lock ${c.name}", onDismiss = onDismiss) {
            prefs.setLocked(c.streamId, c.streamId !in prefs.lockedChannels); onDismiss()
        }
        "gate-hide" -> ParentGate(requireSetup = false, reason = "Hide ${c.name}", onDismiss = onDismiss) {
            prefs.setHidden(c.streamId, true); onDismiss()
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Appearance.palette.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(c.icon, 36.dp)
                    Text("  ${c.name}", color = DS.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column(Modifier.heightIn(max = 520.dp)) {
                    val fav = c.streamId in favs
                    OptionItem(if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (fav) "Remove from Favorites" else "Add to Favorites") {
                        prefs.toggleFavorite(c.streamId); onDismiss()
                    }
                    if (!kids) {
                        OptionItem(Icons.Default.PlaylistAdd, "Add to a group…") { step = "group" }
                        OptionItem(Icons.Default.DriveFileRenameOutline, "Rename") { step = "rename" }
                        OptionItem(Icons.Default.Numbers, "Set channel number") { step = "number" }
                        OptionItem(Icons.Default.CalendarViewWeek, if (c.streamId in prefs.epgOverrides) "Change guide match" else "Match guide…") { step = "guide" }
                        OptionItem(Icons.Default.PushPin, if (c.streamId in pinned) "Unpin from Sports" else "Pin to Sports · Your channels") {
                            prefs.toggleSportsChannel(c.streamId); onDismiss()
                        }
                        OptionItem(if (prefs.isChannelLocked(c)) Icons.Default.LockOpen else Icons.Default.Lock,
                            if (c.streamId in prefs.lockedChannels) "Unlock" else "Lock with parent PIN") { step = "gate-lock" }
                        OptionItem(Icons.Default.VisibilityOff, "Hide channel") { step = "gate-hide" }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = DS.TextDim) } },
        )
    }
}

/** Long-press a category: rename, hide, lock. [prefix] is "l:", "v:" or "s:". */
@Composable
fun CategoryOptionsDialog(cat: Category, prefix: String, onDismiss: () -> Unit, order: List<String>? = null) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    val prefs = session.prefs
    val key = prefix + cat.id
    var step by remember { mutableStateOf("menu") }
    if (session.profile.kids) { onDismiss(); return }
    // Moving a category: order holds every Live TV category key in its current order.
    val myKey = if (cat.id.startsWith("__") || cat.id.startsWith("g:")) cat.id else prefix + cat.id
    fun move(to: (Int, Int) -> Int) {
        val list = order?.toMutableList() ?: return
        val i = list.indexOf(myKey); if (i < 0) return
        val j = to(i, list.size).coerceIn(0, list.size - 1)
        list.removeAt(i); list.add(j, myKey)
        prefs.setCatOrder(list); onDismiss()
    }
    @Composable fun moveItems() {
        if (order == null) return
        OptionItem(Icons.Default.VerticalAlignTop, "Move to top") { move { _, _ -> 0 } }
        OptionItem(Icons.Default.KeyboardArrowUp, "Move up") { move { i, _ -> i - 1 } }
        OptionItem(Icons.Default.KeyboardArrowDown, "Move down") { move { i, _ -> i + 1 } }
        OptionItem(Icons.Default.VerticalAlignBottom, "Move to bottom") { move { _, n -> n - 1 } }
    }
    if (cat.id.startsWith("__")) {
        if (order == null) { onDismiss(); return }
        AlertDialog(
            onDismissRequest = onDismiss, containerColor = Appearance.palette.surface,
            title = { Text(cat.name, color = DS.Text) },
            text = { Column { moveItems() } },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = DS.TextDim) } },
        )
        return
    }
    if (cat.id.startsWith("g:")) { GroupEditDialog(cat.id.removePrefix("g:"), onDismiss); return }
    when (step) {
        "rename" -> TextInputDialog("Rename category", prefs.catNames[key] ?: cat.name, "Leave empty to use the original name", onDismiss = onDismiss) {
            prefs.renameCategory(key, it); onDismiss()
        }
        "gate-lock" -> ParentGate(reason = "Lock or unlock ${cat.name}", onDismiss = onDismiss) {
            prefs.setCatLocked(key, key !in prefs.lockedCats); onDismiss()
        }
        "gate-hide" -> ParentGate(requireSetup = false, reason = "Hide ${cat.name}", onDismiss = onDismiss) {
            prefs.setCatHidden(key, true); onDismiss()
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Appearance.palette.surface,
            title = { Text(cat.name, color = DS.Text) },
            text = {
                Column {
                    OptionItem(Icons.Default.DriveFileRenameOutline, "Rename") { step = "rename" }
                    OptionItem(if (key in prefs.lockedCats) Icons.Default.LockOpen else Icons.Default.Lock,
                        if (key in prefs.lockedCats) "Unlock" else "Lock with parent PIN") { step = "gate-lock" }
                    OptionItem(Icons.Default.VisibilityOff, "Hide category") { step = "gate-hide" }
                    moveItems()
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = DS.TextDim) } },
        )
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, hint: String?, numeric: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Appearance.palette.surface,
        title = { Text(title, color = DS.Text) },
        text = {
            Column {
                OutlinedTextField(v, { v = if (numeric) it.filter(Char::isDigit).take(5) else it.take(60) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text), modifier = Modifier.tvTextInput())
                hint?.let { Text(it, color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v) }) { Text("Save", color = DS.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DS.TextDim) } },
    )
}

@Composable
private fun GroupPickerDialog(c: Channel, onDismiss: () -> Unit) {
    val prefs = DekStreamApp.instance.session?.prefs ?: return
    val version by prefs.customVersion.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("New group", "", "e.g. Sports, Kids, News", onDismiss = { creating = false }) { name ->
            if (name.isNotBlank()) {
                val g = CustomGroup(java.util.UUID.randomUUID().toString().take(6), name.trim(), listOf(c.streamId))
                prefs.saveGroup(g)
            }
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Appearance.palette.surface,
        title = { Text("Add ${c.name} to…", color = DS.Text) },
        text = {
            Column {
                @Suppress("UNUSED_EXPRESSION") version
                prefs.groups.forEach { g ->
                    val inIt = c.streamId in g.channels
                    OptionItem(Icons.Default.PlaylistAdd, g.name + if (inIt) "  ✓" else "") {
                        if (inIt) prefs.saveGroup(g.copy(channels = g.channels - c.streamId)) else prefs.addToGroup(g.id, c.streamId)
                        onDismiss()
                    }
                }
                OptionItem(Icons.Default.PlaylistAdd, "New group…") { creating = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = DS.TextDim) } },
    )
}

/** Rename, reorder or delete a custom group. */
@Composable
fun GroupEditDialog(groupId: String, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val prefs = app.session?.prefs ?: return
    val repo = app.repo ?: return
    val version by prefs.customVersion.collectAsState()
    val g = remember(version) { prefs.groups.firstOrNull { it.id == groupId } } ?: run { onDismiss(); return }
    val all by rememberLoad(Unit) { repo.rawChannels() }
    var renaming by remember { mutableStateOf(false) }
    val byId = all.valueOrNull().orEmpty().associateBy { it.streamId }
    if (renaming) {
        TextInputDialog("Rename group", g.name, null, onDismiss = { renaming = false }) { if (it.isNotBlank()) prefs.saveGroup(g.copy(name = it.trim())); renaming = false }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Appearance.palette.surface,
        title = { Text(g.name, color = DS.Text) },
        text = {
            Column {
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename", color = DS.Accent) }
                    TextButton(onClick = { prefs.deleteGroup(g.id); onDismiss() }) { Text("Delete group", color = DS.Live) }
                }
                Text("Order", color = DS.TextDim, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(g.channels.size, key = { g.channels[it] }) { i ->
                        val id = g.channels[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. " + (byId[id]?.name ?: "Channel $id"), color = DS.Text, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(enabled = i > 0, onClick = {
                                val l = g.channels.toMutableList(); l.add(i - 1, l.removeAt(i)); prefs.saveGroup(g.copy(channels = l))
                            }) { Text("▲", color = DS.Text) }
                            TextButton(enabled = i < g.channels.lastIndex, onClick = {
                                val l = g.channels.toMutableList(); l.add(i + 1, l.removeAt(i)); prefs.saveGroup(g.copy(channels = l))
                            }) { Text("▼", color = DS.Text) }
                            TextButton(onClick = { prefs.saveGroup(g.copy(channels = g.channels - id)) }) { Text("✕", color = DS.Live) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = DS.Accent) } },
    )
}

/** Pick which guide channel feeds this channel (for channels with no or wrong guide data). */
@Composable
fun MatchGuideDialog(c: Channel, onDismiss: () -> Unit) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val prefs = app.session?.prefs ?: return
    var q by remember { mutableStateOf(c.name.replace(Regex("(?i)\\b(hd|fhd|uhd|4k|sd)\\b"), "").trim()) }
    val idx by rememberLoad(Unit) { repo.guideIndex() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Appearance.palette.surface,
        title = { Text("Match guide for ${c.name}", color = DS.Text, maxLines = 2) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search guide channels") }, modifier = Modifier.tvTextInput().fillMaxWidth())
                when (val l = idx) {
                    is Load.Loading -> Text("Loading the guide…", color = DS.TextDim, modifier = Modifier.padding(top = 10.dp))
                    is Load.Err -> Text(l.message, color = DS.Live, modifier = Modifier.padding(top = 10.dp))
                    is Load.Ok -> {
                        val index = l.value
                        if (index == null) {
                            Text("This playlist has no full guide. Add one under Profile → playlist → Extra guides.", color = DS.TextDim,
                                modifier = Modifier.padding(top = 10.dp))
                        } else {
                            val n = q.trim().lowercase()
                            val list = index.guideChannels().filter { n.isEmpty() || it.second.lowercase().contains(n) || it.first.contains(n) }.take(100)
                            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                                items(list, key = { it.first }) { (id, name) ->
                                    val now = index.nowOn(c.copy(epgChannelId = id))
                                    val shape = RoundedCornerShape(8.dp)
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp).focusRing(shape, 1.02f).clip(shape).background(DS.Surface2)
                                            .clickable { prefs.setEpgOverride(c.streamId, id); repo.clearEpgCache(); onDismiss() }.padding(10.dp),
                                    ) {
                                        Text(name, color = DS.Text, fontSize = 14.sp)
                                        Text(now?.let { "Now: ${it.title}" } ?: id, color = DS.TextDim, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (c.streamId in prefs.epgOverrides) TextButton(onClick = { prefs.setEpgOverride(c.streamId, null); repo.clearEpgCache(); onDismiss() }) {
                Text("Remove match", color = DS.Live)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DS.TextDim) } },
    )
}

@Suppress("unused") private val vis = Icons.Default.Visibility
