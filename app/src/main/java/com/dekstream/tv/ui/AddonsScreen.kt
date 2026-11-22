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
    var coll by remember { mutableStateOf(Stremio.collectionsUrl(ctx)) }
    var iptvVod by remember { mutableStateOf(Stremio.iptvVod(ctx)) }
    var status by remember { mutableStateOf<String?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DS.Accent, cursorColor = DS.Accent, focusedTextColor = DS.Text, unfocusedTextColor = DS.Text)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).widthIn(max = 760.dp)) {
        Text("Addons & Discover", color = DS.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Discover's catalog comes from Cinemeta (popular, new, top rated), TVmaze (new episodes, premieres) and, with a free key, TMDB " +
            "(movies coming soon, collections). Sources come from the Stremio addons you add here, like your Usenet Ultimate.",
            color = DS.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Movies & series", color = DS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
        Row(Modifier.padding(top = 8.dp)) {
            Pill("From IPTV and addons", iptvVod, onClick = { iptvVod = true; Stremio.setIptvVod(ctx, true) })
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            Pill("Addons only", !iptvVod, onClick = { iptvVod = false; Stremio.setIptvVod(ctx, false) })
        }
        Text("Addons only hides your IPTV provider's Movies and Series everywhere (sidebar, Home, Search). Discover is where you browse.",
            color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(urls, { urls = it }, label = { Text("Addon manifest URLs (one per line), e.g. your Usenet Ultimate") }, colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp), minLines = 2, maxLines = 6)
        OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB API key (optional, for Coming soon movies)") }, singleLine = true, colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Text("Get one free at themoviedb.org → Settings → API.", color = DS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(coll, { coll = it }, label = { Text("Collections file (Nuvio collection JSON URL)") }, singleLine = true, colors = colors,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Row(Modifier.padding(top = 8.dp)) {
            Pill("Use Kaptain's Collection", coll == Stremio.KAPTAIN, onClick = { coll = Stremio.KAPTAIN })
        }
        Row(Modifier.padding(top = 16.dp)) {
            PillButton("Save and test", {
                Stremio.setAddonUrls(ctx, urls.split('\n').map { it.trim() }.filter { it.startsWith("http") })
                Stremio.setTmdbKey(ctx, tmdb)
                Stremio.setCollectionsUrl(ctx, coll)
                status = "Checking…"
                scope.launch {
                    val found = runCatching { Stremio.addons(ctx) }.getOrDefault(emptyList())
                    status = if (found.isEmpty()) (if (urls.isBlank()) "No addons added." else "Couldn't load any addon. Check the URLs.")
                    else found.joinToString("\n") { "✓ ${it.name}" + if ("stream" in it.resources) " · streams" else "" }
                    if (coll.isNotBlank()) status += "\n" + runCatching { com.dekstream.tv.data.Collections.groups(ctx, refresh = true) }
                        .fold({ "✓ Collections: ${it.size} groups, ${it.sumOf { g -> g.folders.size }} folders" }, { "✗ Collections: ${it.message}" })
                }
            }, Icons.Default.Check, primary = true)
        }
        status?.let { Text(it, color = DS.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}
