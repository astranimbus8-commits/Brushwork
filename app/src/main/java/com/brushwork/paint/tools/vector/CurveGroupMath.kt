package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.vector.VSplinePoint

/**
 * v1.7 (items 1 and 12, design §3.1(c) and §3.12): how a group edit of the curve tools maps the
 * points it captured at its start. Pure functions (no tool state); every call maps the CAPTURED
 * points, never the current ones, so a gesture never drifts.
 *
 * - Anchors: the position by [Affine2.map], a dragged (custom) tangent handle as a vector by
 *   [Affine2.mapVector]; automatic tangents stay automatic (they follow their neighbours, and
 *   they are linear in the positions, so mapping every point maps them exactly).
 * - Control points: the position only; weight, thickness and the sharp flag stay (thickness is
 *   a factor of the brush size and is never scaled, §3.12).
 */
internal object CurveGroupMath {

    /**
     * [base] with the anchors of [sel] (null = every anchor) mapped by [m], positions held to
     * ±[limit]. The identity returns [base] itself.
     */
    fun mappedAnchors(base: List<CurveAnchor>, sel: PointSelection?, m: Affine2, limit: Float): List<CurveAnchor> {
        if (m == Affine2.IDENTITY) return base
        val out = ArrayList<CurveAnchor>(base.size)
        for (i in base.indices) {
            val a = base[i]
            out += if (sel == null || i in sel) mapped(a, m, limit) else a
        }
        return out
    }

    /** [a] mapped by [m] (see the class docs). */
    fun mapped(a: CurveAnchor, m: Affine2, limit: Float): CurveAnchor {
        val p = m.map(a.pos)
        return a.copy(
            x = held(p.x, a.x, limit),
            y = held(p.y, a.y, limit),
            handleIn = a.handleIn?.let { finite(m.mapVector(it)) ?: it },
            handleOut = a.handleOut?.let { finite(m.mapVector(it)) ?: it },
        )
    }

    /** [base] with the control points of [sel] moved by [m] (positions held to the [VSplinePoint] range). */
    fun mappedPoints(base: List<VSplinePoint>, sel: PointSelection, m: Affine2): List<VSplinePoint> {
        if (m == Affine2.IDENTITY) return base
        val lim = com.brushwork.paint.vector.VSpline.MAX_COORD
        val out = ArrayList<VSplinePoint>(base.size)
        for (i in base.indices) {
            val p = base[i]
            if (i !in sel) { out += p; continue }
            val q = m.map(Vec2(p.x, p.y))
            out += p.copy(x = held(q.x, p.x, lim), y = held(q.y, p.y, lim))
        }
        return out
    }

    private fun held(v: Float, fallback: Float, limit: Float): Float = if (v.isFinite()) v.coerceIn(-limit, limit) else fallback

    private fun finite(v: Vec2): Vec2? = if (v.x.isFinite() && v.y.isFinite()) v else null
}
