import SwiftUI
import UniformTypeIdentifiers

enum LibrarySection: Hashable {
    case all, recent, unfiled, folder(String)
}

enum SortMode: String, CaseIterable {
    case modified = "Date de modification"
    case created = "Date de création"
    case title = "Titre"
}

struct OpenNote: Identifiable {
    let id: String
}

struct FolderRow {
    let folder: Folder
    let depth: Int
}

struct DeleteTarget {
    let title: String
    let message: String
    let action: () -> Void
}

/// Miniatures des notes, calculées hors du fil principal.
@MainActor
final class Thumbnails {
    static let shared = Thumbnails()
    private let cache = NSCache<NSString, UIImage>()

    func image(for meta: NoteMeta) async -> UIImage? {
        let key = "\(meta.id)|\(meta.updatedAt)|\(meta.paper.rawValue)|\(meta.paperColor)" as NSString
        if let img = cache.object(forKey: key) { return img }
        let pending = NoteStore.shared.pendingContent(meta.id)
        let id = meta.id, style = meta.paper, color = meta.paperColor
        let img = await Task.detached(priority: .utility) { () -> UIImage in
            let content = pending ?? NoteStore.readContent(id)
            return PageRenderer.thumbnail(page: content.pages.first ?? Page(), style: style, color: color, width: 220)
        }.value
        cache.setObject(img, forKey: key)
        return img
    }
}

struct NoteThumb: View {
    let meta: NoteMeta
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(white: 0.96)
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            }
        }
        .aspectRatio(PAGE_W / PAGE_H, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Color.black.opacity(0.08)))
        .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
        .task(id: "\(meta.updatedAt)|\(meta.paper.rawValue)|\(meta.paperColor)") {
            image = await Thumbnails.shared.image(for: meta)
        }
    }
}

struct NoteCard: View {
    let meta: NoteMeta

    static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            NoteThumb(meta: meta)
            Text(meta.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
                .foregroundColor(.primary)
            Text(Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(meta.updatedAt) / 1000)))
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}

/// La bibliothèque : dossiers à gauche, notes en grille avec miniatures.
struct LibraryView: View {
    @EnvironmentObject private var store: NoteStore
    @EnvironmentObject private var sync: SyncManager

    @State private var section: LibrarySection? = .all
    @State private var search = ""
    @AppStorage("sortMode") private var sort: String = SortMode.modified.rawValue
    @State private var openNote: OpenNote?
    @State private var showImporter = false
    @State private var showSync = false
    @State private var showPrompt = false
    @State private var promptTitle = ""
    @State private var promptText = ""
    @State private var promptAction: ((String) -> Void)?
    @State private var deleteTarget: DeleteTarget?
    @State private var importFailed = false

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            notesGrid
        }
        .fullScreenCover(item: $openNote) { n in
            EditorHost(noteId: n.id) { openNote = nil }
                .ignoresSafeArea()
        }
        .sheet(isPresented: $showSync) {
            SyncView().environmentObject(sync)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [UTType.pdf]) { result in
            if case .success(let url) = result { importPdf(url) }
        }
        .alert(promptTitle, isPresented: $showPrompt) {
            TextField("Nom", text: $promptText)
            Button("Annuler", role: .cancel) {}
            Button("OK") { promptAction?(promptText) }
        }
        .alert("Impossible d'importer ce PDF.", isPresented: $importFailed) {
            Button("OK", role: .cancel) {}
        }
        .confirmationDialog(deleteTarget?.title ?? "",
                            isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
                            titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) { deleteTarget?.action() }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text(deleteTarget?.message ?? "")
        }
        .onOpenURL { url in
            if url.pathExtension.lowercased() == "pdf" { importPdf(url) }
        }
    }

    // MARK: - Barre latérale

    private var sidebar: some View {
        List(selection: $section) {
            NavigationLink(value: LibrarySection.all) { Label("Toutes les notes", systemImage: "doc.on.doc") }
            NavigationLink(value: LibrarySection.recent) { Label("Récentes", systemImage: "clock") }
            NavigationLink(value: LibrarySection.unfiled) { Label("Non classées", systemImage: "tray") }
            Section("Dossiers") {
                ForEach(folderRows, id: \.folder.id) { row in
                    NavigationLink(value: LibrarySection.folder(row.folder.id)) {
                        Label {
                            Text(row.folder.name)
                        } icon: {
                            Image(systemName: "folder.fill").foregroundColor(Color(uiColor: UIColor(argb: row.folder.color)))
                        }
                        .padding(.leading, CGFloat(row.depth) * 16)
                    }
                    .contextMenu { folderMenu(row.folder) }
                }
                Button {
                    askName("Nouveau dossier", "") { name in _ = store.createFolder(name: name, parentId: nil) }
                } label: {
                    Label("Nouveau dossier", systemImage: "folder.badge.plus")
                }
            }
        }
        .navigationTitle("Plume")
        .toolbar {
            ToolbarItem(placement: .primaryAction) { syncButton }
        }
    }

    private var syncIcon: String {
        if !sync.isPaired { return "icloud" }
        if sync.status.error != nil { return "exclamationmark.icloud" }
        if sync.status.unreachable { return "icloud.slash" }
        if sync.status.running { return "arrow.triangle.2.circlepath.icloud" }
        return "checkmark.icloud"
    }

    private var syncButton: some View {
        Button { showSync = true } label: { Image(systemName: syncIcon) }
            .accessibilityLabel("Synchroniser avec le PC")
    }

    @ViewBuilder
    private func folderMenu(_ f: Folder) -> some View {
        Button {
            askName("Renommer le dossier", f.name) { name in store.renameFolder(f.id, name: name) }
        } label: { Label("Renommer", systemImage: "pencil") }
        Button {
            askName("Nouveau sous-dossier", "") { name in _ = store.createFolder(name: name, parentId: f.id) }
        } label: { Label("Nouveau sous-dossier", systemImage: "folder.badge.plus") }
        Button(role: .destructive) {
            deleteTarget = DeleteTarget(title: "Supprimer « \(f.name) » ?",
                                        message: "Le dossier, ses sous-dossiers et toutes leurs notes seront supprimés, ici et sur votre PC.") {
                if section == .folder(f.id) { section = .all }
                store.deleteFolder(f.id)
            }
        } label: { Label("Supprimer", systemImage: "trash") }
    }

    private var folderRows: [FolderRow] {
        let all = store.index.folders
        let ids = Set(all.map(\.id))
        var out: [FolderRow] = []
        var seen = Set<String>()
        func add(_ parent: String?, _ depth: Int) {
            let children = all.filter { $0.parentId == parent }
                .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            for f in children where !seen.contains(f.id) {
                seen.insert(f.id)
                out.append(FolderRow(folder: f, depth: depth))
                if depth < 8 { add(f.id, depth + 1) }
            }
        }
        add(nil, 0)
        // Dossiers dont le parent n'existe plus.
        for f in all where !seen.contains(f.id) && !(f.parentId.map { ids.contains($0) } ?? false) {
            seen.insert(f.id)
            out.append(FolderRow(folder: f, depth: 0))
            add(f.id, 1)
        }
        return out
    }

    // MARK: - Notes

    private var currentFolderId: String? {
        if case .folder(let id) = section { return id }
        return nil
    }

    private var sectionTitle: String {
        switch section ?? .all {
        case .all: return "Toutes les notes"
        case .recent: return "Récentes"
        case .unfiled: return "Non classées"
        case .folder(let id): return store.index.folders.first { $0.id == id }?.name ?? "Dossier"
        }
    }

    private var visibleNotes: [NoteMeta] {
        var list = store.index.notes
        let q = search.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty {
            list = list.filter { $0.title.localizedCaseInsensitiveContains(q) }
        } else {
            switch section ?? .all {
            case .all:
                break
            case .recent:
                list = Array(list.sorted { $0.updatedAt > $1.updatedAt }.prefix(20))
                return list
            case .unfiled:
                let ids = Set(store.index.folders.map(\.id))
                list = list.filter { n in n.folderId.map { !ids.contains($0) } ?? true }
            case .folder(let id):
                list = list.filter { $0.folderId == id }
            }
        }
        switch SortMode(rawValue: sort) ?? .modified {
        case .modified: return list.sorted { $0.updatedAt > $1.updatedAt }
        case .created: return list.sorted { $0.createdAt > $1.createdAt }
        case .title: return list.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        }
    }

    private var notesGrid: some View {
        let notes = visibleNotes
        return ScrollView {
            if notes.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "pencil.and.outline").font(.system(size: 44)).foregroundColor(.secondary)
                    Text(search.isEmpty ? "Aucune note ici." : "Aucune note trouvée.").foregroundColor(.secondary)
                    if search.isEmpty {
                        Button { newNote() } label: { Label("Nouvelle note", systemImage: "square.and.pencil") }
                            .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 120)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140, maximum: 220), spacing: 20)], spacing: 26) {
                    ForEach(notes) { n in
                        Button { openNote = OpenNote(id: n.id) } label: { NoteCard(meta: n) }
                            .buttonStyle(.plain)
                            .contextMenu { noteMenu(n) }
                    }
                }
                .padding(20)
            }
        }
        .searchable(text: $search, prompt: "Rechercher")
        .navigationTitle(sectionTitle)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Menu {
                    Picker("Trier par", selection: $sort) {
                        ForEach(SortMode.allCases, id: \.rawValue) { m in Text(m.rawValue).tag(m.rawValue) }
                    }
                } label: { Image(systemName: "arrow.up.arrow.down") }
                Button { showImporter = true } label: { Image(systemName: "doc.badge.arrow.up") }
                    .accessibilityLabel("Importer un PDF")
                Button { newNote() } label: { Image(systemName: "square.and.pencil") }
                    .accessibilityLabel("Nouvelle note")
            }
        }
    }

    @ViewBuilder
    private func noteMenu(_ n: NoteMeta) -> some View {
        Button {
            askName("Renommer la note", n.title) { title in store.renameNote(n.id, title: title) }
        } label: { Label("Renommer", systemImage: "pencil") }
        Menu {
            Button("Non classées") { store.moveNote(n.id, folderId: nil) }
            ForEach(folderRows, id: \.folder.id) { r in
                Button(String(repeating: "   ", count: r.depth) + r.folder.name) { store.moveNote(n.id, folderId: r.folder.id) }
            }
        } label: { Label("Déplacer", systemImage: "folder") }
        Button { _ = store.duplicateNote(n.id) } label: { Label("Dupliquer", systemImage: "plus.square.on.square") }
        Button(role: .destructive) {
            deleteTarget = DeleteTarget(title: "Supprimer « \(n.title) » ?",
                                        message: "La note sera supprimée de cet appareil et de votre PC.") {
                store.deleteNote(n.id)
            }
        } label: { Label("Supprimer", systemImage: "trash") }
    }

    // MARK: - Actions

    private func askName(_ title: String, _ initial: String, _ action: @escaping (String) -> Void) {
        promptTitle = title
        promptText = initial
        promptAction = action
        showPrompt = true
    }

    private func newNote() {
        let n = store.createNote(folderId: currentFolderId)
        openNote = OpenNote(id: n.id)
    }

    private func importPdf(_ url: URL) {
        if let n = store.importPdf(url, folderId: currentFolderId) {
            openNote = OpenNote(id: n.id)
        } else {
            importFailed = true
        }
    }
}
