import UIKit
import PencilKit
import PhotosUI
import SwiftUI

/// Fond d'une page (papier, PDF, photos, texte), affiché sous l'encre PencilKit.
final class PageView: UIView {
    private struct Background: Equatable {
        var texts: [TextBox]
        var images: [ImageItem]
        var pdf: PdfRef?
        var style: PaperStyle
        var color: Int64
    }

    private var page = Page()
    private var background: Background?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        backgroundColor = .white
        isUserInteractionEnabled = false
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.12
        layer.shadowRadius = 6
        layer.shadowOffset = CGSize(width: 0, height: 2)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(page: Page, style: PaperStyle, color: Int64, scale: CGFloat) {
        let bg = Background(texts: page.texts, images: page.images, pdf: page.pdf, style: style, color: color)
        var redraw = bg != background
        if abs(contentScaleFactor - scale) > 0.01 {
            contentScaleFactor = scale
            redraw = true
        }
        self.page = Page(texts: page.texts, images: page.images, pdf: page.pdf)
        background = bg
        layer.shadowPath = UIBezierPath(rect: bounds).cgPath
        if redraw { setNeedsDisplay() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let bg = background else { return }
        PageRenderer.drawPage(ctx, page: page, style: bg.style, color: bg.color,
                              pixelWidth: PAGE_W * contentScaleFactor, blocking: false, ink: false) { [weak self] in
            self?.setNeedsDisplay()
        }
    }
}

/// L'éditeur d'une note : pages en défilement continu, écriture au stylet avec PencilKit.
final class EditorViewController: UIViewController, PKCanvasViewDelegate, PHPickerViewControllerDelegate {
    private let store = NoteStore.shared
    private let noteId: String
    private var meta: NoteMeta
    private var content: NoteContent
    private let onClose: () -> Void

    private let canvas = PKCanvasView()
    private let underlay = UIView()
    private var pageViews: [Int: PageView] = [:]
    private let toolPicker = PKToolPicker()
    private var originals: [String: InkBridge.Original] = [:]
    private var loading = true
    private var dirty = false
    private var saveTask: Task<Void, Never>?
    private var lastWidth: CGFloat = 0
    private var renderScale: CGFloat = 2
    private var textMode = false
    private var tap: UITapGestureRecognizer!
    private var textItem: UIBarButtonItem!
    private var moreItem: UIBarButtonItem!

    init(noteId: String, onClose: @escaping () -> Void) {
        self.noteId = noteId
        self.onClose = onClose
        let s = NoteStore.shared
        let t = nowMs()
        meta = s.note(noteId) ?? NoteMeta(id: noteId, folderId: nil, title: "Note", createdAt: t, updatedAt: t)
        content = s.loadContent(noteId)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Cycle de vie

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.84, green: 0.85, blue: 0.87, alpha: 1)

        canvas.frame = view.bounds
        canvas.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        // L'encre garde ses vraies couleurs, même quand l'iPad est en mode sombre.
        canvas.overrideUserInterfaceStyle = .light
        canvas.alwaysBounceVertical = true
        canvas.delegate = self
        applyDrawingPolicy()
        view.addSubview(canvas)

        underlay.isUserInteractionEnabled = false
        underlay.layer.anchorPoint = .zero
        canvas.insertSubview(underlay, at: 0)

        tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.isEnabled = false
        canvas.addGestureRecognizer(tap)

        let (drawing, originals) = InkBridge.load(content)
        self.originals = originals
        canvas.drawing = drawing

        setupNavigation()
        store.editingNoteId = noteId
        NotificationCenter.default.addObserver(self, selector: #selector(appWillResign),
                                               name: UIApplication.willResignActiveNotification, object: nil)
        // Le premier « dessin modifié » vient du chargement : on l'ignore.
        DispatchQueue.main.async { [weak self] in self?.loading = false }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        toolPicker.colorUserInterfaceStyle = .light
        toolPicker.addObserver(canvas)
        toolPicker.setVisible(true, forFirstResponder: canvas)
        canvas.becomeFirstResponder()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let w = canvas.bounds.width
        guard w > 0, abs(w - lastWidth) > 0.5 else { return }
        let first = lastWidth == 0
        let rel = first ? 1 : canvas.zoomScale / max(canvas.minimumZoomScale, 0.01)
        lastWidth = w
        let fit = w / InkBridge.canvasWidth
        canvas.minimumZoomScale = fit
        canvas.maximumZoomScale = fit * 4
        canvas.zoomScale = fit * rel
        renderScale = pageScale()
        updateContentSize()
        if first { canvas.contentOffset = CGPoint(x: 0, y: -canvas.adjustedContentInset.top) }
    }

    @objc private func appWillResign() { saveNow() }

    // MARK: - Mise en page

    private func pageScale() -> CGFloat {
        min(2.6, max(0.6, (view.window?.screen.scale ?? 2) * canvas.zoomScale))
    }

    private func updateContentSize() {
        let z = canvas.zoomScale
        let h = InkBridge.canvasHeight(pages: content.pages.count)
        canvas.contentSize = CGSize(width: InkBridge.canvasWidth * z, height: h * z)
        underlay.transform = .identity
        underlay.bounds = CGRect(x: 0, y: 0, width: InkBridge.canvasWidth, height: h)
        underlay.transform = CGAffineTransform(scaleX: z, y: z)
        underlay.layer.position = .zero
        canvas.sendSubviewToBack(underlay)
        updateVisiblePages()
    }

    /// Ne garde à l'écran que les pages visibles (et leurs voisines), pour économiser la mémoire.
    private func updateVisiblePages() {
        let z = canvas.zoomScale
        guard z > 0 else { return }
        let top = canvas.contentOffset.y / z - PAGE_H
        let bottom = (canvas.contentOffset.y + canvas.bounds.height) / z + PAGE_H
        var keep = Set<Int>()
        for i in content.pages.indices {
            let y = InkBridge.pageOrigin(i).y
            if y + PAGE_H >= top && y <= bottom { keep.insert(i) }
        }
        for (i, v) in pageViews where !keep.contains(i) {
            v.removeFromSuperview()
            pageViews[i] = nil
        }
        for i in keep.sorted() {
            let v: PageView
            if let existing = pageViews[i] {
                v = existing
            } else {
                v = PageView(frame: .zero)
                underlay.addSubview(v)
                pageViews[i] = v
            }
            v.frame = CGRect(origin: InkBridge.pageOrigin(i), size: CGSize(width: PAGE_W, height: PAGE_H))
            v.configure(page: content.pages[i], style: meta.paper, color: meta.paperColor, scale: renderScale)
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        updateVisiblePages()
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        updateContentSize()
    }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        renderScale = pageScale()
        updateContentSize()
    }

    private func currentPageIndex() -> Int {
        let z = max(canvas.zoomScale, 0.01)
        let y = (canvas.contentOffset.y + canvas.bounds.height / 2) / z
        return InkBridge.pageIndex(forY: y, pageCount: content.pages.count)
    }

    // MARK: - Encre

    func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
        guard !loading else { return }
        dirty = true
        ensureTrailingPage()
        scheduleSave()
    }

    /// Une page s'ajoute quand on écrit sur la dernière.
    private func ensureTrailingPage() {
        let b = canvas.drawing.bounds
        guard !b.isNull, !b.isEmpty else { return }
        var added = false
        while b.maxY > InkBridge.pageOrigin(content.pages.count - 1).y + 20 && content.pages.count < 500 {
            content.pages.append(Page())
            added = true
        }
        if added { updateContentSize() }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    private func saveNow() {
        saveTask?.cancel()
        saveTask = nil
        guard dirty else { return }
        dirty = false
        let (pages, next) = InkBridge.extract(canvas.drawing, pageCount: content.pages.count, originals: originals)
        originals = next
        for i in content.pages.indices { content.pages[i].strokes = pages[i] }
        store.saveContent(noteId, content)
        if let m = store.note(noteId) { meta = m }
    }

    private func applyDrawingPolicy() {
        let pref = UserDefaults.standard.object(forKey: "fingerDraw") as? Bool
        if UIDevice.current.userInterfaceIdiom == .phone {
            canvas.drawingPolicy = pref == false ? .pencilOnly : .anyInput
        } else if let pref {
            canvas.drawingPolicy = pref ? .anyInput : .pencilOnly
        } else {
            canvas.drawingPolicy = .default
        }
    }

    private var fingerDrawing: Bool { canvas.drawingPolicy == .anyInput }

    private func toggleFinger() {
        UserDefaults.standard.set(!fingerDrawing, forKey: "fingerDraw")
        applyDrawingPolicy()
        moreItem.menu = makeMenu()
    }

    // MARK: - Barre du haut

    private func setupNavigation() {
        title = meta.title
        navigationItem.largeTitleDisplayMode = .never
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "Bibliothèque", image: UIImage(systemName: "chevron.backward"),
                                                           primaryAction: UIAction { [weak self] _ in self?.close() }, menu: nil)
        textItem = UIBarButtonItem(image: UIImage(systemName: "textformat"), style: .plain, target: self, action: #selector(toggleText))
        textItem.accessibilityLabel = "Texte"
        let photo = UIBarButtonItem(image: UIImage(systemName: "photo"), style: .plain, target: self, action: #selector(addPhoto))
        photo.accessibilityLabel = "Photo"
        let undo = UIBarButtonItem(image: UIImage(systemName: "arrow.uturn.backward"), style: .plain, target: self, action: #selector(undoAction))
        let redo = UIBarButtonItem(image: UIImage(systemName: "arrow.uturn.forward"), style: .plain, target: self, action: #selector(redoAction))
        moreItem = UIBarButtonItem(title: "Plus", image: UIImage(systemName: "ellipsis.circle"), primaryAction: nil, menu: makeMenu())
        navigationItem.rightBarButtonItems = [moreItem, photo, textItem, redo, undo]
    }

    private func swatch(_ color: Int64) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 22, height: 22)).image { _ in
            let path = UIBezierPath(ovalIn: CGRect(x: 1, y: 1, width: 20, height: 20))
            UIColor(argb: color | 0xFF000000).setFill()
            path.fill()
            UIColor.gray.setStroke()
            path.lineWidth = 1
            path.stroke()
        }.withRenderingMode(.alwaysOriginal)
    }

    private func makeMenu() -> UIMenu {
        let currentStyle = meta.paper
        let currentColor = meta.paperColor
        let rename = UIAction(title: "Renommer", image: UIImage(systemName: "pencil")) { [weak self] _ in self?.rename() }
        let styles = PaperStyle.allCases.map { s in
            UIAction(title: s.label, state: s == currentStyle ? .on : .off) { [weak self] _ in self?.setPaper(style: s, color: nil) }
        }
        let colors = PaperColors.map { entry -> UIAction in
            let (c, name) = entry
            return UIAction(title: name, image: swatch(c), state: c == currentColor ? .on : .off) { [weak self] _ in
                self?.setPaper(style: nil, color: c)
            }
        }
        let paper = UIMenu(title: "Papier", image: UIImage(systemName: "doc.plaintext"), children: [
            UIMenu(title: "", options: .displayInline, children: styles),
            UIMenu(title: "Couleur du papier", image: UIImage(systemName: "paintpalette"), children: colors),
        ])
        let addPage = UIAction(title: "Ajouter une page", image: UIImage(systemName: "doc.badge.plus")) { [weak self] _ in self?.appendPage() }
        let finger = UIAction(title: "Dessiner au doigt", image: UIImage(systemName: "hand.draw"), state: fingerDrawing ? .on : .off) { [weak self] _ in
            self?.toggleFinger()
        }
        let export = UIAction(title: "Exporter en PDF", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in self?.exportPdf() }
        return UIMenu(children: [rename, paper, addPage, finger, export])
    }

    @objc private func undoAction() { canvas.undoManager?.undo() }
    @objc private func redoAction() { canvas.undoManager?.redo() }

    private func close() {
        saveNow()
        store.editingNoteId = nil
        toolPicker.setVisible(false, forFirstResponder: canvas)
        toolPicker.removeObserver(canvas)
        canvas.resignFirstResponder()
        SyncManager.shared.requestSync(delay: 0.5)
        onClose()
    }

    private func rename() {
        let alert = UIAlertController(title: "Renommer la note", message: nil, preferredStyle: .alert)
        alert.addTextField { [meta] f in
            f.text = meta.title
            f.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "Annuler", style: .cancel))
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self, weak alert] _ in
            guard let self, let text = alert?.textFields?.first?.text else { return }
            self.store.renameNote(self.noteId, title: text)
            if let m = self.store.note(self.noteId) { self.meta = m }
            self.title = self.meta.title
        })
        present(alert, animated: true)
    }

    private func setPaper(style: PaperStyle?, color: Int64?) {
        meta.paper = style ?? meta.paper
        meta.paperColor = color ?? meta.paperColor
        store.setPaper(noteId, style: meta.paper, color: meta.paperColor)
        if let m = store.note(noteId) { meta = m }
        moreItem.menu = makeMenu()
        updateVisiblePages()
    }

    private func appendPage() {
        content.pages.append(Page())
        dirty = true
        saveNow()
        updateContentSize()
        let z = canvas.zoomScale
        let y = InkBridge.pageOrigin(content.pages.count - 1).y * z - canvas.adjustedContentInset.top
        canvas.setContentOffset(CGPoint(x: canvas.contentOffset.x, y: max(0, y)), animated: true)
    }

    private func exportPdf() {
        dirty = true
        saveNow()
        guard let url = PageRenderer.exportPdf(content: content, meta: meta) else { return }
        let share = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        share.popoverPresentationController?.barButtonItem = moreItem
        present(share, animated: true)
    }

    // MARK: - Texte

    @objc private func toggleText() {
        textMode.toggle()
        tap.isEnabled = textMode
        canvas.drawingGestureRecognizer.isEnabled = !textMode
        textItem.image = UIImage(systemName: textMode ? "character.textbox" : "textformat")
        textItem.tintColor = textMode ? .systemOrange : nil
        if textMode {
            let hint = UIAlertController(title: "Mode texte",
                                         message: "Touchez la page pour ajouter un texte, ou touchez un texte ou une photo pour le modifier. Touchez à nouveau le bouton pour revenir à l'écriture.",
                                         preferredStyle: .alert)
            hint.addAction(UIAlertAction(title: "OK", style: .default))
            if !UserDefaults.standard.bool(forKey: "textHintShown") {
                UserDefaults.standard.set(true, forKey: "textHintShown")
                present(hint, animated: true)
            }
        }
    }

    @objc private func handleTap(_ g: UITapGestureRecognizer) {
        guard textMode else { return }
        let z = max(canvas.zoomScale, 0.01)
        let p = g.location(in: canvas)
        let cx = p.x / z, cy = p.y / z
        let idx = InkBridge.pageIndex(forY: cy, pageCount: content.pages.count)
        let o = InkBridge.pageOrigin(idx)
        let x = cx - o.x, y = cy - o.y
        guard x >= 0, x <= PAGE_W, y >= 0, y <= PAGE_H else { return }
        let page = content.pages[idx]
        if let t = page.texts.last(where: { PageRenderer.textRect($0).insetBy(dx: -12, dy: -12).contains(CGPoint(x: x, y: y)) }) {
            editText(page: idx, existing: t, x: x, y: y)
        } else if let im = page.images.last(where: { CGRect(x: $0.x, y: $0.y, width: $0.width, height: $0.height).contains(CGPoint(x: x, y: y)) }) {
            askRemoveImage(page: idx, id: im.id)
        } else {
            editText(page: idx, existing: nil, x: x, y: y)
        }
    }

    private func editText(page: Int, existing: TextBox?, x: CGFloat, y: CGFloat) {
        let alert = UIAlertController(title: existing == nil ? "Nouveau texte" : "Modifier le texte", message: nil, preferredStyle: .alert)
        alert.addTextField { f in
            f.text = existing?.text
            f.placeholder = "Texte"
            f.autocapitalizationType = .sentences
        }
        alert.addAction(UIAlertAction(title: "Annuler", style: .cancel))
        if let existing {
            alert.addAction(UIAlertAction(title: "Supprimer", style: .destructive) { [weak self] _ in
                self?.updatePage(page) { $0.texts.removeAll { t in t.id == existing.id } }
            })
        }
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self, weak alert] _ in
            let text = alert?.textFields?.first?.text ?? ""
            guard let self else { return }
            if let existing {
                self.updatePage(page) { p in
                    if text.trimmingCharacters(in: .whitespaces).isEmpty {
                        p.texts.removeAll { $0.id == existing.id }
                    } else if let i = p.texts.firstIndex(where: { $0.id == existing.id }) {
                        p.texts[i].text = text
                    }
                }
            } else if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                let width = max(200, min(600, PAGE_W - x - 20))
                let box = TextBox(id: Ids.next(), x: Double(x), y: Double(max(0, y - 18)), width: Double(width), text: text)
                self.updatePage(page) { $0.texts.append(box) }
            }
        })
        present(alert, animated: true)
    }

    private func updatePage(_ i: Int, _ change: (inout Page) -> Void) {
        guard content.pages.indices.contains(i) else { return }
        change(&content.pages[i])
        dirty = true
        saveNow()
        updateVisiblePages()
    }

    // MARK: - Photos

    @objc private func addPhoto() {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else { return }
        Task { @MainActor [weak self] in
            let image: UIImage? = await withCheckedContinuation { cont in
                provider.loadObject(ofClass: UIImage.self) { obj, _ in cont.resume(returning: obj as? UIImage) }
            }
            if let image { self?.insertImage(image) }
        }
    }

    private func insertImage(_ image: UIImage) {
        guard let imported = store.importImage(image) else { return }
        let (asset, size) = imported
        guard size.width > 0, size.height > 0 else { return }
        var w: CGFloat = 500
        var h = w * size.height / size.width
        if h > 700 {
            h = 700
            w = h * size.width / size.height
        }
        let item = ImageItem(id: Ids.next(), asset: asset, x: Double((PAGE_W - w) / 2), y: Double((PAGE_H - h) / 2),
                             width: Double(w), height: Double(h))
        updatePage(currentPageIndex()) { $0.images.append(item) }
    }

    private func askRemoveImage(page: Int, id: Int64) {
        let alert = UIAlertController(title: "Photo", message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Supprimer la photo", style: .destructive) { [weak self] _ in
            self?.updatePage(page) { $0.images.removeAll { im in im.id == id } }
        })
        alert.addAction(UIAlertAction(title: "Annuler", style: .cancel))
        present(alert, animated: true)
    }
}

/// L'éditeur, présenté en plein écran depuis la bibliothèque SwiftUI.
struct EditorHost: UIViewControllerRepresentable {
    let noteId: String
    let onClose: () -> Void

    func makeUIViewController(context: Context) -> UINavigationController {
        let nav = UINavigationController(rootViewController: EditorViewController(noteId: noteId, onClose: onClose))
        nav.navigationBar.prefersLargeTitles = false
        return nav
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}
