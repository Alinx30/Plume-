import Foundation
import CoreGraphics

// Même format de données que Plume Android et Plume PC : les notes passent de l'un à l'autre sans conversion.

/// Largeur et hauteur logiques d'une page (format A4). Les traits sont stockés dans ces unités.
let PAGE_W: CGFloat = 1000
let PAGE_H: CGFloat = 1414
/// Espace entre deux pages dans le défilement continu.
let PAGE_GAP: CGFloat = 36

func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

/// Identifiants uniques pour les traits, zones de texte et photos.
enum Ids {
    private static let lock = NSLock()
    private static var seq: Int64 = nowMs() * 1000

    static func next() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        seq += 1
        return seq
    }

    static func uuid() -> String { UUID().uuidString.lowercased() }
}

// Lecture tolérante : un champ manquant ou d'un type inattendu prend sa valeur par défaut,
// pour ne jamais perdre une note entière à cause d'un détail.
extension KeyedDecodingContainer {
    func i64(_ key: Key, _ def: Int64) -> Int64 {
        if let v = try? decodeIfPresent(Int64.self, forKey: key) { return v }
        if let v = try? decodeIfPresent(Double.self, forKey: key), v.isFinite { return Int64(v) }
        return def
    }

    func int(_ key: Key, _ def: Int) -> Int { Int(i64(key, Int64(def))) }

    func dbl(_ key: Key, _ def: Double) -> Double {
        if let v = try? decodeIfPresent(Double.self, forKey: key) { return v }
        return def
    }

    func str(_ key: Key) -> String? {
        if let v = try? decodeIfPresent(String.self, forKey: key) { return v }
        return nil
    }

    func list<T: Decodable>(_ key: Key) -> [T] {
        if let v = try? decodeIfPresent([T].self, forKey: key) { return v }
        return []
    }
}

struct Folder: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var parentId: String?
    var color: Int64
    var createdAt: Int64
    /// Dernière modification (pour la synchronisation).
    var updatedAt: Int64

    enum CodingKeys: String, CodingKey { case id, name, parentId, color, createdAt, updatedAt }

    init(id: String, name: String, parentId: String?, color: Int64, createdAt: Int64, updatedAt: Int64) {
        self.id = id
        self.name = name
        self.parentId = parentId
        self.color = color
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.str(.id) ?? Ids.uuid()
        name = c.str(.name) ?? "Dossier"
        parentId = c.str(.parentId)
        color = c.i64(.color, 0xFF3F5AA8)
        createdAt = c.i64(.createdAt, 0)
        updatedAt = c.i64(.updatedAt, 0)
    }
}

enum PaperStyle: String, Codable, CaseIterable {
    case BLANK, LINED, LINED_WIDE, SEYES, GRID, GRID_SMALL, MILLIMETER, DOTS, CORNELL, MUSIC

    var label: String {
        switch self {
        case .BLANK: return "Blanc"
        case .LINED: return "Lignes"
        case .LINED_WIDE: return "Grandes lignes"
        case .SEYES: return "Seyès"
        case .GRID: return "Quadrillage"
        case .GRID_SMALL: return "Petits carreaux"
        case .MILLIMETER: return "Millimétré"
        case .DOTS: return "Points"
        case .CORNELL: return "Cornell"
        case .MUSIC: return "Portées"
        }
    }
}

/// Couleurs de papier proposées.
let PaperColors: [(Int64, String)] = [
    (0xFFFFFFFF, "Blanc"),
    (0xFFFBF5E6, "Crème"),
    (0xFFFFF7C2, "Jaune"),
    (0xFFE9EEF3, "Gris bleu"),
    (0xFF232733, "Ardoise"),
    (0xFF121212, "Noir"),
]

let FolderColors: [Int64] = [
    0xFF3F5AA8, 0xFFE07A3F, 0xFF3E9C74, 0xFFB0457E, 0xFF7A5BC4, 0xFFC9A227, 0xFF4E8FB8, 0xFF8C6B4F,
]

struct NoteMeta: Codable, Identifiable, Equatable {
    var id: String
    /// Dossier de la note, ou nil pour « Non classées ».
    var folderId: String?
    var title: String
    var createdAt: Int64
    var updatedAt: Int64
    var paper: PaperStyle
    var pageCount: Int
    var paperColor: Int64

    enum CodingKeys: String, CodingKey {
        case id, folderId = "notebookId", title, createdAt, updatedAt, paper, pageCount, paperColor
    }

    init(id: String, folderId: String?, title: String, createdAt: Int64, updatedAt: Int64,
         paper: PaperStyle = .LINED, pageCount: Int = 1, paperColor: Int64 = 0xFFFFFFFF) {
        self.id = id
        self.folderId = folderId
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.paper = paper
        self.pageCount = pageCount
        self.paperColor = paperColor
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.str(.id) ?? Ids.uuid()
        folderId = c.str(.folderId)
        title = c.str(.title) ?? "Note"
        createdAt = c.i64(.createdAt, 0)
        updatedAt = c.i64(.updatedAt, 0)
        paper = PaperStyle(rawValue: c.str(.paper) ?? "") ?? .LINED
        pageCount = c.int(.pageCount, 1)
        paperColor = c.i64(.paperColor, 0xFFFFFFFF)
    }
}

enum InkTool: String, Codable {
    case PEN, HIGHLIGHTER
}

/// Un trait manuscrit. `points` contient des triplets (x, y, pression) à plat, en coordonnées de page.
struct Stroke: Codable, Equatable {
    var id: Int64
    var tool: InkTool
    var color: Int64
    var width: Double
    var points: [Double]
    /// Enregistrement audio en cours quand le trait a été écrit, et instant (ms) dans cet enregistrement.
    var rec: String?
    var t: Int64

    enum CodingKeys: String, CodingKey { case id, tool, color, width, points, rec, t }

    init(id: Int64, tool: InkTool, color: Int64, width: Double, points: [Double], rec: String? = nil, t: Int64 = -1) {
        self.id = id
        self.tool = tool
        self.color = color
        self.width = width
        self.points = points
        self.rec = rec
        self.t = t
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.i64(.id, Ids.next())
        tool = InkTool(rawValue: c.str(.tool) ?? "") ?? .PEN
        color = c.i64(.color, 0xFF1B1B1F)
        width = c.dbl(.width, 4)
        points = c.list(.points)
        rec = c.str(.rec)
        t = c.i64(.t, -1)
    }
}

/// Zone de texte tapé au clavier, en coordonnées de page.
struct TextBox: Codable, Equatable {
    var id: Int64
    var x: Double
    var y: Double
    var width: Double
    var text: String
    var size: Double
    var color: Int64

    enum CodingKeys: String, CodingKey { case id, x, y, width, text, size, color }

    init(id: Int64, x: Double, y: Double, width: Double, text: String, size: Double = 30, color: Int64 = 0xFF1B1B1F) {
        self.id = id
        self.x = x
        self.y = y
        self.width = width
        self.text = text
        self.size = size
        self.color = color
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.i64(.id, Ids.next())
        x = c.dbl(.x, 0)
        y = c.dbl(.y, 0)
        width = c.dbl(.width, 400)
        text = c.str(.text) ?? ""
        size = c.dbl(.size, 30)
        color = c.i64(.color, 0xFF1B1B1F)
    }
}

/// Page d'un PDF importé, utilisée comme fond.
struct PdfRef: Codable, Equatable {
    var asset: String
    var index: Int

    enum CodingKeys: String, CodingKey { case asset, index }

    init(asset: String, index: Int) {
        self.asset = asset
        self.index = index
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        asset = c.str(.asset) ?? ""
        index = c.int(.index, 0)
    }
}

/// Photo posée sur la page, en coordonnées de page.
struct ImageItem: Codable, Equatable {
    var id: Int64
    var asset: String
    var x: Double
    var y: Double
    var width: Double
    var height: Double

    enum CodingKeys: String, CodingKey { case id, asset, x, y, width, height }

    init(id: Int64, asset: String, x: Double, y: Double, width: Double, height: Double) {
        self.id = id
        self.asset = asset
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.i64(.id, Ids.next())
        asset = c.str(.asset) ?? ""
        x = c.dbl(.x, 0)
        y = c.dbl(.y, 0)
        width = c.dbl(.width, 100)
        height = c.dbl(.height, 100)
    }
}

struct Page: Codable, Equatable {
    var strokes: [Stroke] = []
    var texts: [TextBox] = []
    var images: [ImageItem] = []
    var pdf: PdfRef?

    enum CodingKeys: String, CodingKey { case strokes, texts, images, pdf }

    init(strokes: [Stroke] = [], texts: [TextBox] = [], images: [ImageItem] = [], pdf: PdfRef? = nil) {
        self.strokes = strokes
        self.texts = texts
        self.images = images
        self.pdf = pdf
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        strokes = c.list(.strokes)
        texts = c.list(.texts)
        images = c.list(.images)
        pdf = try? c.decodeIfPresent(PdfRef.self, forKey: .pdf)
    }

    var hasContent: Bool { !strokes.isEmpty || !texts.isEmpty || !images.isEmpty || pdf != nil }
}

/// Enregistrement audio attaché à une note (créé sur Android ; conservé tel quel ici).
struct Recording: Codable, Equatable {
    var id: String
    var asset: String
    var createdAt: Int64
    var durationMs: Int64

    enum CodingKeys: String, CodingKey { case id, asset, createdAt, durationMs }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.str(.id) ?? Ids.uuid()
        asset = c.str(.asset) ?? ""
        createdAt = c.i64(.createdAt, 0)
        durationMs = c.i64(.durationMs, 0)
    }
}

struct NoteContent: Codable, Equatable {
    var pages: [Page] = [Page()]
    var recordings: [Recording] = []

    enum CodingKeys: String, CodingKey { case pages, recordings }

    init(pages: [Page] = [Page()], recordings: [Recording] = []) {
        self.pages = pages.isEmpty ? [Page()] : pages
        self.recordings = recordings
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let p: [Page] = c.list(.pages)
        pages = p.isEmpty ? [Page()] : p
        recordings = c.list(.recordings)
    }
}

struct LibraryIndex: Codable, Equatable {
    var folders: [Folder] = []
    /// Ancien format (v0.1) : les carnets deviennent des dossiers.
    var notebooks: [Folder] = []
    var notes: [NoteMeta] = []

    enum CodingKeys: String, CodingKey { case folders, notebooks, notes }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        folders = c.list(.folders)
        notebooks = c.list(.notebooks)
        notes = c.list(.notes)
        if folders.isEmpty && !notebooks.isEmpty {
            folders = notebooks
            notebooks = []
        }
    }
}
