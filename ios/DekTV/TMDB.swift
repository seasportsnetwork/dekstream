import Foundation

struct CastMember: Identifiable, Hashable { let name: String; let character: String?; let photo: String?; var id: String { name + (character ?? "") } }
struct Review: Identifiable, Hashable { let author: String; let rating: Double?; let content: String; let created: String?; var id: String { author + (created ?? "") } }
struct Trailer: Identifiable, Hashable { let name: String; let youtubeId: String; var id: String { youtubeId } }
struct TitleExtras {
    var overview: String?
    var backdrop: String?
    var year: String?
    var runtime: Int?
    var rating: Double?
    var genres: [String] = []
    var cast: [CastMember] = []
    var directors: [String] = []
    var writers: [String] = []
    var reviews: [Review] = []
    var trailers: [Trailer] = []
}

/// TMDB (needs a free key in Settings). Accepts the short API key or the long Read Access Token.
enum TMDB {
    static var key: String { AppStore.shared.tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines) }
    static var hasKey: Bool { !key.isEmpty }

    private static func get(_ path: String, _ q: String = "", ttl: TimeInterval = 12 * 3600) async throws -> [String: Any] {
        let k = key
        let bearer = k.hasPrefix("eyJ")
        var url = "https://api.themoviedb.org/3/\(path)?language=en-US"
        if !q.isEmpty { url += "&" + q }
        if !bearer { url += "&api_key=\(enc(k))" }
        let headers = bearer ? ["Authorization": "Bearer \(k)"] : [:]
        return try await Net.shared.json(url, ttl: ttl, headers: headers) as? [String: Any] ?? [:]
    }

    static func test(_ k: String) async -> Bool {
        let t = k.trimmingCharacters(in: .whitespacesAndNewlines)
        let bearer = t.hasPrefix("eyJ")
        let url = "https://api.themoviedb.org/3/configuration" + (bearer ? "" : "?api_key=\(enc(t))")
        return (try? await Net.shared.data(url, headers: bearer ? ["Authorization": "Bearer \(t)"] : [:])) != nil
    }

    private static func findId(_ tv: Bool, tmdbId: String?, title: String, year: String?) async -> Int? {
        if let t = tmdbId, let i = Int(t.filter(\.isNumber)), i > 0 { return i }
        var q = "query=\(enc(title))"
        if let y = year?.prefix(4), !y.isEmpty { q += tv ? "&first_air_date_year=\(y)" : "&year=\(y)" }
        let o = try? await get("search/\(tv ? "tv" : "movie")", q, ttl: 24 * 3600)
        return (o?["results"] as? [[String: Any]])?.first.map { $0.int("id") }.flatMap { $0 > 0 ? $0 : nil }
    }

    static func extras(tv: Bool, tmdbId: String?, title: String, year: String?) async -> TitleExtras? {
        guard hasKey, let id = await findId(tv, tmdbId: tmdbId, title: title, year: year) else { return nil }
        let credits = tv ? "aggregate_credits" : "credits"
        guard let o = try? await get("\(tv ? "tv" : "movie")/\(id)", "append_to_response=\(credits),reviews,videos", ttl: 24 * 3600) else { return nil }
        var e = TitleExtras()
        e.overview = o.str("overview")
        e.backdrop = o.str("backdrop_path").map { "https://image.tmdb.org/t/p/w1280\($0)" }
        e.year = (o.str(tv ? "first_air_date" : "release_date")).map { String($0.prefix(4)) }
        e.runtime = tv ? (o["episode_run_time"] as? [Int])?.first : o.int("runtime")
        e.rating = (o["vote_average"] as? NSNumber)?.doubleValue
        e.genres = o.arr("genres").compactMap { $0.str("name") }
        let cr = o.dict(credits)
        e.cast = (cr?.arr("cast") ?? []).prefix(20).compactMap { p in
            guard let n = p.str("name") else { return nil }
            let ch = tv ? (p.arr("roles").first?.str("character")) : p.str("character")
            return CastMember(name: n, character: ch, photo: p.str("profile_path").map { "https://image.tmdb.org/t/p/w185\($0)" })
        }
        let crew = cr?.arr("crew") ?? []
        if tv { e.directors = o.arr("created_by").compactMap { $0.str("name") } }
        else { e.directors = Array(Set(crew.filter { $0.str("job") == "Director" }.compactMap { $0.str("name") })).sorted() }
        let writing: Set<String> = ["Screenplay", "Writer", "Story", "Novel", "Teleplay"]
        e.writers = Array(NSOrderedSet(array: crew.filter { $0.str("department") == "Writing" && (writing.contains($0.str("job") ?? "") ||
            writing.contains($0.arr("jobs").first?.str("job") ?? "")) }.compactMap { $0.str("name") })).compactMap { $0 as? String }.prefix(4).map { $0 }
        e.reviews = (o.dict("reviews")?.arr("results") ?? []).prefix(12).compactMap { r in
            guard let c = r.str("content") else { return nil }
            let text = c.replacingOccurrences(of: "[*_#>]+", with: "", options: .regularExpression)
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
            return Review(author: r.str("author") ?? "Viewer", rating: (r.dict("author_details")?["rating"] as? NSNumber)?.doubleValue,
                          content: text, created: r.str("created_at").map { String($0.prefix(10)) })
        }
        let vids = (o.dict("videos")?.arr("results") ?? []).filter { $0.str("site") == "YouTube" && ["Trailer", "Teaser"].contains($0.str("type") ?? "") && $0.str("key") != nil }
        let sorted = vids.sorted { a, b in
            let ra = (a.str("type") == "Trailer" ? 0 : 1, (a["official"] as? Bool) == true ? 0 : 1)
            let rb = (b.str("type") == "Trailer" ? 0 : 1, (b["official"] as? Bool) == true ? 0 : 1)
            return ra < rb
        }
        var seen = Set<String>()
        e.trailers = sorted.compactMap { v -> Trailer? in
            guard let k = v.str("key"), seen.insert(k).inserted else { return nil }
            return Trailer(name: v.str("name") ?? "Trailer", youtubeId: k)
        }.prefix(6).map { $0 }
        return e
    }

    /// Trending this week (with a key) as (title, year); otherwise Cinemeta's popular list.
    static func popular(tv: Bool) async -> (thisWeek: Bool, titles: [(String, Int?)]) {
        if hasKey {
            var out: [(String, Int?)] = []
            for page in 1...3 {
                guard let o = try? await get("trending/\(tv ? "tv" : "movie")/week", "page=\(page)", ttl: 6 * 3600) else { continue }
                for m in o.arr("results") {
                    let name = m.str(tv ? "name" : "title") ?? ""
                    out.append((name, m.str(tv ? "first_air_date" : "release_date").flatMap { Int($0.prefix(4)) }))
                }
            }
            if !out.isEmpty { return (true, out) }
        }
        var out: [(String, Int?)] = []
        for skip in [0, 50, 100] {
            let url = "https://v3-cinemeta.strem.io/catalog/\(tv ? "series" : "movie")/top" + (skip > 0 ? "/skip=\(skip)" : "") + ".json"
            guard let o = try? await Net.shared.json(url, ttl: 6 * 3600) as? [String: Any] else { continue }
            for m in o.arr("metas") { out.append((m.str("name") ?? "", m.str("year").flatMap { Int($0.prefix(4)) })) }
        }
        return (false, out)
    }

    /// Most popular in a genre (about 100), as (title, year).
    static func genreTitles(tv: Bool, genreId: Int) async -> [(String, Int?)] {
        guard hasKey else { return [] }
        var out: [(String, Int?)] = []
        for page in 1...5 {
            guard let o = try? await get("discover/\(tv ? "tv" : "movie")", "with_genres=\(genreId)&sort_by=popularity.desc&vote_count.gte=40&include_adult=false&page=\(page)") else { continue }
            for m in o.arr("results") { out.append((m.str(tv ? "name" : "title") ?? "", m.str(tv ? "first_air_date" : "release_date").flatMap { Int($0.prefix(4)) })) }
        }
        return out
    }
}

struct Genre: Identifiable, Hashable {
    let name: String; let movieId: Int; let tvId: Int?; let words: [String]
    var id: String { name }
    static let all: [Genre] = [
        Genre(name: "Action", movieId: 28, tvId: 10759, words: ["action"]),
        Genre(name: "Adventure", movieId: 12, tvId: 10759, words: ["adventure"]),
        Genre(name: "Animation", movieId: 16, tvId: 16, words: ["animation", "animated", "anime", "cartoon"]),
        Genre(name: "Comedy", movieId: 35, tvId: 35, words: ["comedy", "comedies"]),
        Genre(name: "Crime", movieId: 80, tvId: 80, words: ["crime", "gangster"]),
        Genre(name: "Documentary", movieId: 99, tvId: 99, words: ["documentar", "docu"]),
        Genre(name: "Drama", movieId: 18, tvId: 18, words: ["drama"]),
        Genre(name: "Family", movieId: 10751, tvId: 10751, words: ["family", "kids", "children"]),
        Genre(name: "Fantasy", movieId: 14, tvId: 10765, words: ["fantasy"]),
        Genre(name: "History", movieId: 36, tvId: nil, words: ["history", "historical"]),
        Genre(name: "Horror", movieId: 27, tvId: nil, words: ["horror"]),
        Genre(name: "Music", movieId: 10402, tvId: nil, words: ["music", "musical", "concert"]),
        Genre(name: "Mystery", movieId: 9648, tvId: 9648, words: ["mystery"]),
        Genre(name: "Romance", movieId: 10749, tvId: nil, words: ["romance", "romantic"]),
        Genre(name: "Sci-Fi", movieId: 878, tvId: 10765, words: ["sci-fi", "scifi", "science fiction", "sci fi"]),
        Genre(name: "Thriller", movieId: 53, tvId: nil, words: ["thriller", "suspense"]),
        Genre(name: "War", movieId: 10752, tvId: 10768, words: ["war", "military"]),
        Genre(name: "Western", movieId: 37, tvId: 37, words: ["western"]),
    ]
}
