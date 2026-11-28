import Foundation
import SwiftUI

/// Everything DekTV remembers: playlists, settings, and per-playlist favorites, recents, resume points and My Library.
@MainActor
final class AppStore: ObservableObject {
    static let shared = AppStore()
    private let d = UserDefaults.standard

    @Published var sources: [Source] = [] { didSet { save(sources, "sources") } }
    @Published var activeId: String? { didSet { d.set(activeId, forKey: "active"); loadPerSource() } }

    // Settings
    @Published var tmdbKey: String { didSet { d.set(tmdbKey, forKey: "tmdbKey") } }
    @Published var showAdult: Bool { didSet { d.set(showAdult, forKey: "showAdult") } }
    @Published var lightMode: Bool { didSet { d.set(lightMode, forKey: "light") } }
    @Published var homeLeagues: [String]? { didSet { d.set(homeLeagues, forKey: "homeLeagues") } }
    @Published var librarySort: Int { didSet { d.set(librarySort, forKey: "librarySort") } }
    @Published var showMoviesOnHome: Bool { didSet { d.set(showMoviesOnHome, forKey: "homeMovies") } }

    // Per playlist
    @Published var favorites: [Int] = [] { didSet { d.set(favorites, forKey: key("favs")) } }
    @Published var recents: [Int] = [] { didSet { d.set(recents, forKey: key("recent")) } }
    @Published var resume: [String: ResumePoint] = [:] { didSet { save(resume, key("resume")) } }
    @Published var library: [SavedTitle] = [] { didSet { save(library, key("library")) } }

    var active: Source? { sources.first { $0.id == activeId } ?? sources.first }

    private init() {
        tmdbKey = d.string(forKey: "tmdbKey") ?? ""
        showAdult = d.bool(forKey: "showAdult")
        lightMode = d.bool(forKey: "light")
        homeLeagues = d.stringArray(forKey: "homeLeagues")
        librarySort = d.integer(forKey: "librarySort")
        showMoviesOnHome = d.object(forKey: "homeMovies") as? Bool ?? true
        sources = load("sources") ?? []
        activeId = d.string(forKey: "active") ?? sources.first?.id
        loadPerSource()
    }

    private func key(_ k: String) -> String { "\(k)_\(active?.id ?? "none")" }
    private func save<T: Encodable>(_ v: T, _ k: String) { d.set(try? JSONEncoder().encode(v), forKey: k) }
    private func load<T: Decodable>(_ k: String) -> T? { d.data(forKey: k).flatMap { try? JSONDecoder().decode(T.self, from: $0) } }

    private func loadPerSource() {
        favorites = d.array(forKey: key("favs")) as? [Int] ?? []
        recents = d.array(forKey: key("recent")) as? [Int] ?? []
        resume = load(key("resume")) ?? [:]
        library = load(key("library")) ?? []
    }

    // MARK: actions

    func addOrUpdate(_ s: Source) {
        if let i = sources.firstIndex(where: { $0.id == s.id }) { sources[i] = s } else { sources.append(s) }
        if activeId == nil || sources.count == 1 { activeId = s.id }
        objectWillChange.send()
    }
    func remove(_ s: Source) {
        sources.removeAll { $0.id == s.id }
        if activeId == s.id { activeId = sources.first?.id }
    }

    func isFavorite(_ c: Channel) -> Bool { favorites.contains(c.streamId) }
    func toggleFavorite(_ c: Channel) {
        if let i = favorites.firstIndex(of: c.streamId) { favorites.remove(at: i) } else { favorites.append(c.streamId) }
    }
    func watched(_ c: Channel) {
        recents.removeAll { $0 == c.streamId }
        recents.insert(c.streamId, at: 0)
        if recents.count > 30 { recents.removeLast(recents.count - 30) }
    }

    func saveResume(_ r: ResumePoint) {
        // Nearly finished counts as watched.
        if r.duration > 0 && r.position / r.duration > 0.95 { resume[r.key] = nil } else if r.position > 30 { resume[r.key] = r }
    }
    func removeResume(_ key: String) { resume[key] = nil }

    func inLibrary(_ kind: String, _ id: Int) -> Bool { library.contains { $0.kind == kind && $0.itemId == id } }
    @discardableResult func toggleLibrary(_ t: SavedTitle) -> Bool {
        if let i = library.firstIndex(where: { $0.kind == t.kind && $0.itemId == t.itemId }) { library.remove(at: i); return false }
        var n = t; n.addedAt = Date().timeIntervalSince1970
        library.insert(n, at: 0); return true
    }
    static let sortNames = ["Recently added", "Title A–Z", "Movies first", "Series first"]
    var sortedLibrary: [SavedTitle] {
        switch librarySort {
        case 1: return library.sorted { cleanTitle($0.name).lowercased() < cleanTitle($1.name).lowercased() }
        case 2: return library.sorted { ($0.kind == "movie" ? 0 : 1, -$0.addedAt) < ($1.kind == "movie" ? 0 : 1, -$1.addedAt) }
        case 3: return library.sorted { ($0.kind == "series" ? 0 : 1, -$0.addedAt) < ($1.kind == "series" ? 0 : 1, -$1.addedAt) }
        default: return library.sorted { $0.addedAt > $1.addedAt }
        }
    }

    /// Merges favorites copied from another device into a playlist's storage.
    func importFavorites(_ ids: [Int], recents rec: [Int], forSource sid: String) {
        d.set(ids, forKey: "favs_\(sid)")
        d.set(rec, forKey: "recent_\(sid)")
        if sid == active?.id { loadPerSource() }
    }
}

/// Hides adult categories and titles unless turned on in Settings.
enum Adult {
    static let words = ["xxx", "adult", "18+", "porn", "+18", "erotic", "for adults"]
    static func isAdult(_ s: String) -> Bool { let l = s.lowercased(); return words.contains { l.contains($0) } }
}
