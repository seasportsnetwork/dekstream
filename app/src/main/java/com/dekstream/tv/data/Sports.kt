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

data class TeamSide(val name: String, val nickname: String, val abbr: String, val logo: String?, val score: String?)

data class Game(
    val id: String,
    val league: String,
    val startMs: Long,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val home: TeamSide,
    val away: TeamSide,
    val broadcasts: List<String>,
) {
    val isLive get() = state == "in"
    fun involves(abbr: String) = home.abbr.equals(abbr, true) || away.abbr.equals(abbr, true)
}

data class MyTeam(val league: String, val sportPath: String, val abbr: String, val label: String)

/** Uses ESPN's public scoreboard JSON (no key needed). */
class SportsRepo(private val http: OkHttpClient) {

    val myTeams = listOf(
        MyTeam("NFL", "football/nfl", "SEA", "Seahawks"),
        MyTeam("NBA", "basketball/nba", "LAL", "Lakers"),
        MyTeam("MLB", "baseball/mlb", "SEA", "Mariners"),
    )

    val leagues = listOf(
        "NFL" to "football/nfl",
        "NBA" to "basketball/nba",
        "MLB" to "baseball/mlb",
        "NHL" to "hockey/nhl",
        "College Football" to "football/college-football",
        "College Basketball" to "basketball/mens-college-basketball",
    )

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            parseObject(r.body?.string() ?: "")
        }
    }

    /** Today's scoreboard for one league. */
    suspend fun scoreboard(league: String, path: String): List<Game> {
        val o = runCatching { get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard") }.getOrNull()
            ?: return emptyList()
        val events = o.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        events.objects { e -> parseEvent(e, league)?.let { out += it } }
        return out.sortedWith(compareBy<Game>({ stateOrder(it.state) }, { it.startMs }))
    }

    /** Live / next / last game for each of my teams. */
    suspend fun myTeamGames(): List<Pair<MyTeam, Game>> = coroutineScope {
        myTeams.map { t ->
            async {
                val o = runCatching {
                    get("https://site.api.espn.com/apis/site/v2/sports/${t.sportPath}/teams/${t.abbr.lowercase()}/schedule")
                }.getOrNull() ?: return@async emptyList<Pair<MyTeam, Game>>()
                val events = o.optJSONArray("events") ?: return@async emptyList()
                val games = ArrayList<Game>()
                events.objects { e -> parseEvent(e, t.league)?.let { games += it } }
                val now = System.currentTimeMillis()
                val live = games.filter { it.isLive }
                val next = games.filter { it.state == "pre" && it.startMs > now - 3 * 3600_000 }.minByOrNull { it.startMs }
                val last = games.filter { it.state == "post" }.maxByOrNull { it.startMs }
                (live + listOfNotNull(next, last)).distinctBy { it.id }.map { t to it }
            }
        }.awaitAll().flatten()
    }

    private fun stateOrder(s: String) = when (s) { "in" -> 0; "pre" -> 1; else -> 2 }

    private fun parseEvent(e: JSONObject, league: String): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: TeamSide? = null
        var away: TeamSide? = null
        competitors.objects { c ->
            val team = c.optJSONObject("team") ?: return@objects
            val logo = team.s("logo") ?: team.optJSONArray("logos")?.optJSONObject(0)?.s("href")
            // scoreboard: "score":"21"; schedule: "score":{"displayValue":"21"}
            val score = c.optJSONObject("score")?.s("displayValue") ?: c.s("score")
            val side = TeamSide(
                name = team.s("displayName") ?: team.s("name") ?: "?",
                nickname = team.s("shortDisplayName") ?: team.s("name") ?: "",
                abbr = team.s("abbreviation") ?: "",
                logo = logo,
                score = score,
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
        comp.optJSONArray("geoBroadcasts")?.objects { b ->
            b.optJSONObject("media")?.s("shortName")?.let { nets += it }
        }
        return Game(e.s("id") ?: "${h.abbr}-${a.abbr}-$start", league, start, state, detail, h, a, nets.toList())
    }

    /** ESPN sometimes omits seconds: "2026-11-18T01:15Z". */
    private fun normaliseDate(d: String): String =
        if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(d)) d.replace("Z", ":00Z") else d

    companion object {
        /** Ranks live channels that probably carry this game: team-named channels first, then networks. */
        fun matchChannels(game: Game, channels: List<Channel>): List<Channel> {
            fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            val teamWords = listOf(game.home.nickname, game.away.nickname, game.home.name, game.away.name)
                .map { norm(it) }.filter { it.length >= 3 }.distinct()
            val netWords = game.broadcasts.flatMap { networkAliases(it) }.map { norm(it) }.distinct()

            val scored = channels.mapNotNull { c ->
                val n = " ${norm(c.name)} "
                var score = 0
                if (teamWords.any { n.contains(" $it ") }) score += 100
                if (netWords.any { n.contains(" $it ") }) score += 50
                if (score == 0) return@mapNotNull null
                if (Regex("\\b(us|usa)\\b").containsMatchIn(n)) score += 10
                if (n.contains(" hd ") || n.contains(" fhd ") || n.contains(" 4k ")) score += 3
                c to score
            }
            return scored.sortedByDescending { it.second }.map { it.first }.take(40)
        }

        private fun networkAliases(n: String): List<String> {
            val l = n.lowercase()
            return when {
                l.contains("prime") -> listOf("prime video", "amazon prime", "tnf", "thursday night football")
                l.contains("peacock") -> listOf("peacock")
                l.contains("netflix") -> listOf("netflix")
                l.contains("espn+") -> listOf("espn plus", "espn+")
                l.contains("nfl net") -> listOf("nfl network")
                l.contains("nba tv") -> listOf("nba tv")
                l.contains("mlb net") -> listOf("mlb network")
                l.contains("tnt") -> listOf("tnt")
                l.contains("abc") -> listOf("abc")
                l.contains("cbs") -> listOf("cbs")
                l.contains("nbc") -> listOf("nbc")
                l.contains("fox") && l.contains("fs1") -> listOf("fs1")
                l.contains("fs1") -> listOf("fs1", "fox sports 1")
                l.contains("fox") -> listOf("fox")
                else -> listOf(n)
            }
        }
    }
}
