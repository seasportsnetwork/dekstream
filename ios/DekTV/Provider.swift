import Foundation

/// Loads channels, movies and series from one playlist (Xtream API or an M3U link).
protocol Provider {
    func liveCategories() async throws -> [Category]
    func channels() async throws -> [Channel]
    func vodCategories() async throws -> [Category]
    func vods(_ categoryId: String?) async throws -> [Vod]
    func seriesCategories() async throws -> [Category]
    func series(_ categoryId: String?) async throws -> [Series]
    func vodInfo(_ v: Vod) async throws -> VodInfo
    func seriesInfo(_ s: Series) async throws -> SeriesInfo
    func liveURL(_ c: Channel) -> String
    func vodURL(_ v: Vod) -> String
    func episodeURL(_ e: Episode) -> String
    var guideURL: String? { get }
}

func makeProvider(_ s: Source) -> Provider { s.type == .xtream ? XtreamProvider(s) : M3UProvider(s) }

// MARK: - Xtream

final class XtreamProvider: Provider {
    let src: Source
    init(_ s: Source) { src = s }

    private func api(_ action: String?, _ params: [String: String] = [:]) async throws -> Any {
        var u = "\(src.base)/player_api.php?username=\(enc(src.username))&password=\(enc(src.password))"
        if let a = action { u += "&action=\(a)" }
        params.forEach { u += "&\($0)=\(enc($1))" }
        return try await Net.shared.json(u)
    }

    func login() async throws {
        let o = try await api(nil) as? [String: Any]
        let auth = o?.dict("user_info")?.int("auth", 0) ?? 0
        if auth != 1 { throw NetError.message("The server didn't accept that username and password") }
    }

    private func cats(_ action: String) async throws -> [Category] {
        let a = try await api(action) as? [[String: Any]] ?? []
        return a.compactMap { o in o.str("category_id").map { Category(id: $0, name: o.str("category_name") ?? "Other") } }
    }
    func liveCategories() async throws -> [Category] { try await cats("get_live_categories") }
    func vodCategories() async throws -> [Category] { try await cats("get_vod_categories") }
    func seriesCategories() async throws -> [Category] { try await cats("get_series_categories") }

    func channels() async throws -> [Channel] {
        let a = try await api("get_live_streams") as? [[String: Any]] ?? []
        return a.compactMap { o in
            let id = o.int("stream_id", -1); guard id >= 0 else { return nil }
            return Channel(streamId: id, num: o.int("num"), name: o.str("name") ?? "Channel \(id)", icon: o.str("stream_icon"),
                           categoryId: o.str("category_id") ?? "", epgId: o.str("epg_channel_id"), url: nil)
        }
    }

    func vods(_ categoryId: String?) async throws -> [Vod] {
        let a = try await api("get_vod_streams", categoryId.map { ["category_id": $0] } ?? [:]) as? [[String: Any]] ?? []
        return a.compactMap { o in
            let id = o.int("stream_id", -1); guard id >= 0 else { return nil }
            return Vod(streamId: id, name: o.str("name") ?? "Movie", poster: o.str("stream_icon"), categoryId: o.str("category_id") ?? categoryId ?? "",
                       ext: o.str("container_extension") ?? "mp4", rating: o.str("rating"), url: nil)
        }
    }

    func series(_ categoryId: String?) async throws -> [Series] {
        let a = try await api("get_series", categoryId.map { ["category_id": $0] } ?? [:]) as? [[String: Any]] ?? []
        return a.compactMap { o in
            let id = o.int("series_id", -1); guard id >= 0 else { return nil }
            return Series(seriesId: id, name: o.str("name") ?? "Series", cover: o.str("cover"), categoryId: o.str("category_id") ?? categoryId ?? "",
                          plot: o.str("plot"), rating: o.str("rating"), genre: o.str("genre"), releaseDate: o.str("releaseDate") ?? o.str("release_date"))
        }
    }

    func vodInfo(_ v: Vod) async throws -> VodInfo {
        let o = try await api("get_vod_info", ["vod_id": "\(v.streamId)"]) as? [String: Any] ?? [:]
        let i = o.dict("info") ?? [:]
        let backdrop = (i["backdrop_path"] as? [String])?.first?.nilIfBlank ?? i.str("backdrop_path").flatMap { $0.hasPrefix("http") ? $0 : nil }
        return VodInfo(plot: i.str("plot") ?? i.str("description"), genre: i.str("genre"), cast: i.str("cast") ?? i.str("actors"),
                       director: i.str("director"), releaseDate: i.str("releasedate") ?? i.str("release_date"), duration: i.str("duration"),
                       backdrop: backdrop ?? i.str("movie_image"), rating: i.str("rating"), tmdbId: i.str("tmdb_id"))
    }

    func seriesInfo(_ s: Series) async throws -> SeriesInfo {
        let o = try await api("get_series_info", ["series_id": "\(s.seriesId)"]) as? [String: Any] ?? [:]
        let i = o.dict("info") ?? [:]
        var seasons: [Int: [Episode]] = [:]
        if let eps = o["episodes"] as? [String: Any] {
            for (k, v) in eps {
                let n = Int(k) ?? 0
                let list = (v as? [[String: Any]] ?? []).compactMap { e -> Episode? in
                    guard let id = e.str("id") else { return nil }
                    let info = e.dict("info") ?? [:]
                    return Episode(id: id, season: n, number: e.int("episode_num"), title: e.str("title") ?? "Episode",
                                   ext: e.str("container_extension") ?? "mp4", image: info.str("movie_image"), plot: info.str("plot"), duration: info.str("duration"))
                }
                seasons[n] = list.sorted { $0.number < $1.number }
            }
        } else if let arr = o["episodes"] as? [[[String: Any]]] {
            for (idx, list) in arr.enumerated() {
                seasons[idx + 1] = list.compactMap { e in
                    guard let id = e.str("id") else { return nil }
                    let info = e.dict("info") ?? [:]
                    return Episode(id: id, season: idx + 1, number: e.int("episode_num"), title: e.str("title") ?? "Episode",
                                   ext: e.str("container_extension") ?? "mp4", image: info.str("movie_image"), plot: info.str("plot"), duration: info.str("duration"))
                }
            }
        }
        let backdrop = (i["backdrop_path"] as? [String])?.first?.nilIfBlank
        return SeriesInfo(plot: i.str("plot"), cast: i.str("cast"), director: i.str("director"), backdrop: backdrop ?? i.str("cover"), seasons: seasons)
    }

    // HLS for live TV: iPhone's player handles .m3u8 natively.
    func liveURL(_ c: Channel) -> String { "\(src.base)/live/\(src.username)/\(src.password)/\(c.streamId).m3u8" }
    func vodURL(_ v: Vod) -> String { "\(src.base)/movie/\(src.username)/\(src.password)/\(v.streamId).\(v.ext)" }
    func episodeURL(_ e: Episode) -> String { "\(src.base)/series/\(src.username)/\(src.password)/\(e.id).\(e.ext)" }
    var guideURL: String? { src.epgUrl.nilIfBlank ?? "\(src.base)/xmltv.php?username=\(enc(src.username))&password=\(enc(src.password))" }
}

// MARK: - M3U

/// Parsed M3U playlist: live channels, plus movies/series when the playlist groups them that way.
final class M3UProvider: Provider {
    let src: Source
    private var parsed: (cats: [Category], chans: [Channel], vodCats: [Category], vods: [Vod], serCats: [Category], series: [Series], eps: [Int: [Episode]], epg: String?)?
    init(_ s: Source) { src = s }

    private func load() async throws -> (cats: [Category], chans: [Channel], vodCats: [Category], vods: [Vod], serCats: [Category], series: [Series], eps: [Int: [Episode]], epg: String?) {
        if let p = parsed { return p }
        let file = try await Net.shared.download(src.url)
        defer { try? FileManager.default.removeItem(at: file) }
        guard let text = String(data: try Data(contentsOf: file), encoding: .utf8) ?? String(data: try Data(contentsOf: file), encoding: .isoLatin1) else {
            throw NetError.message("Couldn't read the playlist")
        }
        var cats: [String: Category] = [:], vodCats: [String: Category] = [:], serCats: [String: Category] = [:]
        var chans: [Channel] = [], vods: [Vod] = [], seriesMap: [Int: Series] = [:], eps: [Int: [Episode]] = [:]
        var epg: String?
        var pending: (name: String, logo: String?, group: String, tvgId: String?)?
        func attr(_ line: Substring, _ key: String) -> String? {
            guard let r = line.range(of: key + "=\"") else { return nil }
            let rest = line[r.upperBound...]
            guard let end = rest.firstIndex(of: "\"") else { return nil }
            return String(rest[..<end]).nilIfBlank
        }
        var num = 0
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXTM3U") {
                epg = attr(Substring(line), "url-tvg") ?? attr(Substring(line), "x-tvg-url")
            } else if line.hasPrefix("#EXTINF") {
                let l = Substring(line)
                let name = line.split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false).dropFirst().first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? "Channel"
                pending = (name, attr(l, "tvg-logo"), attr(l, "group-title") ?? "Other", attr(l, "tvg-id"))
            } else if !line.hasPrefix("#"), !line.isEmpty, let p = pending {
                pending = nil
                let url = line
                let id = javaStableId(url)
                let lower = url.lowercased()
                if lower.contains("/movie/") {
                    let c = vodCats[p.group] ?? Category(id: "c\(javaStableId(p.group))", name: p.group); vodCats[p.group] = c
                    let ext = url.split(separator: ".").last.map(String.init) ?? "mp4"
                    vods.append(Vod(streamId: id, name: p.name, poster: p.logo, categoryId: c.id, ext: ext, rating: nil, url: url))
                } else if lower.contains("/series/") {
                    let c = serCats[p.group] ?? Category(id: "c\(javaStableId(p.group))", name: p.group); serCats[p.group] = c
                    // "Show S01 E02" → group episodes under the show.
                    var show = p.name, season = 1, number = 1
                    if let r = p.name.range(of: #"[Ss](\d{1,2})\s*[Ee](\d{1,3})"#, options: .regularExpression) {
                        show = String(p.name[..<r.lowerBound]).trimmingCharacters(in: CharacterSet(charactersIn: " -|"))
                        let digits = p.name[r].split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
                        if digits.count >= 2 { season = digits[0]; number = digits[1] }
                    }
                    let sid = javaStableId(show.lowercased() + "|" + p.group)
                    if seriesMap[sid] == nil { seriesMap[sid] = Series(seriesId: sid, name: show, cover: p.logo, categoryId: c.id, plot: nil, rating: nil, genre: nil, releaseDate: nil) }
                    let ext = url.split(separator: ".").last.map(String.init) ?? "mp4"
                    eps[sid, default: []].append(Episode(id: url, season: season, number: number, title: p.name, ext: ext, image: p.logo, plot: nil, duration: nil))
                } else {
                    let c = cats[p.group] ?? Category(id: "c\(javaStableId(p.group))", name: p.group); cats[p.group] = c
                    num += 1
                    chans.append(Channel(streamId: id, num: num, name: p.name, icon: p.logo, categoryId: c.id, epgId: p.tvgId, url: url))
                }
            }
        }
        let order: ([String: Category]) -> [Category] = { d in d.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending } }
        let r = (order(cats), chans, order(vodCats), vods, order(serCats), Array(seriesMap.values), eps, epg)
        parsed = r
        return r
    }

    func liveCategories() async throws -> [Category] { try await load().cats }
    func channels() async throws -> [Channel] { try await load().chans }
    func vodCategories() async throws -> [Category] { try await load().vodCats }
    func vods(_ categoryId: String?) async throws -> [Vod] { let v = try await load().vods; return categoryId == nil ? v : v.filter { $0.categoryId == categoryId } }
    func seriesCategories() async throws -> [Category] { try await load().serCats }
    func series(_ categoryId: String?) async throws -> [Series] { let s = try await load().series; return categoryId == nil ? s : s.filter { $0.categoryId == categoryId } }
    func vodInfo(_ v: Vod) async throws -> VodInfo { VodInfo() }
    func seriesInfo(_ s: Series) async throws -> SeriesInfo {
        let eps = try await load().eps[s.seriesId] ?? []
        return SeriesInfo(plot: nil, cast: nil, director: nil, backdrop: nil, seasons: Dictionary(grouping: eps, by: \.season).mapValues { $0.sorted { $0.number < $1.number } })
    }
    func liveURL(_ c: Channel) -> String { c.url ?? "" }
    func vodURL(_ v: Vod) -> String { v.url ?? "" }
    func episodeURL(_ e: Episode) -> String { e.id }
    var guideURL: String? { src.epgUrl.nilIfBlank ?? parsed?.epg }
}

// MARK: - Guide (XMLTV)

/// The provider's guide, streamed from a downloaded file and trimmed to a window around now.
final class GuideParser: NSObject, XMLParserDelegate {
    private var want: Set<String>
    private var out: [String: [Program]] = [:]
    private var cur: (ch: String, start: Date, end: Date)?
    private var field = ""
    private var title = ""
    private var desc = ""
    private let from = Date().addingTimeInterval(-3 * 3600)
    private let to = Date().addingTimeInterval(24 * 3600)
    private static let fmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMddHHmmss Z"; return f
    }()
    private static let fmtNoZone: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMddHHmmss"; f.timeZone = TimeZone(identifier: "UTC"); return f
    }()

    init(channels: Set<String>) { want = Set(channels.map { $0.lowercased() }) }

    static func date(_ s: String) -> Date? {
        let t = s.trimmingCharacters(in: .whitespaces)
        return fmt.date(from: t) ?? fmtNoZone.date(from: String(t.prefix(14)))
    }

    func parse(_ file: URL) -> [String: [Program]] {
        guard let p = XMLParser(contentsOf: file) else { return [:] }
        p.delegate = self
        p.parse()
        return out.mapValues { $0.sorted { $0.start < $1.start } }
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes a: [String: String] = [:]) {
        if name == "programme" {
            guard let ch = a["channel"]?.lowercased(), want.isEmpty || want.contains(ch),
                  let s = a["start"].flatMap(Self.date), let e = a["stop"].flatMap(Self.date), e > from, s < to else { cur = nil; return }
            cur = (ch, s, e); title = ""; desc = ""
        } else if cur != nil && (name == "title" || name == "desc") {
            field = name
        }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard cur != nil else { return }
        if field == "title" { title += string } else if field == "desc" { desc += string }
    }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if name == "title" || name == "desc" { field = "" }
        if name == "programme", let c = cur {
            out[c.ch, default: []].append(Program(start: c.start, end: c.end, title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                                  desc: desc.nilIfBlank))
            cur = nil
        }
    }
}
