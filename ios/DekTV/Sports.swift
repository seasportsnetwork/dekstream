import Foundation
import SwiftUI

struct League: Identifiable, Hashable {
    let key: String; let name: String; let short: String; let path: String
    var id: String { key }
    static let all: [League] = [
        League(key: "nfl", name: "NFL", short: "NFL", path: "football/nfl"),
        League(key: "nba", name: "NBA", short: "NBA", path: "basketball/nba"),
        League(key: "mlb", name: "MLB", short: "MLB", path: "baseball/mlb"),
        League(key: "nhl", name: "NHL", short: "NHL", path: "hockey/nhl"),
        League(key: "wnba", name: "WNBA", short: "WNBA", path: "basketball/wnba"),
        League(key: "cfb", name: "College Football", short: "NCAAF", path: "football/college-football"),
        League(key: "cbb", name: "College Basketball", short: "NCAAM", path: "basketball/mens-college-basketball"),
        League(key: "mls", name: "MLS", short: "MLS", path: "soccer/usa.1"),
        League(key: "nwsl", name: "NWSL", short: "NWSL", path: "soccer/usa.nwsl"),
        League(key: "epl", name: "Premier League", short: "EPL", path: "soccer/eng.1"),
        League(key: "ucl", name: "Champions League", short: "UCL", path: "soccer/uefa.champions"),
        League(key: "uel", name: "Europa League", short: "UEL", path: "soccer/uefa.europa"),
        League(key: "laliga", name: "La Liga", short: "La Liga", path: "soccer/esp.1"),
        League(key: "seriea", name: "Serie A", short: "Serie A", path: "soccer/ita.1"),
        League(key: "bundesliga", name: "Bundesliga", short: "Bundesliga", path: "soccer/ger.1"),
        League(key: "ligue1", name: "Ligue 1", short: "Ligue 1", path: "soccer/fra.1"),
        League(key: "ligamx", name: "Liga MX", short: "Liga MX", path: "soccer/mex.1"),
    ]
    static func byKey(_ k: String) -> League? { all.first { $0.key == k } }
}

struct Team: Hashable { let name: String; let short: String; let abbr: String; let logo: String?; let score: String?; let record: String? }

struct Game: Identifiable, Hashable {
    let id: String
    let league: League
    let start: Date
    let state: String // pre / in / post
    let detail: String
    let home: Team
    let away: Team
    let broadcasts: [String]
    var isLive: Bool { state == "in" }
    var key: String { league.key + ":" + id }
}

enum Sports {
    private static let dayFmt: DateFormatter = { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd"; return f }()
    private static let iso = ISO8601DateFormatter()
    private static let isoShort: DateFormatter = { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd'T'HH:mm'Z'"; f.timeZone = TimeZone(identifier: "UTC"); return f }()

    static func day(_ d: Date) -> String { dayFmt.string(from: d) }

    /// ESPN's public scoreboard. `dates` is yyyyMMdd or a range yyyyMMdd-yyyyMMdd.
    static func scoreboard(_ l: League, dates: String) async -> [Game] {
        let url = "https://site.api.espn.com/apis/site/v2/sports/\(l.path)/scoreboard?dates=\(dates)&limit=300"
        guard let o = try? await Net.shared.json(url, ttl: 60) as? [String: Any] else { return [] }
        return o.arr("events").compactMap { e in
            guard let id = e.str("id"), let comp = e.arr("competitions").first else { return nil }
            let teams = comp.arr("competitors")
            func team(_ side: String) -> Team? {
                guard let c = teams.first(where: { $0.str("homeAway") == side }), let t = c.dict("team") else { return nil }
                return Team(name: t.str("displayName") ?? "", short: t.str("shortDisplayName") ?? t.str("name") ?? "", abbr: t.str("abbreviation") ?? "",
                            logo: t.str("logo"), score: c.str("score"), record: c.arr("records").first?.str("summary"))
            }
            guard let h = team("home"), let a = team("away") else { return nil }
            let st = e.dict("status")?.dict("type")
            let dateStr = e.str("date") ?? ""
            let start = iso.date(from: dateStr) ?? isoShort.date(from: dateStr) ?? Date()
            let casts = comp.arr("broadcasts").flatMap { ($0["names"] as? [String]) ?? [] } +
                comp.arr("geoBroadcasts").compactMap { $0.dict("media")?.str("shortName") }
            var seen = Set<String>()
            return Game(id: id, league: l, start: start, state: st?.str("state") ?? "pre", detail: st?.str("shortDetail") ?? "",
                        home: h, away: a, broadcasts: casts.filter { seen.insert($0).inserted })
        }.sorted { ($0.isLive ? 0 : $0.state == "pre" ? 1 : 2, $0.start) < ($1.isLive ? 0 : $1.state == "pre" ? 1 : 2, $1.start) }
    }

    static func today(_ leagues: [League]) async -> [Game] {
        await withTaskGroup(of: [Game].self) { g in
            for l in leagues { g.addTask { await scoreboard(l, dates: day(Date())) } }
            var out: [Game] = []
            for await r in g { out += r }
            // Keep live, upcoming, and games that finished in the last 4 hours.
            let cutoff = Date().addingTimeInterval(-4 * 3600)
            return out.filter { $0.state != "post" || $0.start > cutoff.addingTimeInterval(-3 * 3600) }
        }
    }

    /// Next game day in the coming week for a league (one request).
    static func next(_ l: League) async -> [Game] {
        let from = day(Date().addingTimeInterval(86400)), to = day(Date().addingTimeInterval(7 * 86400))
        let g = await scoreboard(l, dates: "\(from)-\(to)").filter { $0.state == "pre" }.sorted { $0.start < $1.start }
        guard let first = g.first else { return [] }
        let d = day(first.start)
        return g.filter { day($0.start) == d }
    }

    /// Channels likely showing a game: names mentioning a team, or a listed network.
    @MainActor static func channels(for g: Game, in all: [Channel], catalog: Catalog) -> [Channel] {
        let words = [g.home.short, g.away.short, g.home.name, g.away.name].map { $0.lowercased() }.filter { $0.count > 3 }
        let byGuide = all.filter { c in
            guard let p = catalog.now(c) ?? catalog.next(c) else { return false }
            let t = p.title.lowercased()
            return words.contains { t.contains($0) }
        }
        let nets = g.broadcasts.map { $0.lowercased() }
        let byNet = all.filter { c in let n = c.name.lowercased(); return nets.contains { !$0.isEmpty && n.contains($0) } }
        var seen = Set<Int>()
        return (byGuide + byNet).filter { seen.insert($0.streamId).inserted }.prefix(30).map { $0 }
    }
}

/// Small dot colour for a network name.
func networkColor(_ raw: String) -> Color {
    let l = raw.lowercased()
    if l.contains("prime") || l.contains("amazon") { return Color(red: 0, green: 0.66, blue: 0.88) }
    if l.contains("netflix") || l.contains("espn") || l.contains("youtube") { return Color(red: 0.85, green: 0.1, blue: 0.1) }
    if l.contains("peacock") { return Color(red: 0.96, green: 0.77, blue: 0.09) }
    if l.contains("nbc") { return Color(red: 0.6, green: 0.35, blue: 0.82) }
    if l.contains("cbs") || l.contains("paramount") { return Color(red: 0.18, green: 0.49, blue: 0.88) }
    if l.contains("fox") || l.contains("fs1") || l.contains("nfl net") { return Color(red: 0.16, green: 0.44, blue: 0.86) }
    return Color.gray
}
