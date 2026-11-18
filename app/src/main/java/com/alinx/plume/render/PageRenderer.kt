package com.alinx.plume.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.alinx.plume.data.ImageItem
import com.alinx.plume.data.InkTool
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.PAGE_H
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.Page
import com.alinx.plume.data.PaperSpec
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.data.Stroke
import com.alinx.plume.data.TextBox
import java.io.OutputStream

/**
 * Dessine une page (papier, encre, texte) sur un Canvas Android, en coordonnées de page.
 * Utilisé par l'éditeur, les miniatures et l'export PDF.
 */
object PageRenderer {

    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val layoutCache = HashMap<TextBox, StaticLayout>()

    val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2F6FEB.toInt()
    }
    val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2F6FEB.toInt()
    }
    val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99555555.toInt()
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint().apply { color = 0xFFEDEDED.toInt() }

    /**
     * @param pixelWidth largeur de la page à l'écran, en pixels, pour choisir la résolution des images.
     * @param blocking vrai pour l'export : les images sont rendues immédiatement.
     */
    fun drawPage(
        canvas: Canvas,
        page: Page,
        paper: PaperSpec,
        cache: MutableMap<Long, Path>?,
        exclude: Set<Long> = emptySet(),
        pixelWidth: Int = 1200,
        blocking: Boolean = false,
        replay: Replay? = null,
    ) {
        val pdf = page.pdf
        if (pdf != null) {
            canvas.drawRect(0f, 0f, PAGE_W, PAGE_H, paperPaint)
            val bmp = if (blocking) Bitmaps.pdfPageBlocking(pdf, 1600) else Bitmaps.pdfPage(pdf, pixelWidth)
            if (bmp != null) canvas.drawBitmap(bmp, null, fitRect(bmp.width, bmp.height), bitmapPaint)
        } else {
            drawPaper(canvas, paper)
        }
        drawContent(canvas, page.strokes, page.texts, page.images, cache, exclude, pixelWidth, blocking, replay)
    }

    /** Rectangle centré dans la page qui garde les proportions de l'image. */
    fun fitRect(w: Int, h: Int): RectF {
        val ratio = h.toFloat() / w.toFloat()
        return if (ratio >= PAGE_H / PAGE_W) {
            val dw = PAGE_H / ratio
            RectF((PAGE_W - dw) / 2f, 0f, (PAGE_W + dw) / 2f, PAGE_H)
        } else {
            val dh = PAGE_W * ratio
            RectF(0f, (PAGE_H - dh) / 2f, PAGE_W, (PAGE_H + dh) / 2f)
        }
    }

    fun drawContent(
        canvas: Canvas,
        strokes: List<Stroke>,
        texts: List<TextBox>,
        images: List<ImageItem>,
        cache: MutableMap<Long, Path>?,
        exclude: Set<Long> = emptySet(),
        pixelWidth: Int = 1200,
        blocking: Boolean = false,
        replay: Replay? = null,
    ) {
        for (im in images) if (im.id !in exclude) {
            val px = (pixelWidth * im.width / PAGE_W).toInt().coerceAtLeast(64)
            val bmp = if (blocking) Bitmaps.imageBlocking(im.asset, 1600) else Bitmaps.image(im.asset, px)
            val dst = RectF(im.x, im.y, im.x + im.width, im.y + im.height)
            if (bmp != null) canvas.drawBitmap(bmp, null, dst, bitmapPaint) else canvas.drawRect(dst, placeholderPaint)
        }
        // Le surligneur passe sous l'encre et le texte, comme sur du vrai papier.
        for (s in strokes) if (s.tool == InkTool.HIGHLIGHTER && s.id !in exclude) drawStroke(canvas, s, cache, replay.fade(s))
        for (t in texts) if (t.id !in exclude) drawText(canvas, t)
        for (s in strokes) if (s.tool != InkTool.HIGHLIGHTER && s.id !in exclude) drawStroke(canvas, s, cache, replay.fade(s))
    }

    private fun linePaint(color: Int, width: Float = 1.6f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width }

    private fun hLines(c: Canvas, start: Float, step: Float, paint: Paint, end: Float = PAGE_H, x0: Float = 0f, x1: Float = PAGE_W) {
        var y = start
        while (y < end) { c.drawLine(x0, y, x1, y, paint); y += step }
    }

    private fun vLines(c: Canvas, start: Float, step: Float, paint: Paint, end: Float = PAGE_W) {
        var x = start
        while (x < end) { c.drawLine(x, 0f, x, PAGE_H, paint); x += step }
    }

    /** Fond de page : couleur et motif (lignes, carreaux, Seyès, portées…). */
    fun drawPaper(canvas: Canvas, spec: PaperSpec) {
        val dark = spec.isDark
        canvas.drawRect(0f, 0f, PAGE_W, PAGE_H, Paint().apply { color = spec.color.toInt() })
        val line = linePaint(if (dark) 0x44FFFFFF else 0xFFC9D7E8.toInt())
        val strong = linePaint(if (dark) 0x77FFFFFF else 0xFF9DB2CC.toInt(), 2f)
        val margin = linePaint(if (dark) 0x99FF8A80.toInt() else 0xFFEFB4B0.toInt(), 2f)
        val mm = 1000f / 210f // 1 mm en unités de page
        when (spec.style) {
            PaperStyle.BLANK -> Unit
            PaperStyle.LINED -> {
                hLines(canvas, 150f, 44f, line, PAGE_H - 40f)
                canvas.drawLine(110f, 0f, 110f, PAGE_H, margin)
            }
            PaperStyle.LINED_WIDE -> {
                hLines(canvas, 160f, 60f, line, PAGE_H - 40f)
                canvas.drawLine(110f, 0f, 110f, PAGE_H, margin)
            }
            PaperStyle.SEYES -> {
                // Carreaux de 8 mm, interlignes de 2 mm, marge rouge.
                val thin = linePaint(if (dark) 0x33B0B8FF else 0xFFD3D5EE.toInt(), 1.2f)
                val thick = linePaint(if (dark) 0x66B0B8FF else 0xFFA3A8DA.toInt(), 1.8f)
                hLines(canvas, 2 * mm, 2 * mm, thin)
                hLines(canvas, 8 * mm, 8 * mm, thick)
                vLines(canvas, 8 * mm, 8 * mm, thick)
                canvas.drawLine(40 * mm, 0f, 40 * mm, PAGE_H, margin)
            }
            PaperStyle.GRID -> {
                vLines(canvas, 40f, 40f, line)
                hLines(canvas, 40f, 40f, line)
            }
            PaperStyle.GRID_SMALL -> {
                vLines(canvas, 5 * mm, 5 * mm, line)
                hLines(canvas, 5 * mm, 5 * mm, line)
            }
            PaperStyle.MILLIMETER -> {
                val fine = linePaint(if (dark) 0x22FFB74D else 0x55F0A868, 0.8f)
                val mid = linePaint(if (dark) 0x44FFB74D else 0x99F0A868.toInt(), 1.2f)
                val big = linePaint(if (dark) 0x77FFB74D else 0xFFE59B57.toInt(), 1.8f)
                vLines(canvas, mm, mm, fine); hLines(canvas, mm, mm, fine)
                vLines(canvas, 5 * mm, 5 * mm, mid); hLines(canvas, 5 * mm, 5 * mm, mid)
                vLines(canvas, 10 * mm, 10 * mm, big); hLines(canvas, 10 * mm, 10 * mm, big)
            }
            PaperStyle.DOTS -> {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0x66FFFFFF else 0xFFB4C2D3.toInt() }
                var y = 40f
                while (y < PAGE_H) {
                    var x = 40f
                    while (x < PAGE_W) { canvas.drawCircle(x, y, 2.4f, dot); x += 40f }
                    y += 40f
                }
            }
            PaperStyle.CORNELL -> {
                // Titre en haut, colonne de mots-clés à gauche, résumé en bas.
                val top = 130f
                val bottom = PAGE_H - 260f
                hLines(canvas, top + 44f, 44f, line, bottom, x0 = 280f)
                canvas.drawLine(0f, top, PAGE_W, top, strong)
                canvas.drawLine(280f, top, 280f, bottom, strong)
                canvas.drawLine(0f, bottom, PAGE_W, bottom, strong)
            }
            PaperStyle.MUSIC -> {
                val staff = linePaint(if (dark) 0x88FFFFFF.toInt() else 0xFF8C96A8.toInt(), 1.6f)
                var y = 120f
                while (y + 48f < PAGE_H - 60f) {
                    for (k in 0 until 5) canvas.drawLine(60f, y + k * 12f, PAGE_W - 60f, y + k * 12f, staff)
                    y += 118f
                }
            }
        }
    }

    fun drawStroke(canvas: Canvas, s: Stroke, cache: MutableMap<Long, Path>?, alpha: Float = 1f) {
        val path = cache?.getOrPut(s.id) { buildPath(s.points) } ?: buildPath(s.points)
        configurePaint(s.tool, s.color, s.width)
        if (alpha < 1f) inkPaint.alpha = (inkPaint.alpha * alpha).toInt()
        canvas.drawPath(path, inkPaint)
    }

    /** Relecture audio : l'encre écrite après la position de lecture est estompée. */
    data class Replay(val rec: String, val positionMs: Long)

    private fun Replay?.fade(s: Stroke): Float =
        if (this != null && s.rec == rec && s.t > positionMs) 0.18f else 1f

    /** Trait dessiné avec une couleur imposée (aperçu de recoloration, etc.). */
    fun drawStrokeUncached(canvas: Canvas, s: Stroke) {
        configurePaint(s.tool, s.color, s.width)
        canvas.drawPath(buildPath(s.points), inkPaint)
    }

    fun drawLive(canvas: Canvas, tool: InkTool, color: Long, width: Float, points: List<Float>) {
        if (points.isEmpty()) return
        configurePaint(tool, color, width)
        canvas.drawPath(buildPath(points), inkPaint)
    }

    private fun configurePaint(tool: InkTool, color: Long, width: Float) {
        inkPaint.color = color.toInt()
        if (tool == InkTool.HIGHLIGHTER) inkPaint.alpha = 0x70
        inkPaint.strokeWidth = width
    }

    // ---- Texte ----

    fun textLayout(t: TextBox): StaticLayout {
        layoutCache[t]?.let { return it }
        if (layoutCache.size > 300) layoutCache.clear()
        val paint = TextPaint(textPaint).apply {
            textSize = t.size
            color = t.color.toInt()
        }
        val text = t.text.ifEmpty { " " }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, t.width.toInt().coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        layoutCache[t] = layout
        return layout
    }

    fun textHeight(t: TextBox): Float = textLayout(t).height.toFloat()

    fun drawText(canvas: Canvas, t: TextBox) {
        val layout = textLayout(t)
        canvas.save()
        canvas.translate(t.x, t.y)
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- Divers ----

    fun dashed(paint: Paint, scale: Float, widthPx: Float = 2f): Paint {
        paint.strokeWidth = widthPx / scale
        paint.pathEffect = DashPathEffect(floatArrayOf(10f / scale, 7f / scale), 0f)
        return paint
    }

    /** Courbe lissée (quadratiques passant par les milieux) à partir des triplets x, y, pression. */
    fun buildPath(p: List<Float>): Path {
        val path = Path()
        if (p.size < 3) return path
        path.moveTo(p[0], p[1])
        if (p.size == 3) {
            path.lineTo(p[0] + 0.1f, p[1])
            return path
        }
        var i = 3
        while (i + 1 < p.size) {
            val px = p[i - 3]; val py = p[i - 2]
            val x = p[i]; val y = p[i + 1]
            path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            i += 3
        }
        path.lineTo(p[p.size - 3], p[p.size - 2])
        return path
    }

    /** Largeur finale d'un trait de stylo selon la pression moyenne (0..1). */
    fun pressureWidth(base: Float, avgPressure: Float): Float =
        base * (0.55f + 0.9f * avgPressure.coerceIn(0f, 1f))

    fun exportPdf(content: NoteContent, paper: PaperSpec, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val w = 595 // A4 en points PDF
            val h = 842
            content.pages.forEachIndexed { i, page ->
                val info = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                val pdfPage = doc.startPage(info)
                val c = pdfPage.canvas
                c.scale(w / PAGE_W, h / PAGE_H)
                drawPage(c, page, paper, null, blocking = true)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
