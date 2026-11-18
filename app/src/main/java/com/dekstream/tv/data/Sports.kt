package com.dekstream.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class League(val key: String, val name: String, val short: String, val path: String)

data class TeamSide(
    val id: String,
    val name: String,
    val nickname: String,
    val location: String,
    val abbr: String,
    val logo: String?,
    val score: String?,
    val record: String?,
)

data class Game(
    val id: String,
    val league: League,
    val startMs: Long,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val home: TeamSide,
    val away: TeamSide,
    val broadcasts: List<String>,
    val venue: String?,
) {
    val isLive get() = state == "in"
}

data class FavTeam(val leagueKey: String, val teamId: String, val name: String, val abbr: String, val logo: String?) {
    val key get() = "$leagueKey|$teamId"
}

data class TeamInfo(val id: String, val name: String, val abbr: String, val logo: String?)

/** A channel offered for a game, with what its guide says is on. */
data class ChannelMatch(val channel: Channel, val programme: EpgIndex.Entry?, val confirmed: Boolean, val score: Int)

/** ESPN's public scoreboard JSON (no key needed). */
class SportsRepo(private val http: OkHttpClient) {

    val allLeagues = listOf(
        League("nfl", "NFL", "NFL", "football/nfl"),
        League("nba", "NBA", "NBA", "basketball/nba"),
        League("mlb", "MLB", "MLB", "baseball/mlb"),
        League("nhl", "NHL", "NHL", "hockey/nhl"),
        League("wnba", "WNBA", "WNBA", "basketball/wnba"),
        League("cfb", "College Football", "NCAAF", "football/college-football"),
        League("cbb", "College Basketball", "NCAAM", "basketball/mens-college-basketball"),
        League("mls", "MLS", "MLS", "soccer/usa.1"),
        League("epl", "Premier League", "EPL", "soccer/eng.1"),
        League("ucl", "Champions League", "UCL", "soccer/uefa.champions"),
        League("laliga", "La Liga", "LaLiga", "soccer/esp.1"),
        League("seriea", "Serie A", "Serie A", "soccer/ita.1"),
        League("bund", "Bundesliga", "Bundesliga", "soccer/ger.1"),
        League("ligamx", "Liga MX", "Liga MX", "soccer/mex.1"),
    )

    fun league(key: String) = allLeagues.firstOrNull { it.key == key }

    private val teamsCache = ConcurrentHashMap<String, List<TeamInfo>>()

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        http.newCall(req).execute().use { r -> parseObject(r.body?.string() ?: "") }
    }

    private fun base(l: League) = "https://site.api.espn.com/apis/site/v2/sports/${l.path}"

    /** Today's games for one league, live first. */
    suspend fun scoreboard(l: League): List<Game> {
        val o = runCatching { get("${base(l)}/scoreboard") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Game>()
        o.optJSONArray("events")?.objects { e -> parseEvent(e, l)?.let { out += it } }
        return out.sortedWith(compareBy<Game>({ stateOrder(it.state) }, { it.startMs }))
    }

    /** All teams in a league (for picking favorites). */
    suspend fun teams(l: League): List<TeamInfo> {
        teamsCache[l.key]?.let { return it }
        val o = get("${base(l)}/teams?limit=500")
        val out = ArrayList<TeamInfo>()
        o.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)
            ?.optJSONArray("teams")?.objects { w ->
                val t = w.optJSONObject("team") ?: return@objects
                out += TeamInfo(
                    id = t.s("id") ?: return@objects,
                    name = t.s("displayName") ?: "?",
                    abbr = t.s("abbreviation") ?: "",
                    logo = t.optJSONArray("logos")?.optJSONObject(0)?.s("href"),
                )
            }
        val sorted = out.sortedBy { it.name }
        teamsCache[l.key] = sorted
        return sorted
    }

    /** Live, next and last game for each favorite team. */
    suspend fun favoriteGames(favs: List<FavTeam>): List<Pair<FavTeam, Game>> = coroutineScope {
        favs.map { t ->
            async {
                val l = league(t.leagueKey) ?: return@async emptyList<Pair<FavTeam, Game>>()
                val o = runCatching { get("${base(l)}/teams/${t.teamId}/schedule") }.getOrNull()
                    ?: return@async emptyList()
                val games = ArrayList<Game>()
                o.optJSONArray("events")?.objects { e -> parseEvent(e, l)?.let { games += it } }
                val now = System.currentTimeMillis()
                val live = games.filter { it.isLive }
                val next = games.filter { it.state == "pre" && it.startMs > now - 3 * 3600_000 }.minByOrNull { it.startMs }
                val last = games.filter { it.state == "post" }.maxByOrNull { it.startMs }
                val pick = if (live.isNotEmpty()) live else listOfNotNull(next ?: last)
                pick.map { t to it }
            }
        }.awaitAll().flatten().sortedWith(compareBy({ stateOrder(it.second.state) }, { it.second.startMs }))
    }

    /** Fresh copy of one game (for live score updates on the detail screen). */
    suspend fun refresh(g: Game): Game =
        scoreboard(g.league).firstOrNull { it.id == g.id } ?: g

    private fun stateOrder(s: String) = when (s) { "in" -> 0; "pre" -> 1; else -> 2 }

    private fun parseEvent(e: JSONObject, league: League): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: TeamSide? = null
        var away: TeamSide? = null
        competitors.objects { c ->
            val team = c.optJSONObject("team") ?: return@objects
            val logo = team.s("logo") ?: team.optJSONArray("logos")?.optJSONObject(0)?.s("href")
            val score = c.optJSONObject("score")?.s("displayValue") ?: c.s("score")
            val record = c.optJSONArray("records")?.optJSONObject(0)?.s("summary")
                ?: c.optJSONArray("record")?.optJSONObject(0)?.s("displayValue")
            val side = TeamSide(
                id = team.s("id") ?: "",
                name = team.s("displayName") ?: team.s("name") ?: "?",
                nickname = team.s("name") ?: team.s("shortDisplayName") ?: "",
                location = team.s("location") ?: "",
                abbr = team.s("abbreviation") ?: "",
                logo = logo,
                score = score,
                record = record,
            )
            if (c.s("homeAway") == "home") home = side else away = side
        }
        val h = home ?: return null
        val a = away ?: return null
        val status = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
        val state = status?.s("state") ?: "pre"
        val detail = status?.s("shortDetail") ?: status?.s("detail") ?: ""
        val start = runCatching { Instant.parse(normaliseDate(e.s("date") ?: comp.s("date") ?: "")).toEpochMilli() }
            .getOrDefault(0L)
        val nets = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.objects { b ->
            b.optJSONObject("media")?.s("shortName")?.let { nets += it }
            b.optJSONArray("names")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { nets += it } }
        }
        comp.optJSONArray("geoBroadcasts")?.objects { b -> b.optJSONObject("media")?.s("shortName")?.let { nets += it } }
        val venue = comp.optJSONObject("venue")?.s("fullName")
        return Game(e.s("id") ?: "${h.abbr}-${a.abbr}-$start", league, start, state, detail, h, a, nets.toList(), venue)
    }

    private fun normaliseDate(d: String): String =
        if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(d)) d.replace("Z", ":00Z") else d

    companion object {
        private fun norm(s: String) =
            " " + s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

        private fun words(t: TeamSide): List<String> =
            listOf(t.nickname, t.name).map { norm(it).trim() }.filter { it.length >= 3 }.distinct()

        /**
         * Channels for a game. "Confirmed" = the provider's guide shows the game on that channel
         * around game time. Name-only guesses (team or network in the channel name) come after.
         */
        fun matchChannels(game: Game, channels: List<Channel>, epg: EpgIndex?): List<ChannelMatch> {
            val at = when {
                game.isLive -> System.currentTimeMillis()
                game.startMs > 0 -> game.startMs + 15 * 60_000
                else -> System.currentTimeMillis()
            }
            val homeW = words(game.home)
            val awayW = words(game.away)
            val leagueW = listOf(game.league.short, game.league.name).map { norm(it).trim() }
            val netW = game.broadcasts.flatMap { networkAliases(it) }.map { norm(it).trim() }.distinct()

            val out = ArrayList<ChannelMatch>()
            for (c in channels) {
                val prog = epg?.nowOn(c, at)
                var score = 0
                var confirmed = false
                if (prog != null) {
                    val text = norm(prog.title + " " + prog.desc)
                    val h = homeW.any { text.contains(" $it ") }
                    val a = awayW.any { text.contains(" $it ") }
                    if (h && a) { score = 200; confirmed = true }
                    else if ((h || a) && leagueW.any { text.contains(" $it ") }) { score = 150; confirmed = true }
                }
                if (!confirmed) {
                    val n = norm(c.name)
                    if ((homeW + awayW).any { n.contains(" $it ") }) score = 60
                    else if (netW.any { n.contains(" $it ") }) score = 40
                }
                if (score == 0) continue
                val n = norm(c.name)
                if (Regex(" (us|usa) ").containsMatchIn(n)) score += 8
                if (Regex(" (4k|uhd) ").containsMatchIn(n)) score += 5
                else if (Regex(" (fhd|1080p?) ").containsMatchIn(n)) score += 4
                else if (Regex(" hd ").containsMatchIn(n)) score += 2
                out += ChannelMatch(c, prog, confirmed, score)
            }
            return out.sortedByDescending { it.score }.take(60)
        }

        /** Quality labels pulled from a channel name, e.g. "FHD", "60 fps". */
        fun qualityTags(name: String): List<String> {
            val n = norm(name)
            val tags = ArrayList<String>()
            when {
                Regex(" (4k|uhd) ").containsMatchIn(n) -> tags += "4K"
                Regex(" (fhd|1080p?) ").containsMatchIn(n) -> tags += "FHD"
                Regex(" (hd|720p?) ").containsMatchIn(n) -> tags += "HD"
                Regex(" sd ").containsMatchIn(n) -> tags += "SD"
            }
            Regex(" (50|60) ?fps ").find(n)?.let { tags += "${it.groupValues[1]} fps" }
            return tags
        }

        private fun networkAliases(n: String): List<String> {
            val l = n.lowercase()
            return when {
                l.contains("prime") -> listOf("prime video", "amazon prime", "tnf")
                l.contains("peacock") -> listOf("peacock")
                l.contains("netflix") -> listOf("netflix")
                l.contains("espn+") -> listOf("espn plus")
                l.contains("nfl net") -> listOf("nfl network")
                l.contains("nba tv") -> listOf("nba tv")
                l.contains("mlb net") -> listOf("mlb network")
                l.contains("fs1") -> listOf("fs1", "fox sports 1")
                l.contains("tnt") -> listOf("tnt")
                l.contains("abc") -> listOf("abc")
                l.contains("cbs") -> listOf("cbs")
                l.contains("nbc") -> listOf("nbc")
                l.contains("fox") -> listOf("fox")
                else -> listOf(n)
            }
        }
    }
}
