package com.dekstream.tv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat

/** In-app voice search state: [start] listens; the heard text goes to the callback. */
class VoiceSearch internal constructor(
    val available: Boolean,
    internal val begin: () -> Unit,
    internal val cancel: () -> Unit,
) {
    var listening by mutableStateOf(false); internal set
    var heard by mutableStateOf(""); internal set
    var message by mutableStateOf<String?>(null); internal set
    var level by mutableFloatStateOf(0f); internal set
    fun start() = begin()
    fun stop() = cancel()
}

@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit): VoiceSearch {
    val ctx = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val inApp = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    val viaScreen = remember {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    val recognizer = remember { if (inApp) runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() else null }
    DisposableEffect(recognizer) { onDispose { runCatching { recognizer?.destroy() } } }

    // Fallback: Android's own voice screen (only when in-app listening can't work here).
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { result(it) }
    }
    fun openScreen() {
        if (!viaScreen) return
        runCatching {
            screen.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, show, team or movie"))
        }
    }

    lateinit var vs: VoiceSearch
    fun listenNow() {
        val r = recognizer ?: return openScreen()
        vs.heard = ""; vs.message = null; vs.level = 0f; vs.listening = true
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { vs.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { vs.level = 0f }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { vs.heard = it }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) { vs.message = "Didn't catch that. Press OK to try again."; return }
                vs.listening = false; result(text)
            }
            override fun onError(error: Int) {
                vs.level = 0f
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { vs.listening = false; openScreen() }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        vs.message = "Didn't catch that. Press OK to try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        vs.message = "Voice needs an internet connection. Press OK to try again."
                    else -> vs.message = "Voice search had a problem. Press OK to try again."
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName))
        }.onFailure { vs.listening = false; openScreen() }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listenNow() else openScreen()
    }

    vs = remember {
        VoiceSearch(
            available = inApp || viaScreen,
            begin = {
                if (recognizer == null) openScreen()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listenNow()
                else mic.launch(Manifest.permission.RECORD_AUDIO)
            },
            cancel = { runCatching { recognizer?.cancel() } },
        )
    }
    return vs
}

/** DekTV's listening panel: pulsing mic, live transcript. OK retries after a miss; Back closes. */
@Composable
fun VoicePanel(vs: VoiceSearch) {
    if (!vs.listening) return
    Dialog(onDismissRequest = { vs.stop(); vs.listening = false }) {
        Column(
            Modifier.widthIn(min = 420.dp, max = 560.dp).clip(RoundedCornerShape(24.dp)).background(DS.Surface2)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val retry = vs.message != null
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { focus.requestFocus() } }
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).scale(1f + vs.level * 0.35f).clip(CircleShape).background(DS.Accent.copy(alpha = 0.22f)))
                Box(
                    Modifier.size(72.dp).focusRequester(focus).focusRing(CircleShape, 1.05f).clip(CircleShape).background(if (retry) DS.Card else DS.Accent)
                        .tvClickable({ if (retry) vs.start() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Mic, "Voice search", tint = if (retry) DS.Text else androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Text(
                vs.message ?: vs.heard.ifBlank { "Listening… say a channel, show, team or movie" },
                color = if (vs.heard.isNotBlank() && vs.message == null) DS.Text else DS.TextDim,
                fontSize = if (vs.heard.isNotBlank() && vs.message == null) 22.sp else 15.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text("Back to cancel", color = DS.TextDim, fontSize = 12.sp)
        }
    }
}
