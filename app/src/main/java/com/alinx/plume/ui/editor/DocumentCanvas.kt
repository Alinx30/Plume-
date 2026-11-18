package com.alinx.plume.ui.editor

import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.alinx.plume.data.InkTool
import com.alinx.plume.data.PAGE_GAP
import com.alinx.plume.data.PAGE_H
import com.alinx.plume.data.PAGE_W
import com.alinx.plume.data.PaperSpec
import com.alinx.plume.render.Bitmaps
import com.alinx.plume.render.PageRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Position et zoom du document (pages empilées verticalement) dans la vue. */
class Viewport(private val state: EditorState) {
    var scale by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)
    var viewW by mutableFloatStateOf(0f)
    var viewH by mutableFloatStateOf(0f)
    var fitScale = 1f

    fun pageTop(i: Int) = i * (PAGE_H + PAGE_GAP)
    private fun docHeight(): Float {
        val n = state.pages.size
        return n * PAGE_H + (n - 1) * PAGE_GAP
    }

    fun pageAt(docY: Float): Int = floor(docY / (PAGE_H + PAGE_GAP)).toInt().coerceIn(0, state.pages.size - 1)

    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val first = viewW == 0f
        val docTop = (MARGIN - offY) / scale
        val zoom = scale / fitScale
        viewW = w; viewH = h
        fitScale = min((w - 2 * MARGIN) / PAGE_W, 1.6f)
        if (first) {
            scale = fitScale
            offY = MARGIN
        } else {
            scale = fitScale * zoom
            offY = MARGIN - docTop * scale
        }
        clamp()
    }

    fun clamp() {
        val pw = PAGE_W * scale
        val dh = docHeight() * scale
        offX = if (pw + 2 * MARGIN <= viewW) (viewW - pw) / 2f else offX.coerceIn(viewW - pw - MARGIN, MARGIN)
        offY = if (dh + 2 * MARGIN <= viewH) MARGIN else offY.coerceIn(viewH - dh - MARGIN * 4, MARGIN)
    }

    fun docX(x: Float) = (x - offX) / scale
    fun docY(y: Float) = (y - offY) / scale

    companion object {
        const val MARGIN = 20f
    }
}

private enum class Mode { NONE, DRAW, ERASE, LASSO, MOVE_SEL, SCALE_SEL, TAP, SEEK, PAN, PINCH }

/** Données de geste non observées (modifiées à chaque événement tactile). */
private class Gesture {
    var mode = Mode.NONE
    var pointerId = -1
    var fromStylus = false
    var page = 0
    val points = ArrayList<Float>(2048)
    var pressureSum = 0f
    var snapped = false
    var snapJob: Job? = null
    var flingJob: Job? = null
    var velocity: VelocityTracker? = null
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var lastSpan = 0f
    var stillX = 0f
    var stillY = 0f
    var eraseX = Float.NaN
    var eraseY = Float.NaN
    var selStartDx = 0f
    var selStartDy = 0f
}

private val shadowPaint = Paint().apply { color = 0x26000000 }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DocumentCanvas(state: EditorState, paper: PaperSpec, modifier: Modifier = Modifier) {
    val vp = remember(state) { Viewport(state) }
    val g = remember { Gesture() }
    val pathCache = remember { HashMap<Long, Path>() }
    var liveTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val touchSlop = with(density) { 10.dp.toPx() }
    val handleRadius = with(density) { 22.dp.toPx() }

    // Page la plus visible.
    LaunchedEffect(state) {
        snapshotFlow { if (vp.scale > 0f) vp.pageAt((vp.viewH / 2f - vp.offY) / vp.scale) else 0 }
            .collect { state.currentPage = it }
    }
    LaunchedEffect(state.pages.size) { vp.clamp() }

    // Fait défiler pour que la zone de texte en cours de saisie reste au-dessus du clavier.
    LaunchedEffect(state.editing?.id) {
        val e = state.editing ?: return@LaunchedEffect
        val top = vp.offY + (vp.pageTop(e.page) + e.y) * vp.scale
        if (top < 0f || top > vp.viewH * 0.4f) {
            vp.offY -= top - vp.viewH * 0.2f
            vp.clamp()
        }
    }

    fun isStylus(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    fun pageX(x: Float) = vp.docX(x)
    fun pageY(y: Float, page: Int) = vp.docY(y) - vp.pageTop(page)

    fun addPoint(x: Float, y: Float, pressure: Float) {
        if (g.snapped) return
        val p = if (g.fromStylus) pressure.coerceIn(0f, 1f) else 0.5f
        g.points.add(pageX(x)); g.points.add(pageY(y, g.page)); g.points.add(p)
        g.pressureSum += p
    }

    fun scheduleSnap() {
        g.snapJob?.cancel()
        g.snapJob = scope.launch {
            delay(550)
            if (g.mode == Mode.DRAW && !g.snapped && g.points.size >= 12) {
                val n = g.points.size / 3
                val p = g.pressureSum / n
                val shape = ShapeRecognizer.recognize(g.points, p)
                if (shape != null) {
                    g.points.clear(); g.points.addAll(shape)
                    g.pressureSum = p * (shape.size / 3)
                    g.snapped = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    liveTick++
                }
            }
        }
    }

    fun eraseTo(x: Float, y: Float) {
        val px = pageX(x); val py = pageY(y, g.page)
        val r = state.eraserSize * vp.fitScale / vp.scale
        if (g.eraseX.isNaN()) {
            state.eraseAt(g.page, px, py, r)
        } else {
            // Interpole pour ne pas sauter de traits lors d'un mouvement rapide.
            val d = hypot(px - g.eraseX, py - g.eraseY)
            val steps = max(1, (d / (r * 0.7f)).toInt())
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                state.eraseAt(g.page, g.eraseX + (px - g.eraseX) * t, g.eraseY + (py - g.eraseY) * t, r)
            }
        }
        g.eraseX = px; g.eraseY = py
        liveTick++
    }

    fun centroidAndSpan(ev: MotionEvent, skip: Int = -1): Triple<Float, Float, Float> {
        var cx = 0f; var cy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) if (i != skip) { cx += ev.getX(i); cy += ev.getY(i); n++ }
        if (n == 0) return Triple(0f, 0f, 0f)
        cx /= n; cy /= n
        var span = 0f
        for (i in 0 until ev.pointerCount) if (i != skip) span += hypot(ev.getX(i) - cx, ev.getY(i) - cy)
        return Triple(cx, cy, span / n)
    }

    fun startFling(vx: Float, vy: Float) {
        g.flingJob?.cancel()
        g.flingJob = scope.launch {
            var velX = vx; var velY = vy
            var last = withFrameNanos { it }
            while (abs(velX) > 30f || abs(velY) > 30f) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val bx = vp.offX; val by = vp.offY
                vp.offX += velX * dt; vp.offY += velY * dt
                vp.clamp()
                if (abs(vp.offX - bx) < 0.01f) velX = 0f
                if (abs(vp.offY - by) < 0.01f) velY = 0f
                val decay = exp(-3.2f * dt)
                velX *= decay; velY *= decay
            }
        }
    }

    /** Touché sur la poignée ou dans la sélection ? */
    fun selectionHit(x: Float, y: Float): Mode? {
        val sel = state.selection ?: return null
        val b = state.transformedBounds() ?: return null
        val top = vp.offY + vp.pageTop(sel.page) * vp.scale
        val hx = vp.offX + b.right * vp.scale
        val hy = top + b.bottom * vp.scale
        if (hypot(x - hx, y - hy) <= handleRadius) return Mode.SCALE_SEL
        val l = vp.offX + b.left * vp.scale; val r = vp.offX + b.right * vp.scale
        val t = top + b.top * vp.scale; val bt = top + b.bottom * vp.scale
        val pad = 12f
        return if (x in (l - pad)..(r + pad) && y in (t - pad)..(bt + pad)) Mode.MOVE_SEL else null
    }

    fun startPan(ev: MotionEvent) {
        g.mode = Mode.PAN
        g.lastX = ev.x; g.lastY = ev.y
        g.velocity?.recycle()
        g.velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                g.flingJob?.cancel()
                val stylus = isStylus(ev, 0)
                if (stylus && !state.stylusSeen) {
                    // Premier usage du stylet : le doigt passe en mode défilement (anti-paume).
                    state.stylusSeen = true
                    state.fingerDraws = false
                }
                g.pointerId = ev.getPointerId(0)
                g.fromStylus = stylus
                g.downX = ev.x; g.downY = ev.y
                g.page = vp.pageAt(vp.docY(ev.y))
                val canDraw = stylus || state.fingerDraws
                val eraserButton = ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                    (stylus && (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)

                val selMode = selectionHit(ev.x, ev.y)
                when {
                    state.replay != null -> g.mode = Mode.SEEK
                    selMode != null -> {
                        g.mode = selMode
                        g.selStartDx = state.selDx; g.selStartDy = state.selDy
                    }
                    state.tool == Tool.TEXT -> {
                        if (canDraw) g.mode = Mode.TAP else startPan(ev)
                    }
                    !canDraw -> startPan(ev)
                    eraserButton || state.tool == Tool.ERASER -> {
                        g.mode = Mode.ERASE
                        g.eraseX = Float.NaN
                        state.beginGesture(g.page)
                        eraseTo(ev.x, ev.y)
                    }
                    state.tool == Tool.LASSO -> {
                        state.clearSelection()
                        g.mode = Mode.LASSO
                        g.points.clear()
                        g.points.add(pageX(ev.x)); g.points.add(pageY(ev.y, g.page)); g.points.add(1f)
                    }
                    else -> {
                        g.mode = Mode.DRAW
                        g.points.clear(); g.pressureSum = 0f; g.snapped = false
                        g.stillX = ev.x; g.stillY = ev.y
                        addPoint(ev.x, ev.y, ev.pressure)
                        scheduleSnap()
                    }
                }
                liveTick++
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Paume posée pendant qu'on écrit au stylet : on l'ignore.
                if (g.fromStylus && g.mode != Mode.PAN && g.mode != Mode.NONE) return true
                when (g.mode) {
                    Mode.ERASE -> state.endGesture()
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> state.applySelectionTransform()
                    else -> Unit
                }
                g.snapJob?.cancel()
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
                        if (!g.snapped && hypot(ev.getX(idx) - g.stillX, ev.getY(idx) - g.stillY) > 5f) {
                            g.stillX = ev.getX(idx); g.stillY = ev.getY(idx)
                            scheduleSnap()
                        }
                        liveTick++
                    }
                }
                Mode.ERASE -> {
                    val idx = ev.findPointerIndex(g.pointerId)
                    if (idx >= 0) eraseTo(ev.getX(idx), ev.getY(idx))
                }
                Mode.LASSO -> {
                    val idx = ev.findPointerIndex(g.pointerId)
                    if (idx >= 0) {
                        g.points.add(pageX(ev.getX(idx))); g.points.add(pageY(ev.getY(idx), g.page)); g.points.add(1f)
                        liveTick++
                    }
                }
                Mode.MOVE_SEL -> {
                    state.selDx = g.selStartDx + (ev.x - g.downX) / vp.scale
                    state.selDy = g.selStartDy + (ev.y - g.downY) / vp.scale
                }
                Mode.SCALE_SEL -> {
                    val sel = state.selection
                    if (sel != null) {
                        val b = sel.bounds
                        val ax = b.left + state.selDx
                        val ay = b.top + state.selDy
                        val px = pageX(ev.x); val py = pageY(ev.y, sel.page)
                        val kx = if (b.width > 1f) (px - ax) / b.width else 1f
                        val ky = if (b.height > 1f) (py - ay) / b.height else 1f
                        state.selScale = max(kx, ky).coerceIn(0.1f, 10f)
                    }
                }
                Mode.TAP -> {
                    if (hypot(ev.x - g.downX, ev.y - g.downY) > touchSlop) {
                        if (g.fromStylus) g.mode = Mode.NONE else startPan(ev)
                    }
                }
                Mode.SEEK -> {
                    if (hypot(ev.x - g.downX, ev.y - g.downY) > touchSlop) startPan(ev)
                }
                Mode.PAN -> {
                    g.velocity?.addMovement(ev)
                    vp.offX += ev.x - g.lastX
                    vp.offY += ev.y - g.lastY
                    g.lastX = ev.x; g.lastY = ev.y
                    vp.clamp()
                }
                Mode.PINCH -> {
                    if (ev.pointerCount < 2) return true
                    val (cx, cy, span) = centroidAndSpan(ev)
                    val factor = if (g.lastSpan > 0f) span / g.lastSpan else 1f
                    val newScale = (vp.scale * factor).coerceIn(vp.fitScale * 0.4f, vp.fitScale * 6f)
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
                    val (cx, cy, span) = centroidAndSpan(ev, skip = ev.actionIndex)
                    g.lastX = cx; g.lastY = cy; g.lastSpan = span
                    if (ev.pointerCount - 1 < 2) {
                        g.mode = Mode.PAN
                        g.velocity?.recycle(); g.velocity = null
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                g.snapJob?.cancel()
                when (g.mode) {
                    Mode.DRAW -> {
                        if (g.points.size >= 3) {
                            val n = g.points.size / 3
                            state.addStroke(g.page, state.newStroke(state.tool, ArrayList(g.points), g.pressureSum / n))
                        }
                    }
                    Mode.ERASE -> state.endGesture()
                    Mode.LASSO -> state.selectWithLasso(g.page, ArrayList(g.points))
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> state.applySelectionTransform()
                    Mode.TAP -> {
                        val py = pageY(ev.y, g.page)
                        if (py in 0f..PAGE_H) state.startText(g.page, pageX(ev.x), py)
                    }
                    Mode.SEEK -> {
                        val rp = state.replay
                        val hit = if (rp != null) state.recordedStrokeAt(g.page, pageX(ev.x), pageY(ev.y, g.page), rp.rec) else null
                        if (hit != null) state.seekRequest = (hit.t - 1500L).coerceAtLeast(0L)
                    }
                    Mode.PAN -> {
                        val v = g.velocity
                        if (v != null) {
                            v.addMovement(ev)
                            v.computeCurrentVelocity(1000)
                            val vx = v.xVelocity; val vy = v.yVelocity
                            v.recycle(); g.velocity = null
                            val moved = hypot(ev.x - g.downX, ev.y - g.downY)
                            if (moved < touchSlop && state.tool == Tool.TEXT) {
                                val page = vp.pageAt(vp.docY(ev.y))
                                val py = pageY(ev.y, page)
                                if (py in 0f..PAGE_H) state.startText(page, pageX(ev.x), py)
                            } else if (hypot(vx, vy) > 400f) {
                                startFling(vx, vy)
                            }
                        }
                    }
                    else -> Unit
                }
                g.points.clear(); g.pressureSum = 0f
                g.mode = Mode.NONE
                liveTick++
            }
            MotionEvent.ACTION_CANCEL -> {
                g.snapJob?.cancel()
                when (g.mode) {
                    Mode.ERASE -> state.endGesture()
                    Mode.MOVE_SEL, Mode.SCALE_SEL -> state.applySelectionTransform()
                    else -> Unit
                }
                g.velocity?.recycle(); g.velocity = null
                g.points.clear(); g.pressureSum = 0f
                g.mode = Mode.NONE
                liveTick++
            }
        }
        return true
    }

    LaunchedEffect(state.version) {
        if (pathCache.size > 6000) pathCache.clear()
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { vp.resize(it.width.toFloat(), it.height.toFloat()) }
                .pointerInteropFilter { onTouch(it) }
        ) {
            if (liveTick < 0) return@Canvas
            // Relit l'état des images pour redessiner quand un PDF ou une photo est prêt.
            if (Bitmaps.version.intValue < 0) return@Canvas
            val s = vp.scale
            val ox = vp.offX
            val oy = vp.offY
            val pages = state.pages
            val sel = state.selection
            val selDx = state.selDx
            val selDy = state.selDy
            val selK = state.selScale
            val editing = state.editing
            val replay = state.replay
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                for (i in pages.indices) {
                    val top = oy + vp.pageTop(i) * s
                    if (top > size.height || top + PAGE_H * s < 0f) continue
                    nc.drawRect(ox + 2f, top + 4f, ox + PAGE_W * s + 2f, top + PAGE_H * s + 4f, shadowPaint)
                    nc.save()
                    nc.translate(ox, top)
                    nc.scale(s, s)
                    nc.clipRect(0f, 0f, PAGE_W, PAGE_H)
                    val page = pages[i]
                    val exclude = HashSet<Long>()
                    if (sel != null && sel.page == i) exclude.addAll(sel.ids)
                    if (editing != null && editing.page == i) exclude.add(editing.id)
                    PageRenderer.drawPage(nc, page, paper, pathCache, exclude, pixelWidth = (PAGE_W * s).toInt(), replay = replay)

                    if (sel != null && sel.page == i) {
                        val b = sel.bounds
                        nc.save()
                        nc.translate(b.left + selDx, b.top + selDy)
                        nc.scale(selK, selK)
                        nc.translate(-b.left, -b.top)
                        PageRenderer.drawContent(
                            nc,
                            page.strokes.filter { it.id in sel.strokeIds },
                            page.texts.filter { it.id in sel.textIds },
                            page.images.filter { it.id in sel.imageIds },
                            pathCache,
                            pixelWidth = (PAGE_W * s * selK).toInt(),
                        )
                        nc.restore()
                        val tb = state.transformedBounds()
                        if (tb != null) {
                            val pad = 6f / s
                            nc.drawRect(tb.left - pad, tb.top - pad, tb.right + pad, tb.bottom + pad, PageRenderer.dashed(PageRenderer.selectionPaint, s))
                            nc.drawCircle(tb.right, tb.bottom, 9f / s, PageRenderer.handlePaint)
                        }
                    }
                    if (g.page == i) {
                        when (g.mode) {
                            Mode.DRAW -> if (g.points.isNotEmpty()) {
                                val n = g.points.size / 3
                                if (state.tool == Tool.HIGHLIGHTER) {
                                    PageRenderer.drawLive(nc, InkTool.HIGHLIGHTER, state.highlighterColor, state.highlighterWidth, g.points)
                                } else {
                                    val w = PageRenderer.pressureWidth(state.penWidth, g.pressureSum / max(1, n))
                                    PageRenderer.drawLive(nc, InkTool.PEN, state.penColor, w, g.points)
                                }
                            }
                            Mode.LASSO -> if (g.points.size >= 6) {
                                val path = Path()
                                path.moveTo(g.points[0], g.points[1])
                                var k = 3
                                while (k + 1 < g.points.size) { path.lineTo(g.points[k], g.points[k + 1]); k += 3 }
                                nc.drawPath(path, PageRenderer.dashed(PageRenderer.selectionPaint, s, 1.5f))
                            }
                            Mode.ERASE -> if (!g.eraseX.isNaN()) {
                                PageRenderer.eraserPaint.strokeWidth = 1.5f / s
                                nc.drawCircle(g.eraseX, g.eraseY, state.eraserSize * vp.fitScale / s, PageRenderer.eraserPaint)
                            }
                            else -> Unit
                        }
                    }
                    nc.restore()
                }
            }
        }

        // Saisie de texte au clavier, posée au-dessus de la page.
        val e = state.editing
        if (e != null) {
            val focus = remember(e.id) { FocusRequester() }
            val fontSize = with(density) { (e.size * vp.scale).toSp() }
            val widthDp = with(density) { (e.width * vp.scale).toDp() }
            BasicTextField(
                value = state.editingValue,
                onValueChange = { state.editingValue = it },
                textStyle = TextStyle(color = Color(e.color), fontSize = fontSize),
                cursorBrush = SolidColor(Color(0xFF2F6FEB)),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (vp.offX + e.x * vp.scale).roundToInt(),
                            (vp.offY + (vp.pageTop(e.page) + e.y) * vp.scale).roundToInt(),
                        )
                    }
                    .width(widthDp)
                    .border(1.dp, Color(0x772F6FEB))
                    .focusRequester(focus),
            )
            LaunchedEffect(e.id) { focus.requestFocus() }
        }

        // Actions sur la sélection du lasso.
        val sel = state.selection
        val tb = state.transformedBounds()
        if (sel != null && tb != null) {
            var showColors by remember(sel) { mutableStateOf(false) }
            val barHeight = with(density) { 52.dp.toPx() }
            Column(
                Modifier.offset {
                    val x = vp.offX + tb.left * vp.scale
                    val y = vp.offY + (vp.pageTop(sel.page) + tb.top) * vp.scale - barHeight - (if (showColors) barHeight else 0f)
                    IntOffset(x.roundToInt().coerceAtLeast(8), y.roundToInt().coerceAtLeast(8))
                }
            ) {
                Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
                    Row(Modifier.padding(horizontal = 6.dp)) {
                        TextButton(onClick = { showColors = !showColors }) { Text("Couleur") }
                        TextButton(onClick = { state.duplicateSelection() }) { Text("Dupliquer") }
                        TextButton(onClick = { state.deleteSelection() }) { Text("Supprimer") }
                    }
                }
                if (showColors) {
                    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, modifier = Modifier.padding(top = 6.dp)) {
                        Row(Modifier.padding(8.dp)) {
                            InkColors.forEach { c ->
                                Box(
                                    Modifier
                                        .padding(3.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .clickable { state.recolorSelection(c); showColors = false }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Numéro de page
        val current by remember { derivedStateOf { state.currentPage } }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                "${current + 1} / ${state.pages.size}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
