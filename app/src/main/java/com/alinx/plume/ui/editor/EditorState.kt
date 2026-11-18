package com.alinx.plume.ui.editor

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.alinx.plume.data.Ids
import com.alinx.plume.data.ImageItem
import com.alinx.plume.data.InkTool
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.PAGE_H
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.Page
import com.alinx.plume.data.Recording
import com.alinx.plume.data.Stroke
import com.alinx.plume.data.TextBox
import com.alinx.plume.render.PageRenderer
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO, TEXT }
enum class EraserMode { PARTIAL, STROKE }

val InkColors = listOf(
    0xFF1B1B1F, 0xFF1E4FD8, 0xFFD32F2F, 0xFF2E7D32,
    0xFF6A1B9A, 0xFFEF6C00, 0xFF00897B, 0xFF8D6E63,
)
val HighlighterColors = listOf(0xFFFFE135, 0xFF7CFC4A, 0xFFFF5FA2, 0xFF4FC3F7, 0xFFFFA94D, 0xFFB388FF)

/** Rectangle en coordonnées de page. */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float, pad: Float = 0f) =
        x >= left - pad && x <= right + pad && y >= top - pad && y <= bottom + pad
}

/** Éléments sélectionnés au lasso, sur une page. */
data class Selection(
    val page: Int,
    val strokeIds: Set<Long>,
    val textIds: Set<Long>,
    val imageIds: Set<Long>,
    val bounds: PageRect,
) {
    val ids: Set<Long> get() = strokeIds + textIds + imageIds
}

/** Zone de texte en cours de saisie. */
data class EditingText(
    val page: Int,
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val size: Float,
    val color: Long,
    val isNew: Boolean,
)

/** État d'édition d'une note : pages, outils, sélection, annuler/rétablir. */
class EditorState(initial: NoteContent) {

    val pages = mutableStateListOf<Page>().apply {
        addAll(initial.pages)
        if (isEmpty()) add(Page())
    }

    var tool by mutableStateOf(Tool.PEN)
        private set
    var penColor by mutableLongStateOf(InkColors[0])
    var penWidth by mutableFloatStateOf(4.5f)
    var highlighterColor by mutableLongStateOf(HighlighterColors[0])
    var highlighterWidth by mutableFloatStateOf(30f)
    var eraserMode by mutableStateOf(EraserMode.PARTIAL)
    var eraserSize by mutableFloatStateOf(16f)
    var textColor by mutableLongStateOf(InkColors[0])
    var textSize by mutableFloatStateOf(30f)

    /** Vrai : le doigt écrit. Faux : le doigt fait défiler et seul le stylet écrit. */
    var fingerDraws by mutableStateOf(true)

    /** Page la plus visible à l'écran (mise à jour par le canevas). */
    var currentPage by mutableIntStateOf(0)
    var stylusSeen = false

    var selection by mutableStateOf<Selection?>(null)
        private set
    // Transformation en cours de la sélection (déplacement + échelle depuis le coin haut-gauche).
    var selDx by mutableFloatStateOf(0f)
    var selDy by mutableFloatStateOf(0f)
    var selScale by mutableFloatStateOf(1f)

    var editing by mutableStateOf<EditingText?>(null)
        private set
    var editingValue by mutableStateOf(TextFieldValue(""))

    // ---- Audio ----
    val recordings = mutableStateListOf<Recording>().apply { addAll(initial.recordings) }

    /** Enregistrement en cours : identifiant et instant de départ (SystemClock.elapsedRealtime). */
    var activeRecording: Pair<String, Long>? = null

    /** Relecture en cours : les traits écrits plus tard sont estompés. */
    var replay by mutableStateOf<PageRenderer.Replay?>(null)

    /** Demande de saut dans l'audio (ms), posée par un toucher sur un trait pendant la relecture. */
    var seekRequest by mutableStateOf<Long?>(null)

    fun addRecording(r: Recording) {
        recordings.add(r)
        changed()
    }

    fun removeRecording(id: String) {
        recordings.removeAll { it.id == id }
        changed()
    }

    /** Trait enregistré le plus proche du point, pour sauter à ce moment de l'audio. */
    fun recordedStrokeAt(pageIdx: Int, x: Float, y: Float, rec: String): Stroke? =
        pages[pageIdx].strokes.lastOrNull { it.rec == rec && it.t >= 0 && strokeHits(it, x, y, 18f) }

    var version by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<Pair<Int, Page>>()
    private val redoStack = ArrayDeque<Pair<Int, Page>>()
    private var gestureBefore: Pair<Int, Page>? = null

    init {
        ensureTrailingPage()
    }

    fun selectTool(t: Tool) {
        if (t != Tool.LASSO) clearSelection()
        if (t != Tool.TEXT) commitText()
        tool = t
    }

    // ---- Modifications avec annuler ----

    private fun changed() {
        while (undoStack.size > 200) undoStack.removeFirst()
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        version++
    }

    private fun ensureTrailingPage() {
        if (pages.last().hasContent()) pages.add(Page())
    }

    private fun edit(pageIdx: Int, transform: (Page) -> Page) {
        val before = pages[pageIdx]
        val after = transform(before)
        if (after == before) return
        undoStack.addLast(pageIdx to before)
        redoStack.clear()
        pages[pageIdx] = after
        ensureTrailingPage()
        changed()
    }

    fun addStroke(pageIdx: Int, s: Stroke) = edit(pageIdx) { it.copy(strokes = it.strokes + s) }

    /** Début d'une modification continue (gomme) : un seul « annuler » pour tout le geste. */
    fun beginGesture(pageIdx: Int) {
        gestureBefore = pageIdx to pages[pageIdx]
    }

    fun endGesture() {
        val (idx, before) = gestureBefore ?: return
        gestureBefore = null
        if (before != pages[idx]) {
            undoStack.addLast(idx to before)
            redoStack.clear()
            changed()
        }
    }

    fun undo() {
        clearSelection(); commitText()
        val (i, p) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(i to pages[i])
        pages[i] = p
        changed()
    }

    fun redo() {
        clearSelection(); commitText()
        val (i, p) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(i to pages[i])
        pages[i] = p
        ensureTrailingPage()
        changed()
    }

    fun insertPageAfter(pageIdx: Int) {
        clearSelection(); commitText()
        pages.add(pageIdx + 1, Page())
        undoStack.clear(); redoStack.clear()
        changed()
    }

    fun deletePage(pageIdx: Int) {
        clearSelection(); commitText()
        if (pages.size <= 1) pages[0] = Page() else pages.removeAt(pageIdx)
        if (pages.isEmpty()) pages.add(Page())
        ensureTrailingPage()
        undoStack.clear(); redoStack.clear()
        changed()
    }

    fun clearPage(pageIdx: Int) = edit(pageIdx) { Page() }

    /** Contenu à sauvegarder : sans les pages vides de fin (on en garde au moins une). */
    fun toContent(): NoteContent {
        val list = pages.toMutableList()
        while (list.size > 1 && !list.last().hasContent()) list.removeAt(list.size - 1)
        return NoteContent(list, recordings.toList())
    }

    // ---- Gomme ----

    fun eraseAt(pageIdx: Int, x: Float, y: Float, radius: Float) {
        val page = pages[pageIdx]
        val newPage = when (eraserMode) {
            EraserMode.STROKE -> page.copy(
                strokes = page.strokes.filterNot { strokeHits(it, x, y, radius) },
                texts = page.texts.filterNot { textBounds(it).contains(x, y, radius) },
                images = page.images.filterNot { imageBounds(it).contains(x, y, radius) },
            )
            EraserMode.PARTIAL -> {
                var changedAny = false
                val out = ArrayList<Stroke>(page.strokes.size)
                for (s in page.strokes) {
                    val pieces = erasePartial(s, x, y, radius)
                    if (pieces == null) out.add(s) else { changedAny = true; out.addAll(pieces) }
                }
                if (changedAny) page.copy(strokes = out) else page
            }
        }
        if (newPage != page) pages[pageIdx] = newPage
    }

    /** Coupe le trait autour du point gommé. Null si le trait n'est pas touché. */
    private fun erasePartial(s: Stroke, x: Float, y: Float, r: Float): List<Stroke>? {
        val p = s.points
        val n = p.size / 3
        val lim = r + s.width / 2f
        val lim2 = lim * lim
        var hit = false
        val inside = BooleanArray(n) { i ->
            val dx = p[i * 3] - x; val dy = p[i * 3 + 1] - y
            (dx * dx + dy * dy <= lim2).also { if (it) hit = true }
        }
        if (!hit) return null
        val pieces = ArrayList<Stroke>()
        var start = -1
        for (i in 0..n) {
            val out = i < n && !inside[i]
            if (out && start < 0) start = i
            if (!out && start >= 0) {
                if (i - start >= 2) pieces.add(s.copy(id = Ids.next(), points = p.subList(start * 3, i * 3).toList()))
                start = -1
            }
        }
        return pieces
    }

    private fun strokeHits(s: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val lim = radius + s.width / 2f
        val lim2 = lim * lim
        val p = s.points
        var i = 0
        while (i + 1 < p.size) {
            val ax = p[i]; val ay = p[i + 1]
            if (i + 4 < p.size) {
                if (segDist2(x, y, ax, ay, p[i + 3], p[i + 4]) <= lim2) return true
            } else {
                val dx = ax - x; val dy = ay - y
                if (dx * dx + dy * dy <= lim2) return true
            }
            i += 3
        }
        return false
    }

    private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        val cx = ax + t * vx - px; val cy = ay + t * vy - py
        return cx * cx + cy * cy
    }

    // ---- Lasso et sélection ----

    fun imageBounds(im: ImageItem) = PageRect(im.x, im.y, im.x + im.width, im.y + im.height)

    fun textBounds(t: TextBox) = PageRect(t.x, t.y, t.x + t.width, t.y + max(PageRenderer.textHeight(t), t.size))

    private fun strokeBounds(s: Stroke): PageRect {
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < s.points.size) {
            l = min(l, s.points[i]); r = max(r, s.points[i])
            tp = min(tp, s.points[i + 1]); b = max(b, s.points[i + 1])
            i += 3
        }
        val h = s.width / 2f
        return PageRect(l - h, tp - h, r + h, b + h)
    }

    private fun union(boxes: List<PageRect>): PageRect? {
        if (boxes.isEmpty()) return null
        return PageRect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    /** Sélectionne ce qui est entouré par le lasso (polygone en coordonnées de page). */
    fun selectWithLasso(pageIdx: Int, poly: List<Float>) {
        clearSelection()
        if (poly.size < 9) return
        val page = pages[pageIdx]
        val strokes = page.strokes.filter { s ->
            val n = s.points.size / 3
            var inCount = 0
            for (i in 0 until n) if (pointInPolygon(s.points[i * 3], s.points[i * 3 + 1], poly)) inCount++
            n > 0 && inCount * 2 >= n
        }
        val texts = page.texts.filter { t ->
            val b = textBounds(t)
            pointInPolygon((b.left + b.right) / 2f, (b.top + b.bottom) / 2f, poly)
        }
        val images = page.images.filter { im ->
            pointInPolygon(im.x + im.width / 2f, im.y + im.height / 2f, poly)
        }
        setSelection(pageIdx, strokes.map { it.id }.toSet(), texts.map { it.id }.toSet(), images.map { it.id }.toSet())
    }

    private fun setSelection(pageIdx: Int, strokeIds: Set<Long>, textIds: Set<Long>, imageIds: Set<Long> = emptySet()) {
        val page = pages[pageIdx]
        val boxes = page.strokes.filter { it.id in strokeIds }.map { strokeBounds(it) } +
            page.texts.filter { it.id in textIds }.map { textBounds(it) } +
            page.images.filter { it.id in imageIds }.map { imageBounds(it) }
        val bounds = union(boxes)
        selection = if (bounds == null) null else Selection(pageIdx, strokeIds, textIds, imageIds, bounds)
        selDx = 0f; selDy = 0f; selScale = 1f
    }

    fun clearSelection() {
        if (selection != null) applySelectionTransform()
        selection = null
    }

    /** Boîte de la sélection avec la transformation en cours. */
    fun transformedBounds(): PageRect? {
        val s = selection ?: return null
        val b = s.bounds
        return PageRect(b.left + selDx, b.top + selDy, b.left + selDx + b.width * selScale, b.top + selDy + b.height * selScale)
    }

    /** Applique pour de bon le déplacement / redimensionnement de la sélection. */
    fun applySelectionTransform() {
        val s = selection ?: return
        if (selDx == 0f && selDy == 0f && selScale == 1f) return
        val ax = s.bounds.left; val ay = s.bounds.top
        val dx = selDx; val dy = selDy; val k = selScale
        fun tx(x: Float) = ax + dx + (x - ax) * k
        fun ty(y: Float) = ay + dy + (y - ay) * k
        edit(s.page) { page ->
            page.copy(
                strokes = page.strokes.map { st ->
                    if (st.id !in s.strokeIds) st else {
                        val pts = ArrayList<Float>(st.points.size)
                        var i = 0
                        while (i + 2 < st.points.size) {
                            pts.add(tx(st.points[i])); pts.add(ty(st.points[i + 1])); pts.add(st.points[i + 2])
                            i += 3
                        }
                        st.copy(points = pts, width = st.width * k)
                    }
                },
                texts = page.texts.map { t ->
                    if (t.id !in s.textIds) t else t.copy(x = tx(t.x), y = ty(t.y), width = t.width * k, size = t.size * k)
                },
                images = page.images.map { im ->
                    if (im.id !in s.imageIds) im else im.copy(x = tx(im.x), y = ty(im.y), width = im.width * k, height = im.height * k)
                },
            )
        }
        selDx = 0f; selDy = 0f; selScale = 1f
        setSelection(s.page, s.strokeIds, s.textIds, s.imageIds)
    }

    fun deleteSelection() {
        val s = selection ?: return
        selection = null
        selDx = 0f; selDy = 0f; selScale = 1f
        edit(s.page) { page ->
            page.copy(
                strokes = page.strokes.filterNot { it.id in s.strokeIds },
                texts = page.texts.filterNot { it.id in s.textIds },
                images = page.images.filterNot { it.id in s.imageIds },
            )
        }
    }

    fun duplicateSelection() {
        applySelectionTransform()
        val s = selection ?: return
        val off = 30f
        val page = pages[s.page]
        val newStrokes = page.strokes.filter { it.id in s.strokeIds }.map { st ->
            st.copy(id = Ids.next(), points = st.points.mapIndexed { i, v -> if (i % 3 == 2) v else v + off })
        }
        val newTexts = page.texts.filter { it.id in s.textIds }.map { t ->
            t.copy(id = Ids.next(), x = t.x + off, y = t.y + off)
        }
        val newImages = page.images.filter { it.id in s.imageIds }.map { im ->
            im.copy(id = Ids.next(), x = im.x + off, y = im.y + off)
        }
        edit(s.page) { it.copy(strokes = it.strokes + newStrokes, texts = it.texts + newTexts, images = it.images + newImages) }
        setSelection(s.page, newStrokes.map { it.id }.toSet(), newTexts.map { it.id }.toSet(), newImages.map { it.id }.toSet())
    }

    fun recolorSelection(color: Long) {
        applySelectionTransform()
        val s = selection ?: return
        edit(s.page) { page ->
            page.copy(
                strokes = page.strokes.map {
                    if (it.id in s.strokeIds && it.tool == InkTool.PEN) it.copy(color = color) else it
                },
                texts = page.texts.map { if (it.id in s.textIds) it.copy(color = color) else it },
            )
        }
        setSelection(s.page, s.strokeIds, s.textIds, s.imageIds)
    }

    /** Pose une photo au centre de la page et la sélectionne pour qu'on puisse la placer. */
    fun insertImage(pageIdx: Int, asset: String, imgW: Int, imgH: Int) {
        commitText()
        clearSelection()
        val ratio = imgH.toFloat() / imgW.coerceAtLeast(1)
        var w = PAGE_W * 0.7f
        var h = w * ratio
        if (h > PAGE_H * 0.6f) { h = PAGE_H * 0.6f; w = h / ratio }
        val im = ImageItem(Ids.next(), asset, (PAGE_W - w) / 2f, (PAGE_H - h) / 3f, w, h)
        edit(pageIdx) { it.copy(images = it.images + im) }
        tool = Tool.LASSO
        setSelection(pageIdx, emptySet(), emptySet(), setOf(im.id))
    }

    private fun pointInPolygon(x: Float, y: Float, poly: List<Float>): Boolean {
        var inside = false
        val n = poly.size / 3
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 3]; val yi = poly[i * 3 + 1]
            val xj = poly[j * 3]; val yj = poly[j * 3 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    // ---- Texte ----

    fun textAt(pageIdx: Int, x: Float, y: Float): TextBox? =
        pages[pageIdx].texts.lastOrNull { textBounds(it).contains(x, y, 8f) }

    /** Ouvre une zone de texte : existante si on touche du texte, sinon nouvelle. */
    fun startText(pageIdx: Int, x: Float, y: Float) {
        commitText()
        val existing = textAt(pageIdx, x, y)
        if (existing != null) {
            editing = EditingText(pageIdx, existing.id, existing.x, existing.y, existing.width, existing.size, existing.color, false)
            editingValue = TextFieldValue(existing.text, androidx.compose.ui.text.TextRange(existing.text.length))
        } else {
            val w = min(560f, PAGE_W - x - 30f).coerceAtLeast(160f)
            val left = min(x, PAGE_W - w - 10f).coerceAtLeast(10f)
            val top = (y - textSize * 0.6f).coerceIn(10f, PAGE_H - textSize * 2f)
            editing = EditingText(pageIdx, Ids.next(), left, top, w, textSize, textColor, true)
            editingValue = TextFieldValue("")
        }
    }

    fun commitText() {
        val e = editing ?: return
        editing = null
        val text = editingValue.text.trimEnd()
        edit(e.page) { page ->
            val others = page.texts.filterNot { it.id == e.id }
            if (text.isBlank()) {
                page.copy(texts = others)
            } else {
                val old = page.texts.firstOrNull { it.id == e.id }
                val tb = old?.copy(text = text) ?: TextBox(e.id, e.x, e.y, e.width, text, e.size, e.color)
                // Conserve l'ordre d'empilement.
                if (old != null) page.copy(texts = page.texts.map { if (it.id == e.id) tb else it })
                else page.copy(texts = others + tb)
            }
        }
    }

    fun newStroke(tool: Tool, points: List<Float>, avgPressure: Float): Stroke {
        val rec = activeRecording
        val recId = rec?.first
        val t = if (rec != null) SystemClock.elapsedRealtime() - rec.second else -1L
        return when (tool) {
            Tool.HIGHLIGHTER -> Stroke(Ids.next(), InkTool.HIGHLIGHTER, highlighterColor, highlighterWidth, points, recId, t)
            else -> Stroke(Ids.next(), InkTool.PEN, penColor, PageRenderer.pressureWidth(penWidth, avgPressure), points, recId, t)
        }
    }
}
