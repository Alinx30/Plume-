package com.alinx.plume.ui.editor

import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.alinx.plume.data.InkTool
import com.alinx.plume.data.PAGE_H
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.PaperStyle
import com.alinx.plume.data.Stroke
import com.alinx.plume.render.PageRenderer
import kotlin.math.hypot
import kotlin.math.max

/** Position et zoom de la page dans la vue. */
private class Viewport {
    var scale by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)
    var viewW = 0f
    var viewH = 0f
    var fitScale = 1f

    fun fit() {
        if (viewW <= 0f) return
        fitScale = (viewW - 2 * MARGIN) / PAGE_W
        scale = fitScale
        offX = MARGIN
        offY = MARGIN
        clamp()
    }

    fun clamp() {
        val pw = PAGE_W * scale
        val ph = PAGE_H * scale
        offX = if (pw + 2 * MARGIN <= viewW) (viewW - pw) / 2f else offX.coerceIn(viewW - pw - MARGIN, MARGIN)
        offY = if (ph + 2 * MARGIN <= viewH) (viewH - ph) / 2f else offY.coerceIn(viewH - ph - MARGIN, MARGIN)
    }

    fun toPageX(x: Float) = (x - offX) / scale
    fun toPageY(y: Float) = (y - offY) / scale

    companion object {
        const val MARGIN = 24f
    }
}

private enum class Mode { NONE, DRAW, ERASE, PAN, PINCH }

/** Données de geste non observées (modifiées à chaque événement tactile). */
private class Gesture {
    var mode = Mode.NONE
    var pointerId = -1
    var fromStylus = false
    val points = ArrayList<Float>(1024)
    var pressureSum = 0f
    var lastX = 0f
    var lastY = 0f
    var lastSpan = 0f
    var lastEraseX = Float.NaN
    var lastEraseY = Float.NaN
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PageCanvas(state: EditorState, paper: PaperStyle, modifier: Modifier = Modifier) {
    val vp = remember { Viewport() }
    val g = remember { Gesture() }
    val pathCache = remember { HashMap<Long, Path>() }
    var liveTick by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.pageIndex, size) {
        vp.viewW = size.width.toFloat()
        vp.viewH = size.height.toFloat()
        vp.fit()
    }

    fun isStylus(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    fun addPoint(x: Float, y: Float, pressure: Float) {
        val p = if (g.fromStylus) pressure.coerceIn(0f, 1f) else 0.5f
        g.points.add(vp.toPageX(x)); g.points.add(vp.toPageY(y)); g.points.add(p)
        g.pressureSum += p
    }

    fun eraseTo(x: Float, y: Float) {
        val px = vp.toPageX(x); val py = vp.toPageY(y)
        val r = max(ERASER_RADIUS, ERASER_RADIUS / vp.scale * 0.6f)
        if (g.lastEraseX.isNaN()) {
            state.eraseAt(px, py, r)
        } else {
            // Interpole pour ne pas sauter de traits lors d'un mouvement rapide.
            val d = hypot(px - g.lastEraseX, py - g.lastEraseY)
            val steps = max(1, (d / r).toInt())
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                state.eraseAt(g.lastEraseX + (px - g.lastEraseX) * t, g.lastEraseY + (py - g.lastEraseY) * t, r)
            }
        }
        g.lastEraseX = px; g.lastEraseY = py
    }

    fun centroidAndSpan(ev: MotionEvent): Triple<Float, Float, Float> {
        var cx = 0f; var cy = 0f
        val n = ev.pointerCount
        for (i in 0 until n) { cx += ev.getX(i); cy += ev.getY(i) }
        cx /= n; cy /= n
        var span = 0f
        for (i in 0 until n) span += hypot(ev.getX(i) - cx, ev.getY(i) - cy)
        return Triple(cx, cy, span / n)
    }

    fun finishStroke() {
        if (g.points.size >= 3) {
            val n = g.points.size / 3
            val avg = g.pressureSum / n
            val stroke = when (state.tool) {
                Tool.HIGHLIGHTER -> Stroke(System.nanoTime(), InkTool.HIGHLIGHTER, state.highlighterColor, HIGHLIGHTER_WIDTH, ArrayList(g.points))
                else -> Stroke(System.nanoTime(), InkTool.PEN, state.penColor, PageRenderer.pressureWidth(state.penWidth, avg), ArrayList(g.points))
            }
            state.addStroke(stroke)
        }
        g.points.clear()
        g.pressureSum = 0f
    }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val stylus = isStylus(ev, 0)
                if (stylus && !state.stylusSeen) {
                    // Premier usage du stylet : on active automatiquement l'anti-paume.
                    state.stylusSeen = true
                    state.stylusOnly = true
                }
                g.pointerId = ev.getPointerId(0)
                g.fromStylus = stylus
                if (!stylus && state.stylusOnly) {
                    g.mode = Mode.PAN
                    g.lastX = ev.x; g.lastY = ev.y
                } else {
                    val eraserButton = ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        (stylus && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
                    if (state.tool == Tool.ERASER || eraserButton) {
                        g.mode = Mode.ERASE
                        g.lastEraseX = Float.NaN
                        state.beginErase()
                        eraseTo(ev.x, ev.y)
                    } else {
                        g.mode = Mode.DRAW
                        g.points.clear(); g.pressureSum = 0f
                        addPoint(ev.x, ev.y, ev.pressure)
                        liveTick++
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Paume posée pendant qu'on écrit au stylet : on l'ignore.
                if ((g.mode == Mode.DRAW || g.mode == Mode.ERASE) && g.fromStylus) return true
                if (g.mode == Mode.ERASE) state.endErase()
                g.points.clear(); g.pressureSum = 0f
                g.mode = Mode.PINCH
                val (cx, cy, span) = centroidAndSpan(ev)
                g.lastX = cx; g.lastY = cy; g.lastSpan = span
                liveTick++
            }
            MotionEvent.ACTION_MOVE -> when (g.mode) {
                Mode.DRAW -> {
                    val idx = ev.findPointerIndex(g.pointerId)
                    if (idx >= 0) {
                        for (h in 0 until ev.historySize) {
                            addPoint(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), ev.getHistoricalPressure(idx, h))
                        }
                        addPoint(ev.getX(idx), ev.getY(idx), ev.getPressure(idx))
                        liveTick++
                    }
                }
                Mode.ERASE -> {
                    val idx = ev.findPointerIndex(g.pointerId)
                    if (idx >= 0) eraseTo(ev.getX(idx), ev.getY(idx))
                }
                Mode.PAN -> {
                    vp.offX += ev.x - g.lastX
                    vp.offY += ev.y - g.lastY
                    g.lastX = ev.x; g.lastY = ev.y
                    vp.clamp()
                }
                Mode.PINCH -> {
                    if (ev.pointerCount < 2) return true
                    val (cx, cy, span) = centroidAndSpan(ev)
                    val factor = if (g.lastSpan > 0f) span / g.lastSpan else 1f
                    val newScale = (vp.scale * factor).coerceIn(vp.fitScale * 0.5f, vp.fitScale * 5f)
                    val ratio = newScale / vp.scale
                    vp.offX = cx - (g.lastX - vp.offX) * ratio
                    vp.offY = cy - (g.lastY - vp.offY) * ratio
                    vp.scale = newScale
                    g.lastX = cx; g.lastY = cy; g.lastSpan = span
                    vp.clamp()
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (g.mode == Mode.PINCH) {
                    // Recalcule le centre avec les doigts restants.
                    val up = ev.actionIndex
                    var cx = 0f; var cy = 0f; var n = 0
                    for (i in 0 until ev.pointerCount) if (i != up) { cx += ev.getX(i); cy += ev.getY(i); n++ }
                    if (n > 0) { g.lastX = cx / n; g.lastY = cy / n }
                    if (n < 2) g.lastSpan = 0f
                    if (n == 1) g.mode = Mode.PAN
                }
            }
            MotionEvent.ACTION_UP -> {
                when (g.mode) {
                    Mode.DRAW -> finishStroke()
                    Mode.ERASE -> state.endErase()
                    else -> Unit
                }
                g.mode = Mode.NONE
                liveTick++
            }
            MotionEvent.ACTION_CANCEL -> {
                if (g.mode == Mode.ERASE) state.endErase()
                g.points.clear(); g.pressureSum = 0f
                g.mode = Mode.NONE
                liveTick++
            }
        }
        return true
    }

    // Les traits supprimés n'ont plus besoin de leur chemin en cache.
    LaunchedEffect(state.version) {
        if (pathCache.size > 4000) pathCache.clear()
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInteropFilter { onTouch(it) }
    ) {
        if (liveTick < 0) return@Canvas
        val strokes = state.currentStrokes
        val s = vp.scale
        val ox = vp.offX
        val oy = vp.offY
        // Ombre de la feuille
        drawRect(Color(0x22000000), topLeft = Offset(ox + 3f, oy + 5f), size = Size(PAGE_W * s, PAGE_H * s))
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            nc.clipRect(0f, 0f, PAGE_W, PAGE_H)
            PageRenderer.drawPage(nc, strokes, paper, pathCache)
            if (g.mode == Mode.DRAW && g.points.isNotEmpty()) {
                val n = g.points.size / 3
                if (state.tool == Tool.HIGHLIGHTER) {
                    PageRenderer.drawLive(nc, InkTool.HIGHLIGHTER, state.highlighterColor, HIGHLIGHTER_WIDTH, g.points)
                } else {
                    val w = PageRenderer.pressureWidth(state.penWidth, g.pressureSum / max(1, n))
                    PageRenderer.drawLive(nc, InkTool.PEN, state.penColor, w, g.points)
                }
            }
            nc.restore()
        }
    }
}

