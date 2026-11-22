package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** One source inside a collection folder: a TMDB query, an addon catalog, or a Trakt list. */
data class CSource(
    val title: String,
    val provider: String,          // "tmdb", "addon", "trakt"
    val tmdbType: String = "",     // DISCOVER, PERSON, COLLECTION, COMPANY
    val tmdbId: Long? = null,
    val sortBy: String = "popularity.desc",
    val mediaType: String = "MOVIE", // MOVIE / TV
    val filters: Map<String, String> = emptyMap(),
    val addonId: String = "", val catalogId: String = "", val type: String = "", val genre: String? = null,
)

data class CFolder(
    val id: String, val title: String, val cover: String?, val backdrop: String?, val logo: String?,
    val shape: String, val hideTitle: Boolean, val sources: List<CSource>,
)

data class CGroup(val id: String, val title: String, val folders: List<CFolder>)

/**
 * Nuvio collection files (like Kaptain's Collection): groups of folders with artwork, each folder made of
 * catalog sources. TMDB sources need a free TMDB key; addon sources need that addon installed.
 */
object Collections {
    private val http by lazy { com.dekstream.tv.DekStreamApp.instance.http }
    @Volatile private var loaded: Pair<String, List<CGroup>>? = null

    private fun cacheFile(c: Context) = File(c.filesDir, "collections.json")

    /** Loads (and caches on the device) the collection file set in Settings > Addons & Discover. */
    suspend fun groups(c: Context, refresh: Boolean = false): List<CGroup> = withContext(Dispatchers.IO) {
        val url = Stremio.collectionsUrl(c)
        if (url.isBlank()) return@withContext emptyList()
        loaded?.let { (u, g) -> if (u == url && !refresh) return@withContext g }
        val cached = cacheFile(c)
        val text = if (!refresh && cached.exists() && cached.readText().startsWith("[" ) && File(c.filesDir, "collections.url").takeIf { it.exists() }?.readText() == url)
            cached.readText()
        else http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            (r.body?.string() ?: "").also { cached.writeText(it); File(c.filesDir, "collections.url").writeText(url) }
        }
        val g = parse(text)
        loaded = url to g
        g
    }

    fun parse(text: String): List<CGroup> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("collections") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val fs = g.optJSONArray("folders") ?: return@mapNotNull null
            CGroup(g.optString("id"), g.optString("title"), (0 until fs.length()).mapNotNull { j ->
                val f = fs.optJSONObject(j) ?: return@mapNotNull null
                val srcArr = f.optJSONArray("catalogSources")?.takeIf { it.length() > 0 } ?: f.optJSONArray("sources") ?: JSONArray()
                CFolder(
                    f.optString("id"), f.optString("title"), f.optString("coverImageUrl").ifBlank { null },
                    f.optString("heroBackdropUrl").ifBlank { null }, f.optString("titleLogoUrl").ifBlank { null },
                    f.optString("tileShape", "POSTER"), f.optBoolean("hideTitle", false),
                    (0 until srcArr.length()).mapNotNull { k -> parseSource(srcArr.optJSONObject(k)) },
                )
            })
        }.filter { it.folders.isNotEmpty() }
    }

    private fun parseSource(o: JSONObject?): CSource? {
        o ?: return null
        val provider = o.optString("provider").ifBlank { if (o.has("addonId")) "addon" else return null }
        val filters = HashMap<String, String>()
        o.optJSONObject("filters")?.let { f -> f.keys().forEach { k -> if (!f.isNull(k)) filters[k] = f.get(k).toString() } }
        return CSource(
            title = o.optString("title").ifBlank { o.optString("catalogId") },
            provider = provider, tmdbType = o.optString("tmdbSourceType", "DISCOVER"),
            tmdbId = if (o.isNull("tmdbId")) null else o.optLong("tmdbId").takeIf { it > 0 },
            sortBy = o.optString("sortBy", "popularity.desc"), mediaType = o.optString("mediaType", "MOVIE"),
            filters = filters, addonId = o.optString("addonId"), catalogId = o.optString("catalogId"),
            type = o.optString("type"), genre = o.optString("genre").ifBlank { null },
        )
    }

    /** Titles for one source. Returns null when it can't be shown here (no TMDB key, addon missing, Trakt). */
    suspend fun items(c: Context, s: CSource): List<MetaPreview>? = when (s.provider) {
        "tmdb" -> tmdb(c, s)
        "addon" -> Stremio.addonCatalog(c, s.addonId, s.type.ifBlank { "movie" }, s.catalogId, s.genre) ?: fallback(c, s)
        else -> null
    }

    /**
     * No AIOMetadata / Netflix-catalog addon installed: fill those rows from TMDB and DekTV's own lists instead.
     */
    private suspend fun fallback(c: Context, s: CSource): List<MetaPreview>? {
        val id = s.catalogId.lowercase()
        val tv = s.type == "series"
        return when {
            id.contains("watchlist") -> Stremio.watchlistItems(c).filter { s.type == "all" || it.type == s.type }
            id.contains("upnext") || id.contains("unwatched") || id.contains("continue") -> continueItems(c)
            s.addonId.contains("netflix") -> tmdbRaw(c, if (tv) "tv" else "movie", "discover", mapOf("with_watch_providers" to "8", "watch_region" to "US", "sort_by" to "popularity.desc"), tv)
            id.contains("recommend") || id.contains("trending") || id.contains("popular") -> tmdbRaw(c, if (tv) "tv" else "movie", "trending/week", emptyMap(), tv)
            else -> null
        }
    }

    private fun continueItems(c: Context): List<MetaPreview> {
        val app = com.dekstream.tv.DekStreamApp.instance
        val prefs = runCatching { app.prefs }.getOrNull() ?: return emptyList()
        return prefs.progress.value.values.filter { it.key.startsWith("st:") }.sortedByDescending { it.updatedAt }.mapNotNull { p ->
            val parts = p.key.split(':')               // st:type:tt123[:s:e]
            if (parts.size < 3) null else MetaPreview(parts[2], parts[1], p.title, p.poster, null, null, p.subtitle, null, null, emptyList())
        }.distinctBy { it.id }
    }

    private suspend fun tmdbRaw(c: Context, kind: String, path: String, params: Map<String, String>, tv: Boolean): List<MetaPreview>? = withContext(Dispatchers.IO) {
        val key = Stremio.tmdbKey(c).ifBlank { return@withContext null }
        val url = "https://api.themoviedb.org/3/" + (if (path == "discover") "discover/$kind" else "$path".replace("trending/week", "trending/$kind/week")) +
            "?api_key=$key&language=en-US" + params.entries.joinToString("") { "&${it.key}=${it.value}" }
        val o = runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { r -> JSONObject(r.body?.string() ?: "{}") } }.getOrNull()
            ?: return@withContext emptyList()
        val arr = o.optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i -> tmdbPreview(arr.optJSONObject(i), tv) }
    }

    /** Why a source can't be shown, for the empty state. */
    fun unavailableReason(c: Context, s: CSource): String = when (s.provider) {
        "tmdb" -> "Add a free TMDB key in Settings → Addons & Discover"
        "addon" -> "Needs the \"${s.addonId}\" addon — add its URL in Settings → Addons & Discover"
        "trakt" -> "Trakt list — not supported yet"
        else -> "Not supported"
    }

    // ---------------- TMDB ----------------
    private suspend fun tmdb(c: Context, s: CSource): List<MetaPreview>? = withContext(Dispatchers.IO) {
        val key = Stremio.tmdbKey(c).ifBlank { return@withContext null }
        val tv = s.mediaType.equals("TV", true)
        val kind = if (tv) "tv" else "movie"
        fun sort(): String = when (s.sortBy) {
            "original", "" -> "popularity.desc"
            "primary_release_date.desc" -> if (tv) "first_air_date.desc" else s.sortBy
            "first_air_date.desc" -> if (tv) s.sortBy else "primary_release_date.desc"
            else -> s.sortBy
        }
        val q = linkedMapOf("api_key" to key, "language" to "en-US", "include_adult" to "false", "sort_by" to sort())
        s.filters["withGenres"]?.let { q["with_genres"] = it }
        s.filters["withNetworks"]?.let { q["with_networks"] = it }
        s.filters["withKeywords"]?.let { q["with_keywords"] = it }
        s.filters["withOriginalLanguage"]?.let { q["with_original_language"] = it }
        s.filters["voteCountGte"]?.let { q["vote_count.gte"] = it }
        s.filters["voteAverageGte"]?.let { q["vote_average.gte"] = it }
        s.filters["year"]?.let { q[if (tv) "first_air_date_year" else "primary_release_year"] = it }
        s.filters["releaseDateGte"]?.let { q[if (tv) "first_air_date.gte" else "primary_release_date.gte"] = it }
        s.filters["releaseDateLte"]?.let { q[if (tv) "first_air_date.lte" else "primary_release_date.lte"] = it }
        if (sort().startsWith("primary_release_date") || sort().startsWith("first_air_date"))
            q.putIfAbsent(if (tv) "first_air_date.lte" else "primary_release_date.lte", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
        val path = when (s.tmdbType) {
            "COLLECTION" -> "collection/${s.tmdbId}"
            "PERSON" -> { q[if (tv) "with_people" else "with_cast"] = s.tmdbId.toString(); "discover/$kind" }
            "COMPANY" -> { q["with_companies"] = s.tmdbId.toString(); "discover/$kind" }
            else -> "discover/$kind"
        }
        val url = "https://api.themoviedb.org/3/$path?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val o = runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { r -> JSONObject(r.body?.string() ?: "{}") } }.getOrNull()
            ?: return@withContext emptyList()
        val arr = o.optJSONArray("results") ?: o.optJSONArray("parts") ?: JSONArray()
        val out = (0 until arr.length()).mapNotNull { i -> tmdbPreview(arr.optJSONObject(i), tv) }
        if (s.tmdbType == "COLLECTION") out.sortedBy { it.year ?: "9999" } else out
    }

    private fun tmdbPreview(m: JSONObject?, tv: Boolean): MetaPreview? {
        m ?: return null
        val poster = m.optString("poster_path").ifBlank { return null }
        val date = m.optString(if (tv) "first_air_date" else "release_date").ifBlank { m.optString("release_date") }
        val upcoming = date.isNotBlank() && date > java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return MetaPreview(
            "tmdb:${if (tv) "tv" else "movie"}:${m.optLong("id")}", if (tv) "series" else "movie",
            m.optString(if (tv) "name" else "title").ifBlank { m.optString("title") },
            "https://image.tmdb.org/t/p/w342$poster",
            m.optString("backdrop_path").ifBlank { null }?.let { "https://image.tmdb.org/t/p/w1280$it" }, null,
            m.optString("overview").ifBlank { null }, date.take(4).ifBlank { null },
            m.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }, emptyList(),
            note = if (upcoming) "Coming " + date else null, upcoming = upcoming,
        )
    }

    /** TMDB title → its IMDb id (what Cinemeta and your addons use). */
    suspend fun imdbFor(c: Context, m: MetaPreview): String? = withContext(Dispatchers.IO) {
        val parts = m.id.split(':'); if (parts.size < 3) return@withContext null
        val key = Stremio.tmdbKey(c).ifBlank { return@withContext null }
        runCatching {
            http.newCall(Request.Builder().url("https://api.themoviedb.org/3/${parts[1]}/${parts[2]}/external_ids?api_key=$key").build()).execute()
                .use { r -> JSONObject(r.body?.string() ?: "{}").optString("imdb_id").ifBlank { null } }
        }.getOrNull()
    }
}
