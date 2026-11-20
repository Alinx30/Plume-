import UIKit
import PDFKit

extension UIColor {
    /// Couleur au format ARGB d'Android (0xAARRGGBB).
    convenience init(argb: Int64) {
        let v = UInt32(truncatingIfNeeded: argb)
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255,
                  green: CGFloat((v >> 8) & 0xFF) / 255,
                  blue: CGFloat(v & 0xFF) / 255,
                  alpha: CGFloat((v >> 24) & 0xFF) / 255)
    }

    var argb: Int64 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        if !getRed(&r, green: &g, blue: &b, alpha: &a) {
            var w: CGFloat = 0
            if getWhite(&w, alpha: &a) { r = w; g = w; b = w } else { a = 1 }
        }
        func c(_ x: CGFloat) -> Int64 { Int64(max(0, min(255, (x * 255).rounded()))) }
        return (c(a) << 24) | (c(r) << 16) | (c(g) << 8) | c(b)
    }
}

/// Fichiers importés (PDF, photos) et leurs images en cache.
final class AssetCache {
    static let shared = AssetCache()

    private let cache = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private let renderLock = NSLock()
    private let queue = DispatchQueue(label: "plume.render", qos: .userInitiated)
    private var docs: [String: PDFDocument] = [:]
    private var inFlight = Set<String>()

    private init() {
        cache.totalCostLimit = 160 * 1024 * 1024
    }

    private static func bucket(_ w: CGFloat) -> CGFloat { w <= 400 ? 400 : (w <= 1000 ? 1000 : 1800) }

    private func document(_ asset: String) -> PDFDocument? {
        lock.lock()
        defer { lock.unlock() }
        if let d = docs[asset] { return d }
        let d = PDFDocument(url: Paths.asset(asset))
        if let d { docs[asset] = d }
        return d
    }

    func pdfPageCount(_ asset: String) -> Int { document(asset)?.pageCount ?? 0 }

    /// Image d'une page de PDF. Sans `blocking`, renvoie nil si elle n'est pas prête et appelle `onReady` plus tard.
    func pdfImage(_ ref: PdfRef, pixelWidth: CGFloat, blocking: Bool, onReady: (() -> Void)?) -> UIImage? {
        let b = Self.bucket(pixelWidth)
        let key = "pdf|\(ref.asset)|\(ref.index)|\(Int(b))"
        return lookup(key, blocking: blocking, onReady: onReady) { [weak self] in self?.renderPdf(ref, width: b) }
    }

    /// Photo réduite à la taille utile.
    func photo(_ asset: String, pixelWidth: CGFloat, blocking: Bool, onReady: (() -> Void)?) -> UIImage? {
        let b = Self.bucket(pixelWidth)
        let key = "img|\(asset)|\(Int(b))"
        return lookup(key, blocking: blocking, onReady: onReady) {
            guard let img = UIImage(contentsOfFile: Paths.asset(asset).path) else { return nil }
            let side = max(img.size.width, img.size.height)
            if side <= b { return img }
            let k = b / side
            return img.preparingThumbnail(of: CGSize(width: img.size.width * k, height: img.size.height * k)) ?? img
        }
    }

    private func lookup(_ key: String, blocking: Bool, onReady: (() -> Void)?, make: @escaping () -> UIImage?) -> UIImage? {
        if let img = cache.object(forKey: key as NSString) { return img }
        if blocking {
            let img = make()
            if let img { store(img, key) }
            return img
        }
        lock.lock()
        let start = !inFlight.contains(key)
        if start { inFlight.insert(key) }
        lock.unlock()
        if start {
            queue.async { [weak self] in
                let img = make()
                guard let self else { return }
                if let img { self.store(img, key) }
                self.lock.lock()
                self.inFlight.remove(key)
                self.lock.unlock()
                if img != nil, let onReady { DispatchQueue.main.async { onReady() } }
            }
        }
        return nil
    }

    private func store(_ img: UIImage, _ key: String) {
        let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
        cache.setObject(img, forKey: key as NSString, cost: cost)
    }

    private func renderPdf(_ ref: PdfRef, width: CGFloat) -> UIImage? {
        guard let doc = document(ref.asset) else { return nil }
        renderLock.lock()
        defer { renderLock.unlock() }
        guard let page = doc.page(at: ref.index) else { return nil }
        let box = page.bounds(for: .mediaBox)
        var w = box.width, h = box.height
        if page.rotation % 180 != 0 { swap(&w, &h) }
        guard w > 0, h > 0 else { return nil }
        return page.thumbnail(of: CGSize(width: width, height: width * h / w), for: .mediaBox)
    }
}

/// Dessine une page (papier, PDF, photos, texte, encre) en coordonnées de page.
/// Utilisé par l'éditeur (fond sous l'encre), les miniatures et l'export PDF.
enum PageRenderer {

    static func isDark(_ color: Int64) -> Bool {
        let v = UInt32(truncatingIfNeeded: color)
        let r = Double((v >> 16) & 0xFF) / 255, g = Double((v >> 8) & 0xFF) / 255, b = Double(v & 0xFF) / 255
        return r * 0.299 + g * 0.587 + b * 0.114 < 0.5
    }

    /// Rectangle centré dans la page qui garde les proportions de l'image.
    static func fitRect(_ size: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0 else { return CGRect(x: 0, y: 0, width: PAGE_W, height: PAGE_H) }
        let ratio = size.height / size.width
        if ratio >= PAGE_H / PAGE_W {
            let dw = PAGE_H / ratio
            return CGRect(x: (PAGE_W - dw) / 2, y: 0, width: dw, height: PAGE_H)
        } else {
            let dh = PAGE_W * ratio
            return CGRect(x: 0, y: (PAGE_H - dh) / 2, width: PAGE_W, height: dh)
        }
    }

    /// Dessine une page complète. Le contexte UIKit courant doit être `ctx`.
    /// - Parameters:
    ///   - pixelWidth: largeur de la page en pixels, pour choisir la résolution des images.
    ///   - ink: dessiner aussi l'encre (miniatures, export). Dans l'éditeur, l'encre est gérée par PencilKit.
    static func drawPage(_ ctx: CGContext, page: Page, style: PaperStyle, color: Int64,
                         pixelWidth: CGFloat, blocking: Bool, ink: Bool, onReady: (() -> Void)? = nil) {
        if let pdf = page.pdf {
            ctx.setFillColor(UIColor.white.cgColor)
            ctx.fill(CGRect(x: 0, y: 0, width: PAGE_W, height: PAGE_H))
            if let img = AssetCache.shared.pdfImage(pdf, pixelWidth: pixelWidth, blocking: blocking, onReady: onReady) {
                img.draw(in: fitRect(img.size))
            }
        } else {
            drawPaper(ctx, style: style, color: color)
        }
        for im in page.images {
            let rect = CGRect(x: im.x, y: im.y, width: im.width, height: im.height)
            let px = max(64, pixelWidth * CGFloat(im.width) / PAGE_W)
            if let img = AssetCache.shared.photo(im.asset, pixelWidth: px, blocking: blocking, onReady: onReady) {
                img.draw(in: rect)
            } else {
                ctx.setFillColor(UIColor(white: 0.93, alpha: 1).cgColor)
                ctx.fill(rect)
            }
        }
        // Le surligneur passe sous l'encre et le texte, comme sur du vrai papier.
        if ink { for s in page.strokes where s.tool == .HIGHLIGHTER { drawStroke(ctx, s) } }
        for t in page.texts { drawText(t) }
        if ink { for s in page.strokes where s.tool != .HIGHLIGHTER { drawStroke(ctx, s) } }
    }

    // MARK: - Papier

    private static func hLines(_ ctx: CGContext, _ start: CGFloat, _ step: CGFloat, _ color: Int64, _ width: CGFloat,
                               end: CGFloat = PAGE_H, x0: CGFloat = 0, x1: CGFloat = PAGE_W) {
        var y = start
        while y < end {
            ctx.move(to: CGPoint(x: x0, y: y))
            ctx.addLine(to: CGPoint(x: x1, y: y))
            y += step
        }
        strokeLines(ctx, color, width)
    }

    private static func vLines(_ ctx: CGContext, _ start: CGFloat, _ step: CGFloat, _ color: Int64, _ width: CGFloat, end: CGFloat = PAGE_W) {
        var x = start
        while x < end {
            ctx.move(to: CGPoint(x: x, y: 0))
            ctx.addLine(to: CGPoint(x: x, y: PAGE_H))
            x += step
        }
        strokeLines(ctx, color, width)
    }

    private static func line(_ ctx: CGContext, _ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ color: Int64, _ width: CGFloat) {
        ctx.move(to: CGPoint(x: x0, y: y0))
        ctx.addLine(to: CGPoint(x: x1, y: y1))
        strokeLines(ctx, color, width)
    }

    private static func strokeLines(_ ctx: CGContext, _ color: Int64, _ width: CGFloat) {
        ctx.setStrokeColor(UIColor(argb: color).cgColor)
        ctx.setLineWidth(width)
        ctx.setLineCap(.butt)
        ctx.strokePath()
    }

    /// Fond de page : couleur et motif (lignes, carreaux, Seyès, portées…).
    static func drawPaper(_ ctx: CGContext, style: PaperStyle, color: Int64) {
        ctx.setFillColor(UIColor(argb: color | 0xFF000000).cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: PAGE_W, height: PAGE_H))
        let dark = isDark(color)
        let line: Int64 = dark ? 0x44FFFFFF : 0xFFC9D7E8
        let strong: Int64 = dark ? 0x77FFFFFF : 0xFF9DB2CC
        let margin: Int64 = dark ? 0x99FF8A80 : 0xFFEFB4B0
        let mm: CGFloat = 1000 / 210 // 1 mm en unités de page
        switch style {
        case .BLANK:
            break
        case .LINED:
            hLines(ctx, 150, 44, line, 1.6, end: PAGE_H - 40)
            self.line(ctx, 110, 0, 110, PAGE_H, margin, 2)
        case .LINED_WIDE:
            hLines(ctx, 160, 60, line, 1.6, end: PAGE_H - 40)
            self.line(ctx, 110, 0, 110, PAGE_H, margin, 2)
        case .SEYES:
            // Carreaux de 8 mm, interlignes de 2 mm, marge rouge.
            let thin: Int64 = dark ? 0x33B0B8FF : 0xFFD3D5EE
            let thick: Int64 = dark ? 0x66B0B8FF : 0xFFA3A8DA
            hLines(ctx, 2 * mm, 2 * mm, thin, 1.2)
            hLines(ctx, 8 * mm, 8 * mm, thick, 1.8)
            vLines(ctx, 8 * mm, 8 * mm, thick, 1.8)
            self.line(ctx, 40 * mm, 0, 40 * mm, PAGE_H, margin, 2)
        case .GRID:
            vLines(ctx, 40, 40, line, 1.6)
            hLines(ctx, 40, 40, line, 1.6)
        case .GRID_SMALL:
            vLines(ctx, 5 * mm, 5 * mm, line, 1.6)
            hLines(ctx, 5 * mm, 5 * mm, line, 1.6)
        case .MILLIMETER:
            let fine: Int64 = dark ? 0x22FFB74D : 0x55F0A868
            let mid: Int64 = dark ? 0x44FFB74D : 0x99F0A868
            let big: Int64 = dark ? 0x77FFB74D : 0xFFE59B57
            vLines(ctx, mm, mm, fine, 0.8); hLines(ctx, mm, mm, fine, 0.8)
            vLines(ctx, 5 * mm, 5 * mm, mid, 1.2); hLines(ctx, 5 * mm, 5 * mm, mid, 1.2)
            vLines(ctx, 10 * mm, 10 * mm, big, 1.8); hLines(ctx, 10 * mm, 10 * mm, big, 1.8)
        case .DOTS:
            let dot: Int64 = dark ? 0x66FFFFFF : 0xFFB4C2D3
            ctx.setFillColor(UIColor(argb: dot).cgColor)
            var y: CGFloat = 40
            while y < PAGE_H {
                var x: CGFloat = 40
                while x < PAGE_W {
                    ctx.addEllipse(in: CGRect(x: x - 2.4, y: y - 2.4, width: 4.8, height: 4.8))
                    x += 40
                }
                y += 40
            }
            ctx.fillPath()
        case .CORNELL:
            // Titre en haut, colonne de mots-clés à gauche, résumé en bas.
            let top: CGFloat = 130
            let bottom: CGFloat = PAGE_H - 260
            hLines(ctx, top + 44, 44, line, 1.6, end: bottom, x0: 280)
            self.line(ctx, 0, top, PAGE_W, top, strong, 2)
            self.line(ctx, 280, top, 280, bottom, strong, 2)
            self.line(ctx, 0, bottom, PAGE_W, bottom, strong, 2)
        case .MUSIC:
            let staff: Int64 = dark ? 0x88FFFFFF : 0xFF8C96A8
            var y: CGFloat = 120
            while y + 48 < PAGE_H - 60 {
                for k in 0..<5 {
                    ctx.move(to: CGPoint(x: 60, y: y + CGFloat(k) * 12))
                    ctx.addLine(to: CGPoint(x: PAGE_W - 60, y: y + CGFloat(k) * 12))
                }
                y += 118
            }
            strokeLines(ctx, staff, 1.6)
        }
    }

    // MARK: - Encre

    /// Courbe lissée (quadratiques passant par les milieux) à partir des triplets x, y, pression.
    static func strokePath(_ p: [Double]) -> CGPath {
        let path = CGMutablePath()
        guard p.count >= 3 else { return path }
        path.move(to: CGPoint(x: p[0], y: p[1]))
        if p.count < 6 {
            path.addLine(to: CGPoint(x: p[0] + 0.1, y: p[1]))
            return path
        }
        var i = 3
        while i + 1 < p.count {
            let px = p[i - 3], py = p[i - 2], x = p[i], y = p[i + 1]
            path.addQuadCurve(to: CGPoint(x: (px + x) / 2, y: (py + y) / 2), control: CGPoint(x: px, y: py))
            i += 3
        }
        path.addLine(to: CGPoint(x: p[p.count - 3], y: p[p.count - 2]))
        return path
    }

    static func drawStroke(_ ctx: CGContext, _ s: Stroke) {
        ctx.saveGState()
        ctx.addPath(strokePath(s.points))
        let color = UIColor(argb: s.color)
        ctx.setStrokeColor(s.tool == .HIGHLIGHTER ? color.withAlphaComponent(0x70 / 255.0).cgColor : color.cgColor)
        ctx.setLineWidth(CGFloat(s.width))
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.strokePath()
        ctx.restoreGState()
    }

    // MARK: - Texte

    static func textAttributes(_ t: TextBox) -> [NSAttributedString.Key: Any] {
        [.font: UIFont.systemFont(ofSize: CGFloat(max(4, t.size))), .foregroundColor: UIColor(argb: t.color)]
    }

    static func textRect(_ t: TextBox) -> CGRect {
        let w = CGFloat(max(20, t.width))
        let s = NSAttributedString(string: t.text.isEmpty ? " " : t.text, attributes: textAttributes(t))
        let r = s.boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil)
        return CGRect(x: CGFloat(t.x), y: CGFloat(t.y), width: w, height: max(ceil(r.height), CGFloat(t.size)))
    }

    static func drawText(_ t: TextBox) {
        let s = NSAttributedString(string: t.text, attributes: textAttributes(t))
        s.draw(with: textRect(t), options: [.usesLineFragmentOrigin], context: nil)
    }

    // MARK: - Miniatures et export

    static func thumbnail(page: Page, style: PaperStyle, color: Int64, width: CGFloat) -> UIImage {
        let size = CGSize(width: width, height: (width * PAGE_H / PAGE_W).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { r in
            let ctx = r.cgContext
            ctx.scaleBy(x: width / PAGE_W, y: width / PAGE_W)
            drawPage(ctx, page: page, style: style, color: color, pixelWidth: width * 2, blocking: true, ink: true)
        }
    }

    /// Écrit la note dans un fichier PDF (A4) et renvoie son adresse.
    static func exportPdf(content: NoteContent, meta: NoteMeta) -> URL? {
        let w: CGFloat = 595, h: CGFloat = 842 // A4 en points PDF
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: w, height: h))
        var name = meta.title.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|")).joined(separator: "-")
        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent((name.isEmpty ? "Note" : name) + ".pdf")
        do {
            try renderer.writePDF(to: url) { r in
                for page in content.pages {
                    r.beginPage()
                    let ctx = r.cgContext
                    ctx.saveGState()
                    ctx.scaleBy(x: w / PAGE_W, y: h / PAGE_H)
                    drawPage(ctx, page: page, style: meta.paper, color: meta.paperColor, pixelWidth: 1800, blocking: true, ink: true)
                    ctx.restoreGState()
                }
            }
            return url
        } catch {
            return nil
        }
    }
}
