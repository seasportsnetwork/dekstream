package com.dekstream.tv.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.data.DeviceSync
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Copy DekTV's setup between the Shield and the tablet over the home network. */
@Composable
fun SyncScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(DekStreamAppRef.takeSyncMode()) } // 0 = choose, 1 = send, 2 = receive
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var payload by remember { mutableStateOf<JSONObject?>(null) }

    DisposableEffect(mode) {
        when (mode) {
            1 -> DeviceSync.startSending(ctx)
            2 -> DeviceSync.startDiscovery(ctx)
        }
        onDispose { DeviceSync.stopSending(ctx); DeviceSync.stopDiscovery(ctx) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 720.dp)) {
        Text("Sync with another device", color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Copy playlists, profiles, favorites, teams, channel edits and appearance between your Shield and tablet. " +
            "Both need DekTV open on the same Wi-Fi.", color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Send from this device", mode == 1, onClick = { mode = 1; err = null; payload = null }, icon = Icons.Default.Upload)
            Pill("Get from another device", mode == 2, onClick = { mode = 2; err = null; payload = null }, icon = Icons.Default.Download)
        }
        Spacer(Modifier.height(18.dp))

        when (mode) {
            0 -> Text("This device: ${DeviceSync.deviceName(ctx)}", color = DS.TextDim, fontSize = 13.sp)
            1 -> BentoCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("On the other device, choose \"Get from another device\" and enter:", color = DS.TextDim, fontSize = 14.sp)
                    Text(DeviceSync.sendCode?.chunked(3)?.joinToString(" ") ?: "…", color = DS.Text, fontSize = 56.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 4.sp, modifier = Modifier.padding(vertical = 8.dp))
                    Text("This device appears as \"${DeviceSync.deviceName(ctx)}\"" +
                        (DeviceSync.sendAddress?.let { " · address $it" } ?: ""), color = DS.TextDim, fontSize = 13.sp)
                    Text(DeviceSync.sendStatus ?: "", color = DS.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp))
                    Text("Stays open for 10 minutes. Leave this screen to stop.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            2 -> Column {
                val p = payload
                if (p != null) {
                    BentoCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp)) {
                            Text("From ${p.optString("from")}", color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(DeviceSync.summary(p), color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            Text("This replaces everything on this device. DekTV restarts afterwards.", color = DS.Live, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 12.dp))
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton("Replace and restart", { DeviceSync.importAndRestart(ctx, p) }, Icons.Default.Download, primary = true)
                                PillButton("Cancel", { payload = null })
                            }
                        }
                    }
                } else {
                    Text("DEVICES FOUND", color = DS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (DeviceSync.found.isEmpty()) Text("Looking… On the other device, choose \"Send from this device\".",
                        color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    DeviceSync.found.forEach { f ->
                        Pill(f.name, address == f.address, onClick = { address = f.address }, icon = Icons.Default.Devices,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("Address (picked above, or type it)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent),
                        modifier = Modifier.tvTextInput().fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("6-digit code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent),
                        modifier = Modifier.tvTextInput().width(220.dp))
                    err?.let { Text(it, color = DS.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(if (busy) "Connecting…" else "Get setup", {
                            if (busy || address.isBlank() || code.length != 6) { err = "Pick a device and enter the 6-digit code."; return@PillButton }
                            busy = true; err = null
                            scope.launch {
                                payload = runCatching { DeviceSync.fetch(address, code) }.getOrElse { err = it.message ?: "Couldn't connect"; null }
                                busy = false
                            }
                        }, Icons.Default.Download, primary = true)
                    }
                }
            }
        }
    }
}

/** Debug test hook: open Sync straight in send/receive mode. */
object DekStreamAppRef {
    var syncMode = 0
    fun takeSyncMode(): Int = syncMode.also { syncMode = 0 }
}
