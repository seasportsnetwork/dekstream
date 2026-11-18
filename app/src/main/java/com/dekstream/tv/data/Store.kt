package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SourceType(val label: String) {
    XTREAM("Xtream Codes"),
    M3U_URL("M3U playlist (URL)"),
    M3U_FILE("M3U playlist (file)"),
    STALKER("Stalker / MAC portal"),
}

/** One IPTV playlist/provider the user has added. */
data class Source(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val type: SourceType,
    /** Xtream server, M3U URL, local file path, or portal URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional XMLTV guide URL (M3U sources). */
    val epgUrl: String = "",
    /** MAC address (Stalker portals). */
    val mac: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type.name); put("url", url)
        put("user", username); put("pass", password); put("epg", epgUrl); put("mac", mac)
    }

    companion object {
        fun fromJson(o: JSONObject) = Source(
            id = o.getString("id"),
            name = o.optString("name"),
            type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrDefault(SourceType.XTREAM),
            url = o.optString("url"),
            username = o.optString("user"),
            password = o.optString("pass"),
            epgUrl = o.optString("epg"),
            mac = o.optString("mac"),
        )
    }
}

/** A person using the app: own favorites, progress, teams and chosen playlist. */
data class Profile(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val color: Long,
    val sourceId: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("color", color); put("source", sourceId ?: "")
    }

    companion object {
        val Colors = listOf(0xFFE50914, 0xFF2D7FF9, 0xFF34C759, 0xFFFF9F0A, 0xFFAF52DE, 0xFF00C7BE, 0xFFFF375F, 0xFF8E8E93)
        fun fromJson(o: JSONObject) = Profile(
            id = o.getString("id"),
            name = o.optString("name", "Me"),
            color = o.optLong("color", Colors[0]),
            sourceId = o.optString("source").ifBlank { null },
        )
    }
}

/** App-wide store of profiles and sources (shared by every profile). */
class Store(private val context: Context) {
    private val sp = context.getSharedPreferences("dekstream_global", Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(load("sources") { Source.fromJson(it) })
    val sources: StateFlow<List<Source>> = _sources

    private val _profiles = MutableStateFlow(load("profiles") { Profile.fromJson(it) })
    val profiles: StateFlow<List<Profile>> = _profiles

    var lastProfileId: String?
        get() = sp.getString("lastProfile", null)
        set(v) = sp.edit().putString("lastProfile", v).apply()

    init { migrateFromV1() }

    fun saveSource(s: Source) {
        val l = _sources.value.toMutableList()
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l += s
        _sources.value = l
        save("sources", l.map { it.toJson() })
    }

    fun deleteSource(id: String) {
        _sources.value = _sources.value.filter { it.id != id }
        save("sources", _sources.value.map { it.toJson() })
        _profiles.value.filter { it.sourceId == id }.forEach { saveProfile(it.copy(sourceId = _sources.value.firstOrNull()?.id)) }
    }

    fun source(id: String?) = _sources.value.firstOrNull { it.id == id }

    fun saveProfile(p: Profile) {
        val l = _profiles.value.toMutableList()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l += p
        _profiles.value = l
        save("profiles", l.map { it.toJson() })
    }

    fun deleteProfile(id: String) {
        _profiles.value = _profiles.value.filter { it.id != id }
        save("profiles", _profiles.value.map { it.toJson() })
        context.deleteSharedPreferences("dsp_$id")
        _sources.value.forEach { context.deleteSharedPreferences("ds_${id}_${it.id}") }
    }

    fun profile(id: String?) = _profiles.value.firstOrNull { it.id == id }

    private fun <T> load(key: String, f: (JSONObject) -> T): List<T> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { f(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(key: String, l: List<JSONObject>) {
        val a = JSONArray(); l.forEach { a.put(it) }
        sp.edit().putString(key, a.toString()).apply()
    }

    /** v1.0.x kept one Xtream login in "dekstream" prefs: turn it into a source + first profile. */
    private fun migrateFromV1() {
        if (_profiles.value.isNotEmpty()) return
        val old = context.getSharedPreferences("dekstream", Context.MODE_PRIVATE)
        val server = old.getString("server", null) ?: return
        val src = Source(name = "My IPTV", type = SourceType.XTREAM, url = server,
            username = old.getString("user", "") ?: "", password = old.getString("pass", "") ?: "")
        saveSource(src)
        val p = Profile(name = "Me", color = Profile.Colors[0], sourceId = src.id)
        saveProfile(p)
        lastProfileId = p.id
        // Carry favorites, recents, progress and settings over.
        val dst = context.getSharedPreferences("ds_${p.id}_${src.id}", Context.MODE_PRIVATE).edit()
        listOf("favs", "recent", "progress", "liveFormat", "tz").forEach { k -> old.getString(k, null)?.let { dst.putString(k, it) } }
        dst.apply()
        val prof = context.getSharedPreferences("dsp_${p.id}", Context.MODE_PRIVATE).edit()
        listOf("sportLeagues", "favTeams").forEach { k -> old.getString(k, null)?.let { prof.putString(k, it) } }
        prof.apply()
    }
}
