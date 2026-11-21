package com.dekstream.tv.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.util.Locale

private data class TrackChoice(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

private fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    return Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
}

@OptIn(UnstableApi::class)
private fun describe(f: Format, n: Int, type: Int): String {
    val parts = ArrayList<String>()
    (f.label ?: langName(f.language))?.let { parts += it }
    if (type == C.TRACK_TYPE_AUDIO) {
        when (f.channelCount) { 1 -> parts += "Mono"; 2 -> parts += "Stereo"; 6 -> parts += "5.1"; 8 -> parts += "7.1" }
        f.sampleMimeType?.substringAfter('/')?.uppercase()?.let { if (it.length <= 6) parts += it }
    }
    if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
    return parts.joinToString(" · ").ifBlank { "Track $n" }
}

private fun choices(tracks: Tracks, type: Int): List<TrackChoice> {
    val out = ArrayList<TrackChoice>()
    var n = 1
    tracks.groups.filter { it.type == type }.forEach { g ->
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            out += TrackChoice(describe(g.getTrackFormat(i), n++, type), g, i, g.isTrackSelected(i))
        }
    }
    return out
}

/** Audio track, subtitles, sleep timer and picture size. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerOptionsDialog(
    player: ExoPlayer,
    sleepAt: Long,
    programmeEnd: Long?,
    onSleep: (Long) -> Unit,
    resizeIdx: Int,
    onResize: (Int) -> Unit,
    onDismiss: () -> Unit,
    onAudioPicked: (language: String?, label: String) -> Unit = { _, _ -> },
) {
    var refresh by remember { mutableIntStateOf(0) }
    val tracks = remember(refresh) { player.currentTracks }
    val audio = choices(tracks, C.TRACK_TYPE_AUDIO)
    val text = choices(tracks, C.TRACK_TYPE_TEXT)
    val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    fun pick(c: TrackChoice, type: Int) {
        val g = c.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, c.index))
            .build()
        refresh++
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(18.dp))
                .background(DS.Surface).verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("Playback options", color = DS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SectionLabel("AUDIO")
            if (audio.isEmpty()) Text("Only one audio track.", color = DS.TextDim, fontSize = 14.sp)
            audio.forEachIndexed { i, c ->
                OptionRow(c.label, c.selected, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                    pick(c, C.TRACK_TYPE_AUDIO)
                    onAudioPicked(c.group?.getTrackFormat(c.index)?.language, c.label)
                }
            }

            SectionLabel("SUBTITLES")
            OptionRow("Off", textOff, if (audio.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                refresh++
            }
            text.forEach { c -> OptionRow(c.label, c.selected && !textOff) { pick(c, C.TRACK_TYPE_TEXT) } }

            SectionLabel("SLEEP TIMER")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip("Off", sleepAt == 0L) { onSleep(0) }
                listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                    CategoryChip(if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}", false) {
                        onSleep(System.currentTimeMillis() + m * 60_000L)
                    }
                }
                if (programmeEnd != null && programmeEnd > System.currentTimeMillis()) {
                    CategoryChip("End of programme", sleepAt == programmeEnd) { onSleep(programmeEnd) }
                }
            }
            if (sleepAt > 0) {
                Text("Stops at ${fmtTime(sleepAt)}", color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }

            SectionLabel("PICTURE SIZE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fit", "Fill (crop)", "Stretch").forEachIndexed { i, label -> CategoryChip(label, i == resizeIdx) { onResize(i) } }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) { Text("Done", color = DS.Accent) }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).focusRing(shape, 1.02f).clip(shape)
            .background(if (selected) DS.AccentSoft else DS.Surface2).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = DS.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = DS.Accent, modifier = Modifier.size(20.dp))
    }
}

@Suppress("unused") private val transparent = Color.Transparent
