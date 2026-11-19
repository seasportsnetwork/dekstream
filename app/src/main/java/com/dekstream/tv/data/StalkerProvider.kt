package com.dekstream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone

/**
 * Stalker / Ministra "MAC portal" sources (the kind MAG boxes use): portal URL + MAC address.
 * Live TV with now/next, plus movies. Every play asks the portal for a fresh stream link.
 */
class StalkerProvider(override val source: Source, private val http: OkHttpClient) : Provider {

    override val supportsVod = true
    override val supportsSeries = false

    private val lock = Mutex()
    private var endpoint: String? = null
    private var token: String? = null
    private var channelsCache: List<Channel>? = null

    private val portalBase: String
        get() {
            var b = source.url.trim().trimEnd('/')
            if (!b.startsWith("http")) b = "http://$b"
            for (suffix in listOf("/c", "/portal.php", "/server/load.php", "/stalker_portal/server/load.php", "/stalker_portal/c", "/stalker_portal")) {
                if (b.endsWith(suffix)) b = b.removeSuffix(suffix)
            }
            return b
        }

    private val mac get() = source.mac.trim().uppercase()

    private fun request(url: String, withAuth: Boolean): Request {
        val tz = TimeZone.getDefault().id
        return Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=$tz")
            .header("Referer", "$portalBase/c/")
            .apply { if (withAuth && token != null) header("Authorization", "Bearer $token") }
            .build()
    }

    private suspend fun raw(endpointUrl: String, params: Map<String, String>, withAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val b = endpointUrl.toHttpUrl().newBuilder()
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            b.addQueryParameter("JsHttpRequest", "1-xml")
            http.newCall(request(b.build().toString(), withAuth)).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) throw IOException("Portal returned ${r.code}")
                val t = body.trim()
                if (!t.startsWith("{")) throw IOException("Portal didn't answer like a Stalker portal")
                JSONObject(t)
            }
        }

    /** Finds the portal's API endpoint and gets a session token. */
    private suspend fun connect() = lock.withLock {
        if (token != null && endpoint != null) return@withLock
        if (mac.isBlank()) throw IOException("Enter the MAC address registered with your provider")
        val candidates = listOf("$portalBase/portal.php", "$portalBase/server/load.php", "$portalBase/stalker_portal/server/load.php")
        var lastErr: Exception? = null
        for (ep in candidates) {
            try {
                val o = raw(ep, mapOf("type" to "stb", "action" to "handshake", "token" to ""), withAuth = false)
                val t = o.optJSONObject("js")?.optString("token")?.ifBlank { null } ?: continue
                endpoint = ep; token = t
                runCatching { raw(ep, mapOf("type" to "stb", "action" to "get_profile", "hd" to "1")) }
                return@withLock
            } catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: IOException("Couldn't connect to the portal. Check the URL and MAC address.")
    }

    /** Calls the portal API, reconnecting once if the token expired. */
    private suspend fun api(params: Map<String, String>): JSONObject {
        connect()
        return try {
            raw(endpoint!!, params).also { if (it.opt("js") == null) throw IOException("expired") }
        } catch (e: IOException) {
            lock.withLock { token = null }
            connect()
            raw(endpoint!!, params)
        }
    }

    override suspend fun verify(): AccountInfo? {
        connect()
        val prof = runCatching { api(mapOf("type" to "stb", "action" to "get_profile", "hd" to "1")).optJSONObject("js") }.getOrNull()
        val acct = runCatching { api(mapOf("type" to "account_info", "action" to "get_main_info")).optJSONObject("js") }.getOrNull()
        val status = prof?.s("status")?.let { if (it == "0") "Active" else "Status $it" } ?: "Connected"
        val exp = acct?.s("end_date") ?: acct?.s("phone")
        return AccountInfo(status = status + (exp?.let { " · $it" } ?: ""), expiresAtMs = null,
            maxConnections = 0, activeConnections = 0, timezone = null)
    }

    override suspend fun liveCategories(): List<Category> {
        val js = api(mapOf("type" to "itv", "action" to "get_genres")).optJSONArray("js") ?: JSONArray()
        val out = ArrayList<Category>()
        js.objects { g -> val id = g.s("id") ?: return@objects; if (id != "*") out += Category(id, g.s("title") ?: "Other") }
        return out
    }

    override suspend fun allChannels(): List<Channel> {
        channelsCache?.let { return it }
        val js = api(mapOf("type" to "itv", "action" to "get_all_channels")).optJSONObject("js")
        val out = ArrayList<Channel>()
        js?.optJSONArray("data")?.objects { c ->
            val id = c.i("id", -1); if (id < 0) return@objects
            out += Channel(
                streamId = id,
                num = c.i("number"),
                name = c.s("name") ?: "Channel $id",
                icon = c.s("logo")?.let { if (it.startsWith("http")) it else "$portalBase/misc/logos/320/$it" },
                categoryId = c.s("tv_genre_id") ?: "",
                epgChannelId = c.s("xmltv_id"),
                hasArchive = false,
                archiveDays = 0,
                url = "stalker:itv:" + (c.s("cmd") ?: ""),
            )
        }
        channelsCache = out
        return out
    }

    override suspend fun shortEpg(c: Channel): List<Program> {
        val js = runCatching { api(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to c.streamId.toString(), "size" to "4")) }
            .getOrNull()?.optJSONArray("js") ?: return emptyList()
        val out = ArrayList<Program>()
        js.objects { p ->
            val s = p.l("start_timestamp") ?: return@objects
            val e = p.l("stop_timestamp") ?: return@objects
            out += Program(p.s("name") ?: "", p.s("descr") ?: "", s * 1000, e * 1000, false)
        }
        return out
    }

    override suspend fun fullEpg(c: Channel): List<Program> = shortEpg(c)

    override suspend fun vodCategories(): List<Category> {
        val js = api(mapOf("type" to "vod", "action" to "get_categories")).optJSONArray("js") ?: JSONArray()
        val out = ArrayList<Category>()
        js.objects { g -> val id = g.s("id") ?: return@objects; if (id != "*") out += Category(id, g.s("title") ?: "Other") }
        return out
    }

    override suspend fun vods(categoryId: String): List<Vod> {
        val out = ArrayList<Vod>()
        for (page in 1..5) {
            val js = runCatching {
                api(mapOf("type" to "vod", "action" to "get_ordered_list", "category" to categoryId, "p" to page.toString(), "sortby" to "added"))
                    .optJSONObject("js")
            }.getOrNull() ?: break
            val data = js.optJSONArray("data") ?: break
            if (data.length() == 0) break
            data.objects { v ->
                if (v.s("is_series") == "1") return@objects
                val id = v.i("id", -1); if (id < 0) return@objects
                val shot = v.s("screenshot_uri")?.let { if (it.startsWith("http")) it else portalBase + it }
                out += Vod(id, v.s("name") ?: "Movie", shot, categoryId, "mp4", v.s("rating_imdb"), "stalker:vod:" + (v.s("cmd") ?: ""))
            }
            val total = js.i("total_items"); val per = js.i("max_page_items", 14)
            if (per <= 0 || page * per >= total) break
        }
        return out
    }

    override fun liveUrl(c: Channel, format: String) = c.url ?: ""

    override suspend fun resolve(url: String): String {
        if (!url.startsWith("stalker:")) return url
        val kind = url.removePrefix("stalker:").substringBefore(':')
        val cmd = url.removePrefix("stalker:$kind:")
        val js = api(mapOf("type" to kind, "action" to "create_link", "cmd" to cmd, "series" to "", "forced_storage" to "undefined",
            "disable_ad" to "0", "download" to "0")).optJSONObject("js")
        val link = js?.s("cmd") ?: cmd
        return link.removePrefix("ffmpeg ").removePrefix("ffrt ").removePrefix("auto ").trim()
    }
}
