package com.brushwork.paint.tools.text

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bends flattened glyph outlines along a [TextPathGuide] (pure; the android side flattens the
 * glyphs and turns the result back into a Path).
 *
 * A glyph point (x, y) — x along the text from its start, y DOWN from the baseline — lands at
 * `P(start + x) + U(start + x) * (-y + shift)`, where P is the guide point and U the letters' "up"
 * normal (left of the direction of travel). Straight outline edges must bend too, so every edge is
 * split wherever it crosses a guide vertex, and each piece is split further while the normal turns
 * enough to move the far end of the piece more than the tolerance (so a letter crossing a sharp
 * rectangle corner fans around it smoothly).
 */
object TextPathWarp {

    /** Most pieces one edge-within-a-guide-segment is split into. */
    private const val MAX_SPLIT = 256

    /**
     * Warps [contours] (each a closed polygon as x, y pairs) for a text starting at arc length
     * [start] whose baseline sits [shift] px off the path along the letters' up normal. [tolerance]
     * (px) bounds the deviation of the output polylines from the exact bent outline.
     */
    fun warp(contours: List<FloatArray>, guide: TextPathGuide, start: Double, shift: Double, tolerance: Double): List<FloatArray> {
        val tol = if (tolerance > 1e-4) tolerance else 1e-4
        val out = ArrayList<FloatArray>(contours.size)
        val w = Walker(guide, tol)
        for (c in contours) {
            val m = c.size / 2
            if (m < 2) continue
            w.begin()
            var sa = start + c[0]
            var ha = -c[1] + shift
            w.emit(sa, ha)
            for (i in 1 until m) {
                val sb = start + c[2 * i]
                val hb = -c[2 * i + 1] + shift
                w.edge(sa, ha, sb, hb)
                sa = sb
                ha = hb
            }
            if (c[0] != c[2 * m - 2] || c[1] != c[2 * m - 1]) w.edge(sa, ha, start + c[0], -c[1] + shift)
            out += w.result()
        }
        return out
    }

    /** Emits the warped points of one contour into a growable buffer. */
    private class Walker(private val guide: TextPathGuide, private val tol: Double) {
        private var buf = FloatArray(256)
        private var n = 0
        private val e = DoubleArray(4)

        fun begin() { n = 0 }

        fun result(): FloatArray = buf.copyOf(n)

        fun emit(s: Double, h: Double) {
            guide.eval(s, e)
            if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = (e[0] + e[3] * h).toFloat()
            buf[n++] = (e[1] - e[2] * h).toFloat()
        }

        /** Emits the edge (sa, ha) -> (sb, hb), without its start point (already emitted). */
        fun edge(sa: Double, ha: Double, sb: Double, hb: Double) {
            if (sa == sb) {
                // Perpendicular to the path: its image is a straight segment along the normal.
                emit(sb, hb)
                return
            }
            val forward = sb > sa
            val span = sb - sa
            var cur = sa
            var hc = ha
            var guard = 0
            while (true) {
                val nx = guide.nextVertex(cur, forward)
                val end = if (forward) minOf(nx, sb) else maxOf(nx, sb)
                val he = if (end == sb) hb else ha + (hb - ha) * ((end - sa) / span)
                val turn = guide.turnBetween(cur, end)
                var pieces = 1
                if (turn > 1e-9) {
                    // Normal turning while the height changes bends the image (~turn·|dh|/4), and a
                    // turn at height h bows it (~h·turn²/8): split until both are within tolerance.
                    val a = sqrt(turn * abs(he - hc) / (4.0 * tol))
                    val b = turn * sqrt(max(abs(hc), abs(he)) / (8.0 * tol))
                    pieces = ceil(max(a, b)).toInt().coerceIn(1, MAX_SPLIT)
                }
                for (k in 1 until pieces) {
                    val f = k.toDouble() / pieces
                    emit(cur + (end - cur) * f, hc + (he - hc) * f)
                }
                emit(end, he)
                if (end == sb || ++guard > 1_000_000) break
                cur = end
                hc = he
            }
        }
    }
}
