package com.dekstream.tv.data

import okhttp3.OkHttpClient

/** Everything the UI needs from an IPTV source, whatever kind it is. */
interface Provider {
    val source: Source
    val supportsVod: Boolean
    val supportsSeries: Boolean
    /** Stable key for caches (guide, etc.). */
    val cacheKey: String get() = source.id + source.url + source.username + source.mac + source.epgUrl + source.extraEpg.joinToString()

    /** Checks the source works; returns account details when the source has any. */
    suspend fun verify(): AccountInfo?

    suspend fun liveCategories(): List<Category>
    suspend fun allChannels(): List<Channel>

    suspend fun vodCategories(): List<Category> = emptyList()
    suspend fun vods(categoryId: String): List<Vod> = emptyList()
    /** Every movie in the playlist (for Top 10). Default: category by category, capped. */
    suspend fun allVods(): List<Vod> = vodCategories().take(40).flatMap { runCatching { vods(it.id) }.getOrDefault(emptyList()) }
    suspend fun vodInfo(v: Vod): VodInfo? = null

    suspend fun seriesCategories(): List<Category> = emptyList()
    suspend fun series(categoryId: String): List<Series> = emptyList()
    suspend fun allSeries(): List<Series> = seriesCategories().take(40).flatMap { runCatching { series(it.id) }.getOrDefault(emptyList()) }
    suspend fun seriesInfo(s: Series): SeriesInfo = SeriesInfo(s.plot, s.cover, s.genre, null, emptyMap())

    suspend fun shortEpg(c: Channel): List<Program> = emptyList()
    suspend fun fullEpg(c: Channel): List<Program> = emptyList()

    fun liveUrl(c: Channel, format: String): String
    fun vodUrl(v: Vod): String = v.url ?: ""
    fun episodeUrl(e: Episode): String = e.url ?: ""
    /** Catch-up URL for a past programme, or null if not supported. */
    fun catchupUrl(c: Channel, p: Program, serverTz: String?): String? = null

    /** XMLTV guide covering all channels, if the source has one. */
    fun xmltvUrl(): String? = null

    /** Main guide plus any extra guides the user added, highest priority first. */
    fun xmltvUrls(): List<String> = (listOfNotNull(xmltvUrl()) + source.extraEpg).distinct()

    /** Turns a stored URL into a playable one (portals hand out a fresh link per play). */
    suspend fun resolve(url: String): String = url

    companion object {
        fun create(s: Source, http: OkHttpClient, epg: EpgIndex, filesDir: java.io.File): Provider = when (s.type) {
            SourceType.XTREAM -> XtreamProvider(s, http)
            SourceType.M3U_URL, SourceType.M3U_FILE -> M3uProvider(s, http, epg, filesDir)
            SourceType.STALKER -> StalkerProvider(s, http)
        }
    }
}

class XtreamProvider(override val source: Source, http: OkHttpClient) : Provider {
    val api = XtreamApi(http, Account(source.url, source.username, source.password))
    override val supportsVod = true
    override val supportsSeries = true

    override suspend fun verify() = api.login()
    override suspend fun liveCategories() = api.liveCategories()
    override suspend fun allChannels() = api.liveStreams()
    override suspend fun vodCategories() = api.vodCategories()
    override suspend fun vods(categoryId: String) = api.vodStreams(categoryId)
    override suspend fun allVods() = api.vodStreams(null)
    override suspend fun allSeries() = api.series(null)
    override suspend fun vodInfo(v: Vod) = api.vodInfo(v.streamId)
    override suspend fun seriesCategories() = api.seriesCategories()
    override suspend fun series(categoryId: String) = api.series(categoryId)
    override suspend fun seriesInfo(s: Series) = api.seriesInfo(s.seriesId)
    override suspend fun shortEpg(c: Channel) = api.shortEpg(c.streamId, 3)
    override suspend fun fullEpg(c: Channel) = api.fullEpg(c.streamId)
    override fun liveUrl(c: Channel, format: String) = api.liveUrl(c.streamId, format)
    override fun vodUrl(v: Vod) = api.vodUrl(v)
    override fun episodeUrl(e: Episode) = api.episodeUrl(e)
    override fun catchupUrl(c: Channel, p: Program, serverTz: String?) =
        if (c.hasArchive) api.catchupUrl(c.streamId, p, serverTz) else null

    override fun xmltvUrl(): String {
        val a = api.account
        return "${a.base}/xmltv.php?username=${a.username}&password=${a.password}"
    }
}
