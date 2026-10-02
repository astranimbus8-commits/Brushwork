package com.brushwork.paint.vector.geom

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.Polyline
import com.brushwork.paint.tools.vector.ShapeHandle
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Geometry helpers for mapping objects (v1.5 A1, `VectorOps.transformed`):
 * - [subdivided]: a path as explicit cubics, each split into pieces before a homography maps its
 *   anchors and handles (a projective map bends a cubic: mapping only its four control points
 *   drifts away from the true image; four pieces keep it within a fraction of a pixel for
 *   Distort-sized perspective);
 * - [mirroredShape]: a shape under a reflection (Flip) stays a shape when it is symmetric (or has
 *   custom points, which are mirrored), checked numerically against the mirrored outline.
 */
object ObjectMapping {
    /** Pieces each cubic is split into before a homography. */
    const val HOMOGRAPHY_PIECES = 4

    /**
     * [p] with every curved segment split into [pieces] cubics (de Casteljau), as sharp anchors
     * with explicit handles (broken tangents, the same geometry). Straight segments stay one
     * piece (a projective map keeps lines straight); polylines are returned as they are. Anchor
     * thickness factors are blended like the renderer does (smoothstep along arc length).
     */
    fun subdivided(p: VPath, pieces: Int = HOMOGRAPHY_PIECES): VPath {
        if (p.polyline || pieces <= 1) return p
        val subs = p.subpaths.map { s -> subdivide(s, p.tension, pieces) }
        return p.copy(subpaths = subs)
    }

    private class A(val x: Float, val y: Float, val w: Float) {
        var inX: Float? = null
        var inY: Float? = null
        var outX: Float? = null
        var outY: Float? = null
        fun build() = VAnchor(x, y, sharp = true, inX = inX, inY = inY, outX = outX, outY = outY, width = w)
    }

    private fun subdivide(s: VSubpath, tension: Float, pieces: Int): VSubpath {
        val anchors = VectorOps.curveAnchors(s)
        val n = anchors.size
        if (n < 2) return s
        val closed = s.closed && n > 2
        val segs = CurveGeometry.segmentCount(n, closed)
        fun w(i: Int): Float = anchors[i % n].width.let { if (it.isFinite()) it else 1f }
        val out = ArrayList<A>()
        out += A(anchors[0].x, anchors[0].y, w(0))
        for (seg in 0 until segs) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, closed, tension, false)
            val wa = w(seg); val wb = w(seg + 1)
            val straight = Geometry2.onChord(c1, p0, p1) && Geometry2.onChord(c2, p0, p1)
            val k = if (straight) 1 else pieces
            // Arc length at the split parameters (for the thickness blend).
            val lengths = arcLengths(p0, c1, c2, p1, k)
            val total = lengths[k]
            var q0 = p0; var q1 = c1; var q2 = c2; val q3 = p1
            var t0 = 0f
            for (i in 1..k) {
                val start = out.last()
                if (i == k) {
                    start.outX = q1.x - q0.x; start.outY = q1.y - q0.y
                    val end = if (closed && seg == segs - 1) out[0] else A(q3.x, q3.y, wb).also { out += it }
                    end.inX = q2.x - q3.x; end.inY = q2.y - q3.y
                } else {
                    // Split the remaining curve [t0, 1] at the global parameter i / k.
                    val t = i.toFloat() / k
                    val local = (t - t0) / (1f - t0)
                    val ab = q0.lerp(q1, local); val bc = q1.lerp(q2, local); val cd = q2.lerp(q3, local)
                    val abc = ab.lerp(bc, local); val bcd = bc.lerp(cd, local)
                    val mid = abc.lerp(bcd, local)
                    start.outX = ab.x - q0.x; start.outY = ab.y - q0.y
                    val f = if (total > 0f) (lengths[i] / total).coerceIn(0f, 1f) else t
                    val e = f * f * (3f - 2f * f)
                    val a = A(mid.x, mid.y, wa + (wb - wa) * e)
                    a.inX = abc.x - mid.x; a.inY = abc.y - mid.y
                    out += a
                    q0 = mid; q1 = bcd; q2 = cd
                    t0 = t
                }
            }
        }
        return VSubpath(out.map { it.build() }, closed)
    }

    /** Arc length of the cubic from 0 to i / [k] for i = 0..k (flattened finely). */
    private fun arcLengths(p0: Vec2, c1: Vec2, c2: Vec2, p1: Vec2, k: Int): FloatArray {
        val steps = 16 * k
        val out = FloatArray(k + 1)
        var prev = p0
        var acc = 0f
        for (j in 1..steps) {
            val q = VectorPath.cubicPoint(p0, c1, c2, p1, j.toFloat() / steps)
            acc += prev.distanceTo(q)
            prev = q
            if (j % 16 == 0) out[j / 16] = acc
        }
        return out
    }

    private object Geometry2 {
        fun onChord(c: Vec2, a: Vec2, b: Vec2): Boolean = com.brushwork.paint.core.Geometry.distanceToSegment(c, a, b) < 1e-3f
    }

    // ------------------------------------------------------------------ reflections

    /**
     * [s] mapped by [m] (3x3 row-major) when [m] is a similarity WITH a reflection and the result
     * can stay a shape: a shape symmetric about one of its own axes (rectangle, ellipse, regular
     * polygon, star...) mirrors into the same shape turned the other way; custom points are
     * mirrored themselves. Null when [m] is not such a map or no candidate matches the mirrored
     * outline (within 0.01 px per 100 px of size).
     */
    fun mirroredShape(s: VShape, m: FloatArray): VShape? {
        if (m.size < 9 || m[6] != 0f || m[7] != 0f || m[8] == 0f) return null
        val k = 1f / m[8]
        val a = m[0] * k; val b = m[1] * k; val c = m[3] * k; val d = m[4] * k
        val scale = sqrt(a * a + c * c)
        if (!(scale > 0f) || !scale.isFinite()) return null
        val eps = 1e-4f * scale
        // A reflection-similarity: [[s cos φ, s sin φ], [s sin φ, -s cos φ]].
        if (abs(a + d) > eps || abs(b - c) > eps) return null
        val o = s.shape
        val phi = Math.toDegrees(atan2(c.toDouble(), a.toDouble())).toFloat()
        val cx = m[0] * o.cx + m[1] * o.cy + m[2]
        val cy = m[3] * o.cx + m[4] * o.cy + m[5]
        val center = Vec2(cx * k, cy * k)
        val base = o.copy(
            cx = center.x, cy = center.y, w = o.w * scale, h = o.h * scale,
            strokeWidth = o.strokeWidth * scale, cornerRadius = o.cornerRadius * scale,
            // (A brush outline's tip mirrors with the shape.)
            brushPreset = o.brushPreset?.let { bp ->
                VectorOps.turnedBrush(if (scale == 1f) bp else bp.copy(size = bp.size * scale, taperStart = bp.taperStart * scale, taperEnd = bp.taperEnd * scale), floatArrayOf(a, b, c, d))
            },
        )
        val candidates = ArrayList<com.brushwork.paint.tools.vector.ShapeObject>(3)
        if (o.points != null) {
            // Custom points: mirror them across the box's vertical axis.
            val pts = o.points.map { p ->
                p.copy(x = -p.x, handleIn = p.handleIn?.let { ShapeHandle(-it.x, it.y) }, handleOut = p.handleOut?.let { ShapeHandle(-it.x, it.y) })
            }
            candidates += base.copy(rotation = norm(phi - o.rotation + 180f), points = pts)
        } else {
            candidates += base.copy(rotation = norm(phi - o.rotation + 180f)) // symmetric about its vertical axis
            candidates += base.copy(rotation = norm(phi - o.rotation))        // about its horizontal axis
        }
        val target = mapped(ShapeOutlines.outline(o), m)
        val tol = max(0.01f, 1e-4f * max(base.w, base.h))
        for (cand in candidates) {
            if (sameCurve(ShapeOutlines.outline(cand), target, tol) && sameArrow(o, cand, m, tol)) return s.copy(shape = cand)
        }
        return null
    }

    private fun norm(deg: Float): Float {
        var r = deg % 360f
        if (r < -180f) r += 360f
        if (r >= 180f) r -= 360f
        return r
    }

    private fun mapped(p: VectorPath, m: FloatArray): VectorPath = p.transformed { q ->
        val w = m[6] * q.x + m[7] * q.y + m[8]
        Vec2((m[0] * q.x + m[1] * q.y + m[2]) / w, (m[3] * q.x + m[4] * q.y + m[5]) / w)
    }

    private fun sameArrow(o: com.brushwork.paint.tools.vector.ShapeObject, cand: com.brushwork.paint.tools.vector.ShapeObject, m: FloatArray, tol: Float): Boolean {
        if (o.type != com.brushwork.paint.tools.vector.ShapeType.ARROW) return true
        val a = ShapeOutlines.arrow(o)
        val b = ShapeOutlines.arrow(cand)
        return sameCurve(b.stroke, mapped(a.stroke, m), tol) && sameCurve(b.fill, mapped(a.fill, m), tol)
    }

    /** True when [a] and [b] trace the same curves (each flattened point lies within [tol] of the other). */
    private fun sameCurve(a: VectorPath, b: VectorPath, tol: Float): Boolean {
        val pa = a.flatten(0.02f)
        val pb = b.flatten(0.02f)
        if (pa.size != pb.size) return false
        if (pa.isEmpty()) return true
        val slack = tol + 0.05f // flattening of both sides
        return within(pa, pb, slack) && within(pb, pa, slack)
    }

    /**
     * True when the points of [from] lie within [tol] of [to]. At most [MAX_CHECKED] points,
     * spread evenly, are checked (a large ellipse flattens to hundreds of points: checking each
     * against every segment of the other would make flipping a layer of shapes slow); a shape
     * that doesn't mirror into the candidate misses it by far more than [tol] along whole
     * stretches of its outline, so the decision stays the same.
     */
    private fun within(from: List<Polyline>, to: List<Polyline>, tol: Float): Boolean {
        val total = from.sumOf { it.points.size }
        val step = max(1, (total + MAX_CHECKED - 1) / MAX_CHECKED)
        var k = 0
        for (poly in from) for (p in poly.points) {
            if (k++ % step != 0) continue
            if (VectorOps.distanceToOutline(to, p) > tol) return false
        }
        return true
    }

    /** Points of an outline checked against the other one (see [within]). */
    private const val MAX_CHECKED = 160
}
