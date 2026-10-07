package com.brushwork.paint.core

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * v1.7 (design §4.2, F4): a 2D affine map, pure Kotlin.
 * x' = a·x + c·y + tx, y' = b·x + d·y + ty (android.graphics.Matrix's convention: [a] is
 * MSCALE_X, [c] MSKEW_X, [b] MSKEW_Y, [d] MSCALE_Y), so [rotateAbout] by 90° turns (1, 0) into
 * (0, 1) as `Matrix.setRotate(90f)` does.
 *
 * The point editors map a group's CAPTURED points (and their tangent handles, as vectors through
 * [mapVector]) by one Affine2 per gesture event (I12); [toArray9] is the row-major 3×3 that
 * `VectorOps.transformed` and `ShapeAffine.mapped` take. The identity maps every point to a
 * bitwise-equal one (its products are exact).
 */
data class Affine2(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    /** [p] mapped (translation included). */
    fun map(p: Vec2): Vec2 = Vec2(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)

    /** The vector [v] mapped (no translation): tangent handles, offsets. */
    fun mapVector(v: Vec2): Vec2 = Vec2(a * v.x + c * v.y, b * v.x + d * v.y)

    /** This map after [o]: `(this * o).map(p) == this.map(o.map(p))` (up to rounding). */
    operator fun times(o: Affine2): Affine2 = Affine2(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty,
    )

    /** The determinant of the linear part (negative for a mirror). */
    val det: Float get() = a * d - b * c

    /** The inverse map, or null when this one is singular (det 0) or not finite. */
    fun inverse(): Affine2? {
        val dt = det.toDouble()
        if (dt == 0.0 || !dt.isFinite() || !isFinite()) return null
        val ia = d / dt
        val ib = -b / dt
        val ic = -c / dt
        val id = a / dt
        val itx = -(ia * tx + ic * ty)
        val ity = -(ib * tx + id * ty)
        // (+ 0.0 turns -0.0 into 0.0: the identity's inverse is IDENTITY.)
        fun f(v: Double): Float = (v + 0.0).toFloat()
        val r = Affine2(f(ia), f(ib), f(ic), f(id), f(itx), f(ity))
        return if (r.isFinite()) r else null
    }

    /** Row-major 3×3 `[a, c, tx, b, d, ty, 0, 0, 1]` (`android.graphics.Matrix.setValues`' order). */
    fun toArray9(): FloatArray = floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)

    /** True when every coefficient is finite. */
    fun isFinite(): Boolean = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && tx.isFinite() && ty.isFinite()

    /** True when this map has no translation and its linear part is the identity. */
    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = Affine2()

        fun translate(dx: Float, dy: Float): Affine2 = Affine2(tx = dx, ty = dy)

        /**
         * Scales by [sx] along the axis at [axisDeg] (degrees, screen convention: y down) and
         * [sy] across it, about [pivot]. axisDeg 0 is the plain axis-aligned scale.
         */
        fun scaleAbout(pivot: Vec2, sx: Float, sy: Float, axisDeg: Float = 0f): Affine2 {
            val lin = if (axisDeg == 0f) {
                Affine2(a = sx, d = sy)
            } else {
                // R(φ) · diag(sx, sy) · R(−φ)
                val (cs, sn) = cosSin(axisDeg)
                Affine2(
                    a = (sx * cs * cs + sy * sn * sn).toFloat(),
                    b = ((sx - sy) * cs * sn).toFloat(),
                    c = ((sx - sy) * cs * sn).toFloat(),
                    d = (sx * sn * sn + sy * cs * cs).toFloat(),
                )
            }
            return about(pivot, lin)
        }

        /** Turns by [deg] degrees about [pivot] (positive: clockwise on screen, like `Matrix.setRotate`). */
        fun rotateAbout(pivot: Vec2, deg: Float): Affine2 {
            val (cs, sn) = cosSin(deg)
            return about(pivot, Affine2(a = cs.toFloat(), b = sn.toFloat(), c = (-sn).toFloat(), d = cs.toFloat()))
        }

        /**
         * The map whose row-major 3×3 is [m] (`Matrix.getValues`' order); null when [m] is not
         * nine values or is projective (a non-zero perspective row).
         */
        fun fromArray9(m: FloatArray): Affine2? {
            if (m.size != 9) return null
            if (m[6] != 0f || m[7] != 0f) return null
            val w = m[8]
            if (w == 0f || !w.isFinite()) return null
            if (w == 1f) return Affine2(m[0], m[3], m[1], m[4], m[2], m[5])
            return Affine2(m[0] / w, m[3] / w, m[1] / w, m[4] / w, m[2] / w, m[5] / w)
        }

        /** [lin] (a linear map) applied about [pivot]: translate(pivot) · lin · translate(−pivot). */
        private fun about(pivot: Vec2, lin: Affine2): Affine2 {
            val px = pivot.x.toDouble()
            val py = pivot.y.toDouble()
            val tx = px - (lin.a * px + lin.c * py)
            val ty = py - (lin.b * px + lin.d * py)
            return lin.copy(tx = tx.toFloat(), ty = ty.toFloat())
        }

        /** cos and sin of [deg] degrees, exact for multiples of 90°. */
        private fun cosSin(deg: Float): Pair<Double, Double> {
            val r = deg.toDouble() % 360.0
            val q = r / 90.0
            if (q == floor(q)) {
                return when (((q.toInt() % 4) + 4) % 4) {
                    0 -> 1.0 to 0.0
                    1 -> 0.0 to 1.0
                    2 -> -1.0 to 0.0
                    else -> 0.0 to -1.0
                }
            }
            val rad = Math.toRadians(r)
            return cos(rad) to sin(rad)
        }
    }
}
