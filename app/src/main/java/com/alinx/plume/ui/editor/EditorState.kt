package com.alinx.plume.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alinx.plume.data.NoteContent
import com.alinx.plume.data.Page
import com.alinx.plume.data.Stroke

enum class Tool { PEN, HIGHLIGHTER, ERASER }

val PenColors = listOf(0xFF1B1B1F, 0xFF1E4FD8, 0xFFD32F2F, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00)
val HighlighterColors = listOf(0xFFFFE135, 0xFF7CFC4A, 0xFFFF5FA2, 0xFF4FC3F7)
val PenWidths = listOf(2.5f, 4.5f, 8f)
const val HIGHLIGHTER_WIDTH = 30f
const val ERASER_RADIUS = 14f

/** État d'édition d'une note : pages, outil courant, annuler/rétablir. */
class EditorState(initial: NoteContent) {

    val pages = mutableStateListOf<List<Stroke>>().apply {
        addAll(initial.pages.map { it.strokes })
        if (isEmpty()) add(emptyList())
    }
    var pageIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(Tool.PEN)
    var penColor by mutableLongStateOf(PenColors[0])
    var highlighterColor by mutableLongStateOf(HighlighterColors[0])
    var penWidth by mutableFloatStateOf(PenWidths[1])

    /** Quand vrai, le doigt fait défiler et seul le stylet écrit (anti-paume). */
    var stylusOnly by mutableStateOf(false)
    var stylusSeen = false

    var version by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<Pair<Int, List<Stroke>>>()
    private val redoStack = ArrayDeque<Pair<Int, List<Stroke>>>()
    private var eraseBefore: List<Stroke>? = null

    val currentStrokes: List<Stroke> get() = pages[pageIndex]

    private fun changed() {
        while (undoStack.size > 200) undoStack.removeFirst()
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        version++
    }

    private fun pushUndo(before: List<Stroke>) {
        undoStack.addLast(pageIndex to before)
        redoStack.clear()
    }

    fun addStroke(s: Stroke) {
        pushUndo(pages[pageIndex])
        pages[pageIndex] = pages[pageIndex] + s
        changed()
    }

    fun beginErase() {
        eraseBefore = pages[pageIndex]
    }

    fun eraseAt(x: Float, y: Float, radius: Float) {
        val cur = pages[pageIndex]
        val kept = cur.filterNot { hits(it, x, y, radius) }
        if (kept.size != cur.size) pages[pageIndex] = kept
    }

    fun endErase() {
        val before = eraseBefore ?: return
        eraseBefore = null
        if (before !== pages[pageIndex]) {
            pushUndo(before)
            changed()
        }
    }

    fun clearPage() {
        if (pages[pageIndex].isEmpty()) return
        pushUndo(pages[pageIndex])
        pages[pageIndex] = emptyList()
        changed()
    }

    fun undo() {
        val (i, s) = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(i to pages[i])
        pages[i] = s
        pageIndex = i
        changed()
    }

    fun redo() {
        val (i, s) = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(i to pages[i])
        pages[i] = s
        pageIndex = i
        changed()
    }

    fun addPage() {
        pages.add(pageIndex + 1, emptyList())
        pageIndex += 1
        undoStack.clear(); redoStack.clear()
        changed()
    }

    fun deletePage() {
        if (pages.size == 1) {
            pages[0] = emptyList()
        } else {
            pages.removeAt(pageIndex)
            pageIndex = pageIndex.coerceAtMost(pages.size - 1)
        }
        undoStack.clear(); redoStack.clear()
        changed()
    }

    fun goTo(index: Int) {
        pageIndex = index.coerceIn(0, pages.size - 1)
    }

    fun toContent() = NoteContent(pages.map { Page(it) })

    private fun hits(s: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val lim = radius + s.width / 2f
        val lim2 = lim * lim
        val p = s.points
        if (p.size < 3) return false
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
}
