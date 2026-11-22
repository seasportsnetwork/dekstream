package com.dekstream.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.data.PlaybackPrefs as P

/** Settings > Playback & screen. */
@Composable
fun PlaybackSettingsScreen(state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 760.dp)) {
        Text("Playback & screen", color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)

        Option("Match frame rate", "The TV switches to the stream's frame rate (60 for sports, 24 for movies) for smoother motion. " +
            "The screen may go black for a second when the rate changes.") {
            Choice("On", P.matchFrameRate) { P.setMatchFrameRate(true) }
            Choice("Off", !P.matchFrameRate) { P.setMatchFrameRate(false) }
        }
        Option("Up and Down while watching", "Browse what's on other channels without leaving the one you're watching, or change channel straight away.") {
            Choice("Browse the guide", P.upDown == "guide") { P.setUpDown("guide") }
            Choice("Change channel", P.upDown == "zap") { P.setUpDown("zap") }
        }
        Option("Back while watching live TV", "Keep it playing in a corner while you browse, or stop it.") {
            Choice("Mini player", P.backToMini) { P.setBackToMini(true) }
            Choice("Stop", !P.backToMini) { P.setBackToMini(false) }
        }
        Option("Best version of a channel", "When a channel is listed more than once (HD, FHD, 4K…), play the copy that has worked best for you.") {
            Choice("On", P.bestVersion) { P.setBestVersion(true) }
            Choice("Off", !P.bestVersion) { P.setBestVersion(false) }
        }
        Option("When DekTV opens", null) {
            Choice("Home", P.startOn == "home") { P.setStartOn("home") }
            Choice("Guide", P.startOn == "guide") { P.setStartOn("guide") }
            Choice("Last channel", P.startOn == "last") { P.setStartOn("last") }
        }
        Option("Captions: size", null) {
            listOf("Small", "Normal", "Large", "Extra large").forEachIndexed { i, l -> Choice(l, P.captionSize == i) { P.setCaptionSize(i) } }
        }
        Option("Captions: background", null) {
            listOf("None (outline)", "Shaded", "Solid black").forEachIndexed { i, l -> Choice(l, P.captionBg == i) { P.setCaptionBg(i) } }
        }
        Option("Captions: position", null) {
            Choice("Bottom", !P.captionHigh) { P.setCaptionHigh(false) }
            Choice("Higher (above score bugs)", P.captionHigh) { P.setCaptionHigh(true) }
        }
        Option("Burn-in protection", "For OLED TVs: menus dim after 3 idle minutes and the layout shifts a few pixels now and then. Never while video plays.") {
            Choice("On", P.burnIn) { P.setBurnIn(true) }
            Choice("Off", !P.burnIn) { P.setBurnIn(false) }
        }
    }
}

@Composable
private fun Option(title: String, body: String?, choices: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(title, color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (body != null) Text(body, color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) = Pill(label, on, onClick = onClick)

