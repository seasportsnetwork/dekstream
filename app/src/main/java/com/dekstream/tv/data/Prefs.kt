package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Small local store: account, favourites, resume points, settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dekstream", Context.MODE_PRIVATE)

    // --- Account ---
    var account: Account?
        get() {
            val server = sp.getString("server", null) ?: return null
            val user = sp.getString("user", null) ?: return null
            val pass = sp.getString("pass", null) ?: return null
            return Account(server, user, pass)
        }
        set(v) {
            sp.edit().apply {
                if (v == null) { remove("server"); remove("user"); remove("pass") }
                else { putString("server", v.server); putString("user", v.username); putString("pass", v.password) }
            }.apply()
        }

    var serverTimezone: String?
        get() = sp.getString("tz", null)
        set(v) = sp.edit().putString("tz", v).apply()

    // --- Settings ---
    /** "ts" or "m3u8" for live channels. */
    var liveFormat: String
        get() = sp.getString("liveFormat", "ts") ?: "ts"
        set(v) = sp.edit().putString("liveFormat", v).apply()

    // --- Sports ---
    private val _sportLeagues = MutableStateFlow(
        (sp.getString("sportLeagues", null) ?: "nfl,nba,mlb,nhl,cfb").split(',').filter { it.isNotBlank() }
    )
    /** League keys shown on the Sports tab, in display order. */
    val sportLeagues: StateFlow<List<String>> = _sportLeagues

    fun toggleLeague(key: String) {
        val l = _sportLeagues.value.toMutableList()
        if (!l.remove(key)) l.add(key)
        _sportLeagues.value = l
        sp.edit().putString("sportLeagues", l.joinToString(",")).apply()
    }

    private val _favTeams = MutableStateFlow(loadFavTeams())
    val favTeams: StateFlow<List<FavTeam>> = _favTeams

    fun isFavTeam(leagueKey: String, teamId: String) = _favTeams.value.any { it.leagueKey == leagueKey && it.teamId == teamId }

    fun toggleFavTeam(t: FavTeam) {
        val l = _favTeams.value.toMutableList()
        if (!l.removeAll { it.key == t.key }) l.add(t)
        _favTeams.value = l
        val arr = JSONArray()
        l.forEach { f ->
            arr.put(JSONObject().apply {
                put("l", f.leagueKey); put("id", f.teamId); put("n", f.name); put("a", f.abbr); put("logo", f.logo ?: "")
            })
        }
        sp.edit().putString("favTeams", arr.toString()).apply()
    }

    private fun loadFavTeams(): List<FavTeam> {
        val raw = sp.getString("favTeams", null)
            ?: return listOf(
                FavTeam("nfl", "26", "Seattle Seahawks", "SEA", "https://a.espncdn.com/i/teamlogos/nfl/500/sea.png"),
                FavTeam("nba", "13", "Los Angeles Lakers", "LAL", "https://a.espncdn.com/i/teamlogos/nba/500/lal.png"),
                FavTeam("mlb", "12", "Seattle Mariners", "SEA", "https://a.espncdn.com/i/teamlogos/mlb/500/sea.png"),
            )
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FavTeam(o.getString("l"), o.getString("id"), o.optString("n"), o.optString("a"), o.optString("logo").ifBlank { null })
            }
        }.getOrDefault(emptyList())
    }

    // --- Favourite channels ---
    private val _favorites = MutableStateFlow(loadIntSet("favs"))
    val favorites: StateFlow<Set<Int>> = _favorites

    fun toggleFavorite(streamId: Int) {
        val s = _favorites.value.toMutableSet()
        if (!s.add(streamId)) s.remove(streamId)
        _favorites.value = s
        saveIntList("favs", s.toList())
    }

    // --- Recently watched channels (most recent first) ---
    private val _recent = MutableStateFlow(loadIntList("recent"))
    val recentChannels: StateFlow<List<Int>> = _recent

    fun markChannelWatched(streamId: Int) {
        val l = listOf(streamId) + _recent.value.filter { it != streamId }
        _recent.value = l.take(20)
        saveIntList("recent", _recent.value)
    }

    // --- Resume points ---
    private val _progress = MutableStateFlow(loadProgress())
    val progress: StateFlow<Map<String, ProgressEntry>> = _progress

    fun saveProgress(e: ProgressEntry) {
        val m = _progress.value.toMutableMap()
        // Treat >95% as finished: drop it from Continue Watching.
        if (e.durationMs > 0 && e.positionMs > e.durationMs * 0.95) m.remove(e.key) else m[e.key] = e
        // Keep the 50 most recent.
        val trimmed = m.values.sortedByDescending { it.updatedAt }.take(50).associateBy { it.key }
        _progress.value = trimmed
        val arr = JSONArray()
        trimmed.values.forEach { p ->
            arr.put(JSONObject().apply {
                put("key", p.key); put("title", p.title); put("subtitle", p.subtitle ?: "")
                put("poster", p.poster ?: ""); put("url", p.url)
                put("pos", p.positionMs); put("dur", p.durationMs); put("t", p.updatedAt)
            })
        }
        sp.edit().putString("progress", arr.toString()).apply()
    }

    fun removeProgress(key: String) {
        val m = _progress.value.toMutableMap(); m.remove(key)
        _progress.value = m
        // re-save
        val arr = JSONArray()
        m.values.forEach { p ->
            arr.put(JSONObject().apply {
                put("key", p.key); put("title", p.title); put("subtitle", p.subtitle ?: "")
                put("poster", p.poster ?: ""); put("url", p.url)
                put("pos", p.positionMs); put("dur", p.durationMs); put("t", p.updatedAt)
            })
        }
        sp.edit().putString("progress", arr.toString()).apply()
    }

    private fun loadProgress(): Map<String, ProgressEntry> {
        val raw = sp.getString("progress", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = HashMap<String, ProgressEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = ProgressEntry(
                    key = o.getString("key"),
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle").ifBlank { null },
                    poster = o.optString("poster").ifBlank { null },
                    url = o.getString("url"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    updatedAt = o.optLong("t"),
                )
                out[e.key] = e
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    private fun loadIntList(key: String): List<Int> =
        sp.getString(key, "")!!.split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun loadIntSet(key: String): Set<Int> = loadIntList(key).toSet()

    private fun saveIntList(key: String, l: List<Int>) =
        sp.edit().putString(key, l.joinToString(",")).apply()

    fun clearAll() {
        sp.edit().clear().apply()
        _favorites.value = emptySet(); _recent.value = emptyList(); _progress.value = emptyMap()
        _favTeams.value = loadFavTeams(); _sportLeagues.value = listOf("nfl", "nba", "mlb", "nhl", "cfb")
    }
}
