import UIKit
import PencilKit

/// Passage entre les traits de Plume (triplets x, y, pression par page) et les traits PencilKit.
///
/// Dans l'éditeur, toutes les pages sont empilées dans un seul dessin PencilKit :
/// la page `i` commence à `pageOrigin(i)`. Les traits qui n'ont pas changé depuis l'ouverture
/// sont rendus tels quels (identifiant, audio synchronisé), sans perte de précision.
enum InkBridge {
    static let margin: CGFloat = 24
    static var canvasWidth: CGFloat { PAGE_W + 2 * margin }

    static func pageOrigin(_ i: Int) -> CGPoint {
        CGPoint(x: margin, y: margin + CGFloat(i) * (PAGE_H + PAGE_GAP))
    }

    static func canvasHeight(pages: Int) -> CGFloat {
        pageOrigin(max(pages, 1) - 1).y + PAGE_H + margin * 4
    }

    static func pageIndex(forY y: CGFloat, pageCount: Int) -> Int {
        let i = Int(floor((y - margin + PAGE_GAP / 2) / (PAGE_H + PAGE_GAP)))
        return min(max(i, 0), max(pageCount - 1, 0))
    }

    struct Original {
        let page: Int
        let stroke: Stroke
    }

    private static let light = UITraitCollection(userInterfaceStyle: .light)

    static func argb(_ c: UIColor) -> Int64 { c.resolvedColor(with: light).argb }

    static func makeStroke(_ s: Stroke, origin: CGPoint) -> PKStroke? {
        let isMarker = s.tool == .HIGHLIGHTER
        let color = isMarker ? UIColor(argb: s.color | 0xFF000000) : UIColor(argb: s.color)
        let ink = PKInk(isMarker ? .marker : .pen, color: color)
        let w = CGFloat(max(0.5, s.width))
        let p = s.points
        var pts: [PKStrokePoint] = []
        pts.reserveCapacity(p.count / 3 + 1)
        var i = 0
        while i + 2 < p.count {
            let force = CGFloat(min(1, max(0.05, p[i + 2])))
            pts.append(PKStrokePoint(location: CGPoint(x: CGFloat(p[i]) + origin.x, y: CGFloat(p[i + 1]) + origin.y),
                                     timeOffset: TimeInterval(pts.count) * 0.008,
                                     size: CGSize(width: w, height: w), opacity: 1, force: force,
                                     azimuth: 0, altitude: .pi / 2))
            i += 3
        }
        guard let first = pts.first else { return nil }
        if pts.count == 1 {
            pts.append(PKStrokePoint(location: CGPoint(x: first.location.x + 0.1, y: first.location.y), timeOffset: 0.008,
                                     size: first.size, opacity: 1, force: first.force, azimuth: 0, altitude: .pi / 2))
        }
        return PKStroke(ink: ink, path: PKStrokePath(controlPoints: pts, creationDate: Date()))
    }

    /// Empreinte d'un trait intact (pas déplacé, pas gommé), pour retrouver le trait Plume d'origine.
    static func key(_ s: PKStroke) -> String? {
        guard s.transform == .identity, s.mask == nil, let f = s.path.first else { return nil }
        let x = Int((f.location.x * 10).rounded()), y = Int((f.location.y * 10).rounded())
        return "\(s.path.count)|\(x)|\(y)|\(argb(s.ink.color))|\(s.ink.inkType.rawValue)"
    }

    /// Construit le dessin PencilKit de toutes les pages.
    static func load(_ content: NoteContent) -> (PKDrawing, [String: Original]) {
        var strokes: [PKStroke] = []
        var originals: [String: Original] = [:]
        for (i, page) in content.pages.enumerated() {
            let o = pageOrigin(i)
            for s in page.strokes {
                guard let pk = makeStroke(s, origin: o) else { continue }
                if let k = key(pk) { originals[k] = Original(page: i, stroke: s) }
                strokes.append(pk)
            }
        }
        return (PKDrawing(strokes: strokes), originals)
    }

    private static func r2(_ v: CGFloat) -> Double {
        let d = Double(v)
        return d.isFinite ? (d * 100).rounded() / 100 : 0
    }

    private static func r3(_ v: CGFloat) -> Double {
        let d = Double(v)
        return d.isFinite ? (d * 1000).rounded() / 1000 : 0
    }

    /// Répartit les traits du dessin PencilKit dans les pages, au format Plume.
    static func extract(_ drawing: PKDrawing, pageCount: Int, originals: [String: Original]) -> ([[Stroke]], [String: Original]) {
        var pages = Array(repeating: [Stroke](), count: max(pageCount, 1))
        var next: [String: Original] = [:]
        var used = Set<String>()
        for pk in drawing.strokes {
            let k = key(pk)
            if let k, !used.contains(k), let o = originals[k], o.page < pages.count {
                used.insert(k)
                pages[o.page].append(o.stroke)
                next[k] = o
                continue
            }
            let idx = pageIndex(forY: pk.renderBounds.midY, pageCount: pages.count)
            let origin = pageOrigin(idx)
            let tool: InkTool = pk.ink.inkType == .marker ? .HIGHLIGHTER : .PEN
            let color = tool == .HIGHLIGHTER ? (argb(pk.ink.color) | 0xFF000000) : argb(pk.ink.color)
            let t = pk.transform
            let det = abs(t.a * t.d - t.b * t.c)
            let scale = det > 0 ? sqrt(det) : 1
            // Un trait gommé en partie garde son tracé complet avec un masque : on ne garde que les morceaux visibles.
            let segments: [[PKStrokePoint]]
            if pk.mask == nil {
                segments = [Array(pk.path)]
            } else {
                segments = pk.maskedPathRanges.map { Array(pk.path.interpolatedPoints(in: $0, by: .distance(3))) }
            }
            for seg in segments where !seg.isEmpty {
                var pts: [Double] = []
                pts.reserveCapacity(seg.count * 3)
                var wsum: CGFloat = 0
                for p in seg {
                    let l = p.location.applying(t)
                    pts.append(r2(l.x - origin.x))
                    pts.append(r2(l.y - origin.y))
                    pts.append(r3(min(1, max(0, p.force))))
                    wsum += p.size.width
                }
                let width = max(0.5, wsum / CGFloat(seg.count) * scale)
                let s = Stroke(id: Ids.next(), tool: tool, color: color, width: r2(width), points: pts)
                pages[idx].append(s)
                if segments.count == 1, let k, !used.contains(k) {
                    used.insert(k)
                    next[k] = Original(page: idx, stroke: s)
                }
            }
        }
        return (pages, next)
    }
}
