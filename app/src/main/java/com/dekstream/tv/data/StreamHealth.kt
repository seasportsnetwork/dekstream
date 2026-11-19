package com.dekstream.tv.data

import android.content.Context
import org.json.JSONObject

/**
 * Remembers how each live channel behaves: how often it starts, fails or freezes, and how long it
 * takes to start. Used to pick the most reliable feed when a channel needs a backup.
 */
class StreamHealth(context: Context) {
    private val sp = context.getSharedPreferences("dekstream_health", Context.MODE_PRIVATE)

    private data class Stat(var ok: Int = 0, var fail: Int = 0, var startMs: Long = 0)

    private val stats: MutableMap<Int, Stat> = load()

    @Synchronized fun recordStart(streamId: Int, msToStart: Long) {
        val s = stats.getOrPut(streamId) { Stat() }
        s.ok++
        s.startMs = if (s.startMs == 0L) msToStart else (s.startMs * 3 + msToStart) / 4
        save()
    }

    @Synchronized fun recordFailure(streamId: Int) {
        stats.getOrPut(streamId) { Stat() }.fail++
        save()
    }

    /** 0..100, higher is better. Unknown channels score a neutral 60. */
    @Synchronized fun score(streamId: Int): Int {
        val s = stats[streamId] ?: return 60
        val total = s.ok + s.fail
        if (total == 0) return 60
        val reliability = s.ok.toFloat() / total            // 0..1
        val speed = when {                                   // faster start = small bonus
            s.startMs == 0L -> 0
            s.startMs < 1500 -> 10
            s.startMs < 3000 -> 5
            s.startMs > 8000 -> -10
            else -> 0
        }
        return (reliability * 90).toInt() + speed
    }

    private fun load(): MutableMap<Int, Stat> = runCatching {
        val o = JSONObject(sp.getString("stats", "{}")!!)
        val out = HashMap<Int, Stat>()
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            out[k.toInt()] = Stat(a.getInt(0), a.getInt(1), a.getLong(2))
        }
        out
    }.getOrDefault(HashMap())

    private fun save() {
        // Keep the 2000 most-used channels.
        val keep = stats.entries.sortedByDescending { it.value.ok + it.value.fail }.take(2000)
        val o = JSONObject()
        keep.forEach { (k, v) -> o.put(k.toString(), org.json.JSONArray().put(v.ok).put(v.fail).put(v.startMs)) }
        sp.edit().putString("stats", o.toString()).apply()
    }

    companion object {
        /** Channel name without region prefixes and quality tags, for spotting feeds of the same channel. */
        fun baseName(name: String): String = name.lowercase()
            .replace(Regex("^\\s*[a-z]{2,4}\\s*[:|\\-]\\s*"), "")          // "US: ", "UK | "
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")                  // [VIP] (backup)
            .replace(Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h\\.?265|h\\.?264|1080p?|720p?|2160p?|50 ?fps|60 ?fps|backup|raw|vip|plus|alt|feed|multi|\\d)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}
