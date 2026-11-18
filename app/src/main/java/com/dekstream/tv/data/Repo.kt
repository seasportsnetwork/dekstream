package com.dekstream.tv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Caching layer over [XtreamApi]. Everything is loaded lazily and kept in memory. */
class Repo(val api: XtreamApi, private val prefs: Prefs) {

    private val lock = Mutex()
    private var liveCats: List<Category>? = null
    private var vodCats: List<Category>? = null
    private var seriesCats: List<Category>? = null
    private var allLive: List<Channel>? = null

    private val vodByCat = ConcurrentHashMap<String, List<Vod>>()
    private val seriesByCat = ConcurrentHashMap<String, List<Series>>()
    private val epgShort = ConcurrentHashMap<Int, Pair<Long, List<Program>>>()
    private val epgFull = ConcurrentHashMap<Int, Pair<Long, List<Program>>>()

    suspend fun liveCategories() = lock.withLock { liveCats ?: api.liveCategories().also { liveCats = it } }
    suspend fun vodCategories() = lock.withLock { vodCats ?: api.vodCategories().also { vodCats = it } }
    suspend fun seriesCategories() = lock.withLock { seriesCats ?: api.seriesCategories().also { seriesCats = it } }

    /** All live channels (one big call, cached). */
    suspend fun allChannels(): List<Channel> {
        allLive?.let { return it }
        val list = api.liveStreams()
        allLive = list
        return list
    }

    suspend fun channels(categoryId: String): List<Channel> =
        allChannels().filter { it.categoryId == categoryId }

    suspend fun channelById(id: Int): Channel? = allChannels().firstOrNull { it.streamId == id }

    suspend fun vods(categoryId: String): List<Vod> =
        vodByCat[categoryId] ?: api.vodStreams(categoryId).also { vodByCat[categoryId] = it }

    suspend fun series(categoryId: String): List<Series> =
        seriesByCat[categoryId] ?: api.series(categoryId).also { seriesByCat[categoryId] = it }

    /** Now/next for a channel, cached 5 minutes. */
    suspend fun nowNext(streamId: Int): List<Program> {
        val now = System.currentTimeMillis()
        epgShort[streamId]?.let { (t, l) -> if (now - t < 5 * 60_000) return l }
        val l = runCatching { api.shortEpg(streamId, 3) }.getOrDefault(emptyList())
            .filter { it.endMs > now }
        epgShort[streamId] = now to l
        return l
    }

    /** Full EPG (past + future), cached 30 minutes. */
    suspend fun fullEpg(streamId: Int): List<Program> {
        val now = System.currentTimeMillis()
        epgFull[streamId]?.let { (t, l) -> if (now - t < 30 * 60_000) return l }
        val l = runCatching { api.fullEpg(streamId) }.getOrDefault(emptyList())
        epgFull[streamId] = now to l
        return l
    }

    fun liveRequest(c: Channel) = PlayRequest(
        url = api.liveUrl(c.streamId, prefs.liveFormat),
        title = c.name,
        isLive = true,
        poster = c.icon,
        channel = c,
    )

    fun catchupRequest(c: Channel, p: Program) = PlayRequest(
        url = api.catchupUrl(c.streamId, p, prefs.serverTimezone),
        title = p.title,
        subtitle = "${c.name} · Catch-up",
        isLive = false,
        poster = c.icon,
    )

    fun vodRequest(v: Vod): PlayRequest {
        val key = "vod:${v.streamId}"
        return PlayRequest(
            url = api.vodUrl(v),
            title = v.name,
            isLive = false,
            progressKey = key,
            poster = v.poster,
            startPositionMs = prefs.progress.value[key]?.positionMs ?: 0,
        )
    }

    fun episodeRequest(s: Series, e: Episode): PlayRequest {
        val key = "ep:${e.id}"
        return PlayRequest(
            url = api.episodeUrl(e),
            title = s.name,
            subtitle = "S${e.season} E${e.episodeNum} · ${e.title}",
            isLive = false,
            progressKey = key,
            poster = e.image ?: s.cover,
            startPositionMs = prefs.progress.value[key]?.positionMs ?: 0,
        )
    }

    /** Search across live channels plus any VOD/series categories already loaded. */
    suspend fun searchChannels(q: String): List<Channel> {
        val n = q.trim().lowercase()
        if (n.length < 2) return emptyList()
        return allChannels().filter { it.name.lowercase().contains(n) }.take(200)
    }

    fun searchLoadedVod(q: String): List<Vod> {
        val n = q.trim().lowercase()
        if (n.length < 2) return emptyList()
        return vodByCat.values.flatten().distinctBy { it.streamId }.filter { it.name.lowercase().contains(n) }.take(100)
    }

    fun searchLoadedSeries(q: String): List<Series> {
        val n = q.trim().lowercase()
        if (n.length < 2) return emptyList()
        return seriesByCat.values.flatten().distinctBy { it.seriesId }.filter { it.name.lowercase().contains(n) }.take(100)
    }
}
