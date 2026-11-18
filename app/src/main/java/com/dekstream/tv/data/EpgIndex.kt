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

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Loads (or reuses, if younger than 30 min) the guide for this account. */
    suspend fun ensure(api: XtreamApi): Map<String, List<Entry>> = lock.withLock {
        val key = api.account.base + api.account.username
        val fresh = System.currentTimeMillis() - loadedAt < 30 * 60_000
        if (fresh && loadedFor == key && byChannel.isNotEmpty()) return byChannel
        _status.value = Status.Loading
        try {
            byChannel = download(api)
            loadedAt = System.currentTimeMillis()
            loadedFor = key
            _status.value = Status.Ready
        } catch (e: Exception) {
            _status.value = Status.Failed
            if (byChannel.isEmpty()) throw e
        }
        byChannel
    }

    fun programmesFor(epgId: String?): List<Entry> = epgId?.let { byChannel[it.lowercase()] }.orEmpty()

    fun nowOn(epgId: String?, at: Long = System.currentTimeMillis()): Entry? =
        programmesFor(epgId).firstOrNull { at >= it.startMs && at < it.endMs }

    private suspend fun download(api: XtreamApi): Map<String, List<Entry>> = withContext(Dispatchers.IO) {
        val a = api.account
        val url = "${a.base}/xmltv.php?username=${a.username}&password=${a.password}"
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

            var inProg = false
            var keep = false
            var ch = ""; var start = 0L; var end = 0L
            var title = ""; var desc = ""
            var field: String? = null

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
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
                    XmlPullParser.TEXT -> if (inProg && keep) when (field) {
                        "title" -> title += p.text
                        "desc" -> if (desc.length < 300) desc += p.text
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
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
        out.mapValues { (_, l) -> l.sortedBy { it.startMs } }
    }
}
