package com.dekstream.tv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Caching layer over whichever [Provider] the current profile uses. */
class Repo(val provider: Provider, private val prefs: Prefs, private val epg: EpgIndex) {

    val source: Source get() = provider.source

    private val lock = Mutex()
    private val allLock = Mutex()
    private var liveCats: List<Category>? = null
    private var vodCats: List<Category>? = null
    private var seriesCats: List<Category>? = null
    private var allLive: List<Channel>? = null

    private val vodByCat = ConcurrentHashMap<String, List<Vod>>()
    private val seriesByCat = ConcurrentHashMap<String, List<Series>>()
    private val epgShort = ConcurrentHashMap<Int, Pair<Long, List<Program>>>()
    private val epgFull = ConcurrentHashMap<Int, Pair<Long, List<Program>>>()

    suspend fun liveCategories() = lock.withLock { liveCats ?: provider.liveCategories().also { liveCats = it } }
    suspend fun vodCategories() = lock.withLock { vodCats ?: provider.vodCategories().also { vodCats = it } }
    suspend fun seriesCategories() = lock.withLock { seriesCats ?: provider.seriesCategories().also { seriesCats = it } }

    /** All live channels (one big call, cached). */
    suspend fun allChannels(): List<Channel> = allLock.withLock {
        allLive ?: provider.allChannels().also { allLive = it }
    }

    suspend fun channels(categoryId: String): List<Channel> = allChannels().filter { it.categoryId == categoryId }

    suspend fun vods(categoryId: String): List<Vod> =
        vodByCat[categoryId] ?: provider.vods(categoryId).also { vodByCat[categoryId] = it }

    suspend fun series(categoryId: String): List<Series> =
        seriesByCat[categoryId] ?: provider.series(categoryId).also { seriesByCat[categoryId] = it }

    suspend fun vodInfo(v: Vod) = provider.vodInfo(v)
    suspend fun seriesInfo(s: Series) = provider.seriesInfo(s)

    /** Now/next for a channel, cached 5 minutes. */
    suspend fun nowNext(c: Channel): List<Program> {
        val now = System.currentTimeMillis()
        epgShort[c.streamId]?.let { (t, l) -> if (now - t < 5 * 60_000) return l }
        val l = runCatching { provider.shortEpg(c) }.getOrDefault(emptyList()).filter { it.endMs > now }
        epgShort[c.streamId] = now to l
        return l
    }

    /** Full EPG (past + future), cached 30 minutes. */
    suspend fun fullEpg(c: Channel): List<Program> {
        val now = System.currentTimeMillis()
        epgFull[c.streamId]?.let { (t, l) -> if (now - t < 30 * 60_000) return l }
        val l = runCatching { provider.fullEpg(c) }.getOrDefault(emptyList())
        epgFull[c.streamId] = now to l
        return l
    }

    /** The whole-guide index (XMLTV) for sports matching, when this source has one. */
    suspend fun guideIndex(): EpgIndex? {
        if (provider is M3uProvider) allChannels() // playlist header may carry the guide URL
        val url = provider.xmltvUrl() ?: return null
        return runCatching { epg.ensure(provider.cacheKey, url); epg }.getOrNull()
    }

    fun liveRequest(c: Channel) = PlayRequest(
        url = provider.liveUrl(c, prefs.liveFormat),
        title = c.name,
        isLive = true,
        poster = c.icon,
        channel = c,
    )

    fun catchupRequest(c: Channel, p: Program): PlayRequest? {
        val url = provider.catchupUrl(c, p, prefs.serverTimezone) ?: return null
        return PlayRequest(url = url, title = p.title, subtitle = "${c.name} · Catch-up", isLive = false, poster = c.icon, channel = c)
    }

    fun vodRequest(v: Vod): PlayRequest {
        val key = "vod:${v.streamId}"
        return PlayRequest(
            url = provider.vodUrl(v),
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
            url = provider.episodeUrl(e),
            title = s.name,
            subtitle = "S${e.season} E${e.episodeNum} · ${e.title}",
            isLive = false,
            progressKey = key,
            poster = e.image ?: s.cover,
            startPositionMs = prefs.progress.value[key]?.positionMs ?: 0,
        )
    }

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
