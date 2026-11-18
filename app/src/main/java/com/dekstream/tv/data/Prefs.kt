package com.dekstream.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-profile local store.
 * `sp` = this profile + this playlist (favourites, recents, resume points, stream settings).
 * `pp` = this profile across playlists (sports leagues, favourite teams).
 */
class Prefs(context: Context, profileId: String, sourceId: String) {
    private val sp = context.getSharedPreferences("ds_${profileId}_$sourceId", Context.MODE_PRIVATE)
    private val pp = context.getSharedPreferences("dsp_$profileId", Context.MODE_PRIVATE)

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
        (pp.getString("sportLeagues", null) ?: "nfl,nba,mlb,nhl,cfb").split(',').filter { it.isNotBlank() }
    )
    /** League keys shown on the Sports tab, in display order. */
    val sportLeagues: StateFlow<List<String>> = _sportLeagues

    fun toggleLeague(key: String) {
        val l = _sportLeagues.value.toMutableList()
        if (!l.remove(key)) l.add(key)
        _sportLeagues.value = l
        pp.edit().putString("sportLeagues", l.joinToString(",")).apply()
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
        pp.edit().putString("favTeams", arr.toString()).apply()
    }

    private fun loadFavTeams(): List<FavTeam> {
        val raw = pp.getString("favTeams", null)
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

    // Hide scores on the Sports tab (spoiler-free).
    private val _hideScores = MutableStateFlow(pp.getBoolean("hideScores", false))
    val hideScores: StateFlow<Boolean> = _hideScores
    fun setHideScores(v: Boolean) { _hideScores.value = v; pp.edit().putBoolean("hideScores", v).apply() }

    // Channels pinned to the Sports tab ("Your channels").
    private val _sportsChannels = MutableStateFlow(loadIntList("sportsChannels"))
    val sportsChannels: StateFlow<List<Int>> = _sportsChannels
    fun toggleSportsChannel(id: Int) {
        val l = _sportsChannels.value.toMutableList()
        if (!l.remove(id)) l.add(id)
        _sportsChannels.value = l
        saveIntList("sportsChannels", l)
    }

    // Saved multiview layouts: name, grid size and channel ids (up to 8).
    private val _layouts = MutableStateFlow(loadLayouts())
    val layouts: StateFlow<List<MultiLayout>> = _layouts
    fun saveLayout(l: MultiLayout) {
        val list = (_layouts.value.filter { it.name != l.name } + l).takeLast(8)
        _layouts.value = list
        val a = JSONArray()
        list.forEach { x -> a.put(JSONObject().apply { put("n", x.name); put("g", x.grid); put("c", x.channels.joinToString(",")) }) }
        sp.edit().putString("layouts", a.toString()).apply()
    }
    fun deleteLayout(name: String) {
        _layouts.value = _layouts.value.filter { it.name != name }
        val a = JSONArray()
        _layouts.value.forEach { x -> a.put(JSONObject().apply { put("n", x.name); put("g", x.grid); put("c", x.channels.joinToString(",")) }) }
        sp.edit().putString("layouts", a.toString()).apply()
    }
    private fun loadLayouts(): List<MultiLayout> = runCatching {
        val a = JSONArray(sp.getString("layouts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            MultiLayout(o.getString("n"), o.optInt("g", 4), o.optString("c").split(',').map { it.trim().toIntOrNull() ?: -1 })
        }
    }.getOrDefault(emptyList())

    // --- Channel & category customisation (this profile + playlist) ---
    /** Bumped on every change so screens reload. */
    private val _customVersion = MutableStateFlow(0)
    val customVersion: StateFlow<Int> = _customVersion
    private fun bump() { _customVersion.value++ }

    var hiddenChannels: Set<Int> = loadIntSet("hiddenCh"); private set
    var lockedChannels: Set<Int> = loadIntSet("lockedCh"); private set
    var hiddenCats: Set<String> = loadStrSet("hiddenCat"); private set
    var lockedCats: Set<String> = loadStrSet("lockedCat"); private set
    var channelNames: Map<Int, String> = loadMap("chNames").mapKeys { it.key.toInt() }; private set
    var channelNumbers: Map<Int, Int> = loadMap("chNums").mapKeys { it.key.toInt() }.mapValues { it.value.toInt() }; private set
    var catNames: Map<String, String> = loadMap("catNames"); private set
    var epgOverrides: Map<Int, String> = loadMap("epgOv").mapKeys { it.key.toInt() }; private set
    var groups: List<CustomGroup> = loadGroups(); private set

    fun setHidden(id: Int, v: Boolean) { hiddenChannels = if (v) hiddenChannels + id else hiddenChannels - id; saveIntList("hiddenCh", hiddenChannels.toList()); bump() }
    fun setLocked(id: Int, v: Boolean) { lockedChannels = if (v) lockedChannels + id else lockedChannels - id; saveIntList("lockedCh", lockedChannels.toList()); bump() }
    fun setCatHidden(id: String, v: Boolean) { hiddenCats = if (v) hiddenCats + id else hiddenCats - id; saveStrSet("hiddenCat", hiddenCats); bump() }
    fun setCatLocked(id: String, v: Boolean) { lockedCats = if (v) lockedCats + id else lockedCats - id; saveStrSet("lockedCat", lockedCats); bump() }
    fun renameChannel(id: Int, name: String?) {
        channelNames = if (name.isNullOrBlank()) channelNames - id else channelNames + (id to name.trim())
        saveMap("chNames", channelNames.mapKeys { it.key.toString() }); bump()
    }
    fun renumberChannel(id: Int, num: Int?) {
        channelNumbers = if (num == null) channelNumbers - id else channelNumbers + (id to num)
        saveMap("chNums", channelNumbers.mapKeys { it.key.toString() }.mapValues { it.value.toString() }); bump()
    }
    fun renameCategory(id: String, name: String?) {
        catNames = if (name.isNullOrBlank()) catNames - id else catNames + (id to name.trim())
        saveMap("catNames", catNames); bump()
    }
    fun setEpgOverride(id: Int, epgId: String?) {
        epgOverrides = if (epgId == null) epgOverrides - id else epgOverrides + (id to epgId)
        saveMap("epgOv", epgOverrides.mapKeys { it.key.toString() }); bump()
    }
    fun isChannelLocked(c: Channel) = c.streamId in lockedChannels || ("l:" + c.categoryId) in lockedCats

    fun saveGroup(g: CustomGroup) {
        val l = groups.toMutableList(); val i = l.indexOfFirst { it.id == g.id }
        if (i >= 0) l[i] = g else l += g
        groups = l; persistGroups()
    }
    fun deleteGroup(id: String) { groups = groups.filter { it.id != id }; persistGroups() }
    fun addToGroup(groupId: String, channelId: Int) {
        groups.firstOrNull { it.id == groupId }?.let { g -> if (channelId !in g.channels) saveGroup(g.copy(channels = g.channels + channelId)) }
    }
    private fun persistGroups() {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().apply { put("id", g.id); put("n", g.name); put("c", g.channels.joinToString(",")) }) }
        sp.edit().putString("groups", a.toString()).apply(); bump()
    }
    private fun loadGroups(): List<CustomGroup> = runCatching {
        val a = JSONArray(sp.getString("groups", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            CustomGroup(o.getString("id"), o.optString("n"), o.optString("c").split(',').mapNotNull { it.trim().toIntOrNull() })
        }
    }.getOrDefault(emptyList())

    private fun loadStrSet(key: String): Set<String> = sp.getString(key, "")!!.split('\u0001').filter { it.isNotEmpty() }.toSet()
    private fun saveStrSet(key: String, v: Set<String>) = sp.edit().putString(key, v.joinToString("\u0001")).apply()
    private fun loadMap(key: String): Map<String, String> = runCatching {
        val o = JSONObject(sp.getString(key, "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
    private fun saveMap(key: String, m: Map<String, String>) = sp.edit().putString(key, JSONObject(m).toString()).apply()

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

    fun clearWatchHistory() {
        sp.edit().remove("recent").remove("progress").apply()
        _recent.value = emptyList(); _progress.value = emptyMap()
    }
}

/** A saved multiview: grid size (2, 3 or 4 tiles; 3 = one big + two small) and a channel per tile (-1 = empty). */
data class MultiLayout(val name: String, val grid: Int, val channels: List<Int>)

/** A user-made channel group (shown above the provider's categories). */
data class CustomGroup(val id: String, val name: String, val channels: List<Int>)
