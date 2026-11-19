package com.dekstream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class XtreamApi(private val http: OkHttpClient, val account: Account) {

    private suspend fun call(action: String?, vararg params: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val b = "${account.base}/player_api.php".toHttpUrl().newBuilder()
                .addQueryParameter("username", account.username)
                .addQueryParameter("password", account.password)
            if (action != null) b.addQueryParameter("action", action)
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            val req = Request.Builder().url(b.build()).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
                resp.body?.string() ?: ""
            }
        }

    /** Returns account info if login works, throws otherwise. */
    suspend fun login(): AccountInfo {
        val o = parseObject(call(null))
        val user = o.optJSONObject("user_info") ?: throw IOException("Server didn't answer like an Xtream panel")
        if (user.i("auth") != 1) throw IOException("Wrong username or password")
        val server = o.optJSONObject("server_info")
        return AccountInfo(
            status = user.s("status") ?: "Unknown",
            expiresAtMs = user.l("exp_date")?.times(1000),
            maxConnections = user.i("max_connections"),
            activeConnections = user.i("active_cons"),
            timezone = server?.s("timezone"),
        )
    }

    private fun categories(body: String): List<Category> {
        val out = ArrayList<Category>()
        parseArray(body).objects { o ->
            val id = o.s("category_id") ?: return@objects
            out += Category(id, o.s("category_name") ?: "Other")
        }
        return out
    }

    suspend fun liveCategories() = categories(call("get_live_categories"))
    suspend fun vodCategories() = categories(call("get_vod_categories"))
    suspend fun seriesCategories() = categories(call("get_series_categories"))

    suspend fun liveStreams(categoryId: String? = null): List<Channel> {
        val body = if (categoryId == null) call("get_live_streams") else call("get_live_streams", "category_id" to categoryId)
        val out = ArrayList<Channel>()
        parseArray(body).objects { o ->
            val id = o.i("stream_id", -1)
            if (id < 0) return@objects
            out += Channel(
                streamId = id,
                num = o.i("num"),
                name = o.s("name") ?: "Channel $id",
                icon = o.s("stream_icon"),
                categoryId = o.s("category_id") ?: "",
                epgChannelId = o.s("epg_channel_id"),
                hasArchive = o.bool("tv_archive"),
                archiveDays = o.i("tv_archive_duration"),
            )
        }
        return out
    }

    suspend fun vodStreams(categoryId: String): List<Vod> {
        val out = ArrayList<Vod>()
        parseArray(call("get_vod_streams", "category_id" to categoryId)).objects { o ->
            val id = o.i("stream_id", -1)
            if (id < 0) return@objects
            out += Vod(
                streamId = id,
                name = o.s("name") ?: "Movie",
                poster = o.s("stream_icon"),
                categoryId = o.s("category_id") ?: categoryId,
                ext = o.s("container_extension") ?: "mp4",
                rating = o.s("rating"),
            )
        }
        return out
    }

    suspend fun vodInfo(id: Int): VodInfo {
        val o = parseObject(call("get_vod_info", "vod_id" to id.toString()))
        val info = o.optJSONObject("info") ?: org.json.JSONObject()
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }
            ?: info.s("backdrop_path")?.takeIf { it.startsWith("http") }
        return VodInfo(
            plot = info.s("plot") ?: info.s("description"),
            genre = info.s("genre"),
            cast = info.s("cast") ?: info.s("actors"),
            director = info.s("director"),
            releaseDate = info.s("releasedate") ?: info.s("release_date"),
            duration = info.s("duration"),
            backdrop = backdrop ?: info.s("movie_image"),
            rating = info.s("rating"),
        )
    }

    suspend fun series(categoryId: String): List<Series> {
        val out = ArrayList<Series>()
        parseArray(call("get_series", "category_id" to categoryId)).objects { o ->
            val id = o.i("series_id", -1)
            if (id < 0) return@objects
            out += Series(
                seriesId = id,
                name = o.s("name") ?: "Series",
                cover = o.s("cover"),
                categoryId = o.s("category_id") ?: categoryId,
                plot = o.s("plot"),
                rating = o.s("rating"),
                genre = o.s("genre"),
                releaseDate = o.s("releaseDate") ?: o.s("release_date"),
                backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") },
            )
        }
        return out
    }

    suspend fun seriesInfo(id: Int): SeriesInfo {
        val o = parseObject(call("get_series_info", "series_id" to id.toString()))
        val info = o.optJSONObject("info") ?: org.json.JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        val eps = o.optJSONObject("episodes")
        if (eps != null) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = eps.optJSONArray(k) ?: continue
                val list = ArrayList<Episode>()
                arr.objects { e ->
                    val epInfo = e.optJSONObject("info")
                    list += Episode(
                        id = e.s("id") ?: return@objects,
                        season = e.i("season", k.toIntOrNull() ?: 0),
                        episodeNum = e.i("episode_num"),
                        title = e.s("title") ?: "Episode",
                        ext = e.s("container_extension") ?: "mp4",
                        plot = epInfo?.s("plot"),
                        image = epInfo?.s("movie_image"),
                        duration = epInfo?.s("duration"),
                    )
                }
                seasons[k.toIntOrNull() ?: list.firstOrNull()?.season ?: 0] = list.sortedBy { it.episodeNum }
            }
        }
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }
        return SeriesInfo(
            plot = info.s("plot"),
            backdrop = backdrop ?: info.s("cover"),
            genre = info.s("genre"),
            cast = info.s("cast"),
            seasons = seasons,
        )
    }

    private fun programs(body: String, key: String): List<Program> {
        val o = parseObject(body)
        val arr = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Program>()
        arr.objects { p ->
            val start = p.l("start_timestamp") ?: return@objects
            val stop = p.l("stop_timestamp") ?: return@objects
            out += Program(
                title = maybeB64(p.s("title")).ifBlank { "No information" },
                description = maybeB64(p.s("description")),
                startMs = start * 1000,
                endMs = stop * 1000,
                hasArchive = p.bool("has_archive"),
            )
        }
        return out.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    suspend fun shortEpg(streamId: Int, limit: Int = 4): List<Program> =
        programs(call("get_short_epg", "stream_id" to streamId.toString(), "limit" to limit.toString()), "epg_listings")

    suspend fun fullEpg(streamId: Int): List<Program> =
        programs(call("get_simple_data_table", "stream_id" to streamId.toString()), "epg_listings")

    // --- Stream URLs ---

    fun liveUrl(streamId: Int, format: String) =
        "${account.base}/live/${account.username}/${account.password}/$streamId.$format"

    fun vodUrl(v: Vod) = "${account.base}/movie/${account.username}/${account.password}/${v.streamId}.${v.ext}"

    fun episodeUrl(e: Episode) = "${account.base}/series/${account.username}/${account.password}/${e.id}.${e.ext}"

    /** Catch-up (timeshift) URL. Start time is formatted in the panel's own timezone. */
    fun catchupUrl(streamId: Int, p: Program, serverTz: String?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        fmt.timeZone = serverTz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val minutes = ((p.endMs - p.startMs) / 60000).coerceAtLeast(1)
        return "${account.base}/timeshift/${account.username}/${account.password}/$minutes/${fmt.format(p.startMs)}/$streamId.ts"
    }

    companion object {
        const val USER_AGENT = "DekStream/1.0 (Linux; Android)"
    }
}
