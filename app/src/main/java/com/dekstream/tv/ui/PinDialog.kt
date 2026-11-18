package com.dekstream.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dekstream.tv.DekStreamApp
import kotlinx.coroutines.delay

/**
 * 4-digit PIN entry with an on-screen keypad (touch and remote) plus hardware number keys.
 * [expected] = the PIN to check; null = "create a new PIN" (asks twice).
 */
@Composable
fun PinDialog(title: String, expected: String?, subtitle: String? = null, onDismiss: () -> Unit, onSuccess: (String) -> Unit) {
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }

    fun press(d: String) {
        if (entry.length >= 4) return
        entry += d
        error = null
        if (entry.length == 4) {
            val e = entry
            when {
                expected != null -> if (e == expected) onSuccess(e) else { error = "Wrong PIN"; entry = "" }
                first == null -> { first = e; entry = "" }
                first == e -> onSuccess(e)
                else -> { error = "PINs didn't match. Try again."; first = null; entry = "" }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Appearance.palette.surface).padding(24.dp)
                .onPreviewKeyEvent { ev ->
                    val k = ev.nativeKeyEvent
                    if (k.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (k.keyCode) {
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press("${k.keyCode - KeyEvent.KEYCODE_0}"); true }
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press("${k.keyCode - KeyEvent.KEYCODE_NUMPAD_0}"); true }
                        KeyEvent.KEYCODE_DEL -> { entry = entry.dropLast(1); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (expected == null && first != null) "Enter it again" else title,
                color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            subtitle?.let { Text(it, color = DS.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < entry.length) DS.Accent else DS.Surface2))
                }
            }
            error?.let { Text(it, color = DS.Live, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
            keys.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        if (k.isEmpty()) Spacer(Modifier.size(64.dp))
                        else {
                            val shape = CircleShape
                            Box(
                                Modifier.size(64.dp)
                                    .then(if (r == 0 && k == "1") Modifier.focusRequester(focus) else Modifier)
                                    .focusRing(shape, 1.08f).clip(shape).background(DS.Surface2)
                                    .clickable { if (k == "⌫") entry = entry.dropLast(1) else press(k) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (k == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = DS.Text)
                                else Text(k, color = DS.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 6.dp)) { Text("Cancel", color = DS.TextDim) }
        }
    }
}

/**
 * Runs [action] after the parent PIN is entered (or right away if none is set and [requireSetup] is false).
 * If no parent PIN exists and [requireSetup] is true, asks to create one first.
 */
@Composable
fun ParentGate(requireSetup: Boolean = true, reason: String, onDismiss: () -> Unit, onPass: () -> Unit) {
    val store = DekStreamApp.instance.store
    val pin = store.parentPin
    when {
        pin.isNotEmpty() -> PinDialog("Parent PIN", pin, reason, onDismiss) { onPass() }
        requireSetup -> PinDialog("Create a parent PIN", null, "You'll use it for kids settings and locked channels.", onDismiss) {
            store.parentPin = it; onPass()
        }
        else -> LaunchedEffect(Unit) { onPass() }
    }
}

@Suppress("unused") private val w = Modifier.width(0.dp)
@Suppress("unused") private val c = Color.Transparent
