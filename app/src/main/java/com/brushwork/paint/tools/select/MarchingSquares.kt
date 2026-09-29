package com.brushwork.paint.tools.select

import kotlin.math.abs
import kotlin.math.min

/**
 * Iso-contours of a byte grid at the selection threshold (inside = value >= 128), used for the
 * marching ants. Pure Kotlin.
 *
 * Grid sample (i, j) is located at (i, j); the grid is implicitly surrounded by zeros so every
 * contour is closed. Crossing points are interpolated at level 127.5, so a hard-edged pixel mask
 * yields outlines exactly on the pixel borders (x - 0.5 in sample coordinates).
 */
object MarchingSquares {
    private const val LEVEL = 127.5f

    /**
     * Traces all contours. Each contour is a closed polyline `[x0, y0, x1, y1, ...]` (the closing
     * segment back to the first point is implicit). Returns null if [cancelled].
     */
    fun contours(values: ByteArray, gw: Int, gh: Int, cancelled: () -> Boolean = { false }): List<FloatArray>? {
        require(values.size >= gw * gh)
        val tracer = Tracer(values, gw, gh)
        val out = ArrayList<FloatArray>()
        // Every contour crosses at least one horizontal edge (between horizontally adjacent samples).
        for (j in 1..gh) {
            if (cancelled()) return null
            for (i in 0..gw) {
                val a = tracer.inside(i, j)
                if (a == tracer.inside(i + 1, j)) continue
                if (tracer.visited(tracer.hId(i, j))) continue
                // Entry cell: walking an edge clockwise from outside to inside.
                val poly = if (!a) tracer.trace(i, j, 0) else tracer.trace(i, j - 1, 2)
                if (poly != null && poly.size >= 6) out += poly
            }
        }
        return out
    }

    private class Tracer(val values: ByteArray, val gw: Int, val gh: Int) {
        /** Padded width: padded sample p corresponds to grid sample p - 1. */
        private val pw = gw + 2
        private val seen = java.util.BitSet(pw * (gh + 2) * 2)
        private val corner = BooleanArray(4)
        private var buf = FloatArray(256)
        private var n = 0

        fun value(i: Int, j: Int): Int {
            val x = i - 1; val y = j - 1
            return if (x < 0 || y < 0 || x >= gw || y >= gh) 0 else values[y * gw + x].toInt() and 0xFF
        }

        fun inside(i: Int, j: Int): Boolean = value(i, j) >= 128
        fun hId(i: Int, j: Int): Int = (j * pw + i) * 2
        fun vId(i: Int, j: Int): Int = (j * pw + i) * 2 + 1
        fun visited(id: Int): Boolean = seen[id]

        /** Edge id of [side] (0 top, 1 right, 2 bottom, 3 left) of cell (ci, cj). */
        private fun sideId(ci: Int, cj: Int, side: Int): Int = when (side) {
            0 -> hId(ci, cj)
            1 -> vId(ci + 1, cj)
            2 -> hId(ci, cj + 1)
            else -> vId(ci, cj)
        }

        private fun addPoint(id: Int) {
            val cell = id shr 1
            val i = cell % pw; val j = cell / pw
            val a: Int; val b: Int
            val x: Float; val y: Float
            if (id and 1 == 0) {
                a = value(i, j); b = value(i + 1, j)
                x = i + (LEVEL - a) / (b - a); y = j.toFloat()
            } else {
                a = value(i, j); b = value(i, j + 1)
                x = i.toFloat(); y = j + (LEVEL - a) / (b - a)
            }
            if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = x - 1f
            buf[n++] = y - 1f
        }

        /** Follows the contour entering cell (ci, cj) through [entrySide] until it closes. */
        fun trace(startI: Int, startJ: Int, startSide: Int): FloatArray? {
            n = 0
            var ci = startI; var cj = startJ; var side = startSide
            val startId = sideId(ci, cj, side)
            val maxSteps = pw * (gh + 2) * 2
            var steps = 0
            while (true) {
                val id = sideId(ci, cj, side)
                seen.set(id)
                addPoint(id)
                corner[0] = inside(ci, cj)
                corner[1] = inside(ci + 1, cj)
                corner[2] = inside(ci + 1, cj + 1)
                corner[3] = inside(ci, cj + 1)
                var crossings = 0
                for (s in 0..3) if (corner[s] != corner[(s + 1) and 3]) crossings++
                val exit = if (crossings == 4) {
                    val sum = value(ci, cj) + value(ci + 1, cj) + value(ci + 1, cj + 1) + value(ci, cj + 1)
                    // Saddle: connect the inside corners through the center if the center is inside.
                    if (sum >= 512) (side + 3) and 3 else (side + 1) and 3
                } else {
                    var e = (side + 1) and 3
                    while (e != side && corner[e] == corner[(e + 1) and 3]) e = (e + 1) and 3
                    e
                }
                if (exit == side) return null // inconsistent cell (cannot happen with valid input)
                when (exit) {
                    0 -> { cj -= 1; side = 2 }
                    1 -> { ci += 1; side = 3 }
                    2 -> { cj += 1; side = 0 }
                    else -> { ci -= 1; side = 1 }
                }
                if (sideId(ci, cj, side) == startId) break
                if (++steps > maxSteps) return null
            }
            return buf.copyOf(n)
        }
    }

    /**
     * Douglas-Peucker simplification of a closed polyline: drops points closer than [epsilon] to
     * the simplified outline. Straight pixel edges collapse to their end points.
     */
    fun simplifyClosed(pts: FloatArray, epsilon: Float): FloatArray {
        val n = pts.size / 2
        if (n <= 4) return pts
        var far = 0
        var farD = -1f
        for (k in 1 until n) {
            val dx = pts[2 * k] - pts[0]; val dy = pts[2 * k + 1] - pts[1]
            val d = dx * dx + dy * dy
            if (d > farD) { farD = d; far = k }
        }
        val keep = BooleanArray(n + 1)
        keep[0] = true; keep[far] = true; keep[n] = true
        var stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0; stack[sp++] = far
        stack[sp++] = far; stack[sp++] = n
        val eps2 = epsilon * epsilon
        while (sp > 0) {
            val b = stack[--sp]; val a = stack[--sp]
            if (b - a < 2) continue
            val ax = pts[2 * (a % n)]; val ay = pts[2 * (a % n) + 1]
            val bx = pts[2 * (b % n)]; val by = pts[2 * (b % n) + 1]
            var best = -1
            var bestD = eps2
            for (k in a + 1 until b) {
                val d = segDist2(pts[2 * k], pts[2 * k + 1], ax, ay, bx, by)
                if (d > bestD) { bestD = d; best = k }
            }
            if (best >= 0) {
                keep[best] = true
                if (sp + 4 > stack.size) stack = stack.copyOf(stack.size * 2)
                stack[sp++] = a; stack[sp++] = best
                stack[sp++] = best; stack[sp++] = b
            }
        }
        var count = 0
        for (k in 0 until n) if (keep[k]) count++
        val out = FloatArray(count * 2)
        var o = 0
        for (k in 0 until n) if (keep[k]) { out[o++] = pts[2 * k]; out[o++] = pts[2 * k + 1] }
        return out
    }

    private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 < 1e-12f) 0f else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val qx = ax + t * dx - px; val qy = ay + t * dy - py
        return qx * qx + qy * qy
    }

    /**
     * Downsamples a [w] x [h] mask by [factor] taking the MAX of each block, so thin (1-2 px)
     * selected areas survive and still produce an outline.
     */
    fun downsampleMax(src: ByteArray, w: Int, h: Int, factor: Int): ByteArray {
        if (factor <= 1) return src
        val gw = (w + factor - 1) / factor
        val gh = (h + factor - 1) / factor
        val out = ByteArray(gw * gh)
        for (gy in 0 until gh) {
            val ys = gy * factor; val ye = min(h, ys + factor)
            for (gx in 0 until gw) {
                val xs = gx * factor; val xe = min(w, xs + factor)
                var m = 0
                var y = ys
                while (y < ye && m < 255) {
                    val row = y * w
                    for (x in xs until xe) { val v = src[row + x].toInt() and 0xFF; if (v > m) m = v }
                    y++
                }
                out[gy * gw + gx] = m.toByte()
            }
        }
        return out
    }

    /** Total number of points in [polys]. */
    fun pointCount(polys: List<FloatArray>): Int = polys.sumOf { it.size / 2 }

    /** Absolute area of a closed polyline. */
    fun area(pts: FloatArray): Float {
        val n = pts.size / 2
        var a = 0.0
        for (k in 0 until n) {
            val j = (k + 1) % n
            a += pts[2 * k].toDouble() * pts[2 * j + 1] - pts[2 * j].toDouble() * pts[2 * k + 1]
        }
        return abs(a / 2.0).toFloat()
    }
}
