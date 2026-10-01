package com.brushwork.paint.vector.lift

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.transform.TransformState
import kotlin.math.abs
import kotlin.math.floor

/**
 * The document-space geometry of a transform of lifted vector objects (v1.5 §4.9, A2): what the
 * Transform tool shows (the floating preview drawn by the state's matrix) expressed as one 3x3
 * row-major map from where the objects are to where they go, so ✓ can map their geometry exactly
 * (`VectorOps.transformed`). Pure Kotlin (no android.graphics), so it is unit-tested on the JVM.
 *
 * The Transform tool draws the floating bitmap, whose pixel (0, 0) is the lift's source rect's
 * top-left corner, through [TransformState.affineValues] (or, distorted, the homography that sends
 * the source rectangle to [TransformState.corners], like `Matrix.setPolyToPoly`). The objects'
 * map is that matrix after a translation by minus the source rect's top-left corner.
 */
internal object LiftGeometry {
    /**
     * The map (3x3 row-major; a homography when [state] is distorted) taking the lifted objects
     * from the source rect at ([left], [top]) to where [state] puts them, or null when it is
     * degenerate or not finite. A distortion that can't be solved falls back to the state's
     * affine part, as the tool's preview does.
     */
    fun matrix(state: TransformState, left: Int, top: Int): FloatArray? {
        val m = (if (state.isDistorted) homography(state, left, top) else null) ?: affine(state.undistorted(), left, top)
        return m.takeIf { v -> v.all { it.isFinite() } && determinant(v) != 0.0 }?.let { v -> FloatArray(9) { v[it].toFloat() } }
    }

    /** The affine part: floating px -> document after document -> floating px. */
    private fun affine(state: TransformState, left: Int, top: Int): DoubleArray {
        val v = state.affineValues()
        val a = v[0].toDouble(); val b = v[1].toDouble(); val tx = v[2].toDouble()
        val c = v[3].toDouble(); val d = v[4].toDouble(); val ty = v[5].toDouble()
        return doubleArrayOf(a, b, tx - a * left - b * top, c, d, ty - c * left - d * top, 0.0, 0.0, 1.0)
    }

    /**
     * The projective map sending the source rect ([left], [top], srcW x srcH) to the state's
     * corners (TL, TR, BR, BL), or null when the quad is degenerate. Heckbert's square-to-quad
     * solution composed with the rect-to-unit-square scaling.
     */
    private fun homography(state: TransformState, left: Int, top: Int): DoubleArray? {
        val q = state.corners()
        val x0 = q[0].x.toDouble(); val y0 = q[0].y.toDouble()
        val x1 = q[1].x.toDouble(); val y1 = q[1].y.toDouble()
        val x2 = q[2].x.toDouble(); val y2 = q[2].y.toDouble()
        val x3 = q[3].x.toDouble(); val y3 = q[3].y.toDouble()
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        val g: Double
        val h: Double
        if (abs(sx) < 1e-12 && abs(sy) < 1e-12) {
            g = 0.0; h = 0.0
        } else {
            val dx1 = x1 - x2; val dx2 = x3 - x2
            val dy1 = y1 - y2; val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            if (den == 0.0 || !den.isFinite()) return null
            g = (sx * dy2 - dx2 * sy) / den
            h = (dx1 * sy - sx * dy1) / den
        }
        // Unit square -> quad.
        val a = x1 - x0 + g * x1; val b = x3 - x0 + h * x3; val c = x0
        val d = y1 - y0 + g * y1; val e = y3 - y0 + h * y3; val f = y0
        // Source rect -> unit square: u = (x - left) / w, v = (y - top) / h.
        val w = state.srcW.toDouble()
        val hh = state.srcH.toDouble()
        val m = doubleArrayOf(
            a / w, b / hh, c - a * left / w - b * top / hh,
            d / w, e / hh, f - d * left / w - e * top / hh,
            g / w, h / hh, 1.0 - g * left / w - h * top / hh,
        )
        val k = m[8]
        if (k == 0.0 || !k.isFinite()) return null
        for (i in m.indices) m[i] /= k
        return m.takeIf { v -> v.all { it.isFinite() } }
    }

    private fun determinant(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /**
     * When [m] is a translation by whole document pixels (within [eps]), the (dx, dy) it moves by;
     * otherwise null. Such a move can shift the cache's pixels instead of re-rendering them
     * (`VectorLayers.ShiftHint`).
     */
    fun wholePixelShift(m: FloatArray, eps: Float = 1e-3f): IntArray? {
        if (m.size < 9) return null
        val linear = abs(m[0] - 1f) < LINEAR_EPS && abs(m[1]) < LINEAR_EPS && abs(m[3]) < LINEAR_EPS && abs(m[4] - 1f) < LINEAR_EPS &&
            m[6] == 0f && m[7] == 0f && m[8] == 1f
        if (!linear) return null
        val dx = roundHalfUp(m[2])
        val dy = roundHalfUp(m[5])
        if (abs(m[2] - dx) > eps || abs(m[5] - dy) > eps) return null
        return intArrayOf(dx.toInt(), dy.toInt())
    }

    /** The exact translation matrix by ([dx], [dy]). */
    fun translation(dx: Float, dy: Float): FloatArray = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    /** [m] applied to ([x], [y]) (homographies divide by w). */
    fun map(m: FloatArray, x: Float, y: Float): Vec2 {
        val qx = m[0] * x + m[1] * y + m[2]
        val qy = m[3] * x + m[4] * y + m[5]
        val w = m[6] * x + m[7] * y + m[8]
        return if (w == 1f || w == 0f) Vec2(qx, qy) else Vec2(qx / w, qy / w)
    }

    private fun roundHalfUp(v: Float): Float = floor(v + 0.5f)

    /** The linear part of a "pure translation" may differ from the identity by float noise only. */
    private const val LINEAR_EPS = 1e-6f
}
