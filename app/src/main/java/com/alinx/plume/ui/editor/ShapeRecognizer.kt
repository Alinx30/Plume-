package com.alinx.plume.ui.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reconnaît une forme dessinée à main levée (ligne, triangle, quadrilatère, ellipse)
 * et renvoie des points propres (triplets x, y, pression), ou null si rien n'est reconnu.
 */
object ShapeRecognizer {

    private const val STEP = 4f

    fun recognize(pts: List<Float>, pressure: Float): List<Float>? {
        val n = pts.size / 3
        if (n < 4) return null
        val xs = FloatArray(n) { pts[it * 3] }
        val ys = FloatArray(n) { pts[it * 3 + 1] }

        var length = 0f
        for (i in 1 until n) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (length < 30f) return null

        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val diag = hypot(maxX - minX, maxY - minY)
        val endGap = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])

        // Ligne droite
        if (endGap > 0.8f * length) {
            var maxDev = 0f
            for (i in 0 until n) maxDev = max(maxDev, pointSegDist(xs[i], ys[i], xs[0], ys[0], xs[n - 1], ys[n - 1]))
            if (maxDev < 0.06f * endGap + 3f) {
                return densify(listOf(xs[0] to ys[0], xs[n - 1] to ys[n - 1]), closed = false, pressure = pressure)
            }
            return null
        }

        // Forme fermée
        if (endGap > 0.25f * diag) return null

        val simplified = douglasPeucker(xs, ys, 0, n - 1, 0.08f * diag)
        val corners = removeFlatCorners(simplified.dropLast(1).toMutableList())
        if (corners.size in 3..4) {
            return densify(corners, closed = true, pressure = pressure)
        }

        // Ellipse
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val a = (maxX - minX) / 2f; val b = (maxY - minY) / 2f
        if (a < 5f || b < 5f) return null
        var dev = 0f
        for (i in 0 until n) {
            val dx = (xs[i] - cx) / a; val dy = (ys[i] - cy) / b
            dev += abs(sqrt(dx * dx + dy * dy) - 1f)
        }
        if (dev / n < 0.16f) {
            val perimeter = (2 * PI * sqrt((a * a + b * b) / 2.0)).toFloat()
            val steps = max(24, (perimeter / STEP).toInt())
            val out = ArrayList<Float>(steps * 3 + 3)
            for (k in 0..steps) {
                val t = 2 * PI * k / steps
                out.add(cx + a * cos(t).toFloat()); out.add(cy + b * sin(t).toFloat()); out.add(pressure)
            }
            return out
        }
        return null
    }

    private fun pointSegDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax; val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(ax + t * vx - px, ay + t * vy - py)
    }

    private fun douglasPeucker(xs: FloatArray, ys: FloatArray, from: Int, to: Int, eps: Float): List<Pair<Float, Float>> {
        var maxD = 0f; var idx = -1
        for (i in from + 1 until to) {
            val d = pointSegDist(xs[i], ys[i], xs[from], ys[from], xs[to], ys[to])
            if (d > maxD) { maxD = d; idx = i }
        }
        // Forme fermée : le premier et le dernier point sont proches, on prend le point le plus éloigné.
        if (from == 0 && to == xs.size - 1 && hypot(xs[to] - xs[from], ys[to] - ys[from]) < eps) {
            var far = 0f; var farIdx = -1
            for (i in from + 1 until to) {
                val d = hypot(xs[i] - xs[from], ys[i] - ys[from])
                if (d > far) { far = d; farIdx = i }
            }
            if (farIdx > 0) {
                val left = douglasPeucker(xs, ys, from, farIdx, eps)
                val right = douglasPeucker(xs, ys, farIdx, to, eps)
                return left.dropLast(1) + right
            }
        }
        return if (maxD > eps && idx > 0) {
            val left = douglasPeucker(xs, ys, from, idx, eps)
            val right = douglasPeucker(xs, ys, idx, to, eps)
            left.dropLast(1) + right
        } else {
            listOf(xs[from] to ys[from], xs[to] to ys[to])
        }
    }

    /** Retire les sommets presque alignés avec leurs voisins (polygone fermé). */
    private fun removeFlatCorners(pts: MutableList<Pair<Float, Float>>): List<Pair<Float, Float>> {
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            for (i in pts.indices) {
                val p = pts[(i - 1 + pts.size) % pts.size]
                val c = pts[i]
                val nx = pts[(i + 1) % pts.size]
                val v1x = c.first - p.first; val v1y = c.second - p.second
                val v2x = nx.first - c.first; val v2y = nx.second - c.second
                val l1 = hypot(v1x, v1y); val l2 = hypot(v2x, v2y)
                if (l1 < 1f || l2 < 1f) { pts.removeAt(i); changed = true; break }
                val cosA = ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
                val turn = acos(cosA) * 180f / PI.toFloat()
                if (turn < 28f) { pts.removeAt(i); changed = true; break }
            }
        }
        return pts
    }

    private fun densify(corners: List<Pair<Float, Float>>, closed: Boolean, pressure: Float): List<Float> {
        val out = ArrayList<Float>()
        val list = if (closed) corners + corners.first() else corners
        for (i in 0 until list.size - 1) {
            val (ax, ay) = list[i]; val (bx, by) = list[i + 1]
            val steps = max(1, (hypot(bx - ax, by - ay) / STEP).toInt())
            for (k in 0 until steps) {
                val t = k / steps.toFloat()
                out.add(ax + (bx - ax) * t); out.add(ay + (by - ay) * t); out.add(pressure)
            }
        }
        val (lx, ly) = list.last()
        out.add(lx); out.add(ly); out.add(pressure)
        return out
    }
}
