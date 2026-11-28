package com.dekstream.tv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Devices
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Source
import com.dekstream.tv.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

private fun randomMac(): String =
    "00:1A:79:" + (1..3).joinToString(":") { "%02X".format(Random.nextInt(256)) }

/** Add or edit a playlist: Xtream, M3U by URL, M3U file, or Stalker MAC portal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceEditorScreen(existing: Source?, firstRun: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val app = DekStreamApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(existing?.type ?: SourceType.XTREAM) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "http://") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var epgUrl by remember { mutableStateOf(existing?.epgUrl ?: "") }
    var mac by remember { mutableStateOf(existing?.mac?.ifBlank { null } ?: randomMac()) }
    var loadLive by remember { mutableStateOf(existing?.loadLive ?: true) }
    var loadMovies by remember { mutableStateOf(existing?.loadMovies ?: true) }
    var loadShows by remember { mutableStateOf(existing?.loadShows ?: true) }
    var extraEpg by remember { mutableStateOf(existing?.extraEpg?.joinToString("\n") ?: "") }
    var extraLogins by remember { mutableStateOf(existing?.extraLogins?.joinToString("\n") ?: "") }
    var fileLabel by remember { mutableStateOf(existing?.takeIf { it.type == SourceType.M3U_FILE }?.url?.substringAfterLast('/')) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: java.util.UUID.randomUUID().toString().take(8) }

    BackHandler { onCancel() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = null
            try {
                val dest = withContext(Dispatchers.IO) {
                    val dir = File(app.filesDir, "playlists").apply { mkdirs() }
                    val f = File(dir, "$id.m3u")
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Couldn't open that file")
                    f
                }
                url = dest.absolutePath
                fileLabel = uri.lastPathSegment?.substringAfterLast('/') ?: dest.name
                if (name.isBlank()) name = fileLabel!!.substringBeforeLast('.')
            } catch (e: Exception) {
                error = e.message ?: "Couldn't import that file"
            } finally { busy = false }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = DS.Accent, unfocusedBorderColor = DS.Surface2, focusedLabelColor = DS.Accent, cursorColor = DS.Accent,
    )

    fun build() = Source(
        id = id,
        name = name.trim().ifBlank {
            when (type) {
                SourceType.XTREAM -> "Xtream"
                SourceType.M3U_URL, SourceType.M3U_FILE -> "M3U playlist"
                SourceType.STALKER -> "MAC portal"
            }
        },
        type = type,
        url = url.trim(),
        username = if (type == SourceType.XTREAM) user.trim() else "",
        password = if (type == SourceType.XTREAM) pass.trim() else "",
        epgUrl = if (type == SourceType.M3U_URL || type == SourceType.M3U_FILE) epgUrl.trim() else "",
        mac = if (type == SourceType.STALKER) mac.trim().uppercase() else "",
        loadLive = loadLive, loadMovies = loadMovies, loadShows = loadShows,
        extraEpg = extraEpg.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("http") },
        extraLogins = if (type == SourceType.XTREAM) extraLogins.split('\n').map { it.trim() }.filter { it.contains(':') } else emptyList(),
    )

    val canSave = when (type) {
        SourceType.XTREAM -> url.length > 8 && user.isNotBlank() && pass.isNotBlank()
        SourceType.M3U_URL -> url.length > 10
        SourceType.M3U_FILE -> fileLabel != null && url.startsWith("/")
        SourceType.STALKER -> url.length > 8 && Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac.trim())
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!firstRun || app.store.profiles.value.size > 1) {
                    IconButton(onClick = onCancel, modifier = Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DS.Text) }
                }
                if (firstRun) BrandMark(36.dp, nameSize = 24)
                else Text(if (existing == null) "Add playlist" else "Edit playlist", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (firstRun) Text("Add your IPTV service to get started.", color = DS.TextDim, fontSize = 15.sp)
            if (firstRun) {
                var copying by remember { mutableStateOf(false) }
                PillButton("Copy from another device", { copying = true }, Icons.Default.Devices)
                if (copying) androidx.compose.ui.window.Dialog(onDismissRequest = { copying = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    Column(Modifier.widthIn(max = 640.dp).dialogSheet()
                        .verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Copy from another device", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("On the device that's already set up, open Settings › Sync › Send from this device.", color = DS.TextDim,
                            fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        CopyFromDevicePanel()
                        Row(Modifier.padding(top = 14.dp)) { PillButton("Close", { copying = false }) }
                    }
                }
            }
            if (type != SourceType.M3U_FILE) PhoneSetupCard("source", onReceived = { o ->
                runCatching { SourceType.valueOf(o.optString("type")) }.getOrNull()?.let { type = it }
                o.optString("name").takeIf { it.isNotBlank() }?.let { name = it }
                o.optString("url").takeIf { it.isNotBlank() }?.let { url = it }
                o.optString("user").takeIf { it.isNotBlank() }?.let { user = it }
                o.optString("pass").takeIf { it.isNotBlank() }?.let { pass = it }
                o.optString("epg").takeIf { it.isNotBlank() }?.let { epgUrl = it }
                o.optString("mac").takeIf { it.isNotBlank() }?.let { mac = it }
                error = null
            }, Modifier.fillMaxWidth())

            Text("Type", color = DS.TextDim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceType.entries.forEach { t ->
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        Modifier.focusRing(shape).clip(shape).background(if (t == type) DS.Accent else DS.Surface)
                            .clickable { type = t; error = null; if (t == SourceType.M3U_FILE && fileLabel == null) url = "" else if (url.isBlank()) url = "http://" }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon(), null, tint = DS.Text, modifier = Modifier.size(18.dp))
                        Text("  " + t.label, color = DS.Text, fontSize = 14.sp)
                    }
                }
            }

            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth())

            when (type) {
                SourceType.XTREAM -> {
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://example.com:8080") },
                        singleLine = true, colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                SourceType.M3U_URL -> {
                    OutlinedTextField(url, { url = it }, label = { Text("Playlist URL (.m3u / .m3u8)") }, singleLine = true, colors = fieldColors,
                        modifier = Modifier.tvTextInput().fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(epgUrl, { epgUrl = it }, label = { Text("Guide (XMLTV) URL, optional") }, singleLine = true, colors = fieldColors,
                        modifier = Modifier.tvTextInput().fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("Leave the guide empty if the playlist already includes one.", color = DS.TextDim, fontSize = 12.sp)
                }
                SourceType.M3U_FILE -> {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp).focusRing(RoundedCornerShape(26.dp))) {
                        Icon(Icons.Default.FolderOpen, null, tint = DS.Text)
                        Text("  " + (fileLabel ?: "Choose an .m3u file"), color = DS.Text)
                    }
                    OutlinedTextField(epgUrl, { epgUrl = it }, label = { Text("Guide (XMLTV) URL, optional") }, singleLine = true, colors = fieldColors,
                        modifier = Modifier.tvTextInput().fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                SourceType.STALKER -> {
                    OutlinedTextField(url, { url = it }, label = { Text("Portal URL") }, placeholder = { Text("http://portal.example.com/c/") },
                        singleLine = true, colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(mac, { mac = it.uppercase().take(17) }, label = { Text("MAC address") }, singleLine = true, colors = fieldColors,
                        modifier = Modifier.tvTextInput().fillMaxWidth())
                    Text("Use the MAC your provider registered. The one shown is random, so give it to your provider, or replace it with yours.",
                        color = DS.TextDim, fontSize = 12.sp)
                }
            }

            Text("Load from this playlist", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip("Live TV", loadLive) { loadLive = !loadLive }
                CategoryChip("Movies", loadMovies) { loadMovies = !loadMovies }
                CategoryChip("Shows", loadShows) { loadShows = !loadShows }
            }
            OutlinedTextField(
                extraEpg, { extraEpg = it }, label = { Text("Extra guides (XMLTV URLs, one per line)") }, colors = fieldColors,
                modifier = Modifier.tvTextInput().fillMaxWidth(), minLines = 2, maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text("Listed in priority order. They add guide data for channels the main guide misses and fill its gaps.",
                color = DS.TextDim, fontSize = 12.sp)
            if (type == SourceType.XTREAM) {
                OutlinedTextField(
                    extraLogins, { extraLogins = it }, label = { Text("Extra logins for Multiview (username:password, one per line)") },
                    colors = fieldColors, modifier = Modifier.tvTextInput().fillMaxWidth(), minLines = 1, maxLines = 3,
                )
                Text("If your plan allows one stream per login, each extra login on the same server gives Multiview one more tile.",
                    color = DS.TextDim, fontSize = 12.sp)
            }

            error?.let { Text(it, color = DS.Live, fontSize = 14.sp) }

            Button(
                enabled = canSave && !busy,
                onClick = {
                    val src = build()
                    busy = true; error = null
                    scope.launch {
                        try {
                            app.provider(src).verify()
                            app.store.saveSource(src)
                            // Edited playlist: drop the saved copy so the new one loads.
                            com.dekstream.tv.data.PlaylistCache(app.filesDir, src.id).clear()
                            val sess = app.session
                            when {
                                firstRun || sess == null -> app.useSource(src)
                                existing != null && sess.source.id == src.id -> app.useSource(src) // reload edited playlist
                                existing == null -> app.useSource(src)
                            }
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't load that playlist"
                        } finally { busy = false }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRing(RoundedCornerShape(26.dp)),
            ) {
                if (busy) CircularProgressIndicator(color = DS.Text, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(if (existing == null) "Test & add" else "Test & save", fontSize = 16.sp)
            }

            if (existing != null && app.store.sources.value.size > 1) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Remove this playlist", color = DS.Live)
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DS.Surface,
            title = { Text("Remove ${existing.name}?", color = DS.Text) },
            text = { Text("Profiles using it switch to another playlist.", color = DS.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val wasActive = app.session?.source?.id == existing.id
                    app.store.deleteSource(existing.id)
                    if (existing.type == SourceType.M3U_FILE) runCatching { File(existing.url).delete() }
                    onDone()
                    if (wasActive) app.store.sources.value.firstOrNull()?.let { app.useSource(it) }
                }) { Text("Remove", color = DS.Live) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = DS.TextDim) } },
        )
    }
}

@Suppress("unused") private val pad = Modifier.padding(0.dp)
