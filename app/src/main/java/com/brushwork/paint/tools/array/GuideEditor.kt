package com.brushwork.paint.tools.array

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.toCurveAnchor
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath

/**
 * The guide of a CURVE array (v1.7 item 3, §3.3 a; area E): a snapshot in document px, drawn
 * like a Curve tool path with tension 0 (`ArrayLayout` flattens it so).
 *
 * - "Draw guide": a finger stroke fitted into at most [ArraySpec.MAX_GUIDE_ANCHORS] smooth
 *   anchors ([fitStroke]: the fewest points a Douglas-Peucker pass keeps, automatic tangents).
 * - "Use a path": the first subpath of a vector path, COPIED (never a link: later edits of the
 *   path do not move the array) with every handle baked ([fromPath]), so the guide follows the
 *   path's exact shape whatever its tension or polyline flag; a path with more anchors than a
 *   guide keeps is flattened and fitted.
 * - The canvas handles move one anchor at a time ([moved]; its handles are offsets and move
 *   with it).
 */
internal object GuideEditor {
    /** The first Douglas-Peucker tolerance of a fit, px; it grows until the guide fits. */
    private const val FIRST_TOLERANCE = 1.5f

    /** Flattening tolerance of a path refitted into a guide, px. */
    private const val FLATTEN = 0.5f

    /**
     * The stroke [points] (document px, in drawing order) as a guide of at most [maxAnchors]
     * smooth anchors; null for fewer than two distinct points or a stroke shorter than 2 px.
     */
    fun fitStroke(points: List<Vec2>, closed: Boolean = false, maxAnchors: Int = ArraySpec.MAX_GUIDE_ANCHORS): VSubpath? {
        val pts = distinct(points)
        if (pts.size < 2) return null
        var length = 0f
        for (i in 1 until pts.size) length += pts[i].distanceTo(pts[i - 1])
        if (!(length >= 2f)) return null
        val limit = maxAnchors.coerceAtLeast(2)
        var tol = FIRST_TOLERANCE
        var kept = simplify(pts, tol)
        while (kept.size > limit) {
            tol *= 1.5f
            kept = simplify(pts, tol)
        }
        // A closed outline repeats its first point at the end: the guide closes it instead.
        if (closed && kept.size > 3 && kept.first().distanceTo(kept.last()) < 1e-3f) kept = kept.dropLast(1)
        return VSubpath(kept.map { VAnchor(it.x, it.y) }, closed = closed && kept.size > 2)
    }

    /**
     * The first subpath of [path] as a guide: each anchor keeps its place and gets the handles
     * the path draws it with (`CurveGeometry.handles` at the path's tension; chord thirds for a
     * polyline), as a sharp anchor with both handles set, which the guide draws as is. Null for
     * a path without a subpath of at least two anchors.
     */
    fun fromPath(path: VPath): VSubpath? {
        val sub = path.subpaths.firstOrNull() ?: return null
        val n = sub.anchors.size
        if (n < 2) return null
        val closed = sub.closed && n > 2
        val ca = sub.anchors.map { it.toCurveAnchor() }
        val baked = List(n) { i ->
            val (hIn, hOut) = if (path.polyline) chordThirds(ca, i, closed) else CurveGeometry.handles(ca, i, closed, path.tension)
            VAnchor(ca[i].x, ca[i].y, sharp = true, inX = hIn.x, inY = hIn.y, outX = hOut.x, outY = hOut.y)
        }
        if (n <= ArraySpec.MAX_GUIDE_ANCHORS) return VSubpath(baked, closed)
        val flat = CurveGeometry.toPath(baked.map { it.toCurveAnchor() }, closed, 0f, false).flatten(FLATTEN).firstOrNull()?.points ?: return null
        return fitStroke(flat, closed)
    }

    /** [guide] with anchor [i] at [p] (its handles move with it). */
    fun moved(guide: VSubpath, i: Int, p: Vec2): VSubpath {
        if (i !in guide.anchors.indices || !p.x.isFinite() || !p.y.isFinite()) return guide
        return guide.copy(anchors = guide.anchors.mapIndexed { k, a -> if (k == i) a.copy(x = p.x, y = p.y) else a })
    }

    /** The guide drawn as one polyline (document px), as `ArrayLayout` measures it. */
    fun polyline(guide: VSubpath): List<Vec2> {
        if (guide.anchors.isEmpty()) return emptyList()
        val closed = guide.closed && guide.anchors.size > 2
        val poly = CurveGeometry.toPath(guide.anchors.map { it.toCurveAnchor() }, closed, 0f, false).flatten(FLATTEN).firstOrNull()?.points ?: return emptyList()
        return if (closed && poly.isNotEmpty() && poly.first() != poly.last()) poly + poly.first() else poly
    }

    // ------------------------------------------------------------------ internals

    /** A polyline segment's handles: a third of the way to each neighbour (what `CurveGeometry.segment` draws). */
    private fun chordThirds(a: List<CurveAnchor>, i: Int, closed: Boolean): Pair<Vec2, Vec2> {
        val n = a.size
        val p = a[i].pos
        val prev = if (i > 0) a[i - 1].pos else if (closed) a[n - 1].pos else null
        val next = if (i < n - 1) a[i + 1].pos else if (closed) a[0].pos else null
        return (prev?.let { (it - p) / 3f } ?: Vec2.ZERO) to (next?.let { (it - p) / 3f } ?: Vec2.ZERO)
    }

    /** [points] without non-finite points and without repeats of the previous point. */
    private fun distinct(points: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>(points.size)
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (out.isEmpty() || out.last().distanceTo(p) > 1e-3f) out += p
        }
        return out
    }

    /** Douglas-Peucker simplification of an open polyline (both ends kept). */
    private fun simplify(pts: List<Vec2>, tol: Float): List<Vec2> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.lastIndex] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.lastIndex))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast().let { it[0] to it[1] }
            if (to - from < 2) continue
            var worst = -1f
            var at = -1
            for (i in from + 1 until to) {
                val d = Geometry.distanceToSegment(pts[i], pts[from], pts[to])
                if (d > worst) { worst = d; at = i }
            }
            if (at >= 0 && worst > tol) {
                keep[at] = true
                stack.addLast(intArrayOf(from, at))
                stack.addLast(intArrayOf(at, to))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }
}
