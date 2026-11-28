import Foundation

enum NetError: LocalizedError {
    case status(Int)
    case message(String)
    var errorDescription: String? {
        switch self {
        case .status(let c): return "The server answered \(c)"
        case .message(let m): return m
        }
    }
}

/// Small HTTP helper with an in-memory cache for JSON lookups (scores, TMDB, Cinemeta).
actor Net {
    static let shared = Net()
    private var cache: [String: (Date, Data)] = [:]
    let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 300
        c.httpAdditionalHeaders = ["User-Agent": "DekTV iOS"]
        return URLSession(configuration: c)
    }()

    func data(_ url: String, ttl: TimeInterval = 0, headers: [String: String] = [:]) async throws -> Data {
        if ttl > 0, let (t, d) = cache[url], Date().timeIntervalSince(t) < ttl { return d }
        guard let u = URL(string: url) else { throw NetError.message("Bad link") }
        var req = URLRequest(url: u)
        headers.forEach { req.setValue($1, forHTTPHeaderField: $0) }
        let (d, resp) = try await session.data(for: req)
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) { throw NetError.status(h.statusCode) }
        if ttl > 0 { cache[url] = (Date(), d) }
        return d
    }

    func json(_ url: String, ttl: TimeInterval = 0, headers: [String: String] = [:]) async throws -> Any {
        let d = try await data(url, ttl: ttl, headers: headers)
        return try JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    /// Downloads to a temporary file (for big playlists and guides, parsed as a stream).
    func download(_ url: String) async throws -> URL {
        guard let u = URL(string: url) else { throw NetError.message("Bad link") }
        let (file, resp) = try await session.download(from: u)
        if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) { throw NetError.status(h.statusCode) }
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: file, to: dest)
        return dest
    }
}

func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+?/"))) ?? s }

/// Loose JSON readers (providers mix numbers and strings).
extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? {
        guard let v = self[k], !(v is NSNull) else { return nil }
        if let s = v as? String { return s.nilIfBlank }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }
    func int(_ k: String, _ def: Int = 0) -> Int {
        guard let s = str(k), let d = Double(s) else { return def }
        return Int(d)
    }
    func dict(_ k: String) -> [String: Any]? { self[k] as? [String: Any] }
    func arr(_ k: String) -> [[String: Any]] { self[k] as? [[String: Any]] ?? [] }
}
