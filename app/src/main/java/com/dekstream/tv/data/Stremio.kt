package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A title in a catalog row. */
data class MetaPreview(
    val id: String, val type: String, val name: String,
    val poster: String?, val background: String?, val logo: String?,
    val description: String?, val year: String?, val rating: String?, val genres: List<String>,
    /** Extra line for rows like "New episodes" ("S2 E5 · Today"). */
    val note: String? = null,
    /** Not out yet (Coming soon): no streams to look up. */
    val upcoming: Boolean = false,
)

data class Episode2(val id: String, val season: Int, val episode: Int, val title: String, val released: Long?, val thumbnail: String?, val overview: String?)

data class MetaDetail(
    val preview: MetaPreview, val runtime: String?, val cast: List<String>, val director: String?,
    val episodes: List<Episode2>, val trailer: String?,
    val writers: List<String> = emptyList(), val tmdbId: String? = null,
)

/** Cast, crew and reviews from TMDB (needs a key) for the movie / series page. */
data class CastMember(val name: String, val character: String?, val photo: String?)
data class Review(val author: String, val rating: Double?, val content: String, val created: String?)
data class Trailer(val name: String, val youtubeId: String)
data class TitleExtras(val cast: List<CastMember>, val directors: List<String>, val writers: List<String>, val reviews: List<Review>,
    val trailers: List<Trailer> = emptyList())

/** A playable source an addon offered. */
data class StreamItem(val addon: String, val name: String, val title: String, val url: String) {
    /** "4K", "1080p" … pulled from the addon's labels. */
    val quality: String? get() = Regex("(?i)\\b(2160p|4k|uhd|1080p|720p|480p)\\b").find("$name $title")?.value?.uppercase()?.let {
        when (it) { "2160P", "UHD" -> "4K"; else -> it.lowercase().replace("4k", "4K") }
    }
    val size: String? get() = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s?(GB|MB)").find(title)?.value
    val tags: List<String> get() = listOfNotNull(
        if (Regex("(?i)\\b(dv|dolby ?vision)\\b").containsMatchIn("$name $title")) "DV" else null,
        if (Regex("(?i)\\bhdr(10)?\\+?\\b").containsMatchIn("$name $title")) "HDR" else null,
        if (Regex("(?i)\\b(atmos)\\b").containsMatchIn("$name $title")) "Atmos" else null,
        if (Regex("(?i)\\bremux\\b").containsMatchIn("$name $title")) "Remux" else null,
    )
}

/** An installed Stremio-style addon. */
data class Addon(val url: String, val name: String, val resources: Set<String>, val types: Set<String>, val idPrefixes: List<String>, val id: String = "")

/**
 * Discover: Stremio addons (your Usenet Ultimate for streams) plus free catalogs — Cinemeta for popular,
 * new and top-rated titles, TVmaze for new episodes and premieres, and TMDB for upcoming movies when a key is set.
 */
object Stremio {
    const val CINEMETA = "https://v3-cinemeta.strem.io"
    /** Kaptain's official Nuvio mega collection (11 categories, 356 folders). */
    const val KAPTAIN = "https://imkaptain.github.io/nuvio-assets/nuvio-share-hub/collections/nuvio_mega_collection.json"

    private val http: OkHttpClient by lazy { com.dekstream.tv.DekStreamApp.instance.http }
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ---------------- Settings ----------------
    private fun sp(c: Context) = c.getSharedPreferences("discover", Context.MODE_PRIVATE)
    fun addonUrls(c: Context): List<String> =
        sp(c).getString("addons", null)?.split('\n')?.map { normalizeManifest(it) }?.filter { it.startsWith("http") } ?: emptyList()

    /** Accepts what people paste: the addon page, a configured path without manifest.json, or stremio:// links. */
    fun normalizeManifest(raw: String): String {
        var u = raw.trim().replace(Regex("^stremio://"), "https://")
        if (u.isBlank()) return u
        if (!u.startsWith("http")) u = "https://$u"
        u = u.substringBefore('#').substringBefore('?')
        if (u.endsWith("/configure")) u = u.removeSuffix("/configure")
        return if (u.endsWith("manifest.json")) u else u.trimEnd('/') + "/manifest.json"
    }
    fun setAddonUrls(c: Context, urls: List<String>) {
        sp(c).edit().putString("addons", urls.map { normalizeManifest(it) }.filter { it.startsWith("http") }.joinToString("\n")).apply(); manifests.clear()
    }
    /** Movies & series from your IPTV provider too (false = addons only: IPTV Movies/Series hidden everywhere). */
    /** Addons (Stremio streams, e.g. Usenet Ultimate) are switched off for now: none load, playlist movies always show. */
    const val ADDONS_ENABLED = false

    fun iptvVod(c: Context): Boolean = !ADDONS_ENABLED || sp(c).getBoolean("iptvVod", true)
    fun setIptvVod(c: Context, on: Boolean) { sp(c).edit().putBoolean("iptvVod", on).apply() }
    fun collectionsUrl(c: Context): String = sp(c).getString("collections", "") ?: ""
    fun setCollectionsUrl(c: Context, u: String) { sp(c).edit().putString("collections", u.trim()).apply() }

    fun tmdbKey(c: Context): String = sp(c).getString("tmdb", "") ?: ""
    fun setTmdbKey(c: Context, k: String) { sp(c).edit().putString("tmdb", k.trim()).apply() }
    fun watchlist(c: Context): List<String> = sp(c).getString("list", "")!!.split('\n').filter { it.contains('|') }
    fun inWatchlist(c: Context, m: MetaPreview) = watchlist(c).any { it.startsWith(m.type + "|" + m.id + "|") }
    fun toggleWatchlist(c: Context, m: MetaPreview) {
        val l = watchlist(c).toMutableList()
        val i = l.indexOfFirst { it.startsWith(m.type + "|" + m.id + "|") }
        if (i >= 0) l.removeAt(i) else l.add(0, listOf(m.type, m.id, m.name.replace('|', ' '), m.poster ?: "").joinToString("|"))
        sp(c).edit().putString("list", l.take(200).joinToString("\n")).apply()
    }
    fun watchlistItems(c: Context): List<MetaPreview> = watchlist(c).mapNotNull {
        val p = it.split('|'); if (p.size < 3) null
        else MetaPreview(p[1], p[0], p[2], p.getOrNull(3)?.ifBlank { null }, null, null, null, null, null, emptyList())
    }

    // ---------------- HTTP ----------------
    private suspend fun getText(url: String, ttlMs: Long = 30 * 60_000L): String = withContext(Dispatchers.IO) {
        cache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < ttlMs) return@withContext body }
        http.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body?.string() ?: ""
            cache[url] = System.currentTimeMillis() to body
            body
        }
    }
    private suspend fun getJson(url: String, ttlMs: Long = 30 * 60_000L) = JSONObject(getText(url, ttlMs))

    /**
     * TMDB gives two kinds of key: the short "API Key" (sent as api_key=) and the long "Read Access Token"
     * (starts with eyJ, must be sent as a Bearer header). Either works here.
     */
    private fun request(url: String): Request {
        val b = Request.Builder().header("User-Agent", "DekTV")
        val u = url.toHttpUrlOrNull()
        val key = u?.queryParameter("api_key")
        return if (u != null && u.host == "api.themoviedb.org" && key != null && key.startsWith("eyJ")) {
            b.url(u.newBuilder().removeAllQueryParameters("api_key").build()).header("Authorization", "Bearer $key").build()
        } else b.url(url).build()
    }

    // ---------------- Addons ----------------
    private val manifests = ConcurrentHashMap<String, Addon>()
    private fun base(url: String) = url.trim().removeSuffix("/manifest.json").removeSuffix("/")

    suspend fun addons(c: Context): List<Addon> = if (!ADDONS_ENABLED) emptyList() else coroutineScope {
        addonUrls(c).map { u ->
            async {
                manifests[u] ?: runCatching {
                    val m = getJson(u, 6 * 3600_000L)
                    val res = HashSet<String>()
                    m.optJSONArray("resources")?.let { a ->
                        for (i in 0 until a.length()) res += (a.optJSONObject(i)?.optString("name") ?: a.optString(i))
                    }
                    Addon(u, m.optString("name", "Addon"), res, m.optJSONArray("types").strings().toSet(), m.optJSONArray("idPrefixes").strings(), m.optString("id"))
                        .also { manifests[u] = it }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /** Streams for a movie ("tt123") or an episode ("tt123:1:2") from every addon that has streams. */
    suspend fun streams(c: Context, type: String, id: String): List<StreamItem> = coroutineScope {
        addons(c).filter { "stream" in it.resources && (it.types.isEmpty() || type in it.types) &&
            (it.idPrefixes.isEmpty() || it.idPrefixes.any { p -> id.startsWith(p) }) }.map { a ->
            async {
                runCatching {
                    val o = getJson("${base(a.url)}/stream/$type/${enc(id)}.json", 5 * 60_000L)
                    val arr = o.optJSONArray("streams") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val s = arr.getJSONObject(i)
                        val url = s.optString("url").ifBlank { null } ?: return@mapNotNull null
                        StreamItem(a.name, s.optString("name").ifBlank { a.name }, s.optString("title").ifBlank { s.optString("description") }, url)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /** A catalog from one of your installed addons, found by its manifest id (e.g. "aio-metadata"). */
    suspend fun addonCatalog(c: Context, addonId: String, type: String, catalogId: String, genre: String?): List<MetaPreview>? {
        val a = addons(c).firstOrNull { it.id == addonId } ?: return null
        val g = genre?.takeIf { it.isNotBlank() && it != "None" }?.let { "/genre=${enc(it)}" } ?: ""
        val arr = runCatching { getJson("${base(a.url)}/catalog/$type/${enc(catalogId)}$g.json").optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parsePreview(arr.optJSONObject(it), type) }
    }

    // ---------------- Cinemeta catalogs ----------------
    suspend fun catalog(type: String, id: String, genre: String? = null, skip: Int = 0): List<MetaPreview> {
        val extra = listOfNotNull(genre?.let { "genre=${enc(it)}" }, if (skip > 0) "skip=$skip" else null).joinToString("&")
        val url = "$CINEMETA/catalog/$type/$id" + (if (extra.isNotEmpty()) "/$extra" else "") + ".json"
        val arr = runCatching { getJson(url).optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parsePreview(arr.optJSONObject(it), type) }.filter { it.poster != null }
    }

    /** Cinemeta search (used to look up IPTV movies by name). */
    suspend fun search(type: String, query: String): List<MetaPreview> {
        val arr = runCatching { getJson("$CINEMETA/catalog/$type/top/search=${enc(query)}.json", 24 * 3600_000L).optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parsePreview(arr.optJSONObject(it), type) }
    }

    /**
     * Finds full details (rating, cast, artwork…) for an IPTV title by name.
     * Provider names look like "EN - The Batman (2022) 4K", so the noise is stripped first.
     */
    suspend fun lookup(type: String, rawName: String): MetaDetail? {
        val year = Regex("\\b(19|20)\\d{2}\\b").findAll(rawName).lastOrNull()?.value
        val clean = rawName
            .replace(Regex("^\\s*[A-Z]{2,3}\\s*[-|:]\\s*"), "")          // "EN - "
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")              // [4K] (2022)
            .replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|hevc|x265|h264|multi|sub|dub|vod)\\b"), " ")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            .replace(Regex("[._]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '|', ':')
        if (clean.length < 2) return null
        val hits = search(type, clean)
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9]"), "")
        val best = hits.firstOrNull { norm(it.name) == norm(clean) && (year == null || it.year?.startsWith(year) == true) }
            ?: hits.firstOrNull { year != null && it.year?.startsWith(year) == true && norm(it.name).contains(norm(clean)) }
            ?: hits.firstOrNull { norm(it.name) == norm(clean) }
            ?: hits.firstOrNull()?.takeIf { year == null || it.year?.startsWith(year) == true }
            ?: return null
        return meta(type, best.id)
    }

    suspend fun meta(type: String, id: String): MetaDetail? = runCatching {
        val m = getJson("$CINEMETA/meta/$type/${enc(id)}.json", 6 * 3600_000L).getJSONObject("meta")
        val p = parsePreview(m, type) ?: return null
        val vids = m.optJSONArray("videos")
        val eps = if (vids == null) emptyList() else (0 until vids.length()).mapNotNull { i ->
            val v = vids.getJSONObject(i)
            val s = v.optInt("season", -1); val e = v.optInt("episode", v.optInt("number", -1))
            if (s < 0 || e < 0) null else Episode2(v.optString("id"), s, e, v.optString("title", v.optString("name")).ifBlank { "Episode $e" },
                parseDate(v.optString("released")), v.optString("thumbnail").ifBlank { null }, v.optString("overview", v.optString("description")).ifBlank { null })
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        MetaDetail(p, m.optString("runtime").ifBlank { null }, m.optJSONArray("cast").strings().take(8),
            m.optJSONArray("director").strings().firstOrNull(), eps,
            m.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")?.ifBlank { null },
            m.optJSONArray("writer").strings().take(4), m.optString("moviedb_id").ifBlank { null }?.takeIf { it != "0" })
    }.getOrNull()

    private fun parsePreview(o: JSONObject?, fallbackType: String): MetaPreview? {
        o ?: return null
        val id = o.optString("imdb_id").ifBlank { o.optString("id") }.ifBlank { return null }
        return MetaPreview(
            id, o.optString("type", fallbackType), o.optString("name").ifBlank { return null },
            o.optString("poster").ifBlank { null }, o.optString("background").ifBlank { null }, o.optString("logo").ifBlank { null },
            o.optString("description").ifBlank { null }, o.optString("releaseInfo", o.optString("year")).ifBlank { null },
            o.optString("imdbRating").ifBlank { null }, o.optJSONArray("genres").strings().ifEmpty { o.optJSONArray("genre").strings() },
        )
    }

    // ---------------- TVmaze: new episodes and premieres ----------------
    private fun day(offset: Int): String = Calendar.getInstance().let { it.add(Calendar.DAY_OF_YEAR, offset); SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.time) }

    private data class TvEp(val imdb: String, val show: String, val image: String?, val season: Int, val number: Int, val airMs: Long, val summary: String?, val genres: List<String>)

    private suspend fun tvDay(offset: Int): List<TvEp> = coroutineScope {
        val d = day(offset)
        listOf("https://api.tvmaze.com/schedule/web?date=$d&country=US", "https://api.tvmaze.com/schedule?country=US&date=$d").map { u ->
            async {
                runCatching {
                    val arr = JSONArray(getText(u, 3 * 3600_000L))
                    (0 until arr.length()).mapNotNull { i ->
                        val e = arr.getJSONObject(i)
                        val show = e.optJSONObject("show") ?: e.optJSONObject("_embedded")?.optJSONObject("show") ?: return@mapNotNull null
                        val imdb = show.optJSONObject("externals")?.optString("imdb")?.ifBlank { null } ?: return@mapNotNull null
                        TvEp(imdb, show.optString("name"), show.optJSONObject("image")?.optString("original")?.ifBlank { null },
                            e.optInt("season"), e.optInt("number"), parseDate(e.optString("airstamp")) ?: 0L,
                            show.optString("summary").replace(Regex("<[^>]+>"), "").ifBlank { null }, show.optJSONArray("genres").strings())
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /** Shows with an episode out today or yesterday (popular-ish: ones TVmaze has artwork for). */
    suspend fun newEpisodes(): List<MetaPreview> = coroutineScope {
        listOf(0, -1).map { async { tvDay(it) } }.awaitAll().flatten()
            .filter { it.image != null }.distinctBy { it.imdb }.take(30).map { e ->
                MetaPreview(e.imdb, "series", e.show, e.image, null, null, e.summary, null, null, e.genres,
                    note = "S${e.season} E${e.number} · " + relDay(e.airMs))
            }
    }

    /** Season premieres in the next 10 days. */
    suspend fun premieres(): List<MetaPreview> = coroutineScope {
        (1..10).map { async { tvDay(it) } }.awaitAll().flatten()
            .filter { it.number == 1 && it.image != null }.distinctBy { it.imdb }.sortedBy { it.airMs }.take(30).map { e ->
                MetaPreview(e.imdb, "series", e.show, e.image, null, null, e.summary, null, null, e.genres,
                    note = (if (e.season == 1) "New series" else "Season ${e.season}") + " · " + relDay(e.airMs))
            }
    }

    // ---------------- TMDB: movies coming soon (needs a free key) ----------------
    suspend fun upcomingMovies(c: Context): List<MetaPreview> {
        val key = tmdbKey(c).ifBlank { return emptyList() }
        return runCatching {
            val o = getJson("https://api.themoviedb.org/3/movie/upcoming?region=US&language=en-US&api_key=${enc(key)}", 6 * 3600_000L)
            val arr = o.optJSONArray("results") ?: return emptyList()
            val today = System.currentTimeMillis()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.getJSONObject(i)
                val rel = parseDate(m.optString("release_date")) ?: return@mapNotNull null
                if (rel < today - 86_400_000L) return@mapNotNull null
                MetaPreview("tmdb:" + m.optInt("id"), "movie", m.optString("title"),
                    m.optString("poster_path").ifBlank { null }?.let { "https://image.tmdb.org/t/p/w500$it" },
                    m.optString("backdrop_path").ifBlank { null }?.let { "https://image.tmdb.org/t/p/w1280$it" }, null,
                    m.optString("overview").ifBlank { null }, m.optString("release_date").take(4), null, emptyList(),
                    note = "In theaters " + SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(rel)), upcoming = true)
            }.sortedBy { it.note }
        }.getOrDefault(emptyList())
    }

    /**
     * What's popular right now, most popular first, as (title, year): TMDB's weekly trending when a key is
     * set ("this week"), otherwise Cinemeta's popular list. type = "movie" or "series".
     */
    suspend fun popularTitles(c: Context, type: String): Pair<Boolean, List<Pair<String, String?>>> {
        val key = tmdbKey(c)
        if (key.isNotBlank()) {
            val t = if (type == "movie") "movie" else "tv"
            val list = (1..3).flatMap { page ->
                runCatching {
                    val arr = getJson("https://api.themoviedb.org/3/trending/$t/week?language=en-US&page=$page&api_key=${enc(key)}", 6 * 3600_000L)
                        .optJSONArray("results") ?: return@runCatching emptyList()
                    (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        val name = m.optString(if (t == "movie") "title" else "name")
                        name to m.optString(if (t == "movie") "release_date" else "first_air_date").take(4).ifBlank { null }
                    }
                }.getOrDefault(emptyList())
            }
            if (list.isNotEmpty()) return true to list
        }
        val list = listOf(0, 50, 100).flatMap { skip -> runCatching { catalog(type, "top", skip = skip) }.getOrDefault(emptyList()) }
            .map { it.name to it.year?.take(4) }
        return false to list
    }

    /**
     * TMDB cast (with photos and characters), director/creators, writers and reviews. Finds the title by
     * TMDB id, then IMDb id, then name + year. Null without a key or when nothing matches.
     */
    suspend fun extras(c: Context, type: String, tmdbId: String?, imdbId: String?, title: String, year: String?): TitleExtras? = runCatching {
        val key = tmdbKey(c).ifBlank { return null }
        val tv = type != "movie"
        val kind = if (tv) "tv" else "movie"
        val day = 24 * 3600_000L
        val id = tmdbId?.filter(Char::isDigit)?.ifBlank { null }
            ?: imdbId?.takeIf { it.startsWith("tt") }?.let { im ->
                getJson("https://api.themoviedb.org/3/find/${enc(im)}?external_source=imdb_id&api_key=${enc(key)}", day)
                    .optJSONArray(if (tv) "tv_results" else "movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
            }
            ?: run {
                val q = enc(title)
                val y = year?.take(4)?.let { if (tv) "&first_air_date_year=$it" else "&year=$it" } ?: ""
                getJson("https://api.themoviedb.org/3/search/$kind?query=$q$y&api_key=${enc(key)}", day)
                    .optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
            } ?: return null
        val credits = if (tv) "aggregate_credits" else "credits"
        val o = getJson("https://api.themoviedb.org/3/$kind/$id?append_to_response=$credits,reviews,videos&api_key=${enc(key)}", day)
        val cr = o.optJSONObject(credits)
        val castArr = cr?.optJSONArray("cast")
        val cast = if (castArr == null) emptyList() else (0 until minOf(castArr.length(), 20)).map { i ->
            val p = castArr.getJSONObject(i)
            val character = if (tv) p.optJSONArray("roles")?.optJSONObject(0)?.optString("character") else p.optString("character")
            CastMember(p.optString("name"), character?.ifBlank { null },
                p.optString("profile_path").ifBlank { null }?.let { "https://image.tmdb.org/t/p/w185$it" })
        }.filter { it.name.isNotBlank() }
        val crew = cr?.optJSONArray("crew")
        fun crewWhere(test: (JSONObject) -> Boolean): List<String> = if (crew == null) emptyList()
            else (0 until crew.length()).map { crew.getJSONObject(it) }.filter(test).map { it.optString("name") }.filter { it.isNotBlank() }.distinct()
        val directors = if (tv) o.optJSONArray("created_by")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
            else crewWhere { it.optString("job") == "Director" }
        val writers = crewWhere { it.optString("department") == "Writing" && (it.optString("job") in setOf("Screenplay", "Writer", "Story", "Novel", "Teleplay") ||
            it.optJSONArray("jobs")?.optJSONObject(0)?.optString("job") in setOf("Writer", "Screenplay", "Story", "Teleplay")) }.take(4)
        val rv = o.optJSONObject("reviews")?.optJSONArray("results")
        val reviews = if (rv == null) emptyList() else (0 until minOf(rv.length(), 12)).map { i ->
            val r = rv.getJSONObject(i)
            Review(r.optString("author").ifBlank { "Viewer" },
                r.optJSONObject("author_details")?.optDouble("rating")?.takeIf { !it.isNaN() && it > 0 },
                r.optString("content").replace(Regex("[*_#>]+"), "").replace(Regex("\\s+"), " ").trim(),
                r.optString("created_at").take(10).ifBlank { null })
        }.filter { it.content.isNotBlank() }
        val vids = o.optJSONObject("videos")?.optJSONArray("results")
        val trailers = if (vids == null) emptyList() else (0 until vids.length()).map { vids.getJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") && it.optString("key").isNotBlank() }
            .sortedWith(compareBy({ if (it.optString("type") == "Trailer") 0 else 1 }, { if (it.optBoolean("official")) 0 else 1 }))
            .map { Trailer(it.optString("name").ifBlank { "Trailer" }, it.optString("key")) }.distinctBy { it.youtubeId }.take(6)
        TitleExtras(cast, directors.filter { it.isNotBlank() }.take(3), writers, reviews, trailers)
    }.getOrNull()

    /** True if TMDB accepts the key (v3 API key). */
    suspend fun testTmdbKey(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request("https://api.themoviedb.org/3/configuration?api_key=${enc(key.trim())}")).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // ---------------- helpers ----------------
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")
    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd"))
            runCatching { return SimpleDateFormat(f, Locale.US).parse(s.trim())?.time }
        return null
    }
    private fun relDay(ms: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR); val year = c.get(Calendar.YEAR)
        c.timeInMillis = ms
        val diff = (c.get(Calendar.YEAR) - year) * 365 + c.get(Calendar.DAY_OF_YEAR) - today
        return when (diff) { 0 -> "Today"; -1 -> "Yesterday"; 1 -> "Tomorrow"; else -> SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(java.util.Date(ms)) }
    }
}
