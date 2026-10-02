package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The filled outline of a line whose width varies along it (per-point thickness, §4.5; v1.5,
 * owned by A4, API frozen): exactly the area a disc of the local width sweeps along the line
 * (the union of the discs at the points and of the hulls between neighbouring discs), as
 * closed contours to fill with the NON-ZERO rule (android's default).
 *
 * The line is cut into runs of segments whose neighbouring discs don't contain one another.
 * Each run is outlined by contours that follow the true boundary: the outer tangents of the
 * discs along both sides; where a side turns outward, an arc of the local disc (a round join);
 * where it turns inward, the corner where the two tangent lines cross. Where an inner side folds
 * (its tangent lines don't cross within both segments — a tight turn or a fast width change
 * relative to the segment length) the run is cut there: the two contours' round caps make the
 * whole disc, so nothing is lost. Contours are also cut about every [CHUNK] px (tight dirty
 * regions and undo areas for long lines). Where a disc holds its neighbour, the larger disc is
 * all there is (part of the run it ends, or drawn on its own). Every contour winds the same way
 * (negative signed area in x-right / y-down coordinates), so overlaps add up and the non-zero
 * fill is their union; a closed line's inside stays empty. Arcs are polylines within [build]'s
 * tolerance (inside the true round shape).
 *
 * Pure Kotlin, thread-safe (vector layer renderers call it off the main thread).
 */
object VariableWidthOutline {
    /** Arc length of one contour piece (document px). */
    const val CHUNK = 128f

    /** Points closer than this are one (document px); radii below it draw nothing. */
    private const val EPS = 1e-3f

    /** A join this close to a U-turn is decided by the centre line's turn, not by the normals. */
    private const val NEAR_U_TURN = (0.9 * PI).toFloat()

    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * Outline of the polyline [xs]/[ys] (first [n] points, document px) with full widths
     * [widths] per point; [closed] joins the last point back to the first (more than two
     * points). [tolerance] (px) bounds how far arcs may cut inside the true round shape.
     * Non-finite coordinates split the line; non-finite or negative widths count as 0. Empty
     * when nothing has a width.
     */
    fun build(xs: FloatArray, ys: FloatArray, widths: FloatArray, n: Int, closed: Boolean = false, tolerance: Float = 0.25f): VectorPath {
        val m = min(min(n, xs.size), min(ys.size, widths.size))
        if (m <= 0) return VectorPath.EMPTY
        val tol = if (tolerance.isFinite() && tolerance > 0f) tolerance else 0.25f
        val out = Builder(tol)
        var start = 0
        for (i in 0..m) {
            val ok = i < m && xs[i].isFinite() && ys[i].isFinite()
            if (ok) continue
            if (i > start) out.line(xs, ys, widths, start, i, closed && start == 0 && i == m)
            start = i + 1
        }
        return if (out.ops.isEmpty()) VectorPath.EMPTY else VectorPath(out.ops)
    }

    /** One build: the contours collected in [ops]. */
    private class Builder(val tol: Float) {
        val ops = ArrayList<PathOp>()

        // The cleaned line: k points with radii.
        private var px = FloatArray(0)
        private var py = FloatArray(0)
        private var pr = FloatArray(0)
        private var k = 0

        // Per segment s (from point s to point (s + 1) % k): valid hull, unit outer-tangent
        // normals of the left (nl) and right (nr) side, direction (u), half-angle of the tangent
        // normals around u (phi) and length.
        private var valid = BooleanArray(0)
        private var nlx = FloatArray(0); private var nly = FloatArray(0)
        private var nrx = FloatArray(0); private var nry = FloatArray(0)
        private var ux = FloatArray(0); private var uy = FloatArray(0)
        private var phi = FloatArray(0)
        private var len = FloatArray(0)

        // Per joint j of the run being outlined (between its segments j − 1 and j): whether each
        // side turns outward (round join, sweeping [lSweep] / [rSweep] as the side is drawn),
        // else where its tangent lines cross; and whether the run is cut there.
        private var lOuter = BooleanArray(0)
        private var rOuter = BooleanArray(0)
        private var lSweep = FloatArray(0)
        private var rSweep = FloatArray(0)
        private var lx = FloatArray(0); private var ly = FloatArray(0)
        private var rx = FloatArray(0); private var ry = FloatArray(0)
        private var cut = BooleanArray(0)

        /** Outlines points [from] until [until] (all finite); [closed] joins the end back to the start. */
        fun line(xs: FloatArray, ys: FloatArray, widths: FloatArray, from: Int, until: Int, closed: Boolean) {
            clean(xs, ys, widths, from, until, closed)
            if (k == 0) return
            var anyWidth = false
            for (i in 0 until k) if (pr[i] >= EPS) { anyWidth = true; break }
            if (!anyWidth) return
            if (k == 1) { disc(px[0], py[0], pr[0]); return }
            val loop = closed && k > 2
            val segs = if (loop) k else k - 1
            segments(segs)
            // Runs of consecutive valid segments (a loop goes round from just after an invalid
            // one, or from its first point when all are valid: caps meet there).
            var first = 0
            if (loop) {
                val bad = (0 until segs).firstOrNull { !valid[it] }
                if (bad == null) {
                    run(IntArray(segs) { it })
                    return
                }
                first = (bad + 1) % segs
            }
            val covered = BooleanArray(k)
            val cur = ArrayList<Int>()
            fun flush() {
                if (cur.isEmpty()) return
                val r = cur.toIntArray()
                for (s in r) { covered[s] = true; covered[(s + 1) % k] = true }
                run(r)
                cur.clear()
            }
            for (j in 0 until segs) {
                val s = (first + j) % segs
                if (valid[s]) cur += s else flush()
            }
            flush()
            // Points no run reaches: their own disc.
            for (i in 0 until k) if (!covered[i] && pr[i] >= EPS) disc(px[i], py[i], pr[i])
        }

        /** Copies the points, merging neighbours closer than [EPS] (keeping the larger radius). */
        private fun clean(xs: FloatArray, ys: FloatArray, widths: FloatArray, from: Int, until: Int, closed: Boolean) {
            val cap = until - from
            if (px.size < cap) { px = FloatArray(cap); py = FloatArray(cap); pr = FloatArray(cap) }
            k = 0
            for (i in from until until) {
                val w = widths[i]
                val r = if (w.isFinite() && w > 0f) w / 2f else 0f
                if (k > 0 && hypot(xs[i] - px[k - 1], ys[i] - py[k - 1]) < EPS) {
                    pr[k - 1] = max(pr[k - 1], r)
                    continue
                }
                px[k] = xs[i]; py[k] = ys[i]; pr[k] = r
                k++
            }
            // A closed line that ends on its first point: one point.
            if (closed && k > 1 && hypot(px[k - 1] - px[0], py[k - 1] - py[0]) < EPS) {
                pr[0] = max(pr[0], pr[k - 1])
                k--
            }
        }

        private fun segments(segs: Int) {
            if (valid.size < segs) {
                valid = BooleanArray(segs)
                nlx = FloatArray(segs); nly = FloatArray(segs); nrx = FloatArray(segs); nry = FloatArray(segs)
                ux = FloatArray(segs); uy = FloatArray(segs); phi = FloatArray(segs); len = FloatArray(segs)
            }
            for (s in 0 until segs) {
                val a = s
                val b = (s + 1) % k
                val dx = px[b] - px[a]
                val dy = py[b] - py[a]
                val d = hypot(dx, dy)
                val ra = pr[a]
                val rb = pr[b]
                len[s] = d
                valid[s] = d > EPS && d - abs(ra - rb) > EPS
                if (!valid[s]) continue
                val ex = dx / d
                val ey = dy / d
                val vx = -ey
                val vy = ex
                val sn = (ra - rb) / d
                val c = sqrt((1f - sn * sn).coerceAtLeast(0f))
                // Unit normals of the two outer tangents: n·(b − a) = ra − rb.
                nlx[s] = sn * ex + c * vx; nly[s] = sn * ey + c * vy
                nrx[s] = sn * ex - c * vx; nry[s] = sn * ey - c * vy
                ux[s] = ex; uy[s] = ey
                phi[s] = atan2(c, sn)
            }
        }

        /**
         * Outlines the run of valid segments [segs] (in order along the line): plans every
         * joint (round on the outer side, the tangent lines' crossing on the inner side), cuts
         * where an inner side folds or about every [CHUNK] px, then draws each piece.
         */
        private fun run(segs: IntArray) {
            val m = segs.size
            if (cut.size < m) {
                lOuter = BooleanArray(m); rOuter = BooleanArray(m)
                lSweep = FloatArray(m); rSweep = FloatArray(m)
                lx = FloatArray(m); ly = FloatArray(m); rx = FloatArray(m); ry = FloatArray(m)
                cut = BooleanArray(m)
            }
            // Where the current segment's inner sides were cut by the joint before (line parameter 0..1).
            var startL = 0f
            var startR = 0f
            var acc = 0f
            for (j in 1 until m) {
                val s = segs[j - 1]
                val t = segs[j]
                val i = (s + 1) % k
                acc += len[s]
                val tr = angle(ux[s], uy[s], ux[t], uy[t])
                // Left side (drawn forward): outer when its normals turn negatively.
                var dl = angle(nlx[s], nly[s], nlx[t], nly[t])
                val leftOuter = if (abs(dl) > NEAR_U_TURN) {
                    val o = tr < 0f
                    if (o && dl > 0f) dl -= TWO_PI
                    o
                } else {
                    dl < 0f
                }
                // Right side (drawn backward): outer when its normals turn positively going forward.
                var dr = angle(nrx[s], nry[s], nrx[t], nry[t])
                val rightOuter = if (abs(dr) > NEAR_U_TURN) {
                    val o = tr > 0f
                    if (o && dr < 0f) dr += TWO_PI
                    o
                } else {
                    dr > 0f
                }
                lOuter[j] = leftOuter
                rOuter[j] = rightOuter
                lSweep[j] = dl
                rSweep[j] = -dr
                var ok = true
                var nextL = 0f
                var nextR = 0f
                if (!leftOuter) {
                    ok = crossing(s, t, i, left = true, minFirst = startL)
                    if (ok) { lx[j] = hitX; ly[j] = hitY; nextL = hitMu }
                }
                if (ok && !rightOuter) {
                    ok = crossing(s, t, i, left = false, minFirst = startR)
                    if (ok) { rx[j] = hitX; ry[j] = hitY; nextR = hitMu }
                }
                if (!ok || acc >= CHUNK) {
                    cut[j] = true
                    acc = 0f
                    startL = 0f
                    startR = 0f
                } else {
                    cut[j] = false
                    startL = nextL
                    startR = nextR
                }
            }
            var from = 0
            for (j in 1 until m) {
                if (cut[j]) {
                    piece(segs, from, j)
                    from = j
                }
            }
            piece(segs, from, m)
        }

        /** Signed angle (radians, (−π, π]) from direction ([ax], [ay]) to ([bx], [by]). */
        private fun angle(ax: Float, ay: Float, bx: Float, by: Float): Float = atan2(ax * by - ay * bx, ax * bx + ay * by)

        // Result of [crossing]: the crossing point and its line parameter on the outgoing segment.
        private var hitX = 0f
        private var hitY = 0f
        private var hitMu = 0f

        /**
         * Where the [left] (else right) tangent lines of segment [s] (ending at point [i]) and
         * segment [t] (starting there) cross, into [hitX] / [hitY] / [hitMu]: false when they
         * don't cross within both (on [s] not before [minFirst], where its start was cut).
         */
        private fun crossing(s: Int, t: Int, i: Int, left: Boolean, minFirst: Float): Boolean {
            val a = s
            val b = (t + 1) % k
            val nsx = if (left) nlx[s] else nrx[s]
            val nsy = if (left) nly[s] else nry[s]
            val ntx = if (left) nlx[t] else nrx[t]
            val nty = if (left) nly[t] else nry[t]
            val a0x = px[a] + pr[a] * nsx; val a0y = py[a] + pr[a] * nsy
            val a1x = px[i] + pr[i] * nsx; val a1y = py[i] + pr[i] * nsy
            val b0x = px[i] + pr[i] * ntx; val b0y = py[i] + pr[i] * nty
            val b1x = px[b] + pr[b] * ntx; val b1y = py[b] + pr[b] * nty
            if (hypot(b0x - a1x, b0y - a1y) <= tol * 0.25f) {
                // The sides meet (a straight or barely turning joint): no corner to cut.
                hitX = a1x; hitY = a1y; hitMu = 0f
                return minFirst <= 1f
            }
            val d1x = a1x - a0x; val d1y = a1y - a0y
            val d2x = b1x - b0x; val d2y = b1y - b0y
            val l1 = hypot(d1x, d1y)
            val l2 = hypot(d2x, d2y)
            val det = d1x * d2y - d1y * d2x
            if (l1 <= EPS || l2 <= EPS || abs(det) <= 1e-5f * l1 * l2) return false
            val wx = b0x - a0x
            val wy = b0y - a0y
            val lambda = (wx * d2y - wy * d2x) / det
            val mu = (wx * d1y - wy * d1x) / det
            if (!(lambda >= minFirst && lambda <= 1f && mu >= 0f && mu <= 1f)) return false
            hitX = a0x + lambda * d1x
            hitY = a0y + lambda * d1y
            hitMu = mu
            return true
        }

        /**
         * One contour around the run's segments segs[from until until] (joints from + 1 ..
         * until − 1 are inside it): left side forward, end cap, right side backward, start cap.
         */
        private fun piece(segs: IntArray, from: Int, until: Int) {
            if (until <= from) return
            var visible = false
            for (j in from until until) {
                val s = segs[j]
                if (pr[s] >= EPS || pr[(s + 1) % k] >= EPS) { visible = true; break }
            }
            if (!visible) return
            val s0 = segs[from]
            // Left side, forward.
            moveTo(px[s0] + pr[s0] * nlx[s0], py[s0] + pr[s0] * nly[s0])
            for (j in from + 1 until until) {
                val s = segs[j - 1]
                val t = segs[j]
                val i = (s + 1) % k
                if (lOuter[j]) {
                    lineTo(px[i] + pr[i] * nlx[s], py[i] + pr[i] * nly[s])
                    arc(i, nlx[s], nly[s], lSweep[j], px[i] + pr[i] * nlx[t], py[i] + pr[i] * nly[t])
                } else {
                    lineTo(lx[j], ly[j])
                }
            }
            val sl = segs[until - 1]
            val e = (sl + 1) % k
            lineTo(px[e] + pr[e] * nlx[sl], py[e] + pr[e] * nly[sl])
            // End cap: round the front of the last point's disc (through its direction).
            arc(e, nlx[sl], nly[sl], -2f * phi[sl], px[e] + pr[e] * nrx[sl], py[e] + pr[e] * nry[sl])
            // Right side, backward.
            for (j in until - 1 downTo from + 1) {
                val s = segs[j - 1]
                val t = segs[j]
                val i = (s + 1) % k
                if (rOuter[j]) {
                    lineTo(px[i] + pr[i] * nrx[t], py[i] + pr[i] * nry[t])
                    arc(i, nrx[t], nry[t], rSweep[j], px[i] + pr[i] * nrx[s], py[i] + pr[i] * nry[s])
                } else {
                    lineTo(rx[j], ry[j])
                }
            }
            lineTo(px[s0] + pr[s0] * nrx[s0], py[s0] + pr[s0] * nry[s0])
            // Start cap: round the back of the first point's disc.
            arc(s0, nrx[s0], nry[s0], -(TWO_PI - 2f * phi[s0]), px[s0] + pr[s0] * nlx[s0], py[s0] + pr[s0] * nly[s0])
            ops += PathOp.Close
        }

        /**
         * An arc of point [i]'s disc from the direction ([ax], [ay]) sweeping [sweep] radians
         * (negative), ending exactly at ([ex], [ey]); chords within the tolerance.
         */
        private fun arc(i: Int, ax: Float, ay: Float, sweep: Float, ex: Float, ey: Float) {
            val r = pr[i]
            if (r >= EPS && abs(sweep) > 1e-6f) {
                val step = maxStep(r)
                val n = ceil(abs(sweep) / step).toInt().coerceIn(1, 4096)
                val a0 = atan2(ay, ax)
                val cx = px[i]
                val cy = py[i]
                for (j in 1 until n) {
                    val a = a0 + sweep * j / n
                    lineTo(cx + r * cos(a), cy + r * sin(a))
                }
            }
            lineTo(ex, ey)
        }

        /** Largest angle (radians) whose chord on a circle of radius [r] stays within the tolerance. */
        private fun maxStep(r: Float): Float {
            val c = 1f - tol / r
            if (c <= 0f) return (PI / 2).toFloat()
            return min((PI / 2).toFloat(), 2f * acos(c))
        }

        private var lastX = Float.NaN
        private var lastY = Float.NaN

        private fun moveTo(x: Float, y: Float) {
            ops += PathOp.MoveTo(Vec2(x, y))
            lastX = x; lastY = y
        }

        private fun lineTo(x: Float, y: Float) {
            // Repeated points add nothing.
            if (x == lastX && y == lastY) return
            ops += PathOp.LineTo(Vec2(x, y))
            lastX = x; lastY = y
        }

        /**
         * A disc of radius [r] at ([cx], [cy]) as cubic arcs, wound like the contours (angles
         * decreasing). Four arcs are within 0.03 % of the radius; large discs get eight.
         */
        fun disc(cx: Float, cy: Float, r: Float) {
            if (r < EPS) return
            val arcs = if (r * 2.8e-4f <= tol) 4 else 8
            val step = (-2.0 * PI / arcs).toFloat()
            val kk = (4.0 / 3.0 * tan(step / 4.0)).toFloat()
            var a0 = 0f
            var p0 = Vec2(cx + r, cy)
            ops += PathOp.MoveTo(p0)
            for (i in 0 until arcs) {
                val a1 = if (i == arcs - 1) -TWO_PI else a0 + step
                val p1 = if (i == arcs - 1) Vec2(cx + r, cy) else Vec2(cx + r * cos(a1), cy + r * sin(a1))
                val c1 = Vec2(p0.x - kk * r * sin(a0), p0.y + kk * r * cos(a0))
                val c2 = Vec2(p1.x + kk * r * sin(a1), p1.y - kk * r * cos(a1))
                ops += PathOp.CubicTo(c1, c2, p1)
                a0 = a1
                p0 = p1
            }
            ops += PathOp.Close
            lastX = Float.NaN; lastY = Float.NaN
        }
    }
}
