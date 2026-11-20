package com.dekstream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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

    /** Network + parsing off the main thread (big playlists take seconds to parse). */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---- Raw provider data (cached) ----
    suspend fun rawLiveCategories(): List<Category> =
        if (!source.loadLive) emptyList() else lock.withLock { liveCats ?: io { provider.liveCategories() }.also { liveCats = it } }
    suspend fun rawVodCategories(): List<Category> =
        if (!source.loadMovies) emptyList() else lock.withLock { vodCats ?: io { provider.vodCategories() }.also { vodCats = it } }
    suspend fun rawSeriesCategories(): List<Category> =
        if (!source.loadShows) emptyList() else lock.withLock { seriesCats ?: io { provider.seriesCategories() }.also { seriesCats = it } }
    suspend fun rawChannels(): List<Channel> =
        if (!source.loadLive) emptyList() else allLock.withLock { allLive ?: io { provider.allChannels() }.also { allLive = it } }

    // ---- What this profile sees: hidden items removed, renames/numbers/guide matches applied ----
    private fun viewCats(list: List<Category>, prefix: String) = list
        .filter { (prefix + it.id) !in prefs.hiddenCats && !ContentFilter.hides(it.name) }
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
        val adult = if (ContentFilter.showAdult) emptySet() else adultChannelIds(raw)
        if (hidden.isEmpty() && hiddenCats.isEmpty() && names.isEmpty() && nums.isEmpty() && ov.isEmpty() && adult.isEmpty()) return raw
        return withContext(Dispatchers.Default) { raw.asSequence()
            .filter { it.streamId !in hidden && ("l:" + it.categoryId) !in hiddenCats && it.streamId !in adult }
            .map { c ->
                if (c.streamId !in names && c.streamId !in nums && c.streamId !in ov) c
                else c.copy(
                    name = names[c.streamId] ?: c.name,
                    num = nums[c.streamId] ?: c.num,
                    epgChannelId = ov[c.streamId] ?: c.epgChannelId,
                )
            }.toList() }
    }

    // Adult detection runs once per playlist load, off the main thread, then it's a set lookup.
    @Volatile private var adultFor: List<Channel>? = null
    @Volatile private var adultIds: Set<Int> = emptySet()
    private suspend fun adultChannelIds(raw: List<Channel>): Set<Int> {
        if (adultFor === raw) return adultIds
        val cats = rawLiveCategories().filter { ContentFilter.isAdult(it.name) }.map { it.id }.toSet()
        val ids = withContext(Dispatchers.Default) {
            raw.asSequence().filter { it.categoryId in cats || ContentFilter.isAdult(it.name) }.map { it.streamId }.toHashSet()
        }
        adultIds = ids; adultFor = raw
        return ids
    }
    private val vodClean = ConcurrentHashMap<String, List<Vod>>()
    private val seriesClean = ConcurrentHashMap<String, List<Series>>()

    suspend fun channels(categoryId: String): List<Channel> = allChannels().filter { it.categoryId == categoryId }

    /**
     * Backup feeds for a live channel, best first: other feeds of the same channel (same guide id or
     * same name once region/quality tags are removed), then channels showing the same programme now.
     */
    suspend fun alternatesFor(c: Channel, health: StreamHealth): List<Channel> {
        val all = allChannels()
        val base = StreamHealth.baseName(c.name)
        val epgId = c.epgChannelId?.lowercase()
        val sameChannel = all.filter { o ->
            o.streamId != c.streamId && (
                (epgId != null && o.epgChannelId?.lowercase() == epgId) ||
                    (base.length >= 3 && StreamHealth.baseName(o.name) == base)
                )
        }
        // Same programme on another channel (e.g. a simulcast), from the whole guide if it's loaded.
        val sameShow = run {
            val idx = epg.takeIf { it.isLoaded } ?: return@run emptyList()
            val now = idx.nowOn(c)?.title?.lowercase()?.trim() ?: return@run emptyList()
            if (now.length < 4) return@run emptyList()
            all.asSequence().filter { o -> o.streamId != c.streamId && o !in sameChannel }
                .filter { o -> idx.nowOn(o)?.title?.lowercase()?.trim() == now }
                .take(10).toList()
        }
        fun quality(n: String): Int {
            val l = n.lowercase()
            return when { "4k" in l || "uhd" in l -> 3; "fhd" in l || "1080" in l -> 2; " hd" in " $l" -> 1; else -> 0 }
        }
        return sameChannel.sortedWith(compareByDescending<Channel> { health.score(it.streamId) }.thenByDescending { quality(it.name) }) +
            sameShow.sortedByDescending { health.score(it.streamId) }
    }

    /** Forget cached guide data after a guide match changes. */
    fun clearEpgCache() { epgShort.clear(); epgFull.clear() }

    suspend fun vods(categoryId: String): List<Vod> =
        (vodByCat[categoryId] ?: io { provider.vods(categoryId) }.also { vodByCat[categoryId] = it })
            .let { l -> if (ContentFilter.showAdult) l else vodClean[categoryId]
                ?: withContext(Dispatchers.Default) { l.filter { !ContentFilter.isAdult(it.name) } }.also { vodClean[categoryId] = it } }

    suspend fun series(categoryId: String): List<Series> =
        (seriesByCat[categoryId] ?: io { provider.series(categoryId) }.also { seriesByCat[categoryId] = it })
            .let { l -> if (ContentFilter.showAdult) l else seriesClean[categoryId]
                ?: withContext(Dispatchers.Default) { l.filter { !ContentFilter.isAdult(it.name) } }.also { seriesClean[categoryId] = it } }

    // ConcurrentHashMap can't hold nulls, so "no info" is remembered in its own set.
    private val vodInfoCache = ConcurrentHashMap<Int, VodInfo>()
    private val vodInfoMissing = ConcurrentHashMap.newKeySet<Int>()
    suspend fun vodInfo(v: Vod): VodInfo? {
        vodInfoCache[v.streamId]?.let { return it }
        if (v.streamId in vodInfoMissing) return null
        val i = runCatching { io { provider.vodInfo(v) } }.getOrNull()
        if (i != null) vodInfoCache[v.streamId] = i else vodInfoMissing += v.streamId
        return i
    }
    suspend fun seriesInfo(s: Series) = io { provider.seriesInfo(s) }

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
        val l = (overrideEpg(c) ?: runCatching { io { provider.shortEpg(c) } }.getOrDefault(emptyList())).filter { it.endMs > now }.take(3)
        epgShort[c.streamId] = now to l
        return l
    }

    /** Full EPG (past + future), cached 30 minutes. */
    suspend fun fullEpg(c: Channel): List<Program> {
        val now = System.currentTimeMillis()
        epgFull[c.streamId]?.let { (t, l) -> if (now - t < 30 * 60_000) return l }
        val l = overrideEpg(c) ?: runCatching { io { provider.fullEpg(c) } }.getOrDefault(emptyList())
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
