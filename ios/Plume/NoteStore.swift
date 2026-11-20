import Foundation
import UIKit
import PDFKit

/// Emplacement des fichiers de Plume dans l'appareil.
enum Paths {
    static let root: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("Plume", isDirectory: true)
        for d in [dir, dir.appendingPathComponent("notes", isDirectory: true), dir.appendingPathComponent("assets", isDirectory: true)] {
            try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        }
        return dir
    }()

    static var index: URL { root.appendingPathComponent("index.json") }
    static var notes: URL { root.appendingPathComponent("notes", isDirectory: true) }
    static var assets: URL { root.appendingPathComponent("assets", isDirectory: true) }

    static func note(_ id: String) -> URL {
        notes.appendingPathComponent(id.replacingOccurrences(of: "/", with: "_") + ".json")
    }

    /// Fichier joint (PDF, photo, audio), nommé comme sur Android et sur le PC.
    static func asset(_ name: String) -> URL {
        assets.appendingPathComponent(name.replacingOccurrences(of: "/", with: "_"))
    }

    static func writeAtomic(_ data: Data, to url: URL) {
        try? data.write(to: url, options: .atomic)
    }
}

/// Stockage local des dossiers et des notes, en fichiers JSON (même format qu'Android).
@MainActor
final class NoteStore: ObservableObject {
    static let shared = NoteStore()

    @Published private(set) var index = LibraryIndex()

    /// Note ouverte dans l'éditeur : la synchronisation ne la remplace pas pendant l'édition.
    var editingNoteId: String?

    /// Appelé après chaque modification locale (pour lancer une synchronisation).
    var onLocalChange: (@MainActor () -> Void)?

    private let io = DispatchQueue(label: "plume.io")
    private var pending: [String: NoteContent] = [:]
    private var pendingVersion: [String: Int] = [:]
    private let defaults = UserDefaults.standard

    private init() {
        if let data = try? Data(contentsOf: Paths.index),
           let idx = try? JSONDecoder().decode(LibraryIndex.self, from: data) {
            index = idx
        }
    }

    // MARK: - Papier par défaut

    var defaultPaper: (PaperStyle, Int64) {
        get {
            let style = PaperStyle(rawValue: defaults.string(forKey: "paper") ?? "") ?? .LINED
            let color = (defaults.object(forKey: "paperColor") as? NSNumber)?.int64Value ?? 0xFFFFFFFF
            return (style, color)
        }
        set {
            defaults.set(newValue.0.rawValue, forKey: "paper")
            defaults.set(NSNumber(value: newValue.1), forKey: "paperColor")
        }
    }

    // MARK: - Écriture de l'index

    private func persistIndex() {
        guard let data = try? JSONEncoder().encode(index) else { return }
        io.async { Paths.writeAtomic(data, to: Paths.index) }
    }

    private func updateIndex(_ transform: (inout LibraryIndex) -> Void) {
        writeIndex(transform)
        onLocalChange?()
    }

    private func writeIndex(_ transform: (inout LibraryIndex) -> Void) {
        var idx = index
        transform(&idx)
        index = idx
        persistIndex()
    }

    // MARK: - Éléments supprimés, à transmettre au PC (id -> date de suppression)

    private func tombs(_ key: String) -> [String: Int64] {
        guard let s = defaults.string(forKey: key), let d = s.data(using: .utf8),
              let m = try? JSONDecoder().decode([String: Int64].self, from: d) else { return [:] }
        return m
    }

    private func editTombs(_ key: String, _ f: (inout [String: Int64]) -> Void) {
        var m = tombs(key)
        f(&m)
        if let d = try? JSONEncoder().encode(m) { defaults.set(String(decoding: d, as: UTF8.self), forKey: key) }
    }

    func deletedNotes() -> [String: Int64] { tombs("tombNotes") }
    func deletedFolders() -> [String: Int64] { tombs("tombFolders") }
    func forgetDeletedNote(_ id: String) { editTombs("tombNotes") { $0[id] = nil } }
    func forgetDeletedFolder(_ id: String) { editTombs("tombFolders") { $0[id] = nil } }

    // MARK: - Dossiers

    @discardableResult
    func createFolder(name: String, parentId: String?) -> Folder {
        let t = nowMs()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let f = Folder(id: Ids.uuid(), name: trimmed.isEmpty ? "Dossier" : trimmed, parentId: parentId,
                       color: FolderColors[index.folders.count % FolderColors.count], createdAt: t, updatedAt: t)
        updateIndex { $0.folders.append(f) }
        return f
    }

    func renameFolder(_ id: String, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        updateIndex { idx in
            for i in idx.folders.indices where idx.folders[i].id == id {
                idx.folders[i].name = trimmed
                idx.folders[i].updatedAt = nowMs()
            }
        }
    }

    /// Le dossier et tous ses sous-dossiers.
    func folderTree(_ id: String) -> Set<String> {
        var result: Set<String> = [id]
        var grew = true
        while grew {
            grew = false
            for f in index.folders {
                if let p = f.parentId, result.contains(p), !result.contains(f.id) {
                    result.insert(f.id)
                    grew = true
                }
            }
        }
        return result
    }

    /// Supprime le dossier, ses sous-dossiers et toutes leurs notes.
    func deleteFolder(_ id: String) {
        let tree = folderTree(id)
        let removed = index.notes.filter { n in n.folderId.map { tree.contains($0) } ?? false }.map(\.id)
        let t = nowMs()
        editTombs("tombFolders") { m in tree.forEach { m[$0] = t } }
        editTombs("tombNotes") { m in removed.forEach { m[$0] = t } }
        removed.forEach { pending[$0] = nil }
        updateIndex { idx in
            idx.folders.removeAll { tree.contains($0.id) }
            idx.notes.removeAll { removed.contains($0.id) }
        }
        io.async { removed.forEach { try? FileManager.default.removeItem(at: Paths.note($0)) } }
    }

    // MARK: - Notes

    private static let titleFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateFormat = "d MMM yyyy"
        return f
    }()

    func createNote(folderId: String?) -> NoteMeta {
        let t = nowMs()
        let (style, color) = defaultPaper
        let note = NoteMeta(id: Ids.uuid(), folderId: folderId, title: "Note du \(Self.titleFormatter.string(from: Date()))",
                            createdAt: t, updatedAt: t, paper: style, pageCount: 1, paperColor: color)
        updateIndex { $0.notes.append(note) }
        return note
    }

    func note(_ id: String) -> NoteMeta? { index.notes.first { $0.id == id } }

    private func updateNote(_ id: String, _ transform: (inout NoteMeta) -> Void) {
        updateIndex { idx in
            for i in idx.notes.indices where idx.notes[i].id == id { transform(&idx.notes[i]) }
        }
    }

    func renameNote(_ id: String, title: String) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        updateNote(id) { $0.title = trimmed; $0.updatedAt = nowMs() }
    }

    func moveNote(_ id: String, folderId: String?) {
        updateNote(id) { $0.folderId = folderId; $0.updatedAt = nowMs() }
    }

    func setPaper(_ id: String, style: PaperStyle, color: Int64) {
        defaultPaper = (style, color)
        updateNote(id) { $0.paper = style; $0.paperColor = color; $0.updatedAt = nowMs() }
    }

    @discardableResult
    func duplicateNote(_ id: String) -> NoteMeta? {
        guard let meta = note(id) else { return nil }
        let content = loadContent(id)
        let t = nowMs()
        var copy = meta
        copy.id = Ids.uuid()
        copy.title = meta.title + " (copie)"
        copy.createdAt = t
        copy.updatedAt = t
        updateIndex { $0.notes.append(copy) }
        saveContent(copy.id, content)
        return copy
    }

    func deleteNote(_ id: String) {
        pending[id] = nil
        let t = nowMs()
        editTombs("tombNotes") { $0[id] = t }
        updateIndex { idx in idx.notes.removeAll { n in n.id == id } }
        io.async { try? FileManager.default.removeItem(at: Paths.note(id)) }
    }

    /// Lit le contenu d'une note sur le disque (utilisable hors du fil principal).
    nonisolated static func readContent(_ id: String) -> NoteContent {
        guard let data = try? Data(contentsOf: Paths.note(id)),
              let c = try? JSONDecoder().decode(NoteContent.self, from: data) else { return NoteContent() }
        return c
    }

    func pendingContent(_ id: String) -> NoteContent? { pending[id] }

    func loadContent(_ id: String) -> NoteContent {
        pending[id] ?? Self.readContent(id)
    }

    func saveContent(_ id: String, _ content: NoteContent) {
        pending[id] = content
        let v = (pendingVersion[id] ?? 0) + 1
        pendingVersion[id] = v
        updateNote(id) { $0.updatedAt = nowMs(); $0.pageCount = content.pages.count }
        let data = try? JSONEncoder().encode(content)
        io.async {
            if let data { Paths.writeAtomic(data, to: Paths.note(id)) }
            Task { @MainActor in
                let store = NoteStore.shared
                if store.pendingVersion[id] == v {
                    store.pending[id] = nil
                    store.pendingVersion[id] = nil
                }
            }
        }
    }

    // MARK: - Synchronisation : changements venant du PC (sans relancer de synchronisation)

    func hasPendingWrite(_ id: String) -> Bool { pending[id] != nil }

    func applyRemoteFolder(_ f: Folder) {
        writeIndex { idx in
            if let i = idx.folders.firstIndex(where: { $0.id == f.id }) { idx.folders[i] = f } else { idx.folders.append(f) }
        }
    }

    func removeFolderLocally(_ id: String) {
        writeIndex { idx in idx.folders.removeAll { f in f.id == id } }
    }

    func applyRemoteNote(_ meta: NoteMeta, _ content: NoteContent) {
        if let data = try? JSONEncoder().encode(content) { Paths.writeAtomic(data, to: Paths.note(meta.id)) }
        pending[meta.id] = nil
        writeIndex { idx in
            if let i = idx.notes.firstIndex(where: { $0.id == meta.id }) { idx.notes[i] = meta } else { idx.notes.append(meta) }
        }
    }

    func removeNoteLocally(_ id: String) {
        pending[id] = nil
        writeIndex { idx in idx.notes.removeAll { n in n.id == id } }
        io.async { try? FileManager.default.removeItem(at: Paths.note(id)) }
    }

    // MARK: - Import de fichiers

    /// Copie un PDF dans l'appli et crée une note dont chaque page est une page du PDF.
    func importPdf(_ url: URL, folderId: String?) -> NoteMeta? {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let asset = Ids.uuid() + ".pdf"
        let dest = Paths.asset(asset)
        do { try FileManager.default.copyItem(at: url, to: dest) } catch { return nil }
        guard let doc = PDFDocument(url: dest), doc.pageCount > 0 else {
            try? FileManager.default.removeItem(at: dest)
            return nil
        }
        let count = doc.pageCount
        let name = url.deletingPathExtension().lastPathComponent
        let t = nowMs()
        let note = NoteMeta(id: Ids.uuid(), folderId: folderId, title: name.isEmpty ? "PDF importé" : name,
                            createdAt: t, updatedAt: t, paper: .BLANK, pageCount: count, paperColor: 0xFFFFFFFF)
        updateIndex { $0.notes.append(note) }
        saveContent(note.id, NoteContent(pages: (0..<count).map { Page(pdf: PdfRef(asset: asset, index: $0)) }))
        return note
    }

    /// Copie une photo dans l'appli (réduite à 2048 px au plus). Renvoie son nom et sa taille.
    func importImage(_ image: UIImage) -> (String, CGSize)? {
        let px = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        guard px.width > 0, px.height > 0 else { return nil }
        let k = min(1, 2048 / max(px.width, px.height))
        let target = CGSize(width: floor(px.width * k), height: floor(px.height * k))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let img = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let data = img.jpegData(compressionQuality: 0.9) else { return nil }
        let asset = Ids.uuid() + ".jpg"
        Paths.writeAtomic(data, to: Paths.asset(asset))
        return (asset, target)
    }
}
