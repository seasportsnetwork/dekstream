package com.dekstream.tv.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dekstream.tv.data.Backup
import com.dekstream.tv.data.DeviceSync
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Copy DekTV's setup between devices: securely over Wi-Fi, or with a password-protected backup file. */
@Composable
fun SyncScreen(state: AppState) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(DekStreamAppRef.takeSyncMode()) } // 0 = choose, 1 = send, 2 = receive, 3 = backup

    DisposableEffect(mode) {
        if (mode == 1) DeviceSync.startSending(ctx)
        onDispose { DeviceSync.stopSending(ctx) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 760.dp)) {
        ScreenTitle("Sync with another device") { state.pop() }
        Text("Copy playlists, profiles, favorites, teams, channel edits and appearance to another device. " +
            "Over Wi-Fi it's encrypted and needs your OK on this device; a backup file works anywhere and is locked with a password.",
            color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Send from this device", mode == 1, onClick = { mode = 1 }, icon = Icons.Default.Upload)
            Pill("Get from another device", mode == 2, onClick = { mode = 2 }, icon = Icons.Default.Download)
            Pill("Backup file", mode == 3, onClick = { mode = 3 }, icon = Icons.Default.Save)
        }
        Spacer(Modifier.height(18.dp))

        when (mode) {
            0 -> Text("This device: ${DeviceSync.deviceName(ctx)}", color = DS.TextDim, fontSize = 13.sp)
            1 -> SendPanel()
            2 -> CopyFromDevicePanel()
            3 -> BackupPanel()
        }
    }
    DeviceSync.pending?.let { AllowDialog(it) }
}

@Composable
private fun SendPanel() {
    val ctx = LocalContext.current
    BentoCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceSync.sendQr?.let { QrImage(it, 200.dp) }
            Column(Modifier.weight(1f)) {
                Text("On the other device, open Sync › Get from another device and scan this code. No camera? Pick this device there and type:",
                    color = DS.TextDim, fontSize = 14.sp)
                Text(DeviceSync.sendCode?.let { "${it.take(3)} ${it.substring(3, 6)} ${it.drop(6)}" } ?: "…", color = DS.Text,
                    fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.padding(vertical = 6.dp))
                Text("Shows up there as \"${DeviceSync.deviceName(ctx)}\"", color = DS.TextDim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Pill(if (DeviceSync.includeLogins) "Include playlist logins" else "Without playlist logins", DeviceSync.includeLogins,
                    onClick = { DeviceSync.includeLogins = !DeviceSync.includeLogins }, icon = if (DeviceSync.includeLogins) Icons.Default.Check else Icons.Default.Lock)
                Text(if (DeviceSync.includeLogins) "Usernames and passwords go too. Turn off when setting up someone else's device."
                    else "They'll enter their own usernames and passwords.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text(DeviceSync.sendStatus ?: "", color = DS.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                Text("Encrypted · you'll be asked to allow it · works once · closes after 10 minutes or when you leave",
                    color = DS.TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun AllowDialog(p: DeviceSync.Pending) {
    val deny = remember { FocusRequester() }
    LaunchedEffect(p) { kotlinx.coroutines.delay(100); runCatching { deny.requestFocus() } }
    Dialog(onDismissRequest = { DeviceSync.answer(false) }) {
        Column(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(DS.Surface2).padding(28.dp)) {
            Icon(Icons.Default.Devices, null, tint = DS.Accent)
            Text("${p.from} wants to copy this setup", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text("Playlists, profiles, favorites and appearance" + (if (DeviceSync.includeLogins) ", including playlist logins." else ", without playlist logins.") +
                " PINs are never sent. Only allow it if it's a device you're setting up.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Allow", { DeviceSync.answer(true) }, Icons.Default.Check, primary = true)
                PillButton("Don't allow", { DeviceSync.answer(false) }, modifier = Modifier.focusRequester(deny))
            }
        }
    }
}

/** Get a setup from another device: scan its QR code, or pick it and type its 10-digit code. Also used on first run. */
@Composable
fun CopyFromDevicePanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf<JSONObject?>(null) }
    val camera = remember { hasCamera(ctx) }

    DisposableEffect(Unit) {
        DeviceSync.startDiscovery(ctx)
        onDispose { DeviceSync.stopDiscovery(ctx) }
    }

    fun go(block: suspend () -> JSONObject) {
        busy = true; err = null
        scope.launch {
            payload = runCatching { block() }.getOrElse { err = it.message ?: "Couldn't connect"; null }
            busy = false
        }
    }

    Column(modifier) {
        val p = payload
        if (p != null) {
            ConfirmImport(p, onCancel = { payload = null })
            return@Column
        }
        if (busy) Text("Waiting for the other device to allow it…", color = DS.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp))
        if (camera) {
            PillButton("Scan the QR code", {
                scanQr(ctx) { text ->
                    val qr = text?.let { DeviceSync.parseQr(it) }
                    if (text != null && qr == null) err = "That isn't a DekTV sync code."
                    if (qr != null) go { DeviceSync.fetchWithQr(ctx, qr) }
                }
            }, Icons.Default.QrCodeScanner, primary = true)
            Text("Or type the code instead:", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
        }
        Text("DEVICES SENDING", color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        if (DeviceSync.found.isEmpty()) Text("Looking… On the other device, open Sync › Send from this device.",
            color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        DeviceSync.found.forEach { f ->
            Pill(f.name, address == f.address, onClick = { address = f.address }, icon = Icons.Default.Devices, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent)
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, singleLine = true, label = { Text("10-digit code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = colors,
            modifier = Modifier.tvTextInput().width(260.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("Device address (if it isn't listed)") }, colors = colors,
            modifier = Modifier.tvTextInput().width(360.dp))
        err?.let { Text(it, color = DS.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(top = 14.dp)) {
            PillButton(if (busy) "Waiting…" else "Get setup", {
                if (busy) return@PillButton
                if (address.isBlank() || code.length != 10) { err = "Pick the device and enter its 10-digit code."; return@PillButton }
                go { DeviceSync.fetchWithCode(ctx, address, code) }
            }, Icons.Default.Download, primary = !camera)
        }
    }
}

@Composable
private fun ConfirmImport(p: JSONObject, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    BentoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text("From ${p.optString("from")}", color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(DeviceSync.summary(p), color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text("This replaces the setup on this device (your parent PIN stays). DekTV restarts afterwards.", color = DS.Live, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Replace and restart", { DeviceSync.importAndRestart(ctx, p) }, Icons.Default.Download, primary = true)
                PillButton("Cancel", onCancel)
            }
        }
    }
}

@Composable
private fun BackupPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var logins by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var openUri by remember { mutableStateOf<Uri?>(null) }
    var openPw by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf<JSONObject?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = runCatching { Backup.save(ctx, uri, pw, logins); "Backup saved. Keep the password somewhere safe; it can't be recovered." }
                .getOrElse { it.message ?: "Couldn't save the backup" }
            pw = ""; pw2 = ""
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { openUri = uri; msg = null } }

    payload?.let { ConfirmImport(it, onCancel = { payload = null }); return }

    BentoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text("Save a backup", color = DS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("A file you can move to any device (text it, email it, put it in Drive). It's locked with a password only you know.",
                color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = colors, modifier = Modifier.tvTextInput().width(240.dp))
                OutlinedTextField(pw2, { pw2 = it }, singleLine = true, label = { Text("Same again") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = colors, modifier = Modifier.tvTextInput().width(240.dp))
            }
            Pill(if (logins) "Include playlist logins" else "Without playlist logins", logins, onClick = { logins = !logins },
                icon = if (logins) Icons.Default.Check else Icons.Default.Lock, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 14.dp)) {
                PillButton("Save backup file", {
                    msg = when {
                        pw.length < 6 -> "Use a password of at least 6 characters."
                        pw != pw2 -> "The passwords don't match."
                        else -> { saver.launch("DekTV backup.dektv"); null }
                    }
                }, Icons.Default.Save, primary = true)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    BentoCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Text("Restore from a backup", color = DS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val uri = openUri
            if (uri == null) {
                Row(Modifier.padding(top = 12.dp)) { PillButton("Choose backup file", { opener.launch(arrayOf("*/*")) }, Icons.Default.FolderOpen) }
            } else {
                Text(uri.lastPathSegment?.substringAfterLast('/') ?: "Backup file", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(openPw, { openPw = it }, singleLine = true, label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = colors,
                    modifier = Modifier.tvTextInput().width(300.dp).padding(top = 8.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Unlock", {
                        scope.launch {
                            payload = runCatching { Backup.open(ctx, uri, openPw) }.getOrElse { msg = it.message; null }
                            if (payload != null) { openPw = ""; openUri = null }
                        }
                    }, Icons.Default.Lock, primary = true)
                    PillButton("Choose another", { opener.launch(arrayOf("*/*")) })
                }
            }
        }
    }
    msg?.let { Text(it, color = DS.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
}

/** Debug test hook: open Sync straight in send/receive mode. */
object DekStreamAppRef {
    var syncMode = 0
    fun takeSyncMode(): Int = syncMode.also { syncMode = 0 }
}
