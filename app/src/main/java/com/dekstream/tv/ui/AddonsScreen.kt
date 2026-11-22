package com.dekstream.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.data.Stremio
import kotlinx.coroutines.launch

/** Settings > Addons & Discover: Stremio addon URLs (streams) and an optional TMDB key (coming-soon movies). */
@Composable
fun AddonsScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf(Stremio.addonUrls(ctx).joinToString("\n")) }
    var tmdb by remember { mutableStateOf(Stremio.tmdbKey(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent, focusedTextColor = DS.Text, unfocusedTextColor = DS.Text)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 760.dp)) {
        Text("Addons & Discover", color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Discover's catalog comes from Cinemeta (popular, new, top rated), TVmaze (new episodes, premieres) and, with a free key, TMDB " +
            "(movies coming soon). Sources come from your Stremio addons — your Usenet Ultimate is set up already.",
            color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(urls, { urls = it }, label = { Text("Addon manifest URLs (one per line)") }, colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp), minLines = 2, maxLines = 6)
        OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB API key (optional, for Coming soon movies)") }, singleLine = true, colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Text("Get one free at themoviedb.org → Settings → API.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 16.dp)) {
            PillButton("Save and test", {
                Stremio.setAddonUrls(ctx, urls.split('\n').map { it.trim() }.filter { it.startsWith("http") })
                Stremio.setTmdbKey(ctx, tmdb)
                status = "Checking…"
                scope.launch {
                    val found = runCatching { Stremio.addons(ctx) }.getOrDefault(emptyList())
                    status = if (found.isEmpty()) "Couldn't load any addon. Check the URLs."
                    else found.joinToString("\n") { "✓ ${it.name}" + if ("stream" in it.resources) " · streams" else "" }
                }
            }, Icons.Default.Check, primary = true)
        }
        status?.let { Text(it, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}
