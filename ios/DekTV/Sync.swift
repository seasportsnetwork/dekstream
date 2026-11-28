import Foundation
import CryptoKit
import CommonCrypto
import UIKit

/// Copies a setup from another DekTV device (Android "Send from this device"): same encrypted protocol (v2).
/// The QR code holds the sender's address and a one-time key; the typed 10-digit code is stretched with PBKDF2.
enum SetupSync {
    struct QR { let address: String; let key: Data }
    struct Imported { let from: String; let sources: [Source]; let favorites: [String: [Int]]; let recents: [String: [Int]] }

    static func b64url(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func parseQR(_ text: String) -> QR? {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespaces)), c.scheme == "dektv", c.host == "sync",
              let a = c.queryItems?.first(where: { $0.name == "a" })?.value,
              let k = c.queryItems?.first(where: { $0.name == "k" })?.value, let key = b64url(k) else { return nil }
        return QR(address: a, key: key)
    }

    static func pbkdf2(_ secret: String, salt: Data, bytes: Int = 16, rounds: UInt32 = 150_000) -> Data {
        var out = Data(count: bytes)
        let pw = Array(secret.utf8)
        _ = out.withUnsafeMutableBytes { o in
            salt.withUnsafeBytes { s in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pw.map { Int8(bitPattern: $0) }, pw.count,
                                     s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds,
                                     o.bindMemory(to: UInt8.self).baseAddress, bytes)
            }
        }
        return out
    }

    /// base64url(iv[12] || ciphertext || tag[16]) — CryptoKit's combined layout matches the Android format.
    static func seal(_ key: Data, _ plain: Data) throws -> String {
        let box = try AES.GCM.seal(plain, using: SymmetricKey(data: key))
        guard let c = box.combined else { throw NetError.message("Encryption failed") }
        return b64url(c)
    }
    static func open(_ key: Data, _ sealed: String) throws -> Data {
        guard let d = b64url(sealed) else { throw NetError.message("Bad reply") }
        return try AES.GCM.open(try AES.GCM.SealedBox(combined: d), using: SymmetricKey(data: key))
    }

    private static var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 160
        c.timeoutIntervalForResource = 180
        return URLSession(configuration: c)
    }()

    private static func base(_ address: String) -> String {
        var a = address.trimmingCharacters(in: .whitespaces)
        if a.hasPrefix("http://") { a.removeFirst(7) }
        while a.hasSuffix("/") { a.removeLast() }
        return "http://" + a
    }

    static func fetch(qr: QR) async throws -> Imported { try await pull(qr.address, qr.key) }

    static func fetch(address: String, code: String) async throws -> Imported {
        guard let u = URL(string: base(address) + "/hello") else { throw NetError.message("Check the address") }
        let (d, r) = try await session.data(from: u)
        guard (r as? HTTPURLResponse)?.statusCode == 200, let o = try JSONSerialization.jsonObject(with: d) as? [String: Any],
              o.str("app") == "dektv" else { throw NetError.message("That device isn't sending right now") }
        guard o.int("v") >= 2, let sid = o.str("sid").flatMap(b64url) else { throw NetError.message("Update DekTV on the other device first") }
        let key = await Task.detached { pbkdf2(code.filter(\.isNumber), salt: sid) }.value
        return try await pull(address, key)
    }

    private static func pull(_ address: String, _ key: Data) async throws -> Imported {
        let name = await UIDevice.current.name
        let ask = try seal(key, try JSONSerialization.data(withJSONObject: ["name": name]))
        guard let u = URL(string: base(address) + "/pull") else { throw NetError.message("Check the address") }
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.setValue("text/plain", forHTTPHeaderField: "Content-Type")
        req.httpBody = ask.data(using: .utf8)
        let (d, r) = try await session.data(for: req)
        let body = String(data: d, encoding: .utf8) ?? ""
        switch (r as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200: break
        case 403: throw NetError.message(body == "declined" ? "The other device didn't allow it" : "Wrong code")
        case 410: throw NetError.message("Too many wrong codes. Start sending again on the other device.")
        case 408: throw NetError.message("Nobody answered on the other device")
        case let c: throw NetError.status(c)
        }
        let plain = try open(key, body)
        guard let o = try JSONSerialization.jsonObject(with: plain) as? [String: Any], o.str("app") == "dektv",
              let files = o.dict("files") else { throw NetError.message("That isn't DekTV") }
        return parse(from: o.str("from") ?? "Another device", files: files)
    }

    /// Reads the Android app's settings files: playlists (dekstream_global › sources) and each playlist's favorites.
    static func parse(from: String, files: [String: Any]) -> Imported {
        func value(_ file: String, _ key: String) -> String? { (files[file] as? [String: Any])?.dict(key)?.str("v") }
        var sources: [Source] = []
        if let json = value("dekstream_global", "sources"), let data = json.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            for s in arr {
                guard let id = s.str("id"), let type = SourceType(rawValue: s.str("type") ?? "") else { continue } // Xtream + M3U links
                sources.append(Source(id: id, name: s.str("name") ?? "Playlist", type: type, url: s.str("url") ?? "",
                                      username: s.str("user") ?? "", password: s.str("pass") ?? "", epgUrl: s.str("epg") ?? ""))
            }
        }
        let profile = value("dekstream_global", "lastProfile")
        var favs: [String: [Int]] = [:], recents: [String: [Int]] = [:]
        for s in sources {
            // ds_<profile>_<source>; prefer the profile used last, else any profile that has favorites.
            let all = files.keys.filter { $0.hasPrefix("ds_") && $0.hasSuffix("_\(s.id)") }.sorted()
            let preferred = profile.map { "ds_\($0)_\(s.id)" }
            let names = all.filter { $0 == preferred } + all.filter { $0 != preferred }
            for n in names {
                let f = (value(n, "favs") ?? "").split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                if !f.isEmpty && favs[s.id] == nil { favs[s.id] = f }
                let r = (value(n, "recent") ?? "").split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                if !r.isEmpty && recents[s.id] == nil { recents[s.id] = r }
            }
        }
        return Imported(from: from, sources: sources, favorites: favs, recents: recents)
    }

    @MainActor static func apply(_ i: Imported) {
        let store = AppStore.shared
        for s in i.sources { store.addOrUpdate(s) }
        for s in i.sources { store.importFavorites(i.favorites[s.id] ?? [], recents: i.recents[s.id] ?? [], forSource: s.id) }
        if let first = i.sources.first, store.activeId == nil || !store.sources.contains(where: { $0.id == store.activeId }) { store.activeId = first.id }
    }
}
