import Foundation

/// The active playlist's content, loaded once and shared by every screen.
@MainActor
final class Catalog: ObservableObject {
    static let shared = Catalog()

    @Published var liveCats: [Category] = []
    @Published var channels: [Channel] = []
    @Published var loading = false
    @Published var error: String?
    @Published var guide: [String: [Program]] = [:]
    @Published var guideLoading = false

    private(set) var provider: Provider?
    private var sourceId: String?
    private var vodCatsCache: [Category]?
    private var serCatsCache: [Category]?
    private var vodCache: [String: [Vod]] = [:]
    private var serCache: [String: [Series]] = [:]
    private var allVodsCache: [Vod]?
    private var allSeriesCache: [Series]?

    /// Loads (or reloads when the playlist changed).
    func ensure(_ s: Source?, force: Bool = false) async {
        guard let s else { provider = nil; channels = []; liveCats = []; return }
        if !force && sourceId == s.id && !channels.isEmpty { return }
        sourceId = s.id
        provider = makeProvider(s)
        vodCatsCache = nil; serCatsCache = nil; vodCache = [:]; serCache = [:]; allVodsCache = nil; allSeriesCache = nil
        guide = [:]
        loading = true; error = nil
        do {
            guard let p = provider else { return }
            async let cats = p.liveCategories()
            async let chans = p.channels()
            let (c, ch) = try await (cats, chans)
            let adult = AppStore.shared.showAdult
            let hidden = Set(c.filter { !adult && Adult.isAdult($0.name) }.map(\.id))
            liveCats = c.filter { !hidden.contains($0.id) }
            channels = ch.filter { !hidden.contains($0.categoryId) && (adult || !Adult.isAdult($0.name)) }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
        Task { await loadGuide() }
    }

    func loadGuide() async {
        guard let url = provider?.guideURL, !guideLoading else { return }
        guideLoading = true
        let ids = Set(channels.compactMap { $0.epgId?.lowercased() })
        let result: [String: [Program]] = await Task.detached(priority: .utility) {
            guard let file = try? await Net.shared.download(url) else { return [:] }
            defer { try? FileManager.default.removeItem(at: file) }
            return GuideParser(channels: ids).parse(file)
        }.value
        guide = result
        guideLoading = false
    }

    func programs(_ c: Channel) -> [Program] { c.epgId.flatMap { guide[$0.lowercased()] } ?? [] }
    func now(_ c: Channel) -> Program? { programs(c).first { $0.isNow } }
    func next(_ c: Channel) -> Program? { let n = Date(); return programs(c).first { $0.start > n } }

    func channels(in cat: String) -> [Channel] { channels.filter { $0.categoryId == cat } }
    func channel(_ id: Int) -> Channel? { channels.first { $0.streamId == id } }

    func liveRequest(_ c: Channel) -> PlayRequest {
        PlayRequest(url: provider?.liveURL(c) ?? "", title: c.name, subtitle: now(c)?.title, isLive: true, resumeKey: nil, poster: c.icon, channel: c)
    }
    func vodRequest(_ v: Vod, from start: Double? = nil) -> PlayRequest {
        let key = "vod:\(v.streamId)"
        return PlayRequest(url: provider?.vodURL(v) ?? "", title: cleanTitle(v.name), subtitle: nil, isLive: false, resumeKey: key, poster: v.poster,
                           startAt: start ?? AppStore.shared.resume[key]?.position ?? 0)
    }
    func episodeRequest(_ e: Episode, show: Series, from start: Double? = nil) -> PlayRequest {
        let key = "ep:\(e.id)"
        return PlayRequest(url: provider?.episodeURL(e) ?? "", title: cleanTitle(show.name), subtitle: "S\(e.season) E\(e.number) · \(e.title)",
                           isLive: false, resumeKey: key, poster: show.cover, startAt: start ?? AppStore.shared.resume[key]?.position ?? 0)
    }

    // MARK: movies & series

    private func filterAdult<T>(_ l: [T], _ name: (T) -> String) -> [T] { AppStore.shared.showAdult ? l : l.filter { !Adult.isAdult(name($0)) } }

    func vodCategories() async -> [Category] {
        if let c = vodCatsCache { return c }
        let c = filterAdult((try? await provider?.vodCategories()) ?? [], \.name); vodCatsCache = c; return c
    }
    func seriesCategories() async -> [Category] {
        if let c = serCatsCache { return c }
        let c = filterAdult((try? await provider?.seriesCategories()) ?? [], \.name); serCatsCache = c; return c
    }
    func vods(_ cat: String) async -> [Vod] {
        if let v = vodCache[cat] { return v }
        let v = filterAdult((try? await provider?.vods(cat)) ?? [], \.name); vodCache[cat] = v; return v
    }
    func series(_ cat: String) async -> [Series] {
        if let s = serCache[cat] { return s }
        let s = filterAdult((try? await provider?.series(cat)) ?? [], \.name); serCache[cat] = s; return s
    }
    /// Every movie / series (Top 10, genres, search). One call on Xtream, cached for the session.
    func allVods() async -> [Vod] {
        if let v = allVodsCache { return v }
        let v = filterAdult((try? await provider?.vods(nil)) ?? [], \.name); allVodsCache = v; return v
    }
    func allSeries() async -> [Series] {
        if let s = allSeriesCache { return s }
        let s = filterAdult((try? await provider?.series(nil)) ?? [], \.name); allSeriesCache = s; return s
    }
}

// MARK: - Titles

private let prefixRx = try! NSRegularExpression(pattern: #"^\s*(?:[\[(|]?[A-Z0-9]{2,4}[\])|]?\s*[-:|]\s+)+"#)
private let junkRx = try! NSRegularExpression(pattern: #"\b(4k|uhd|fhd|hd|sd|hdr|hevc|x265|1080p|720p|2160p|multi|multisub|sub|subs|dub|dubbed|vostfr|imax|extended|remastered)\b"#, options: .caseInsensitive)
private let yearRx = try! NSRegularExpression(pattern: #"[(\[]\s*((?:19|20)\d\d)\s*[)\]]|\s[-–]\s*((?:19|20)\d\d)\s*$|\s((?:19|20)\d\d)\s*$"#)
private let parenYearRx = try! NSRegularExpression(pattern: #"\s*[(\[]\s*(?:19|20)\d\d\s*[)\]]"#)

private func replace(_ rx: NSRegularExpression, _ s: String, _ with: String = "") -> String {
    rx.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: with)
}

/// "4K: The Odyssey (2026) FHD" → "The Odyssey"
func cleanTitle(_ raw: String) -> String {
    var s = replace(prefixRx, raw)
    s = replace(parenYearRx, s)
    s = s.replacingOccurrences(of: #"\s*\b(4K|UHD|FHD|HD|SD|HDR|1080p|720p|2160p)\b\s*$"#, with: "", options: [.regularExpression, .caseInsensitive])
    let t = s.trimmingCharacters(in: .whitespaces)
    return t.isEmpty ? raw.trimmingCharacters(in: .whitespaces) : t
}

/// Match key + year, so playlist names and TMDB titles line up.
func titleKey(_ raw: String) -> (String, Int?) {
    var s = replace(junkRx, replace(prefixRx, raw), " ").trimmingCharacters(in: .whitespaces)
    var year: Int?
    if let m = yearRx.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) {
        for g in 1...3 { if let r = Range(m.range(at: g), in: s) { year = Int(s[r]); break } }
        if let r = Range(m.range, in: s) { s.removeSubrange(r) }
    }
    s = s.lowercased().replacingOccurrences(of: "&", with: " and ")
    s = s.replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    return (s, year)
}

/// Keeps the titles from `popular` (in order) that the playlist has.
func matchPopular<T>(_ popular: [(String, Int?)], _ all: [T], limit: Int, name: (T) -> String, hasArt: (T) -> Bool) -> [T] {
    var index: [String: [(T, Int?)]] = [:]
    for v in all { let (k, y) = titleKey(name(v)); if k.count >= 2 { index[k, default: []].append((v, y)) } }
    var out: [T] = []
    var used = Set<String>()
    for (title, year) in popular {
        let k = titleKey(title).0
        guard !used.contains(k), let cands = index[k] else { continue }
        let ok = cands.filter { c in year == nil || c.1 == nil || abs((c.1 ?? 0) - (year ?? 0)) <= 1 }
        guard let best = (ok.first { hasArt($0.0) } ?? ok.first)?.0 else { continue }
        out.append(best); used.insert(k)
        if out.count == limit { break }
    }
    return out
}
