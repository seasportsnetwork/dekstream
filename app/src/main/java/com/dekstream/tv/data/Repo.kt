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

    // ---- Raw provider data (cached) ----
    suspend fun rawLiveCategories(): List<Category> =
        if (!source.loadLive) emptyList() else lock.withLock { liveCats ?: provider.liveCategories().also { liveCats = it } }
    suspend fun rawVodCategories(): List<Category> =
        if (!source.loadMovies) emptyList() else lock.withLock { vodCats ?: provider.vodCategories().also { vodCats = it } }
    suspend fun rawSeriesCategories(): List<Category> =
        if (!source.loadShows) emptyList() else lock.withLock { seriesCats ?: provider.seriesCategories().also { seriesCats = it } }
    suspend fun rawChannels(): List<Channel> =
        if (!source.loadLive) emptyList() else allLock.withLock { allLive ?: provider.allChannels().also { allLive = it } }

    // ---- What this profile sees: hidden items removed, renames/numbers/guide matches applied ----
    private fun viewCats(list: List<Category>, prefix: String) = list
        .filter { (prefix + it.id) !in prefs.hiddenCats }
        .map { c -> prefs.catNames[prefix + c.id]?.let { c.copy(name = it) } ?: c }

    suspend fun liveCategories() = viewCats(rawLiveCategories(), "l:")
    suspend fun vodCategories() = viewCats(rawVodCategories(), "v:")
    suspend fun seriesCategories() = viewCats(rawSeriesCategories(), "s:")

    /** All live channels for this profile. */
    suspend fun allChannels(): List<Channel> {
        val raw = rawChannels()
        val hidden = prefs.hiddenChannels
        val hiddenCats = prefs.hiddenCats
        val names = prefs.channelNames
        val nums = prefs.channelNumbers
        val ov = prefs.epgOverrides
        if (hidden.isEmpty() && hiddenCats.isEmpty() && names.isEmpty() && nums.isEmpty() && ov.isEmpty()) return raw
        return raw.asSequence()
            .filter { it.streamId !in hidden && ("l:" + it.categoryId) !in hiddenCats }
            .map { c ->
                if (c.streamId !in names && c.streamId !in nums && c.streamId !in ov) c
                else c.copy(
                    name = names[c.streamId] ?: c.name,
                    num = nums[c.streamId] ?: c.num,
                    epgChannelId = ov[c.streamId] ?: c.epgChannelId,
                )
            }.toList()
    }

    suspend fun channels(categoryId: String): List<Channel> = allChannels().filter { it.categoryId == categoryId }

    /** Forget cached guide data after a guide match changes. */
    fun clearEpgCache() { epgShort.clear(); epgFull.clear() }

    suspend fun vods(categoryId: String): List<Vod> =
        vodByCat[categoryId] ?: provider.vods(categoryId).also { vodByCat[categoryId] = it }

    suspend fun series(categoryId: String): List<Series> =
        seriesByCat[categoryId] ?: provider.series(categoryId).also { seriesByCat[categoryId] = it }

    suspend fun vodInfo(v: Vod) = provider.vodInfo(v)
    suspend fun seriesInfo(s: Series) = provider.seriesInfo(s)

    /** Guide entries from the whole-guide index for channels matched by hand. */
    private suspend fun overrideEpg(c: Channel): List<Program>? {
        val id = prefs.epgOverrides[c.streamId] ?: return null
        val idx = guideIndex() ?: return null
        return idx.programmesFor(c.copy(epgChannelId = id)).map { Program(it.title, it.desc, it.startMs, it.endMs, c.hasArchive && it.endMs <= System.currentTimeMillis()) }
    }

    /** Now/next for a channel, cached 5 minutes. */
    suspend fun nowNext(c: Channel): List<Program> {
        val now = System.currentTimeMillis()
        epgShort[c.streamId]?.let { (t, l) -> if (now - t < 5 * 60_000) return l }
        val l = (overrideEpg(c) ?: runCatching { provider.shortEpg(c) }.getOrDefault(emptyList())).filter { it.endMs > now }.take(3)
        epgShort[c.streamId] = now to l
        return l
    }

    /** Full EPG (past + future), cached 30 minutes. */
    suspend fun fullEpg(c: Channel): List<Program> {
        val now = System.currentTimeMillis()
        epgFull[c.streamId]?.let { (t, l) -> if (now - t < 30 * 60_000) return l }
        val l = overrideEpg(c) ?: runCatching { provider.fullEpg(c) }.getOrDefault(emptyList())
        epgFull[c.streamId] = now to l
        return l
    }

    /** The whole-guide index (XMLTV) for sports matching, when this source has one. */
    suspend fun guideIndex(): EpgIndex? {
        if (provider is M3uProvider) allChannels() // playlist header may carry the guide URL
        val urls = provider.xmltvUrls()
        if (urls.isEmpty()) return null
        return runCatching { epg.ensure(provider.cacheKey, urls); epg }.getOrNull()
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
