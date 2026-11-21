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

data class League(val key: String, val name: String, val short: String, val path: String, val racing: Boolean = false)

data class TeamSide(
    val id: String,
    val name: String,
    val nickname: String,
    val location: String,
    val abbr: String,
    val logo: String?,
    val score: String?,
    val record: String?,
    /** Points per period (quarters, innings...), when the scoreboard has them. */
    val linescores: List<String> = emptyList(),
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
    /** Football: down & distance text, who has the ball, red zone. */
    val situation: String? = null,
    val possessionId: String? = null,
    val redZone: Boolean = false,
    val lastPlay: String? = null,
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
        League("f1", "Formula 1", "F1", "racing/f1", racing = true),
    )

    fun league(key: String) = allLeagues.firstOrNull { it.key == key }

    private val teamsCache = ConcurrentHashMap<String, List<TeamInfo>>()

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        http.newCall(req).execute().use { r -> parseObject(r.body?.string() ?: "") }
    }

    private fun base(l: League) = "https://site.api.espn.com/apis/site/v2/sports/${l.path}"

    /** Today's games for one league, live first. */
    suspend fun scoreboard(l: League, date: String? = null): List<Game> {
        if (l.racing) return emptyList()
        val url = "${base(l)}/scoreboard" + (date?.let { "?dates=$it" } ?: "")
        val o = runCatching { get(url) }.getOrNull() ?: return emptyList()
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

    /** Line score and team stats from ESPN's game summary. */
    suspend fun boxScore(g: Game): BoxScore? {
        val o = runCatching { get("${base(g.league)}/summary?event=${g.id}") }.getOrNull() ?: return null
        val comp = o.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        var awayLines = emptyList<String>(); var homeLines = emptyList<String>()
        comp?.optJSONArray("competitors")?.objects { c ->
            val l = ArrayList<String>()
            c.optJSONArray("linescores")?.objects { ls -> (ls.s("displayValue") ?: ls.s("value"))?.let { l += it.substringBefore(".0") } }
            if (c.s("homeAway") == "home") homeLines = l else awayLines = l
        }
        // Team stats: pair up the same label for both teams.
        val stats = ArrayList<Triple<String, String, String>>()
        val teams = o.optJSONObject("boxscore")?.optJSONArray("teams")
        if (teams != null && teams.length() == 2) {
            val t0 = teams.optJSONObject(0); val t1 = teams.optJSONObject(1)
            val firstIsHome = t0?.s("homeAway") == "home"
            val awayT = if (firstIsHome) t1 else t0
            val homeT = if (firstIsHome) t0 else t1
            val homeMap = HashMap<String, String>()
            homeT?.optJSONArray("statistics")?.objects { st -> (st.s("label") ?: st.s("name"))?.let { homeMap[it] = st.s("displayValue") ?: "" } }
            awayT?.optJSONArray("statistics")?.objects { st ->
                val label = st.s("label") ?: st.s("name") ?: return@objects
                stats += Triple(label, st.s("displayValue") ?: "", homeMap[label] ?: "")
            }
        }
        if (awayLines.isEmpty() && homeLines.isEmpty() && stats.isEmpty()) return null
        return BoxScore(awayLines, homeLines, stats.take(12))
    }

    /** Scoring plays for "catch me up" (newest last). Big sports list every basket, so callers trim. */
    suspend fun scoringPlays(g: Game): List<ScoringPlay> {
        val o = runCatching { get("${base(g.league)}/summary?event=${g.id}") }.getOrNull() ?: return emptyList()
        val out = ArrayList<ScoringPlay>()
        fun add(p: JSONObject) {
            val text = p.s("text") ?: p.s("shortText") ?: return
            val period = p.optJSONObject("period")?.let { it.s("displayValue") ?: it.s("number")?.let { n -> "P$n" } } ?: ""
            val clock = p.optJSONObject("clock")?.s("displayValue") ?: ""
            val teamId = p.optJSONObject("team")?.s("id")
            out += ScoringPlay(text, period, clock, p.s("awayScore") ?: "", p.s("homeScore") ?: "", teamId)
        }
        val sp = o.optJSONArray("scoringPlays")
        if (sp != null && sp.length() > 0) sp.objects { add(it) }
        else o.optJSONArray("plays")?.objects { p -> if (p.bool("scoringPlay")) add(p) }
        return out
    }

    /** Formula 1 race weekends (practice, qualifying, sprint, race). */
    suspend fun f1Weekends(): List<RaceWeekend> {
        val l = league("f1") ?: return emptyList()
        val o = runCatching { get("${base(l)}/scoreboard") }.getOrNull() ?: return emptyList()
        val out = ArrayList<RaceWeekend>()
        o.optJSONArray("events")?.objects { e ->
            val sessions = ArrayList<RaceSession>()
            e.optJSONArray("competitions")?.objects { c ->
                val type = c.optJSONObject("type")
                val name = type?.s("text") ?: type?.s("abbreviation") ?: "Session"
                val st = c.optJSONObject("status")?.optJSONObject("type")
                val start = runCatching { Instant.parse(normaliseDate(c.s("date") ?: c.s("startDate") ?: "")).toEpochMilli() }.getOrDefault(0L)
                // Leader / winner where available
                var leader: String? = null
                c.optJSONArray("competitors")?.objects { cp ->
                    if (leader == null && (cp.i("order", 99) == 1 || cp.bool("winner"))) {
                        leader = cp.optJSONObject("athlete")?.s("displayName") ?: cp.optJSONObject("athlete")?.s("shortName")
                    }
                }
                sessions += RaceSession(name, start, st?.s("state") ?: "pre", st?.s("shortDetail") ?: "", leader)
            }
            out += RaceWeekend(
                id = e.s("id") ?: "",
                name = e.s("name") ?: e.s("shortName") ?: "Grand Prix",
                circuit = e.optJSONObject("circuit")?.s("fullName") ?: e.optJSONArray("venues")?.optJSONObject(0)?.s("fullName"),
                sessions = sessions.sortedBy { it.startMs },
            )
        }
        return out
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
            val lines = ArrayList<String>()
            c.optJSONArray("linescores")?.objects { ls -> (ls.s("displayValue") ?: ls.s("value"))?.let { lines += it.substringBefore(".0") } }
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
                linescores = lines,
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
        val sit = comp.optJSONObject("situation")
        return Game(
            e.s("id") ?: "${h.abbr}-${a.abbr}-$start", league, start, state, detail, h, a, nets.toList(), venue,
            situation = sit?.s("downDistanceText") ?: sit?.s("shortDownDistanceText"),
            possessionId = sit?.s("possession"),
            redZone = sit?.bool("isRedZone") == true,
            lastPlay = sit?.optJSONObject("lastPlay")?.s("text"),
        )
    }

    private fun normaliseDate(d: String): String =
        if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(d)) d.replace("Z", ":00Z") else d

    companion object {
        private val RX_NON = Regex("[^a-z0-9 ]")
        private val RX_WS = Regex("\\s+")
        private val RX_US = Regex(" (us|usa) ")
        private val RX_4K = Regex(" (4k|uhd) ")
        private val RX_FHD = Regex(" (fhd|1080p?) ")
        private val RX_HD = Regex(" hd ")
        private fun norm(s: String) =
            " " + s.lowercase().replace(RX_NON, " ").replace(RX_WS, " ").trim() + " "

        /** Same as [matchChannels], off the main thread (big playlists take a while). */
        suspend fun matchChannelsAsync(game: Game, channels: List<Channel>, epg: EpgIndex?): List<ChannelMatch> =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { matchChannels(game, channels, epg) }

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
            val nameW = homeW + awayW
            for (c in channels) {
                val n = norm(c.name)
                val nameHit = nameW.any { n.contains(" $it ") }
                val netHit = !nameHit && netW.any { n.contains(" $it ") }
                val prog = epg?.nowOn(c, at)
                if (prog == null && !nameHit && !netHit) continue
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
                    if (nameHit) score = 60
                    else if (netHit) score = 40
                }
                if (score == 0) continue
                if (RX_US.containsMatchIn(n)) score += 8
                if (RX_4K.containsMatchIn(n)) score += 5
                else if (RX_FHD.containsMatchIn(n)) score += 4
                else if (RX_HD.containsMatchIn(n)) score += 2
                out += ChannelMatch(c, prog, confirmed, score)
            }
            return out.sortedByDescending { it.score }.take(60)
        }

        /** True if a guide listing clearly describes this game (both teams, or one team + league). */
        fun programmeShows(game: Game, title: String, desc: String): Boolean {
            val text = norm("$title $desc")
            val h = words(game.home).any { text.contains(" $it ") }
            val a = words(game.away).any { text.contains(" $it ") }
            val league = listOf(game.league.short, game.league.name).map { norm(it).trim() }.any { text.contains(" $it ") }
            return (h && a) || ((h || a) && league)
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

data class BoxScore(val awayLines: List<String>, val homeLines: List<String>, val stats: List<Triple<String, String, String>>)

data class RaceSession(val name: String, val startMs: Long, val state: String, val detail: String, val leader: String?)

data class RaceWeekend(val id: String, val name: String, val circuit: String?, val sessions: List<RaceSession>)

data class ScoringPlay(val text: String, val period: String, val clock: String, val awayScore: String, val homeScore: String, val teamId: String?)
