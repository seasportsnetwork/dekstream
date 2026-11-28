package com.dekstream.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Network logos for the scores card, taken from the playlist's own channels: "US: ESPN 2 FHD" has ESPN2's logo,
 * so a game on "ESPN2" shows it. Networks with no matching channel (streaming services, etc.) fall back to a label.
 */
object NetworkLogos {
    private var map: Map<String, String> = emptyMap()
    /** Bumped when the index changes, so the card redraws with logos. */
    var version by mutableStateOf(0); private set

    private val prefix = Regex("""^\s*(?:[\[(|]?[A-Za-z0-9]{2,4}[\])|]?\s*[-:|]\s*)+""")
    private val noise = Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|raw|east|west|pacific|feed|backup|alt|live|us|usa|tv)\b""")

    /** "US: ESPN 2 FHD" → "espn2"; "NFL Network" → "nflnetwork". */
    fun key(name: String): String =
        name.replace(prefix, "").lowercase().replace("+", "plus").replace(noise, " ").replace(Regex("[^a-z0-9]"), "")

    fun index(channels: List<Channel>) {
        val m = HashMap<String, String>()
        for (c in channels) {
            val icon = c.icon?.takeIf { it.startsWith("http") } ?: continue
            val k = key(c.name)
            if (k.length >= 2 && k !in m) m[k] = icon
        }
        map = m
        version++
    }

    fun logo(network: String): String? {
        val k = key(network)
        if (k.length < 2) return null
        return map[k] ?: map[k.removeSuffix("network")] ?: map[k + "network"]
    }
}
