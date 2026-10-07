package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The Bézier form of a Path-tool spline (v1.6 §3.2c): what a [VPath] stores in its subpaths and
 * what everything else (renderer, hit tests, eraser, snapping, SVG / PDF export, v1.5) reads.
 *
 * - Order 2: straight segments through the control points (sharp anchors, no handles).
 * - Order 3–4 on spans whose weights are equal: **exact**, the polynomial piece of each span
 *   (order 3 degree-elevated to a cubic), one cubic per span.
 * - Order 5–6, or spans with different weights: cubics with the exact end points and end
 *   tangents (Hermite, in the rational standard form, handle lengths fitted by least squares);
 *   the piece that misses most is halved until every piece's [CHECK_SAMPLES] samples (and the
 *   points between them) lie within the tolerance, at most [MAX_PIECES_PER_SPAN] pieces per span.
 *
 * Interior anchors are smooth (`sharp = false`) with BOTH handles set, so the Curve tool draws
 * them exactly and can grab them (V7); open ends are smooth too, with the unused handle mirrored.
 * Each anchor's thickness is the spline's blend of the control widths at that parameter (the
 * Curve tool's smoothstep blend runs between anchors as usual).
 *
 * v1.7 (item 4): a spline with interior sharp points is converted piece by piece
 * ([NurbsGeometry.pieces]: independently clamped pieces, a 2-point piece being a straight
 * segment) and the pieces are joined into ONE subpath (I9 unchanged). Each join is one
 * `VAnchor(sharp = true)` exactly at the corner's control point, with its thickness, and broken
 * tangents: its in handle ends the piece before, its out handle starts the piece after. A closed
 * spline's first corner is its anchor 0 (with fewer than 3 spans in all, its spans are cut into
 * equal parts so the subpath has the 3 anchors a closed curve needs). A spline without sharp
 * points converts exactly as in v1.6.
 */
object SplineBezier {
    /** Largest distance between a converted piece and the spline (document px). */
    const val DEFAULT_TOLERANCE = 0.05f
    const val MAX_PIECES_PER_SPAN = 16
    const val CHECK_SAMPLES = 16

    /** I9: a stored subpath and the conversion of its spline agree within this (document px). */
    const val MATCH_TOLERANCE = 0.01f

    /**
     * I9 fallback: an approximated spline mapped by an affine transform (Transform, canvas
     * operations) keeps its mapped pieces, which may be cut differently from a fresh conversion;
     * its curve still lies this close to the spline's (document px).
     */
    const val GEOMETRIC_TOLERANCE = 0.5f

    /** The Bézier form of [spline] (see the class docs); [tol] is in document px. */
    fun toSubpath(spline: VSpline, tol: Float = DEFAULT_TOLERANCE): VSubpath {
        val s = spline.sanitized()
        trivial(s)?.let { return it }
        val parts = NurbsGeometry.pieces(s)
        if (parts.size > 1 || parts[0] !== s) return joined(s, parts, tolerance(tol))
        val b = NurbsGeometry.basis(s)
        val out = PieceSink(b.spans.size * 2 + 2)
        val conv = Converter(s, b, tolerance(tol))
        for (span in b.spans) conv.span(span, out)
        return out.toSubpath(NurbsGeometry.isClosed(s))
    }

    /**
     * v1.7 (item 4): the Bézier form of the sanitized [s] from its pieces [parts] (more than the
     * spline itself, [NurbsGeometry.pieces]): each piece converted as a spline of its own, joined
     * at the corners (see the class docs).
     */
    private fun joined(s: VSpline, parts: List<VSpline>, tol: Double): VSubpath {
        val closed = NurbsGeometry.isClosed(s)
        val bases = Array(parts.size) { NurbsGeometry.basis(parts[it]) }
        var spanCount = 0
        for (b in bases) spanCount += b.spans.size
        val cuts = cutsFor(closed, spanCount)
        val out = PieceSink(spanCount * cuts * 2 + 2)
        val converters = Converters(tol)
        for ((k, part) in parts.withIndex()) {
            if (k > 0 || closed) out.corner(part.points[0])
            val b = bases[k]
            val conv = converters.get(part, b)
            for (span in b.spans) conv.span(span, out, cuts)
        }
        return out.toSubpath(closed)
    }

    /**
     * v1.7 (item 4): the equal parts each span is cut into. A closed subpath needs 3 anchors to
     * close (CurveGeometry), which a closed spline with one or two corners and only [spans] = 1
     * or 2 spans in all would not give: its spans are then cut (exactly, widths included). 1 in
     * every other case, and always without corners (a closed spline has at least 3 spans).
     */
    private fun cutsFor(closed: Boolean, spans: Int): Int = if (closed && spans in 1..2) (3 + spans - 1) / spans else 1

    /** The Bézier form of a sanitized spline with no span to convert (fewer than 2 points, or degree 1), else null. */
    private fun trivial(s: VSpline): VSubpath? {
        val pts = s.points
        val n = pts.size
        if (n == 0) return VSubpath(emptyList())
        if (n == 1) return VSubpath(listOf(VAnchor(pts[0].x, pts[0].y, width = pts[0].width)))
        if (min(s.effectiveOrder, n) <= 2) {
            // Degree 1: the control polygon itself, corners at the points.
            return VSubpath(pts.map { VAnchor(it.x, it.y, sharp = true, width = it.width) }, NurbsGeometry.isClosed(s))
        }
        return null
    }

    private fun tolerance(tol: Float): Double = tol.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_TOLERANCE.toDouble()

    /**
     * [toSubpath] for a spline edited a little at a time (the Path tool's point drags, weights,
     * widths): keeps the cubic pieces of each span of the spline it converted last and, for a
     * spline of the same structure (point count, effective order, closed or not, endpoint), converts
     * again only the spans whose p + 1 control points changed, reusing the others' pieces. The
     * result is bit for bit [toSubpath]'s with the same tolerance — a span's pieces depend only on
     * its control points and on the knots, which the structure fixes — so I9 holds as before; a
     * change of structure (a point added or deleted, the order, Cyclic, Endpoint) converts it all.
     * Dragging one point of a long path re-converts at most p + 1 spans instead of all of them.
     * v1.7 (item 4): with corners the structure also includes the corners (a point made sharp or
     * smooth converts it all) and the spans are those of every piece ([NurbsGeometry.pieces]).
     * Not thread-safe: one per tool, used on the main thread.
     */
    class Incremental(tol: Float = DEFAULT_TOLERANCE) {
        private val tolerance = tolerance(tol)
        /** The sanitized points converted last (a copy), and the structure they were converted with. */
        private var points: List<VSplinePoint> = emptyList()
        private var closed = false
        private var endpoint = false
        /** Without corners the effective order (as in v1.6); with corners the spline's order. */
        private var order = 0
        private var corners = IntArray(0)
        /** One [Part] per piece of that spline (the spline itself without corners); empty when nothing is kept. */
        private var parts: Array<Part> = emptyArray()
        private val converters = Converters(tolerance)
        private var dirty = BooleanArray(0)

        /**
         * A piece of the spline: the index of its first point in the spline ([start]; the piece's
         * point i is the spline's point (start + i) mod n), its knots, and the pieces of each span
         * of [basis] (in [NurbsBasis.spans] order).
         */
        private class Part(val start: Int, val basis: NurbsBasis, val spans: Array<SpanPieces>)

        /** Spans the last [toSubpath] converted (the others' pieces were reused). */
        var lastConverted: Int = 0
            private set

        /** Forgets the last spline: the next conversion converts every span. */
        fun reset() {
            points = emptyList(); parts = emptyArray(); corners = IntArray(0)
        }

        /** The Bézier form of [spline], equal to `SplineBezier.toSubpath(spline, tol)`. */
        fun toSubpath(spline: VSpline): VSubpath {
            val s = spline.sanitized()
            trivial(s)?.let { reset(); lastConverted = 0; return it }
            val pts = s.points
            val n = pts.size
            val closed = NurbsGeometry.isClosed(s)
            val corners = NurbsGeometry.cornerIndices(s)
            val order = if (corners.isEmpty()) s.effectiveOrder.coerceIn(2, n) else s.order
            val same = parts.isNotEmpty() && points.size == n && this.order == order && this.closed == closed &&
                corners.contentEquals(this.corners) && (closed || corners.isNotEmpty() || endpoint == s.endpoint)
            val pieces = NurbsGeometry.pieces(s)
            if (!same) {
                parts = Array(pieces.size) { k ->
                    val b = NurbsGeometry.basis(pieces[k])
                    val start = when {
                        corners.isEmpty() -> 0
                        closed -> corners[k]
                        k == 0 -> 0
                        else -> corners[k - 1]
                    }
                    Part(start, b, Array(b.spans.size) { SpanPieces() })
                }
            }
            if (dirty.size < n) dirty = BooleanArray(n)
            for (i in 0 until n) dirty[i] = !same || pts[i] != points[i]
            var spanCount = 0
            for (part in parts) spanCount += part.basis.spans.size
            val cuts = cutsFor(closed, spanCount)
            val out = PieceSink(spanCount * cuts * 2 + 2)
            var converted = 0
            for (k in parts.indices) {
                val part = parts[k]
                val b = part.basis
                val p = b.degree
                if (corners.isNotEmpty() && (k > 0 || closed)) out.corner(pts[part.start])
                var conv: Converter? = null
                for (si in b.spans.indices) {
                    val span = b.spans[si]
                    val sp = part.spans[si]
                    var touched = false
                    for (j in 0..p) if (dirty[(part.start + b.ctrl[span - p + j]) % n]) { touched = true; break }
                    if (touched) {
                        sp.clear()
                        val c = conv ?: converters.get(pieces[k], b).also { conv = it }
                        c.span(span, sp, cuts)
                        converted++
                    }
                    sp.replayInto(out)
                }
            }
            points = ArrayList(pts)
            this.closed = closed
            endpoint = s.endpoint
            this.order = order
            this.corners = corners
            lastConverted = converted
            return out.toSubpath(closed)
        }
    }

    /**
     * I9: [p] has a spline and its single subpath is that spline's Bézier form — within
     * [MATCH_TOLERANCE] anchor by anchor, or (an affine-mapped approximation) a curve within
     * [GEOMETRIC_TOLERANCE] of the spline's. False otherwise: the path is then a plain Bézier path.
     */
    fun matches(p: VPath): Boolean {
        val spline = p.spline ?: return false
        // (A tap checks a path, then the tool that reopens it checks the same instance again:
        // paths are immutable, so the last answer is kept for that instance.)
        synchronized(this) { if (lastChecked?.get() === p) return lastMatch }
        val result = run {
            if (p.subpaths.size != 1) return@run false
            val sanitized = spline.sanitized()
            if (sanitized.points.size < 2 || sanitized.points.size != spline.points.size) return@run false
            val stored = p.subpaths[0]
            val fresh = toSubpath(sanitized)
            structurallyEqual(stored, fresh, MATCH_TOLERANCE) || curvesClose(stored, fresh, GEOMETRIC_TOLERANCE)
        }
        synchronized(this) {
            lastChecked = WeakReference(p)
            lastMatch = result
        }
        return result
    }

    /** The path [matches] checked last (weakly held) and its answer. */
    private var lastChecked: WeakReference<VPath>? = null
    private var lastMatch = false

    /** Same anchors (positions, handles, kinds, widths) within [tol]. */
    fun structurallyEqual(a: VSubpath, b: VSubpath, tol: Float): Boolean {
        if (a.closed != b.closed || a.anchors.size != b.anchors.size) return false
        for (i in a.anchors.indices) {
            val x = a.anchors[i]
            val y = b.anchors[i]
            if (x.sharp != y.sharp) return false
            if (!near(x.x, y.x, tol) || !near(x.y, y.y, tol)) return false
            if (!near(x.width, y.width, 1e-3f)) return false
            if (!nearHandle(x.inX, x.inY, y.inX, y.inY, tol) || !nearHandle(x.outX, x.outY, y.outX, y.outY, tol)) return false
        }
        return true
    }

    private fun near(a: Float, b: Float, tol: Float) = abs(a - b) <= tol

    private fun nearHandle(ax: Float?, ay: Float?, bx: Float?, by: Float?, tol: Float): Boolean {
        if (ax == null || ay == null) return bx == null || by == null
        if (bx == null || by == null) return false
        return near(ax, bx, tol) && near(ay, by, tol)
    }

    /**
     * The curves of [a] and [b] (smooth subpaths in Curve-tool form, tension 0) run within [tol]
     * of each other from the same start, in the same direction (a monotone walk along both
     * flattened curves, each point against the other curve).
     */
    fun curvesClose(a: VSubpath, b: VSubpath, tol: Float): Boolean {
        if (a.closed != b.closed) return false
        val pa = flattened(a) ?: return false
        val pb = flattened(b) ?: return false
        if (pa.first().distanceTo(pb.first()) > tol || pa.last().distanceTo(pb.last()) > tol) return false
        return walk(pa, pb, tol) && walk(pb, pa, tol)
    }

    private fun flattened(s: VSubpath): List<Vec2>? {
        if (s.anchors.size < 2) return null
        val path = CurveGeometry.toPath(VectorOps.curveAnchors(s), s.closed, 0f, false)
        val poly = path.flatten(FLATTEN_TOLERANCE).firstOrNull() ?: return null
        val pts = poly.points
        if (pts.size < 2) return null
        return if (poly.closed) pts + pts[0] else pts
    }

    /** Every point of [a] lies within [tol] of the polyline [b], visiting [b] forwards only. */
    private fun walk(a: List<Vec2>, b: List<Vec2>, tol: Float): Boolean {
        var cur = 0
        val last = b.size - 2
        for (q in a) {
            var best = Float.POSITIVE_INFINITY
            var bestJ = cur
            val windowEnd = min(last, cur + WALK_WINDOW)
            for (j in cur..windowEnd) {
                val d = Geometry.distanceToSegment(q, b[j], b[j + 1])
                if (d < best) { best = d; bestJ = j }
            }
            if (best > tol && windowEnd < last) {
                // Farther along than the window: look at the rest once.
                for (j in windowEnd + 1..last) {
                    val d = Geometry.distanceToSegment(q, b[j], b[j + 1])
                    if (d < best) { best = d; bestJ = j }
                }
            }
            if (best > tol) return false
            cur = bestJ
        }
        return true
    }

    private const val FLATTEN_TOLERANCE = 0.05f
    private const val WALK_WINDOW = 48

    // ------------------------------------------------------------------ conversion

    /** Where a span's cubic pieces go, in order: start point and width, two handles, end point and width. */
    private interface PieceOut {
        fun add(p0x: Double, p0y: Double, p0w: Double, ax: Double, ay: Double, bx: Double, by: Double, p1x: Double, p1y: Double, p1w: Double)
    }

    /** The pieces of one span as converted (the doubles [Converter] gave, unchanged), to replay into a [PieceSink]. */
    private class SpanPieces : PieceOut {
        private var data = DoubleArray(2 * PIECE)
        private var n = 0

        fun clear() { n = 0 }

        override fun add(p0x: Double, p0y: Double, p0w: Double, ax: Double, ay: Double, bx: Double, by: Double, p1x: Double, p1y: Double, p1w: Double) {
            if ((n + 1) * PIECE > data.size) data = data.copyOf(data.size * 2)
            val o = n * PIECE
            data[o] = p0x; data[o + 1] = p0y; data[o + 2] = p0w
            data[o + 3] = ax; data[o + 4] = ay; data[o + 5] = bx; data[o + 6] = by
            data[o + 7] = p1x; data[o + 8] = p1y; data[o + 9] = p1w
            n++
        }

        fun replayInto(out: PieceOut) {
            for (k in 0 until n) {
                val o = k * PIECE
                out.add(data[o], data[o + 1], data[o + 2], data[o + 3], data[o + 4], data[o + 5], data[o + 6], data[o + 7], data[o + 8], data[o + 9])
            }
        }

        private companion object {
            const val PIECE = 10
        }
    }

    /**
     * Collects cubic pieces (start point, two handles, start width) and joins them into anchors.
     * v1.7 (item 4): a piece added after [corner] starts at a corner (a sharp anchor).
     */
    private class PieceSink(capacity: Int) : PieceOut {
        /** Per piece: start x, y, width, first handle x, y, second handle x, y. */
        private var data = DoubleArray(max(1, capacity) * STRIDE)
        private var n = 0
        private var endX = 0.0; private var endY = 0.0; private var endW = 1.0
        /** Per piece: its start is a corner (grown on the first corner only). */
        private var sharp = BooleanArray(0)
        private var cornerPending = false
        private var cornerX = 0.0; private var cornerY = 0.0; private var cornerW = 1.0

        /**
         * v1.7 (item 4): the next piece starts at the corner [p] (a sharp control point the curve
         * passes through): its anchor is sharp, exactly at [p] with [p]'s width (not the piece's
         * computed start, which may differ in the last bits); its handles stay the pieces' own.
         */
        fun corner(p: VSplinePoint) {
            cornerPending = true
            cornerX = p.x.toDouble(); cornerY = p.y.toDouble(); cornerW = p.width.toDouble()
        }

        override fun add(p0x: Double, p0y: Double, p0w: Double, ax: Double, ay: Double, bx: Double, by: Double, p1x: Double, p1y: Double, p1w: Double) {
            if ((n + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
            val o = n * STRIDE
            if (cornerPending) {
                cornerPending = false
                if (sharp.size <= n) sharp = sharp.copyOf(max(n + 1, data.size / STRIDE))
                sharp[n] = true
                data[o] = cornerX; data[o + 1] = cornerY; data[o + 2] = cornerW
            } else {
                data[o] = p0x; data[o + 1] = p0y; data[o + 2] = p0w
            }
            data[o + 3] = ax; data[o + 4] = ay; data[o + 5] = bx; data[o + 6] = by
            n++
            endX = p1x; endY = p1y; endW = p1w
        }

        fun toSubpath(closed: Boolean): VSubpath {
            if (n == 0) return VSubpath(emptyList(), closed)
            val count = if (closed) n else n + 1
            val anchors = ArrayList<VAnchor>(count)
            for (i in 0 until count) {
                val o = i * STRIDE
                val px: Double; val py: Double; val pw: Double
                if (i < n) { px = data[o]; py = data[o + 1]; pw = data[o + 2] } else { px = endX; py = endY; pw = endW }
                // Out handle: this anchor's piece; in handle: the previous piece's second handle.
                val hasOut = i < n
                val prev = when {
                    i > 0 -> i - 1
                    closed -> n - 1
                    else -> -1
                }
                val q = prev * STRIDE
                var outX = if (hasOut) data[o + 3] - px else 0.0
                var outY = if (hasOut) data[o + 4] - py else 0.0
                var inX = if (prev >= 0) data[q + 5] - px else 0.0
                var inY = if (prev >= 0) data[q + 6] - py else 0.0
                // Open ends: the unused handle mirrors the used one (a smooth anchor needs both).
                if (!hasOut) { outX = -inX; outY = -inY }
                if (prev < 0) { inX = -outX; inY = -outY }
                anchors += VAnchor(
                    px.toFloat(), py.toFloat(), sharp = i < sharp.size && sharp[i],
                    inX = inX.toFloat(), inY = inY.toFloat(), outX = outX.toFloat(), outY = outY.toFloat(),
                    width = pw.toFloat().coerceIn(0f, VSpline.MAX_WIDTH),
                )
            }
            return VSubpath(anchors, closed)
        }

        private companion object {
            const val STRIDE = 7
        }
    }

    /** One [Converter] per degree, reused across the pieces of a spline (v1.7, item 4). */
    private class Converters(private val tol: Double) {
        private val byDegree = arrayOfNulls<Converter>(VSpline.MAX_ORDER)

        /** A converter of [b]'s degree, set to [s] and [b]. */
        fun get(s: VSpline, b: NurbsBasis): Converter {
            val c = byDegree[b.degree] ?: return Converter(s, b, tol).also { byDegree[b.degree] = it }
            c.s = s
            c.b = b
            return c
        }
    }

    /**
     * Converts the spans of one spline (scratch arrays reused across spans). [s] and [b] may be
     * replaced by another spline and basis of the same degree: a span's pieces depend only on
     * them, never on what was converted before.
     */
    private class Converter(var s: VSpline, var b: NurbsBasis, private val tol: Double) {
        private val p = b.degree
        private val dim = NurbsGeometry.DIM
        private val h = DoubleArray((p + 1) * dim)
        private val scratch = DoubleArray((p + 1) * dim)
        /** The span's homogeneous polynomial in the power basis (Horner evaluation: ~6× cheaper than de Casteljau per sample). */
        private val power = DoubleArray((p + 1) * dim)
        private val v0 = DoubleArray(dim); private val d0 = DoubleArray(dim)
        private val v1 = DoubleArray(dim); private val d1 = DoubleArray(dim)
        private val vs = DoubleArray(dim); private val ds = DoubleArray(dim)

        /** Converts span [span] (a knot index) into [out]; v1.7: cut into [cuts] equal parts first (see [cutsFor]). */
        fun span(span: Int, out: PieceOut, cuts: Int = 1) {
            if (cuts <= 1) {
                NurbsGeometry.spanBezier(s, b, span, h, scratch)
                convert(out)
                return
            }
            val a = b.knots[span]
            val c = b.knots[span + 1]
            for (j in 0 until cuts) {
                val to = if (j == cuts - 1) c else a + (c - a) * (j + 1) / cuts
                NurbsGeometry.spanBezier(s, b, span, h, scratch, a + (c - a) * j / cuts, to)
                convert(out)
            }
        }

        /** The Bézier in [h] as cubic pieces into [out]. */
        private fun convert(out: PieceOut) {
            if (p == 1) line(out)
            else if (p <= 3 && constantWeight()) exact(out) else {
                toPowerBasis()
                approximate(out)
            }
        }

        /**
         * v1.7 (item 4): degree 1, only a 2-point piece between corners (a whole spline of degree
         * 1 never gets here): the straight segment, its handles at the thirds (whatever the
         * weights, which only change how the width is blended along it).
         */
        private fun line(out: PieceOut) {
            val x0 = h[0] / h[2]; val y0 = h[1] / h[2]; val w0 = h[3] / h[2]
            val x1 = h[dim] / h[dim + 2]; val y1 = h[dim + 1] / h[dim + 2]; val w1 = h[dim + 3] / h[dim + 2]
            out.add(
                x0, y0, w0,
                x0 + (x1 - x0) / 3.0, y0 + (y1 - y0) / 3.0, x1 + (x0 - x1) / 3.0, y1 + (y0 - y1) / 3.0,
                x1, y1, w1,
            )
        }

        /** [h] (Bernstein, degree [p]) in the power basis: c_k = C(p, k) Σ_{i ≤ k} (−1)^(k−i) C(k, i) b_i. */
        private fun toPowerBasis() {
            for (k in 0..p) {
                val ck = binom(p, k)
                for (d in 0 until dim) {
                    var acc = 0.0
                    for (i in 0..k) {
                        val sign = if ((k - i) % 2 == 0) 1.0 else -1.0
                        acc += sign * binom(k, i) * h[i * dim + d]
                    }
                    power[k * dim + d] = ck * acc
                }
            }
        }

        private fun binom(n: Int, k: Int): Double {
            var r = 1.0
            for (j in 1..k) r = r * (n - k + j) / j
            return r
        }

        /** The span's homogeneous point at [u] into [value] (and d/du into [deriv] when it is not null), by Horner. */
        private fun horner(u: Double, value: DoubleArray, deriv: DoubleArray?) {
            for (d in 0 until dim) {
                var v = power[p * dim + d]
                var dv = 0.0
                for (k in p - 1 downTo 0) {
                    dv = dv * u + v
                    v = v * u + power[k * dim + d]
                }
                value[d] = v
                if (deriv != null) deriv[d] = dv
            }
        }

        /** All homogeneous weights of the span equal: a polynomial curve (exact cubic). */
        private fun constantWeight(): Boolean {
            val w0 = h[2]
            for (j in 1..p) if (abs(h[j * dim + 2] - w0) > 1e-9 * max(1.0, abs(w0))) return false
            return true
        }

        private fun exact(out: PieceOut) {
            val w = h[2]
            fun px(j: Int) = h[j * dim] / w
            fun py(j: Int) = h[j * dim + 1] / w
            val w0 = h[3] / w
            val w1 = h[p * dim + 3] / w
            if (p == 3) {
                out.add(px(0), py(0), w0, px(1), py(1), px(2), py(2), px(3), py(3), w1)
            } else {
                // Degree 2 elevated to 3: c1 = q0 + 2/3 (q1 − q0), c2 = q2 + 2/3 (q1 − q2).
                val ax = px(0) + 2.0 / 3.0 * (px(1) - px(0)); val ay = py(0) + 2.0 / 3.0 * (py(1) - py(0))
                val bx = px(2) + 2.0 / 3.0 * (px(1) - px(2)); val by = py(2) + 2.0 / 3.0 * (py(1) - py(2))
                out.add(px(0), py(0), w0, ax, ay, bx, by, px(2), py(2), w1)
            }
        }

        /**
         * The span as at most [MAX_PIECES_PER_SPAN] cubics, each with the spline's exact end points
         * and end tangents (so joints stay smooth): while a piece misses one of its samples by more
         * than the tolerance and the budget allows, the piece that misses most is halved, so the
         * pieces go where the curve actually turns.
         */
        private fun approximate(out: PieceOut) {
            count = 0
            fitPiece(0.0, 1.0, 0)
            count = 1
            while (count < MAX_PIECES_PER_SPAN) {
                var worst = -1
                for (k in 0 until count) if (err[k] > tol && (worst < 0 || err[k] > err[worst])) worst = k
                if (worst < 0) break
                // Halve it at the middle of its standard-form parameter: t(½) = ρ / (1 + ρ).
                val a = pa[worst]; val c = pc[worst]
                val rho = prho[worst]
                val m = a + (c - a) * (rho / (1.0 + rho))
                if (!(m > a && m < c)) { err[worst] = 0.0; continue }
                // The right half goes after it (pieces stay in parameter order).
                for (k in count downTo worst + 2) copyPiece(k - 1, k)
                fitPiece(a, m, worst)
                fitPiece(m, c, worst + 1)
                count++
            }
            for (k in 0 until count) {
                val o = k * PIECE
                out.add(geo[o], geo[o + 1], geo[o + 2], geo[o + 3], geo[o + 4], geo[o + 5], geo[o + 6], geo[o + 7], geo[o + 8], geo[o + 9])
            }
        }

        /** Pieces of the span being approximated: parameter range, standard-form ratio, miss, geometry. */
        private var count = 0
        private val pa = DoubleArray(MAX_PIECES_PER_SPAN); private val pc = DoubleArray(MAX_PIECES_PER_SPAN)
        private val prho = DoubleArray(MAX_PIECES_PER_SPAN); private val err = DoubleArray(MAX_PIECES_PER_SPAN)
        /** Per piece: start x, y, width, handles ax, ay, bx, by, end x, y, width. */
        private val geo = DoubleArray(MAX_PIECES_PER_SPAN * PIECE)

        private fun copyPiece(from: Int, to: Int) {
            pa[to] = pa[from]; pc[to] = pc[from]; prho[to] = prho[from]; err[to] = err[from]
            System.arraycopy(geo, from * PIECE, geo, to * PIECE, PIECE)
        }

        /**
         * The rational point and its derivative (d/du) at [u]: x, y, homogeneous weight and width
         * into [v], dx, dy into [d].
         */
        private fun at(u: Double, v: DoubleArray, d: DoubleArray) {
            horner(u, vs, ds)
            val w = vs[2]
            val x = vs[0] / w
            val y = vs[1] / w
            v[0] = x; v[1] = y; v[2] = w; v[3] = vs[3] / w
            d[0] = (ds[0] - x * ds[2]) / w
            d[1] = (ds[1] - y * ds[2]) / w
        }

        /**
         * Fits the piece [a]..[c] of the span into slot [slot]. Everything is done in the rational
         * standard form of the piece: the Möbius reparametrization `t = ρs / ((1 − s) + ρs)` with
         * `ρ = (w(a) / w(c))^(1/p)` makes the end weights equal, which evens out how fast a heavily
         * weighted piece is traversed. The two handle lengths start as the Hermite ones (exact end
         * derivatives in that parameter) and are fitted by least squares to the samples; the miss
         * is the largest distance at matching parameters (a bound a loop or a skipped bend can't
         * slip through).
         */
        private fun fitPiece(a: Double, c: Double, slot: Int) {
            at(a, v0, d0)
            at(c, v1, d1)
            val rho = Math.pow(v0[2] / v1[2], 1.0 / p).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val len = c - a
            // Hermite handles in the standard-form parameter (α = β = 1 below).
            val t0x = d0[0] * len * rho / 3.0; val t0y = d0[1] * len * rho / 3.0
            val t1x = d1[0] * len / (rho * 3.0); val t1y = d1[1] * len / (rho * 3.0)
            sample(a, c, rho)
            fitLengths(v0[0], v0[1], t0x, t0y, v1[0], v1[1], t1x, t1y)
            val ax = v0[0] + t0x * alpha; val ay = v0[1] + t0y * alpha
            val bx = v1[0] - t1x * beta; val by = v1[1] - t1y * beta
            pa[slot] = a; pc[slot] = c; prho[slot] = rho
            err[slot] = miss(v0[0], v0[1], ax, ay, bx, by, v1[0], v1[1])
            val o = slot * PIECE
            geo[o] = v0[0]; geo[o + 1] = v0[1]; geo[o + 2] = v0[3]
            geo[o + 3] = ax; geo[o + 4] = ay; geo[o + 5] = bx; geo[o + 6] = by
            geo[o + 7] = v1[0]; geo[o + 8] = v1[1]; geo[o + 9] = v1[3]
        }

        private val sv = DoubleArray(dim)
        /** The spline at the sample parameters of the piece: [CHECK_SAMPLES] fitted ones, then the points between them. */
        private val qx = DoubleArray(SAMPLE_COUNT); private val qy = DoubleArray(SAMPLE_COUNT)
        /** The standard-form parameter of each sample. */
        private val sf = DoubleArray(SAMPLE_COUNT) { j -> if (j < CHECK_SAMPLES) (j + 0.5) / CHECK_SAMPLES else (j - CHECK_SAMPLES + 1.0) / CHECK_SAMPLES }
        private var alpha = 1.0
        private var beta = 1.0

        /** The spline at every sample parameter of the piece into [qx] / [qy]. */
        private fun sample(a: Double, c: Double, rho: Double) {
            for (j in 0 until SAMPLE_COUNT) {
                val f = sf[j]
                horner(a + (c - a) * (rho * f / ((1.0 - f) + rho * f)), sv, null)
                val w = sv[2]
                qx[j] = sv[0] / w; qy[j] = sv[1] / w
            }
        }
        /**
         * The two handle lengths (as multiples [alpha] / [beta] of the Hermite handles, so the end
         * tangents stay exact and joints stay smooth) that fit the [CHECK_SAMPLES] samples best
         * in the least-squares sense (same parameters); the Hermite lengths when the fit is
         * degenerate or not sensible.
         */
        private fun fitLengths(p0x: Double, p0y: Double, t0x: Double, t0y: Double, p1x: Double, p1y: Double, t1x: Double, t1y: Double) {
            alpha = 1.0; beta = 1.0
            var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var r1 = 0.0; var r2 = 0.0
            for (j in 0 until CHECK_SAMPLES) {
                val f = sf[j]
                val g = 1.0 - f
                val b0 = g * g * g; val b1 = 3.0 * g * g * f; val b2 = 3.0 * g * f * f; val b3 = f * f * f
                // Q − (b0 + b1) P0 − (b2 + b3) P1 = α b1 T0 − β b2 T1
                val rx = qx[j] - (b0 + b1) * p0x - (b2 + b3) * p1x
                val ry = qy[j] - (b0 + b1) * p0y - (b2 + b3) * p1y
                val ux = b1 * t0x; val uy = b1 * t0y
                val vx = -b2 * t1x; val vy = -b2 * t1y
                a11 += ux * ux + uy * uy
                a12 += ux * vx + uy * vy
                a22 += vx * vx + vy * vy
                r1 += ux * rx + uy * ry
                r2 += vx * rx + vy * ry
            }
            val det = a11 * a22 - a12 * a12
            if (!(abs(det) > 1e-12 * max(1.0, a11 * a22))) return
            val al = (r1 * a22 - r2 * a12) / det
            val be = (a11 * r2 - a12 * r1) / det
            // Only sensible lengths (same direction as the tangent, not absurdly long).
            if (al.isFinite() && be.isFinite() && al > 0.0 && be > 0.0 && al < 8.0 && be < 8.0) { alpha = al; beta = be }
        }

        /**
         * The largest distance between the cubic and the spline over every sample (the fitted
         * ones and the points between them), at matching standard-form parameters.
         */
        private fun miss(p0x: Double, p0y: Double, ax: Double, ay: Double, bx: Double, by: Double, p1x: Double, p1y: Double): Double {
            var worst = 0.0
            for (j in 0 until SAMPLE_COUNT) {
                val f = sf[j]
                val g = 1.0 - f
                val b0 = g * g * g; val b1 = 3.0 * g * g * f; val b2 = 3.0 * g * f * f; val b3 = f * f * f
                val x = b0 * p0x + b1 * ax + b2 * bx + b3 * p1x
                val y = b0 * p0y + b1 * ay + b2 * by + b3 * p1y
                val e = hypot(x - qx[j], y - qy[j])
                if (e > worst) worst = e
            }
            return worst
        }

        private companion object {
            /** The fitted samples and the points between them. */
            const val SAMPLE_COUNT = CHECK_SAMPLES * 2 - 1
            /** Doubles per piece in [geo]. */
            const val PIECE = 10
        }
    }
}
