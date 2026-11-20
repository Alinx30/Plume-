import Foundation
import UIKit

/// Un PC trouvé sur le Wi-Fi.
struct FoundPc: Identifiable, Hashable {
    let id: String
    let name: String
    let address: String
    let port: Int
    var baseUrl: String { "http://\(address):\(port)" }
}

/// État affiché dans l'appli.
struct SyncStatus {
    /// Nom du PC associé, ou nil si aucun.
    var pcName: String?
    var running = false
    var lastSync: Int64 = 0
    /// Le PC n'a pas répondu à la dernière tentative (autre réseau, PC éteint…).
    var unreachable = false
    var error: String?
}

struct HttpError: Error {
    let code: Int
    let body: String
}

/// Appels HTTP au programme Plume du PC, sur le réseau Wi-Fi local.
struct SyncApi {
    let baseUrl: String
    let token: String?

    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 600
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private func request(_ method: String, _ path: String, timeout: TimeInterval) throws -> URLRequest {
        guard let url = URL(string: baseUrl + path) else { throw URLError(.badURL) }
        var r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        r.httpMethod = method
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return r
    }

    private func check(_ resp: URLResponse, _ data: Data) throws {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw HttpError(code: code, body: String(decoding: data, as: UTF8.self)) }
    }

    /// Requête JSON ; renvoie le corps de la réponse.
    func json(_ method: String, _ path: String, body: Data? = nil, timeout: TimeInterval = 8) async throws -> Data {
        var r = try request(method, path, timeout: timeout)
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await Self.session.data(for: r)
        try check(resp, data)
        return data
    }

    /// Envoie un fichier joint (PDF, photo, audio).
    func upload(_ path: String, file: URL) async throws {
        var r = try request("PUT", path, timeout: 60)
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (data, resp) = try await Self.session.upload(for: r, fromFile: file)
        try check(resp, data)
    }

    /// Télécharge un fichier joint ; renvoie false s'il n'existe pas sur le PC.
    func download(_ path: String, to dest: URL) async throws -> Bool {
        let r = try request("GET", path, timeout: 60)
        let (tmp, resp) = try await Self.session.download(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 404 { return false }
        guard (200..<300).contains(code) else { throw HttpError(code: code, body: "") }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return true
    }
}

/// Recherche du PC sur le Wi-Fi.
///
/// iOS n'autorise pas les messages « à tout le réseau » sans une autorisation spéciale d'Apple :
/// on interroge donc directement chaque adresse du réseau local (/hub/info), en parallèle.
enum LanDiscovery {
    static let port = 8765

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 2
        c.timeoutIntervalForResource = 3
        c.waitsForConnectivity = false
        c.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: c)
    }()

    /// Interroge une adresse tapée à la main ou trouvée sur le réseau.
    static func probe(_ address: String, timeout: TimeInterval = 4) async -> FoundPc? {
        var host = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.hasPrefix("http://") { host.removeFirst(7) }
        host = String(host.split(separator: "/").first ?? "")
        host = String(host.split(separator: ":").first ?? "")
        guard !host.isEmpty, let url = URL(string: "http://\(host):\(port)/hub/info") else { return nil }
        let r = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        guard let result = try? await session.data(for: r),
              (result.1 as? HTTPURLResponse)?.statusCode == 200,
              let o = (try? JSONSerialization.jsonObject(with: result.0)) as? [String: Any],
              o["app"] as? String == "plume",
              let id = o["id"] as? String else { return nil }
        return FoundPc(id: id, name: o["name"] as? String ?? "PC", address: host, port: (o["port"] as? NSNumber)?.intValue ?? port)
    }

    /// Adresse IPv4 et masque du Wi-Fi de l'appareil.
    private static func localNetwork() -> (UInt32, UInt32)? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var fallback: (UInt32, UInt32)?
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), let mask = ifa.ifa_netmask else { continue }
            guard (ifa.ifa_flags & UInt32(IFF_UP)) != 0, (ifa.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            let ip = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            if name == "en0" { return (ip, m) }
            if name.hasPrefix("en"), fallback == nil { fallback = (ip, m) }
        }
        return fallback
    }

    private static func format(_ a: UInt32) -> String {
        "\((a >> 24) & 255).\((a >> 16) & 255).\((a >> 8) & 255).\(a & 255)"
    }

    static func candidateHosts() -> [String] {
        guard let local = localNetwork() else { return [] }
        let (ip, rawMask) = local
        // Grand réseau : on se limite aux 254 adresses voisines.
        let mask = rawMask < 0xFFFF_FC00 ? UInt32(0xFFFF_FF00) : rawMask
        let net = ip & mask
        let count = ~mask
        guard count >= 2 else { return [] }
        var out: [String] = []
        for h in 1..<count {
            let a = net | h
            if a != ip { out.append(format(a)) }
        }
        return out
    }

    /// Cherche les PC Plume sur le réseau local.
    static func scan() async -> [FoundPc] {
        let hosts = candidateHosts()
        var found: [String: FoundPc] = [:]
        await withTaskGroup(of: FoundPc?.self) { group in
            var it = hosts.makeIterator()
            for _ in 0..<48 {
                guard let h = it.next() else { break }
                group.addTask { await probe(h, timeout: 1.5) }
            }
            while let r = await group.next() {
                if let r { found[r.id] = r }
                if let h = it.next() { group.addTask { await probe(h, timeout: 1.5) } }
            }
        }
        return found.values.sorted { $0.name < $1.name }
    }
}

/// Synchronisation directe avec le programme Plume du PC, sur le même Wi-Fi, sans serveur en ligne.
/// Même fonctionnement que sur Android : pour chaque note, la version modifiée le plus récemment gagne.
@MainActor
final class SyncManager: ObservableObject {
    static let shared = SyncManager()

    @Published private(set) var status = SyncStatus()

    private enum K {
        static let token = "pc.token", base = "pc.baseUrl", id = "pc.id", name = "pc.name"
        static let last = "pc.lastSync", uploaded = "pc.uploaded"
    }

    private let d = UserDefaults.standard
    private let store = NoteStore.shared
    private var loop: Task<Void, Never>?
    private var debounce: Task<Void, Never>?
    private var busy = false
    private var again = false
    private var bgTask: UIBackgroundTaskIdentifier = .invalid

    private func endBackground() {
        guard bgTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(bgTask)
        bgTask = .invalid
    }

    private init() {
        status = SyncStatus(pcName: d.string(forKey: K.name), lastSync: Int64(d.integer(forKey: K.last)))
        store.onLocalChange = { [weak self] in self?.requestSync(delay: 3) }
    }

    var isPaired: Bool { d.string(forKey: K.token) != nil }
    private func api() -> SyncApi { SyncApi(baseUrl: d.string(forKey: K.base) ?? "", token: d.string(forKey: K.token)) }

    // MARK: - Association

    func discover() async -> [FoundPc] { await LanDiscovery.scan() }

    /// Associe cet appareil au PC avec le code affiché sur le PC. Renvoie un message d'erreur, ou nil.
    func pair(_ pc: FoundPc, code: String) async -> String? {
        let device = UIDevice.current.name.isEmpty ? UIDevice.current.model : UIDevice.current.name
        let body = try? JSONSerialization.data(withJSONObject: ["code": code.trimmingCharacters(in: .whitespaces), "device": device])
        do {
            let data = try await SyncApi(baseUrl: pc.baseUrl, token: nil).json("POST", "/hub/pair", body: body)
            guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let token = o["token"] as? String else {
                return "Réponse inattendue du PC."
            }
            d.set(token, forKey: K.token)
            d.set(pc.baseUrl, forKey: K.base)
            d.set(pc.id, forKey: K.id)
            d.set(pc.name, forKey: K.name)
            d.removeObject(forKey: K.uploaded)
            d.set(0, forKey: K.last)
            status = SyncStatus(pcName: pc.name)
            requestSync(delay: 0)
            return nil
        } catch let e as HttpError {
            switch e.code {
            case 401: return "Code incorrect."
            case 409: return "Sur le PC, cliquez d'abord sur « Associer mon téléphone »."
            case 429: return "Trop d'essais : patientez une minute."
            default: return "Erreur du PC (\(e.code))."
            }
        } catch {
            return "PC injoignable. Vérifiez que les deux appareils sont sur le même Wi-Fi."
        }
    }

    func unpair() {
        for k in [K.token, K.base, K.id, K.name, K.last, K.uploaded] { d.removeObject(forKey: k) }
        status = SyncStatus()
    }

    // MARK: - Déclenchement

    /// Synchronise régulièrement tant que l'appli est à l'écran.
    func startAuto() {
        guard loop == nil else { return }
        loop = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                await self?.syncNow()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    /// L'appli passe en arrière-plan : une dernière synchronisation.
    func stopAuto() {
        loop?.cancel()
        loop = nil
        guard isPaired else { return }
        endBackground()
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "plume-sync") { [weak self] in
            Task { @MainActor in self?.endBackground() }
        }
        Task { @MainActor [weak self] in
            await self?.syncNow()
            self?.endBackground()
        }
    }

    func requestSync(delay: Double = 0) {
        guard isPaired else { return }
        debounce?.cancel()
        debounce = Task { @MainActor [weak self] in
            if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
            guard !Task.isCancelled else { return }
            await self?.syncNow()
        }
    }

    func syncNow() async {
        guard isPaired else { return }
        if busy { again = true; return }
        busy = true
        defer { busy = false }
        repeat {
            again = false
            status.running = true
            var unreachable = false
            var message: String?
            do {
                do {
                    try await syncOnce()
                } catch let e where e is URLError {
                    // Le PC a peut-être changé d'adresse : on le cherche sur le Wi-Fi.
                    guard await relocate() else { throw e }
                    try await syncOnce()
                }
            } catch let e as HttpError {
                if e.code == 401 {
                    unpair()
                    message = "Cet appareil n'est plus associé au PC."
                } else {
                    message = "Erreur du PC (\(e.code))."
                }
            } catch is URLError {
                unreachable = true
            } catch {
                message = "Erreur de synchronisation."
            }
            let ok = message == nil && !unreachable
            let now = nowMs()
            if ok { d.set(Int(now), forKey: K.last) }
            status.running = false
            status.error = message
            status.unreachable = unreachable
            if ok { status.lastSync = now }
            if !ok { break }
        } while again
    }

    private func relocate() async -> Bool {
        guard let id = d.string(forKey: K.id) else { return false }
        // D'abord l'ancienne adresse (le PC était peut-être juste lent), puis tout le réseau.
        guard let pc = await LanDiscovery.scan().first(where: { $0.id == id }) else { return false }
        d.set(pc.baseUrl, forKey: K.base)
        d.set(pc.name, forKey: K.name)
        status.pcName = pc.name
        return true
    }

    // MARK: - Synchronisation

    private struct Remote {
        let id: String
        let updatedAt: Int64
        let deleted: Bool
        let data: Any?
    }

    private func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? s
    }

    private func getRows(_ path: String) async throws -> [[String: Any]] {
        let data = try await api().json("GET", path)
        return ((try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]) ?? []
    }

    private func post(_ path: String, _ rows: [[String: Any]]) async throws {
        if rows.isEmpty { return }
        let body = try JSONSerialization.data(withJSONObject: rows)
        _ = try await api().json("POST", path, body: body, timeout: 60)
    }

    private func parseRemote(_ rows: [[String: Any]], key: String) -> [Remote] {
        rows.compactMap { o in
            guard let id = o["id"] as? String, let up = (o["updated_at"] as? NSNumber)?.int64Value else { return nil }
            let deleted = (o["deleted"] as? NSNumber)?.boolValue ?? false
            var data: Any? = o[key]
            if data is NSNull { data = nil }
            return Remote(id: id, updatedAt: up, deleted: deleted, data: data)
        }
    }

    private func toJSON<T: Encodable>(_ v: T) throws -> Any {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(v))
    }

    private func fromJSON<T: Decodable>(_ type: T.Type, _ obj: Any) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: obj))
    }

    private func tombRow(_ id: String, _ at: Int64, _ key: String) -> [String: Any] {
        ["id": id, "updated_at": NSNumber(value: at), "deleted": true, key: [String: Any]()]
    }

    private func syncOnce() async throws {
        try await syncFolders()
        try await syncNotes()
    }

    private func syncFolders() async throws {
        let remote = parseRemote(try await getRows("/hub/folders"), key: "data")
        var local: [String: Folder] = [:]
        for f in store.index.folders { local[f.id] = f }
        let tombs = store.deletedFolders()
        var push: [[String: Any]] = []
        var forget: [String] = []
        for r in remote {
            let l = local[r.id]
            if let tomb = tombs[r.id] {
                if r.deleted {
                    forget.append(r.id)
                } else if r.updatedAt > tomb {
                    forget.append(r.id)
                    applyFolder(r)
                } else {
                    push.append(tombRow(r.id, tomb, "data"))
                    forget.append(r.id)
                }
            } else if r.deleted {
                if let l, l.updatedAt <= r.updatedAt { store.removeFolderLocally(r.id) }
            } else if let l {
                if r.updatedAt > l.updatedAt { applyFolder(r) } else if l.updatedAt > r.updatedAt { push.append(try folderRow(l)) }
            } else {
                applyFolder(r)
            }
        }
        let remoteIds = Set(remote.map(\.id))
        for l in local.values where !remoteIds.contains(l.id) { push.append(try folderRow(l)) }
        for id in tombs.keys where !remoteIds.contains(id) { forget.append(id) }
        try await post("/hub/folders", push)
        forget.forEach { store.forgetDeletedFolder($0) }
    }

    private func applyFolder(_ r: Remote) {
        guard let data = r.data, var f = try? fromJSON(Folder.self, data) else { return }
        f.id = r.id
        f.updatedAt = r.updatedAt
        store.applyRemoteFolder(f)
    }

    private func folderRow(_ f: Folder) throws -> [String: Any] {
        ["id": f.id, "updated_at": NSNumber(value: f.updatedAt), "deleted": false, "data": try toJSON(f)]
    }

    private func syncNotes() async throws {
        let remote = parseRemote(try await getRows("/hub/notes"), key: "meta")
        var local: [String: NoteMeta] = [:]
        for n in store.index.notes { local[n.id] = n }
        let tombs = store.deletedNotes()
        let isBusy = { (id: String) -> Bool in id == self.store.editingNoteId || self.store.hasPendingWrite(id) }
        for r in remote {
            let l = local[r.id]
            if let tomb = tombs[r.id] {
                if r.deleted {
                    store.forgetDeletedNote(r.id)
                } else if r.updatedAt > tomb {
                    store.forgetDeletedNote(r.id)
                    try await pullNote(r.id, r.updatedAt)
                } else {
                    try await post("/hub/notes", [tombRow(r.id, tomb, "meta")])
                    store.forgetDeletedNote(r.id)
                }
            } else if r.deleted {
                if let l, l.updatedAt <= r.updatedAt, !isBusy(r.id) { store.removeNoteLocally(r.id) }
            } else if let l {
                if r.updatedAt > l.updatedAt {
                    if !isBusy(r.id) { try await pullNote(r.id, r.updatedAt) }
                } else if l.updatedAt > r.updatedAt {
                    try await pushNote(l)
                }
            } else {
                try await pullNote(r.id, r.updatedAt)
            }
        }
        let remoteIds = Set(remote.map(\.id))
        for l in local.values where !remoteIds.contains(l.id) {
            // La note a pu être supprimée pendant la synchronisation.
            if let current = store.note(l.id) { try await pushNote(current) }
        }
        for id in tombs.keys where !remoteIds.contains(id) { store.forgetDeletedNote(id) }
    }

    private func assetsOf(_ c: NoteContent) -> Set<String> {
        var out = Set<String>()
        for p in c.pages {
            if let pdf = p.pdf { out.insert(pdf.asset) }
            for im in p.images { out.insert(im.asset) }
        }
        for r in c.recordings { out.insert(r.asset) }
        out.remove("")
        return out
    }

    private func assetPath(_ name: String) -> String { "/hub/assets/\(enc(name))" }

    private var uploaded: Set<String> {
        get { Set(d.stringArray(forKey: K.uploaded) ?? []) }
        set { d.set(Array(newValue), forKey: K.uploaded) }
    }

    private func pushNote(_ meta: NoteMeta) async throws {
        let content = store.loadContent(meta.id)
        for a in assetsOf(content) where !uploaded.contains(a) {
            let f = Paths.asset(a)
            guard FileManager.default.fileExists(atPath: f.path) else { continue }
            try await api().upload(assetPath(a), file: f)
            uploaded.insert(a)
        }
        let row: [String: Any] = [
            "id": meta.id, "updated_at": NSNumber(value: meta.updatedAt), "deleted": false,
            "meta": try toJSON(meta), "content": try toJSON(content),
        ]
        try await post("/hub/notes", [row])
    }

    private func pullNote(_ id: String, _ updatedAt: Int64) async throws {
        let data: Data
        do {
            data = try await api().json("GET", "/hub/notes/\(enc(id))", timeout: 60)
        } catch let e as HttpError where e.code == 404 {
            return
        }
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let metaObj = o["meta"] else { return }
        var meta = try fromJSON(NoteMeta.self, metaObj)
        meta.id = id
        meta.updatedAt = updatedAt
        var content = NoteContent()
        if let c = o["content"], !(c is NSNull) { content = try fromJSON(NoteContent.self, c) }
        for a in assetsOf(content) {
            let f = Paths.asset(a)
            if FileManager.default.fileExists(atPath: f.path) {
                uploaded.insert(a)
            } else if try await api().download(assetPath(a), to: f) {
                uploaded.insert(a)
            }
        }
        if id == store.editingNoteId { return }
        meta.pageCount = content.pages.count
        store.applyRemoteNote(meta, content)
    }
}
