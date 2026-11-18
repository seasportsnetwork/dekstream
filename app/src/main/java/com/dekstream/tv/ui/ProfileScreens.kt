package com.dekstream.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.R
import com.dekstream.tv.data.Profile
import com.dekstream.tv.data.Source
import com.dekstream.tv.data.SourceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrandMark(size: Dp = 40.dp, showName: Boolean = true, nameSize: Int = 24) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_mark), contentDescription = "DekStream", modifier = Modifier.size(size))
        if (showName) {
            Spacer(Modifier.width(10.dp))
            Text("Dek", color = DS.Text, fontSize = nameSize.sp, fontWeight = FontWeight.Bold)
            Text("Stream", color = DS.Accent, fontSize = nameSize.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Avatar(p: Profile, size: Dp, selected: Boolean = false) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color(p.color))
            .then(if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(size / 4)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/** First launch: name your profile. */
@Composable
fun FirstProfileScreen() {
    val app = DekStreamApp.instance
    var editing by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(64.dp, nameSize = 34)
            Text("Welcome! Let's set up your profile.", color = DS.TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
            Button(
                onClick = { editing = true },
                colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                modifier = Modifier.padding(top = 24.dp).focusRing(RoundedCornerShape(24.dp)),
            ) { Text("Create profile") }
        }
    }
    if (editing) {
        ProfileEditDialog(existing = null, onDismiss = { editing = false }, onSaved = { app.start(it) })
    }
}

/** "Who's watching?" */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ProfilePickerScreen() {
    val app = DekStreamApp.instance
    val profiles by app.store.profiles.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(44.dp)
        Text("Who's watching?", color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 32.dp, bottom = 28.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            profiles.forEach { p ->
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier.width(120.dp).focusRing(shape, 1.08f).clip(shape)
                        .combinedClickable(onClick = { app.start(p) }, onLongClick = { editing = p }).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(p, 96.dp)
                    Text(p.name, color = DS.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.width(120.dp).focusRing(shape, 1.08f).clip(shape).clickable { adding = true }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(DS.Surface2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, null, tint = DS.TextDim, modifier = Modifier.size(40.dp))
                }
                Text("Add profile", color = DS.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Text("Long-press a profile to edit it.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 28.dp))
    }
    if (adding) ProfileEditDialog(null, onDismiss = { adding = false }, onSaved = { adding = false })
    editing?.let { p -> ProfileEditDialog(p, onDismiss = { editing = null }, onSaved = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditDialog(existing: Profile?, onDismiss: () -> Unit, onSaved: (Profile) -> Unit) {
    val app = DekStreamApp.instance
    val profiles by app.store.profiles.collectAsState()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableLongStateOf(existing?.color ?: Profile.Colors[profiles.size % Profile.Colors.size]) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DS.Surface,
        title = { Text(if (existing == null) "New profile" else "Edit profile", color = DS.Text) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Profile(name = name.ifBlank { "?" }, color = color), 56.dp)
                    Spacer(Modifier.width(14.dp))
                    OutlinedTextField(
                        name, { name = it.take(20) }, singleLine = true, label = { Text("Name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent),
                    )
                }
                FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Profile.Colors.forEach { c ->
                        Box(
                            Modifier.size(36.dp).focusRing(CircleShape).clip(CircleShape).background(Color(c))
                                .then(if (c == color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                .clickable { color = c },
                        )
                    }
                }
                if (existing != null && profiles.size > 1) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Delete profile", color = DS.Live)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val p = (existing ?: Profile(name = name.trim(), color = color, sourceId = app.store.sources.value.firstOrNull()?.id))
                        .copy(name = name.trim(), color = color)
                    app.store.saveProfile(p)
                    if (app.session?.profile?.id == p.id) app.start(p)
                    onSaved(p)
                },
            ) { Text("Save", color = DS.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DS.TextDim) } },
    )
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DS.Surface,
            title = { Text("Delete ${existing.name}?", color = DS.Text) },
            text = { Text("Their favorites, watch progress and teams are removed from this device. Playlists stay.", color = DS.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (app.session?.profile?.id == existing.id) app.switchProfile()
                    app.store.deleteProfile(existing.id)
                    onDismiss()
                }) { Text("Delete", color = DS.Live) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = DS.TextDim) } },
        )
    }
}

fun SourceType.icon() = when (this) {
    SourceType.XTREAM -> Icons.Default.Dns
    SourceType.M3U_URL -> Icons.Default.Link
    SourceType.M3U_FILE -> Icons.Default.Description
    SourceType.STALKER -> Icons.Default.Router
}

/** Profile tab: who you are, your playlists, your account. */
@Composable
fun ProfileScreen(state: AppState) {
    val app = DekStreamApp.instance
    val session = app.session ?: return
    val sources by app.store.sources.collectAsState()
    val favs by app.prefs.favorites.collectAsState()
    val progress by app.prefs.progress.collectAsState()
    val teams by app.prefs.favTeams.collectAsState()
    var editProfile by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val info by rememberLoad(session.source.id) { session.repo.provider.verify() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 760.dp),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DS.Surface).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(session.profile, 72.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(session.profile.name, color = DS.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${favs.size} favorite channels · ${progress.size} in progress · ${teams.size} teams",
                    color = DS.TextDim, fontSize = 13.sp,
                )
            }
            IconButton(onClick = { editProfile = true }, modifier = Modifier.focusRing()) { Icon(Icons.Default.Edit, "Edit profile", tint = DS.TextDim) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { app.switchProfile() }, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Default.SwapHoriz, null, tint = DS.Text); Text("  Switch profile", color = DS.Text)
            }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Text("Clear watch history", color = DS.TextDim)
            }
        }

        // Playlists
        SectionLabel("PLAYLISTS")
        sources.forEach { s ->
            val active = s.id == session.source.id
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRing(shape, 1.015f).clip(shape)
                    .background(if (active) DS.AccentSoft else DS.Surface)
                    .clickable { if (!active) app.useSource(s) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(s.type.icon(), null, tint = if (active) DS.Accent else DS.TextDim)
                Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    Text(s.name, color = DS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.type.label + " · " + displayUrl(s), color = DS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) Icon(Icons.Default.CheckCircle, "In use", tint = DS.Accent, modifier = Modifier.padding(end = 6.dp))
                IconButton(onClick = { state.push(Route.EditSource(s)) }, modifier = Modifier.focusRing()) {
                    Icon(Icons.Default.Edit, "Edit ${s.name}", tint = DS.TextDim)
                }
            }
        }
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRing(shape, 1.015f).clip(shape).background(DS.Surface)
                .clickable { state.push(Route.EditSource(null)) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, null, tint = DS.Accent)
            Text("  Add playlist (Xtream, M3U, MAC portal)", color = DS.Text, fontSize = 15.sp)
        }
        Text("Tap a playlist to switch to it. Favorites and progress are kept per playlist.", color = DS.TextDim, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))

        // Account for the active playlist
        SectionLabel("ACCOUNT · ${session.source.name.uppercase()}")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DS.Surface).padding(16.dp)) {
            when (val i = info) {
                is Load.Loading -> Text("Checking…", color = DS.TextDim)
                is Load.Err -> Text("Couldn't reach this playlist: ${i.message}", color = DS.Live, fontSize = 14.sp)
                is Load.Ok -> {
                    val a = i.value
                    if (a == null) {
                        Text("Playlist loaded. M3U playlists don't report account details.", color = DS.TextDim, fontSize = 14.sp)
                    } else {
                        InfoLine("Status", a.status)
                        a.expiresAtMs?.let { InfoLine("Expires", SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it))) }
                        if (a.maxConnections > 0) InfoLine("Connections", "${a.activeConnections} of ${a.maxConnections} in use")
                        a.timezone?.let { InfoLine("Server time zone", it) }
                    }
                }
            }
        }
    }

    if (editProfile) ProfileEditDialog(session.profile, onDismiss = { editProfile = false }, onSaved = { editProfile = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DS.Surface,
            title = { Text("Clear watch history?", color = DS.Text) },
            text = { Text("Removes Continue Watching and recently watched channels for ${session.profile.name} on this playlist. Favorites stay.", color = DS.TextDim) },
            confirmButton = { TextButton(onClick = { app.prefs.clearWatchHistory(); confirmClear = false }) { Text("Clear", color = DS.Live) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = DS.TextDim) } },
        )
    }
}

fun displayUrl(s: Source): String = when (s.type) {
    SourceType.M3U_FILE -> s.url.substringAfterLast('/')
    else -> s.url.replace(Regex("^https?://"), "").substringBefore('?')
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(0.6f), textAlign = TextAlign.Start)
    }
}

@Suppress("unused") private val unusedHeight = Modifier.height(0.dp)
