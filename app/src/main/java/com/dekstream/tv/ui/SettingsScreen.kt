package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.BuildConfig
import com.dekstream.tv.DekStreamApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onSignOut: () -> Unit) {
    val app = DekStreamApp.instance
    val repo = app.repo ?: return
    val info by rememberLoad(Unit) { repo.api.login() }
    var format by remember { mutableStateOf(app.prefs.liveFormat) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 640.dp),
    ) {
        Text("Settings", color = DS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)

        SectionTitle("Account")
        Card {
            val acct = repo.api.account
            Line("Server", acct.base)
            Line("Username", acct.username)
            LoadView(info) { i ->
                Line("Status", i.status)
                i.expiresAtMs?.let { Line("Expires", SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it))) }
                Line("Connections", "${i.activeConnections} of ${i.maxConnections} in use")
            }
        }

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

        SectionTitle("Tips")
        Card {
            Text("• Long-press a channel to add or remove it from Favorites.", color = DS.Text, fontSize = 14.sp)
            Text("• On the Shield, press ▲ / ▼ while watching to change channels.", color = DS.Text, fontSize = 14.sp)
            Text("• Long-press something in Continue Watching to remove it.", color = DS.Text, fontSize = 14.sp)
        }

        SectionTitle("About")
        Card { Line("Version", BuildConfig.VERSION_NAME) }

        Button(
            onClick = { confirmSignOut = true },
            colors = ButtonDefaults.buttonColors(containerColor = DS.Surface2),
            modifier = Modifier.padding(top = 24.dp).focusRing(RoundedCornerShape(24.dp)),
        ) { Text("Sign out", color = DS.Live) }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = DS.Surface,
            title = { Text("Sign out?", color = DS.Text) },
            text = { Text("This also clears favorites and watch progress on this device.", color = DS.TextDim) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out", color = DS.Live) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel", color = DS.TextDim) } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DS.Surface).padding(16.dp)) { content() }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.weight(0.35f))
        Text(value, color = DS.Text, fontSize = 14.sp, modifier = Modifier.weight(0.65f))
    }
}
