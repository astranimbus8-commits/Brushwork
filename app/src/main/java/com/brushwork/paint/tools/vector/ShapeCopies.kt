package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Vec2

/**
 * v1.8 (I15, design §3.1): a shape's symmetry copies as plain paint items, for the Shape tool's
 * previews and commits. A map is row-major 3×3 (document px), the identity first, as
 * `ObjectCopies.mapsAt` and `CopyMaps` give them; an affine map moves the shape's paths exactly
 * (their control points), a perspective map (Perspective array) moves the flattened paths.
 *
 * The rulers' affine maps are isometries (mirror, rotation, kaleidoscope, array translations), so
 * a mapped path stroked with the shape's width is exactly the source drawn under
 * `canvas.concat(map)`, as the vector renderer and `ObjectCopies.drawEach` draw a shape layer's
 * copies. Perspective copies keep the stroke width (they are not foreshortened).
 *
 * - [eachSpecs]: one spec per copy, the source first (a shape layer's or a vector object's
 *   copies, drawn one after the other as the renderer draws them).
 * - [unionSpec]: ONE spec holding every copy's paths (painted into a raster layer: the union is
 *   drawn in one call per item, so translucent copies never darken where they overlap).
 * - [mappedAll]: a guide (an outline the brush follows) with every copy.
 *
 * Pure geometry; thread-safe.
 */
internal object ShapeCopies {

    /** Flattening tolerance (document px) of a path moved by a perspective map. */
    private const val FLATTEN = 0.25f

    /**
     * The plain items of [o] once per map of [maps], in their order (the source first); the
     * source's alone ([ShapeOutlines.paintParts]) without maps. [brush]: the outline is painted
     * with the brush (only what stays plain is drawn).
     */
    fun eachSpecs(o: ShapeObject, maps: List<FloatArray>, brush: Boolean): List<VectorPaintSpec> {
        val parts = ShapeOutlines.paintParts(o, brush) ?: return emptyList()
        if (maps.size < 2) return listOfNotNull(parts.spec())
        return maps.mapNotNull { m -> parts.spec { p -> mapped(p, m) } }
    }

    /**
     * The plain items of [o] with every copy of [maps] in ONE spec: each item (fill, line,
     * arrowheads) is the union of its copies' paths, every copy turning the same way, so the
     * non-zero fill covers each overlap once. The source's spec alone without maps.
     */
    fun unionSpec(o: ShapeObject, maps: List<FloatArray>, brush: Boolean): VectorPaintSpec? {
        val parts = ShapeOutlines.paintParts(o, brush) ?: return null
        if (maps.size < 2) return parts.spec()
        return parts.spec { p -> mappedAll(p, maps) }
    }

    /** [path] once per map of [maps] (the source first), as one path; [path] itself without maps. */
    fun mappedAll(path: VectorPath, maps: List<FloatArray>): VectorPath {
        if (maps.size < 2) return path
        val ops = ArrayList<PathOp>(path.ops.size * maps.size)
        for (m in maps) ops += mapped(path, m).ops
        return VectorPath(ops)
    }

    /**
     * [path] moved by [m]: an affine map moves its points and control points (exact), a
     * perspective one its flattened outline. A map that turns the plane over (a mirror) reverses
     * every contour, so a mirrored fill winds as its source does. [path] itself for the identity.
     */
    fun mapped(path: VectorPath, m: FloatArray): VectorPath {
        if (m.size < 9 || isIdentity(m)) return path
        val moved = if (isAffine(m)) {
            path.transformed { p -> Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5]) }
        } else {
            perspective(path, m)
        }
        return if (turnsOver(path, m)) reversed(moved) else moved
    }

    private fun isIdentity(m: FloatArray): Boolean =
        m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f && m[6] == 0f && m[7] == 0f && m[8] == 1f

    private fun isAffine(m: FloatArray): Boolean = m[6] == 0f && m[7] == 0f && m[8] == 1f

    /** The flattened [path] through the homography [m] (a point at or behind the horizon is dropped). */
    private fun perspective(path: VectorPath, m: FloatArray): VectorPath {
        val ops = ArrayList<PathOp>()
        for (poly in path.flatten(FLATTEN)) {
            var first = true
            for (p in poly.points) {
                val w = m[6] * p.x + m[7] * p.y + m[8]
                if (!(w > 1e-6f)) continue
                val q = Vec2((m[0] * p.x + m[1] * p.y + m[2]) / w, (m[3] * p.x + m[4] * p.y + m[5]) / w)
                if (!q.x.isFinite() || !q.y.isFinite()) continue
                ops += if (first) PathOp.MoveTo(q) else PathOp.LineTo(q)
                first = false
            }
            if (!first && poly.closed) ops += PathOp.Close
        }
        return VectorPath(ops)
    }

    /**
     * True when [m] turns [path] over: the sign of its Jacobian (det(M) / w³ for a homography,
     * the linear part's determinant for an affine map) at the path's centre is negative.
     */
    private fun turnsOver(path: VectorPath, m: FloatArray): Boolean {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (isAffine(m)) return det < 0f
        val b = path.controlBounds() ?: return det < 0f
        val x = (b.left + b.right) / 2f
        val y = (b.top + b.bottom) / 2f
        val w = m[6] * x + m[7] * y + m[8]
        return det * w < 0f
    }

    /** [path] with every contour run backwards (same points, same curves; a Close stays a Close). */
    private fun reversed(path: VectorPath): VectorPath {
        val out = ArrayList<PathOp>(path.ops.size)
        for (c in ShapeOutlines.contoursOf(path)) {
            val ops = c.ops
            val start = (ops.firstOrNull() as? PathOp.MoveTo)?.p
            if (start == null) { out += ops; continue }
            val closed = ops.last() == PathOp.Close
            val segments = ops.subList(1, ops.size).filter { it != PathOp.Close }
            // ends[i]: where segment i starts (ends[0] is the contour's start).
            val ends = ArrayList<Vec2>(segments.size + 1)
            ends += start
            for (s in segments) ends += when (s) {
                is PathOp.LineTo -> s.p
                is PathOp.CubicTo -> s.p
                is PathOp.MoveTo -> s.p
                PathOp.Close -> ends.last()
            }
            out += PathOp.MoveTo(ends.last())
            for (i in segments.indices.reversed()) {
                val s = segments[i]
                out += if (s is PathOp.CubicTo) PathOp.CubicTo(s.c2, s.c1, ends[i]) else PathOp.LineTo(ends[i])
            }
            if (closed) out += PathOp.Close
        }
        return VectorPath(out)
    }
}
