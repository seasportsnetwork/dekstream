package com.dekstream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plain M3U / M3U8 playlists, from a URL or a file on the device.
 * Live channels, movies and series are told apart by URL and name; the guide comes from
 * the playlist's x-tvg-url or the EPG URL entered by the user.
 */
class M3uProvider(
    override val source: Source,
    private val http: OkHttpClient,
    private val epg: EpgIndex,
    private val filesDir: File,
) : Provider {

    private class Parsed(
        val live: List<Channel>,
        val liveCats: List<Category>,
        val vods: List<Vod>,
        val vodCats: List<Category>,
        val series: List<Series>,
        val seriesCats: List<Category>,
        val episodes: Map<Int, List<Episode>>,
        val headerEpg: String?,
    )

    private val lock = Mutex()
    private var parsed: Parsed? = null

    override val supportsVod get() = parsed?.vods?.isNotEmpty() ?: true
    override val supportsSeries get() = parsed?.series?.isNotEmpty() ?: true

    private suspend fun data(): Parsed = lock.withLock { parsed ?: load().also { parsed = it } }

    override suspend fun verify(): AccountInfo? {
        val p = data()
        if (p.live.isEmpty() && p.vods.isEmpty() && p.series.isEmpty()) throw IOException("No channels found in this playlist")
        return null
    }

    override suspend fun liveCategories() = data().liveCats
    override suspend fun allChannels() = data().live
    override suspend fun vodCategories() = data().vodCats
    override suspend fun vods(categoryId: String) = data().vods.filter { it.categoryId == categoryId }
    override suspend fun allVods() = data().vods
    override suspend fun allSeries() = data().series
    override suspend fun seriesCategories() = data().seriesCats
    override suspend fun series(categoryId: String) = data().series.filter { it.categoryId == categoryId }

    override suspend fun seriesInfo(s: Series): SeriesInfo {
        val eps = data().episodes[s.seriesId].orEmpty()
        return SeriesInfo(s.plot, s.cover, s.genre, null, eps.groupBy { it.season }.toSortedMap())
    }

    override fun xmltvUrl(): String? = source.epgUrl.ifBlank { null } ?: parsed?.headerEpg

    private suspend fun guide(): EpgIndex? {
        data()
        val urls = xmltvUrls()
        if (urls.isEmpty()) return null
        return runCatching { epg.ensure(cacheKey, urls); epg }.getOrNull()
    }

    private fun EpgIndex.Entry.toProgram(c: Channel) =
        Program(title, desc, startMs, endMs, hasArchive = c.catchupType != null && endMs <= System.currentTimeMillis())

    override suspend fun shortEpg(c: Channel): List<Program> {
        val g = guide() ?: return emptyList()
        val now = System.currentTimeMillis()
        return g.programmesFor(c).filter { it.endMs > now }.take(3).map { it.toProgram(c) }
    }

    override suspend fun fullEpg(c: Channel): List<Program> {
        val g = guide() ?: return emptyList()
        return g.programmesFor(c).map { it.toProgram(c) }
    }

    override fun liveUrl(c: Channel, format: String) = c.url ?: ""

    /** Common M3U catch-up conventions (default/append/shift/flussonic). */
    override fun catchupUrl(c: Channel, p: Program, serverTz: String?): String? {
        val base = c.url ?: return null
        val type = c.catchupType?.lowercase() ?: return null
        val startS = p.startMs / 1000
        val endS = p.endMs / 1000
        val nowS = System.currentTimeMillis() / 1000
        val durS = endS - startS
        fun fill(t: String): String {
            val utc = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(p.startMs)).split('-')
            return t.replace("\${start}", "$startS").replace("{start}", "$startS")
                .replace("\${end}", "$endS").replace("{end}", "$endS")
                .replace("{utc}", "$startS").replace("\${timestamp}", "$startS").replace("{utcend}", "$endS")
                .replace("{lutc}", "$nowS").replace("\${now}", "$nowS")
                .replace("{duration}", "$durS").replace("\${duration}", "$durS")
                .replace("{offset}", "${nowS - startS}").replace("\${offset}", "${nowS - startS}")
                .replace("{Y}", utc[0]).replace("{m}", utc[1]).replace("{d}", utc[2])
                .replace("{H}", utc[3]).replace("{M}", utc[4]).replace("{S}", utc[5])
        }
        val src = c.catchupSource
        return when (type) {
            "append" -> if (src != null) base + fill(src) else null
            "default" -> if (src != null) fill(src) else null
            "shift", "timeshift" -> base + (if ('?' in base) "&" else "?") + "utc=$startS&lutc=$nowS"
            "flussonic", "flussonic-hls", "fs" -> {
                val i = base.lastIndexOf('/')
                if (i < 0) null else base.substring(0, i) + "/timeshift_rel-${nowS - startS}.m3u8"
            }
            else -> if (src != null) fill(src) else null
        }
    }

    // ---------------- parsing ----------------

    private suspend fun load(): Parsed = withContext(Dispatchers.IO) {
        if (source.type == SourceType.M3U_FILE) {
            val f = File(source.url).takeIf { it.isAbsolute } ?: File(filesDir, source.url)
            if (!f.exists()) throw IOException("Playlist file is missing. Import it again.")
            f.bufferedReader().use { parse(it) }
        } else {
            val req = Request.Builder().url(source.url.trim()).header("User-Agent", XtreamApi.USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Playlist download failed (${r.code})")
                val body = r.body ?: throw IOException("Empty playlist")
                body.charStream().buffered().use { parse(it) }
            }
        }
    }

    private val attrRx = Regex("""([\w-]+)="([^"]*)"""")
    private val episodeRx = Regex("""(?i)^(.*?)[\s._-]*S(\d{1,2})\s*[._-]?\s*E(\d{1,3})\b.*$""")
    private val vodExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    private fun parse(reader: BufferedReader): Parsed {
        val live = ArrayList<Channel>()
        val vods = ArrayList<Vod>()
        val episodesBySeries = LinkedHashMap<Int, MutableList<Episode>>()
        val seriesById = LinkedHashMap<Int, Series>()
        val liveCats = LinkedHashMap<String, Category>()
        val vodCats = LinkedHashMap<String, Category>()
        val seriesCats = LinkedHashMap<String, Category>()
        var headerEpg: String? = null

        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var group: String? = null
        var pending = false
        var liveNum = 0

        fun catId(map: LinkedHashMap<String, Category>, name: String): String {
            val key = name.ifBlank { "Uncategorized" }
            return map.getOrPut(key) { Category("c" + (key.hashCode() and 0x7fffffff), key) }.id
        }

        reader.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U", true) -> {
                    val a = attrRx.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    headerEpg = (a["x-tvg-url"] ?: a["url-tvg"])?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF", true) -> {
                    attrs = attrRx.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // Title follows the last comma that is outside quotes.
                    var inQ = false; var cut = -1
                    for (i in line.indices) { val ch = line[i]; if (ch == '"') inQ = !inQ else if (ch == ',' && !inQ) cut = i }
                    title = if (cut >= 0) line.substring(cut + 1).trim() else attrs["tvg-name"] ?: ""
                    group = attrs["group-title"]
                    pending = true
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> {}
                pending -> {
                    val url = line
                    val name = title.ifBlank { attrs["tvg-name"] ?: url.substringAfterLast('/') }
                    val logo = attrs["tvg-logo"]?.ifBlank { null }
                    val grp = group ?: ""
                    val id = url.hashCode() and 0x7fffffff
                    val path = url.substringBefore('?').lowercase()
                    val ext = path.substringAfterLast('.', "")
                    val ep = episodeRx.matchEntire(name)
                    val isSeriesPath = "/series/" in path
                    val isMoviePath = "/movie/" in path || "/movies/" in path || "/vod/" in path
                    when {
                        isSeriesPath || (ep != null && (ext in vodExt || isMoviePath)) -> {
                            val showName = (ep?.groupValues?.get(1)?.trim()?.trimEnd('-', '.', ' ')).takeUnless { it.isNullOrBlank() } ?: name
                            val sid = (showName.lowercase() + "|" + grp).hashCode() and 0x7fffffff
                            val cat = catId(seriesCats, grp)
                            seriesById.getOrPut(sid) { Series(sid, showName, logo, cat, null, null, null, null) }
                            episodesBySeries.getOrPut(sid) { ArrayList() } += Episode(
                                id = id.toString(),
                                season = ep?.groupValues?.get(2)?.toIntOrNull() ?: 1,
                                episodeNum = ep?.groupValues?.get(3)?.toIntOrNull() ?: (episodesBySeries[sid]?.size ?: 0) + 1,
                                title = name, ext = ext.ifBlank { "mp4" }, plot = null, image = logo, duration = null, url = url,
                            )
                        }
                        isMoviePath || ext in vodExt -> vods += Vod(id, name, logo, catId(vodCats, grp), ext.ifBlank { "mp4" }, null, url)
                        else -> live += Channel(
                            streamId = id,
                            num = attrs["tvg-chno"]?.toIntOrNull() ?: ++liveNum,
                            name = name,
                            icon = logo,
                            categoryId = catId(liveCats, grp),
                            epgChannelId = attrs["tvg-id"]?.ifBlank { null },
                            hasArchive = attrs["catchup"] != null || attrs["catchup-source"] != null || attrs["timeshift"] != null,
                            archiveDays = attrs["catchup-days"]?.toIntOrNull() ?: attrs["timeshift"]?.toIntOrNull() ?: 0,
                            url = url,
                            catchupType = attrs["catchup"] ?: attrs["catchup-type"] ?: if (attrs["timeshift"] != null) "shift" else null,
                            catchupSource = attrs["catchup-source"],
                        )
                    }
                    pending = false; attrs = emptyMap(); title = ""; group = null
                }
            }
        }
        return Parsed(
            live = live.distinctBy { it.streamId },
            liveCats = liveCats.values.toList(),
            vods = vods.distinctBy { it.streamId },
            vodCats = vodCats.values.toList(),
            series = seriesById.values.toList(),
            seriesCats = seriesCats.values.toList(),
            episodes = episodesBySeries.mapValues { (_, l) -> l.sortedWith(compareBy({ it.season }, { it.episodeNum })) },
            headerEpg = headerEpg,
        )
    }
}
