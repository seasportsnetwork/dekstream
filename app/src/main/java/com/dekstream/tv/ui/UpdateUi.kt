package com.dekstream.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dekstream.tv.BuildConfig
import com.dekstream.tv.data.Updater
import kotlinx.coroutines.launch

/** Checks for an update once per launch (if enabled) and shows the prompt. */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (Updater.supported && Updater.autoCheck(ctx)) Updater.check(ctx)
    }
    val info = Updater.available ?: return
    if (Updater.dismissed) return
    UpdateDialog(info, onLater = { Updater.dismissed = true }, onSkip = { Updater.skip(ctx, info.tag) })
}

@Composable
fun UpdateDialog(info: com.dekstream.tv.data.UpdateInfo, onLater: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installFocus = remember { FocusRequester() }
    val busy = Updater.progress != null
    Dialog(onDismissRequest = { if (!busy) onLater() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier.widthIn(max = 520.dp).padding(24.dp).clip(shape).background(DS.Card).border(1.dp, DS.Line, shape).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(DS.Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Update available", color = DS.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("DekTV v${info.version} · you have v${BuildConfig.VERSION_NAME}", color = DS.TextDim, fontSize = 13.sp)
                }
            }
            if (info.notes.isNotBlank()) {
                Text("What's new", color = DS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    Text(info.notes, color = DS.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Updater.progress?.let { p ->
                Text("Downloading… ${(p * 100).toInt()}%", color = DS.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(DS.Line)) {
                    Box(Modifier.fillMaxWidth(p).height(6.dp).background(DS.Accent))
                }
            }
            Updater.failure?.let { Text(it, color = DS.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp)) }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (busy) "Downloading…" else "Install", {
                    if (!busy) scope.launch { Updater.downloadAndInstall(ctx, info) }
                }, Icons.Default.SystemUpdate, primary = true, modifier = Modifier.focusRequester(installFocus))
                if (!busy) {
                    PillButton("Later", onLater)
                    PillButton("Skip this version", onSkip)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { installFocus.requestFocus() } }
}
