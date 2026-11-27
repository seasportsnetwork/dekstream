package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Top 10" rows built only from what's in the playlist: takes what's popular right now (TMDB trending
 * this week with a key, otherwise Cinemeta's popular list) and keeps the titles the playlist has, in order.
 */
object Top10 {
    data class Result<T>(val items: List<T>, val thisWeek: Boolean)

    private val prefix = Regex("""^\s*(?:[\[(|]?[A-Z0-9]{2,4}[\])|]?\s*[-:|]\s+)+""")
    private val yearRx = Regex("""[(\[]\s*((?:19|20)\d\d)\s*[)\]]|\s[-–]\s*((?:19|20)\d\d)\s*$|\s((?:19|20)\d\d)\s*$""")
    private val junk = Regex("""\b(4k|uhd|fhd|hd|sd|hdr|hevc|x265|1080p|720p|2160p|multi|multisub|sub|subs|dub|dubbed|vostfr|imax|extended|remastered)\b""", RegexOption.IGNORE_CASE)

    /** (match key, year) for a title as playlists write it ("EN - The Batman (2022) 4K" → "the batman", 2022). */
    fun key(raw: String): Pair<String, String?> {
        var s = raw.replace(prefix, "").replace(junk, " ").trim()
        val m = yearRx.find(s)
        val year = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        if (m != null) s = s.removeRange(m.range)
        s = s.lowercase().replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        return s to year
    }

    private fun <T> pick(popular: List<Pair<String, String?>>, all: List<T>, name: (T) -> String, hasArt: (T) -> Boolean): List<T> {
        val index = HashMap<String, MutableList<Pair<T, String?>>>()
        all.forEach { v -> val (k, y) = key(name(v)); if (k.length >= 2) index.getOrPut(k) { ArrayList() } += v to y }
        val out = ArrayList<T>()
        val used = HashSet<String>()
        for ((title, year) in popular) {
            val k = key(title).first
            if (k in used) continue
            val cands = index[k] ?: continue
            // Same year when both are known (so a remake doesn't stand in for the new film); otherwise any.
            val sameYear = cands.filter { (_, y) -> year == null || y == null || kotlin.math.abs((y.toIntOrNull() ?: 0) - (year.toIntOrNull() ?: 0)) <= 1 }
            val best = sameYear.sortedByDescending { if (hasArt(it.first)) 1 else 0 }.firstOrNull()?.first ?: continue
            out += best; used += k
            if (out.size == 10) break
        }
        return out
    }

    suspend fun movies(c: Context, repo: Repo): Result<Vod> = withContext(Dispatchers.Default) {
        val (week, popular) = Stremio.popularTitles(c, "movie")
        Result(pick(popular, repo.allVods(), { it.name }, { it.poster != null }), week)
    }

    suspend fun series(c: Context, repo: Repo): Result<Series> = withContext(Dispatchers.Default) {
        val (week, popular) = Stremio.popularTitles(c, "series")
        Result(pick(popular, repo.allSeries(), { it.name }, { it.cover != null }), week)
    }
}
