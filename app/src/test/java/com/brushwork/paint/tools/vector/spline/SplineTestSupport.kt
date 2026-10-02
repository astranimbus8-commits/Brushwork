package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorOps
import kotlin.math.min
import kotlin.random.Random

/** Shared helpers of the spline tests (pure JVM). */
internal object SplineTestSupport {

    /** [n] points in a 1000 px square; with [weights], log-uniform weights in [minWeight]..1 / [minWeight]. */
    fun randomPoints(rnd: Random, n: Int, weights: Boolean = false, widths: Boolean = false, minWeight: Double = 0.1): List<VSplinePoint> = List(n) {
        VSplinePoint(
            rnd.nextDouble(20.0, 980.0).toFloat(),
            rnd.nextDouble(20.0, 980.0).toFloat(),
            weight = if (weights) (minWeight * Math.pow(1.0 / (minWeight * minWeight), rnd.nextDouble())).toFloat() else 1f,
            width = if (widths) rnd.nextDouble(0.0, 3.0).toFloat() else 1f,
        )
    }

    /** The cubic segment [j] of [s] (Curve-tool geometry, as the renderer draws it). */
    fun segment(s: VSubpath, j: Int): Array<Vec2> =
        CurveGeometry.segment(VectorOps.curveAnchors(s), j, s.closed && s.anchors.size > 2, 0f, false)

    fun segmentCount(s: VSubpath): Int = CurveGeometry.segmentCount(s.anchors.size, s.closed)

    /** The converted curve as drawn, flattened finely. */
    fun polyline(s: VSubpath, tol: Float = 0.005f): List<Vec2> {
        val path = CurveGeometry.toPath(VectorOps.curveAnchors(s), s.closed, 0f, false)
        val poly = path.flatten(tol).first()
        return if (poly.closed) poly.points + poly.points[0] else poly.points
    }

    fun distanceToPolyline(q: Vec2, poly: List<Vec2>): Float {
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until poly.size) best = min(best, Geometry.distanceToSegment(q, poly[i - 1], poly[i]))
        return best
    }

    /** The spline's points at [count] even parameters over its domain (de Boor). */
    fun splineSamples(s: VSpline, count: Int): List<Vec2> {
        val d = NurbsGeometry.domain(s)
        return List(count) { i ->
            val t = d.start + (d.endInclusive - d.start) * i / (count - 1)
            val p = NurbsGeometry.pointAt(s, t)
            Vec2(p[0].toFloat(), p[1].toFloat())
        }
    }

    fun cubicAt(seg: Array<Vec2>, u: Float): Vec2 = VectorPath.cubicPoint(seg[0], seg[1], seg[2], seg[3], u)
}
