package com.dekstream.tv.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The provider's whole guide (xmltv.php), streamed and trimmed to a window around now,
 * indexed by EPG channel id. Lets us see what is on *every* channel without one call per channel.
 */
class EpgIndex(private val http: OkHttpClient) {

    data class Entry(val title: String, val desc: String, val startMs: Long, val endMs: Long)

    enum class Status { Idle, Loading, Ready, Failed }

    private val lock = Mutex()
    private var loadedAt = 0L
    private var loadedFor: String? = null
    @Volatile private var byChannel: Map<String, List<Entry>> = emptyMap()
    /** Normalised channel display-name -> guide id, for playlists without tvg-id. */
    @Volatile private var byName: Map<String, String> = emptyMap()

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Loads (or reuses, if younger than 30 min) the XMLTV guide at [url]. */
    suspend fun ensure(key: String, url: String): Map<String, List<Entry>> = lock.withLock {
        val fresh = System.currentTimeMillis() - loadedAt < 30 * 60_000
        if (fresh && loadedFor == key && byChannel.isNotEmpty()) return byChannel
        if (loadedFor != key) { byChannel = emptyMap(); byName = emptyMap() }
        _status.value = Status.Loading
        try {
            byChannel = download(url)
            loadedAt = System.currentTimeMillis()
            loadedFor = key
            _status.value = Status.Ready
        } catch (e: Exception) {
            _status.value = Status.Failed
            if (byChannel.isEmpty()) throw e
        }
        byChannel
    }

    val isLoaded get() = byChannel.isNotEmpty()

    fun programmesFor(c: Channel): List<Entry> {
        c.epgChannelId?.lowercase()?.let { id -> byChannel[id]?.let { return it } }
        val id = byName[normName(c.name)] ?: return emptyList()
        return byChannel[id].orEmpty()
    }

    fun nowOn(c: Channel, at: Long = System.currentTimeMillis()): Entry? =
        programmesFor(c).firstOrNull { at >= it.startMs && at < it.endMs }

    private fun normName(s: String) = s.lowercase()
        .replace(Regex("\\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|raw)\\b"), " ")
        .replace(Regex("^[a-z]{2,3}\\s*[:|\\-]\\s*"), "")
        .replace(Regex("[^a-z0-9]"), "")

    private suspend fun download(url: String): Map<String, List<Entry>> = withContext(Dispatchers.IO) {
        val names = HashMap<String, String>(8192)
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        val now = System.currentTimeMillis()
        val from = now - 4 * 3600_000L
        val to = now + 12 * 3600_000L
        val out = HashMap<String, MutableList<Entry>>(8192)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

        fun parseTime(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            val t = s.trim()
            return runCatching { (if (t.length > 14) fmt.parse(t) else fmtNoTz.parse(t))?.time }.getOrNull()
        }

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Guide download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty guide")
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(body.byteStream(), null)

            var chanId: String? = null
            var inName = false
            var inProg = false
            var keep = false
            var ch = ""; var start = 0L; var end = 0L
            var title = ""; var desc = ""
            var field: String? = null

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "channel" -> chanId = p.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> inName = chanId != null && !inProg
                        "programme" -> {
                            inProg = true
                            ch = (p.getAttributeValue(null, "channel") ?: "").lowercase()
                            start = parseTime(p.getAttributeValue(null, "start")) ?: 0L
                            end = parseTime(p.getAttributeValue(null, "stop")) ?: 0L
                            keep = ch.isNotEmpty() && end > from && start < to && end > start
                            title = ""; desc = ""
                        }
                        "title" -> if (inProg) field = "title"
                        "desc" -> if (inProg) field = "desc"
                    }
                    XmlPullParser.TEXT -> if (inName) {
                        val n = normName(p.text ?: "")
                        if (n.isNotEmpty()) names.putIfAbsent(n, chanId!!)
                    } else if (inProg && keep) when (field) {
                        "title" -> title += p.text
                        "desc" -> if (desc.length < 300) desc += p.text
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "display-name" -> inName = false
                        "channel" -> chanId = null
                        "title", "desc" -> field = null
                        "programme" -> {
                            if (keep && title.isNotBlank()) {
                                out.getOrPut(ch) { ArrayList(8) } += Entry(title.trim(), desc.trim().take(300), start, end)
                            }
                            inProg = false; keep = false
                        }
                    }
                }
                ev = p.next()
            }
        }
        byName = names
        out.mapValues { (_, l) -> l.sortedBy { it.startMs } }
    }
}
