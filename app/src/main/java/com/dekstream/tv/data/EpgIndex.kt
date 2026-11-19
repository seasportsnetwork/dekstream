package com.dekstream.tv.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
class EpgIndex(private val http: OkHttpClient, private val cacheDir: java.io.File? = null) {
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshing = false


    data class Entry(val title: String, val desc: String, val startMs: Long, val endMs: Long)

    enum class Status { Idle, Loading, Ready, Failed }

    private val lock = Mutex()
    private var loadedAt = 0L
    private var loadedFor: String? = null
    @Volatile private var byChannel: Map<String, List<Entry>> = emptyMap()
    /** Normalised channel display-name -> guide id, for playlists without tvg-id. */
    @Volatile private var byName: Map<String, String> = emptyMap()
    /** Guide id -> display name (for matching guides to channels by hand). */
    @Volatile private var idNames: Map<String, String> = emptyMap()

    /** All guide channels as (id, name), for the manual matcher. */
    fun guideChannels(): List<Pair<String, String>> =
        byChannel.keys.map { it to (idNames[it] ?: it) }.sortedBy { it.second.lowercase() }

    private fun mergeGuides(a: Map<String, List<Entry>>, b: Map<String, List<Entry>>): Map<String, List<Entry>> {
        if (a.isEmpty()) return b
        val out = HashMap(a)
        for ((id, extra) in b) {
            val base = out[id]
            if (base == null) { out[id] = extra; continue }
            // Gap filling: keep the higher-priority entries, add the other guide's shows where nothing is listed.
            val filled = base.toMutableList()
            for (e in extra) if (filled.none { it.startMs < e.endMs && e.startMs < it.endMs }) filled += e
            out[id] = filled.sortedBy { it.startMs }
        }
        return out
    }

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Single-guide convenience. */
    suspend fun ensure(key: String, url: String): Map<String, List<Entry>> = ensure(key, listOf(url))

    /**
     * Loads (or reuses, if younger than 30 min) one or more XMLTV guides, highest priority first.
     * Lower-priority guides add channels the others lack and fill time gaps in channels they share.
     */
    suspend fun ensure(key: String, urls: List<String>): Map<String, List<Entry>> {
        lock.withLock {
            if (loadedFor == key && byChannel.isNotEmpty()) {
                // Stale but usable: answer now, refresh quietly in the background.
                if (System.currentTimeMillis() - loadedAt > 30 * 60_000) refreshInBackground(key, urls)
                return byChannel
            }
            if (loadedFor != key) { byChannel = emptyMap(); byName = emptyMap(); idNames = emptyMap() }
            // A saved copy from an earlier run (up to 12 h old) makes the first lookup instant.
            if (loadFromDisk(key)) {
                if (System.currentTimeMillis() - loadedAt > 30 * 60_000) refreshInBackground(key, urls)
                return byChannel
            }
        }
        return lock.withLock { downloadAll(key, urls) }
    }

    private fun refreshInBackground(key: String, urls: List<String>) {
        if (refreshing) return
        refreshing = true
        bg.launch {
            try { lock.withLock { downloadAll(key, urls) } } catch (_: Exception) {} finally { refreshing = false }
        }
    }

    private suspend fun downloadAll(key: String, urls: List<String>): Map<String, List<Entry>> {
        if (byChannel.isEmpty()) _status.value = Status.Loading
        try {
            var merged: Map<String, List<Entry>> = emptyMap()
            val names = HashMap<String, String>()
            val ids = HashMap<String, String>()
            var firstError: Exception? = null
            for (u in urls.distinct()) {
                try {
                    val got = download(u)
                    merged = mergeGuides(merged, got)
                    byName.forEach { (n, id) -> names.putIfAbsent(n, id) }
                    idNames.forEach { (id, n) -> ids.putIfAbsent(id, n) }
                } catch (e: Exception) { if (firstError == null) firstError = e }
            }
            if (merged.isEmpty() && firstError != null) throw firstError!!
            byName = names
            idNames = ids
            byChannel = merged
            loadedAt = System.currentTimeMillis()
            loadedFor = key
            _status.value = Status.Ready
            saveToDisk(key)
        } catch (e: Exception) {
            if (byChannel.isEmpty()) { _status.value = Status.Failed; throw e }
        }
        return byChannel
    }

    // ---- On-device copy of the trimmed guide ----
    private fun cacheFile(key: String) = cacheDir?.let { java.io.File(it, "guide_" + Integer.toHexString(key.hashCode()) + ".bin") }

    private fun saveToDisk(key: String) {
        val f = cacheFile(key) ?: return
        runCatching {
            val tmp = java.io.File(f.path + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)).use { o ->
                o.writeInt(2); o.writeLong(loadedAt)
                val map = byChannel
                o.writeInt(map.size)
                for ((id, list) in map) {
                    o.writeUTF(id); o.writeInt(list.size)
                    for (e in list) { o.writeUTF(e.title.take(300)); o.writeUTF(e.desc.take(300)); o.writeLong(e.startMs); o.writeLong(e.endMs) }
                }
                val names = byName; o.writeInt(names.size); for ((k, v) in names) { o.writeUTF(k.take(200)); o.writeUTF(v) }
                val ids = idNames; o.writeInt(ids.size); for ((k, v) in ids) { o.writeUTF(k); o.writeUTF(v.take(200)) }
            }
            tmp.renameTo(f)
        }
    }

    private fun loadFromDisk(key: String): Boolean {
        val f = cacheFile(key) ?: return false
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 12 * 3600_000L) return false
        return runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f), 1 shl 16)).use { i ->
                if (i.readInt() != 2) return false
                val at = i.readLong()
                val n = i.readInt()
                val map = HashMap<String, List<Entry>>(n * 2)
                val now = System.currentTimeMillis()
                repeat(n) {
                    val id = i.readUTF(); val c = i.readInt()
                    val l = ArrayList<Entry>(c)
                    repeat(c) {
                        val e = Entry(i.readUTF(), i.readUTF(), i.readLong(), i.readLong())
                        if (e.endMs > now - 4 * 3600_000L) l += e
                    }
                    map[id] = l
                }
                val names = HashMap<String, String>(); repeat(i.readInt()) { names[i.readUTF()] = i.readUTF() }
                val ids = HashMap<String, String>(); repeat(i.readInt()) { ids[i.readUTF()] = i.readUTF() }
                byChannel = map; byName = names; idNames = ids
                loadedAt = at; loadedFor = key
                _status.value = Status.Ready
                map.isNotEmpty()
            }
        }.getOrDefault(false)
    }

    val isLoaded get() = byChannel.isNotEmpty()

    /** Programmes whose title matches, on now or later (by guide channel id). */
    fun search(q: String, limit: Int = 80): List<Pair<String, Entry>> {
        val n = q.trim().lowercase()
        if (n.length < 2) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<String, Entry>>()
        for ((id, list) in byChannel) {
            for (e in list) {
                if (e.endMs > now && e.title.lowercase().contains(n)) out += id to e
            }
            if (out.size > limit * 4) break
        }
        return out.sortedBy { it.second.startMs }.take(limit)
    }

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
        val displayNames = HashMap<String, String>(8192)
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
                        val raw = (p.text ?: "").trim()
                        if (raw.isNotEmpty()) displayNames.putIfAbsent(chanId!!, raw)
                        val n = normName(raw)
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
        idNames = displayNames
        out.mapValues { (_, l) -> l.sortedBy { it.startMs } }
    }
}
