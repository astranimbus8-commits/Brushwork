package com.brushwork.paint.tools.vector.gen

import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VSubpath
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry of a generated curve (v1.8 item 6, design §4.4): [subpath] is what the Curve and
 * Polyline tools draw (Bézier anchors); [spline] is set for the Path tool (its control points),
 * null for the others.
 */
class CurveBuild(val subpath: VSubpath, val spline: VSpline?)

/**
 * Builds generated curves ([CurveGenerator], `vector/CurveGenerator.kt`) and recognises their
 * geometry (I16). Frozen signatures (design §4.4); area E (CURVES) owns the bodies.
 *
 * Foundation stub (F3): [build] gives every kind a valid polyline approximation of an
 * Archimedean spiral (sharp anchors; a spline through the same points for the Path tool), and
 * [matches] is always false. Nothing calls either yet: E's `CurveTool` places generated curves and
 * wires the reopen check (I16), so until then a stored generator is kept but unused.
 */
object CurveGenerators {
    /** Samples per turn of the stub's spiral when the generator says 0 ([CurveGenerator.density]). */
    private const val DEFAULT_PER_TURN = 8

    /** Most points of a stub build (MAX_TURNS turns at the densest). */
    private const val MAX_POINTS = 4096

    /** The geometry of [g] for a curve of [kind]: never empty, at least two points. */
    fun build(g: CurveGenerator, kind: CurveKind): CurveBuild {
        val s = g.sanitized() ?: CurveGenerator()
        val perTurn = if (s.density > 0) s.density else DEFAULT_PER_TURN
        val n = (ceil(s.turns * perTurn).toInt() + 1).coerceIn(2, MAX_POINTS)
        val start = Math.toRadians(s.angleDeg.toDouble())
        val dir = if (s.clockwise) -1.0 else 1.0
        val inner = s.inner.toDouble()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val f = i.toDouble() / (n - 1)
            val a = start + dir * f * s.turns * 2.0 * PI
            val r = s.size * (inner + (1.0 - inner) * f)
            xs[i] = (s.cx + r * cos(a)).toFloat()
            ys[i] = (s.cy + r * sin(a)).toFloat()
        }
        val anchors = List(n) { VAnchor(xs[it], ys[it], sharp = true) }
        val spline = if (kind == CurveKind.PATH) VSpline(List(n) { VSplinePoint(xs[it], ys[it]) }) else null
        return CurveBuild(VSubpath(anchors, closed = false), spline)
    }

    /** True when [path]'s geometry is its generator's within 0.01 px (I16). Stub: always false. */
    @Suppress("UNUSED_PARAMETER")
    fun matches(path: VPath): Boolean = false
}
