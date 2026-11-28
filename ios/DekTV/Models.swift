import Foundation

enum SourceType: String, Codable, CaseIterable, Identifiable {
    case xtream = "XTREAM"
    case m3u = "M3U_URL"
    var id: String { rawValue }
    var label: String { self == .xtream ? "Xtream Codes" : "M3U playlist link" }
}

/// One IPTV playlist. Field names match the Android app's sync format.
struct Source: Codable, Identifiable, Hashable {
    var id: String = String(UUID().uuidString.prefix(8)).lowercased()
    var name: String
    var type: SourceType
    var url: String
    var username: String = ""
    var password: String = ""
    var epgUrl: String = ""

    /// Xtream server base, e.g. http://host:8080
    var base: String {
        var s = url.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") { s = "http://" + s }
        return s
    }
}

struct Category: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Channel: Identifiable, Hashable {
    let streamId: Int
    let num: Int
    let name: String
    let icon: String?
    let categoryId: String
    let epgId: String?
    /// Direct URL (M3U); nil for Xtream (built from the account).
    let url: String?
    var id: Int { streamId }
}

struct Program: Identifiable, Hashable {
    let start: Date
    let end: Date
    let title: String
    let desc: String?
    var id: String { "\(start.timeIntervalSince1970)-\(title)" }
    var isNow: Bool { let n = Date(); return start <= n && n < end }
    var progress: Double {
        let total = end.timeIntervalSince(start)
        guard total > 0 else { return 0 }
        return min(1, max(0, Date().timeIntervalSince(start) / total))
    }
}

struct Vod: Identifiable, Hashable {
    let streamId: Int
    let name: String
    let poster: String?
    let categoryId: String
    let ext: String
    let rating: String?
    let url: String?
    var id: Int { streamId }
}

struct Series: Identifiable, Hashable {
    let seriesId: Int
    let name: String
    let cover: String?
    let categoryId: String
    let plot: String?
    let rating: String?
    let genre: String?
    let releaseDate: String?
    var id: Int { seriesId }
}

struct Episode: Identifiable, Hashable {
    let id: String
    let season: Int
    let number: Int
    let title: String
    let ext: String
    let image: String?
    let plot: String?
    let duration: String?
}

struct VodInfo {
    var plot: String?
    var genre: String?
    var cast: String?
    var director: String?
    var releaseDate: String?
    var duration: String?
    var backdrop: String?
    var rating: String?
    var tmdbId: String?
}

struct SeriesInfo {
    var plot: String?
    var cast: String?
    var director: String?
    var backdrop: String?
    var seasons: [Int: [Episode]]
}

/// A movie or series saved to My Library.
struct SavedTitle: Codable, Identifiable, Hashable {
    let kind: String // "movie" / "series"
    let itemId: Int
    let name: String
    let poster: String?
    let categoryId: String
    let ext: String
    var addedAt: Double
    var id: String { kind + "\(itemId)" }

    static func of(_ v: Vod) -> SavedTitle { SavedTitle(kind: "movie", itemId: v.streamId, name: v.name, poster: v.poster, categoryId: v.categoryId, ext: v.ext, addedAt: Date().timeIntervalSince1970) }
    static func of(_ s: Series) -> SavedTitle { SavedTitle(kind: "series", itemId: s.seriesId, name: s.name, poster: s.cover, categoryId: s.categoryId, ext: "", addedAt: Date().timeIntervalSince1970) }
    var vod: Vod { Vod(streamId: itemId, name: name, poster: poster, categoryId: categoryId, ext: ext, rating: nil, url: nil) }
    var series: Series { Series(seriesId: itemId, name: name, cover: poster, categoryId: categoryId, plot: nil, rating: nil, genre: nil, releaseDate: nil) }
}

/// Where you stopped in a movie or episode.
struct ResumePoint: Codable, Identifiable, Hashable {
    let key: String // "vod:ID" / "ep:ID"
    let title: String
    let subtitle: String?
    let poster: String?
    let url: String
    var position: Double
    var duration: Double
    var updatedAt: Double
    var id: String { key }
    var fraction: Double { duration > 0 ? min(1, position / duration) : 0 }
}

/// What the player is asked to play.
struct PlayRequest: Identifiable, Hashable {
    let url: String
    let title: String
    let subtitle: String?
    let isLive: Bool
    let resumeKey: String?
    let poster: String?
    var startAt: Double = 0
    var channel: Channel? = nil
    var id: String { url }
}

/// Java's String.hashCode (the Android app uses it for M3U ids, so favorites line up across devices).
func javaHash(_ s: String) -> Int32 {
    var h: Int32 = 0
    for u in s.utf16 { h = h &* 31 &+ Int32(u) }
    return h
}
func javaStableId(_ s: String) -> Int { Int(javaHash(s) & 0x7fffffff) }

extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
