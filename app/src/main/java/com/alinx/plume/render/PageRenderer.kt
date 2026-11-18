package com.alinx.plume.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.alinx.plume.data.InkTool
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.PAGE_H
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.data.Stroke
import java.io.OutputStream

/**
 * Dessine une page (papier + encre) sur un Canvas Android, en coordonnées de page.
 * Utilisé à la fois par l'éditeur, les miniatures et l'export PDF.
 */
object PageRenderer {

    private val paperPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9D7E8.toInt(); strokeWidth = 1.6f
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEFB4B0.toInt(); strokeWidth = 1.6f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4C2D3.toInt() }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun drawPage(canvas: Canvas, strokes: List<Stroke>, paper: PaperStyle, cache: MutableMap<Long, Path>?) {
        drawPaper(canvas, paper)
        // Le surligneur passe sous l'encre, comme sur du vrai papier.
        for (s in strokes) if (s.tool == InkTool.HIGHLIGHTER) drawStroke(canvas, s, cache)
        for (s in strokes) if (s.tool != InkTool.HIGHLIGHTER) drawStroke(canvas, s, cache)
    }

    fun drawPaper(canvas: Canvas, style: PaperStyle) {
        canvas.drawRect(0f, 0f, PAGE_W, PAGE_H, paperPaint)
        when (style) {
            PaperStyle.BLANK -> Unit
            PaperStyle.LINED -> {
                var y = 150f
                while (y < PAGE_H - 40f) {
                    canvas.drawLine(0f, y, PAGE_W, y, linePaint)
                    y += 44f
                }
                canvas.drawLine(110f, 0f, 110f, PAGE_H, marginPaint)
            }
            PaperStyle.GRID -> {
                var x = 40f
                while (x < PAGE_W) { canvas.drawLine(x, 0f, x, PAGE_H, linePaint); x += 40f }
                var y = 40f
                while (y < PAGE_H) { canvas.drawLine(0f, y, PAGE_W, y, linePaint); y += 40f }
            }
            PaperStyle.DOTS -> {
                var y = 40f
                while (y < PAGE_H) {
                    var x = 40f
                    while (x < PAGE_W) { canvas.drawCircle(x, y, 2.4f, dotPaint); x += 40f }
                    y += 40f
                }
            }
        }
    }

    fun drawStroke(canvas: Canvas, s: Stroke, cache: MutableMap<Long, Path>?) {
        val path = cache?.getOrPut(s.id) { buildPath(s.points) } ?: buildPath(s.points)
        configurePaint(s.tool, s.color, s.width)
        canvas.drawPath(path, inkPaint)
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

    fun exportPdf(content: NoteContent, paper: PaperStyle, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val w = 595 // A4 en points PDF
            val h = 842
            content.pages.forEachIndexed { i, page ->
                val info = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                val pdfPage = doc.startPage(info)
                val c = pdfPage.canvas
                c.scale(w / PAGE_W, h / PAGE_H)
                drawPage(c, page.strokes, paper, null)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
