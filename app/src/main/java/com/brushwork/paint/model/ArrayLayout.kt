package com.brushwork.paint.model

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.toCurveAnchor
import com.brushwork.paint.vector.VSubpath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.7 (item 3, I14): where an array's copies go. Pure (no bitmaps); A's export and E's cache
 * both use it. Each instance is a row-major 3×3 affine map (`[a, b, tx, c, d, ty, 0, 0, 1]`,
 * x' = a·x + b·y + tx, as `VectorOps.transformed` takes it), applied to the SOURCE in document
 * px. Instance 0 is the source itself (the exact identity), so an array of count 1 changes
 * nothing on screen. Angles are degrees, positive turning +x towards +y (clockwise on screen,
 * as `android.graphics.Matrix.setRotate`).
 *
 * - LINE: copy k = T(k·(relative·size + constant)).
 * - CIRCLE: copy k = R(centre, k·step); the step is sweep / count for a full turn (|sweep| =
 *   360°), else sweep / (count − 1). With rotateCopies off only the position turns: the copy is
 *   moved by where R takes the source's centre.
 * - CURVE: arc-length positions sₖ = k·L/(count − 1) on an open guide, k·L/count on a closed
 *   one; with a spacing sₖ = k·spacing and copies past the end are dropped. Copy k is moved by
 *   C(sₖ) − C(0) and, with alignToCurve, turned by θ(sₖ) − θ(0) about the source's centre. No
 *   guide (or a guide of length 0) places the source alone.
 * - TRANSFORM: copy k = Mᵏ, M = T(pivot)·T(move)·R(turn)·S(scale)·T(−pivot).
 *
 * [source] is the source's CURRENT bounds, so relative offsets and the default centre / pivot
 * follow edits of the source, as in Blender.
 */
object ArrayLayout {
    /** Flattening tolerance of a CURVE guide, px. */
    private const val GUIDE_FLATTEN = 0.25f

    /** One row-major 3×3 affine per instance; [0] is the identity. [source] = the source's current bounds (document px). */
    fun matrices(spec: ArraySpec, source: RectF): List<FloatArray> {
        val s = spec.sanitized()
        val n = s.count
        if (n <= 1) return listOf(identity())
        return when (s.mode) {
            ArrayMode.LINE -> line(s, source, n)
            ArrayMode.CIRCLE -> circle(s, source, n)
            ArrayMode.CURVE -> curve(s, source, n)
            ArrayMode.TRANSFORM -> transform(s, source, n)
        }
    }

    /** Everything the instances of [source] cover: the union of the mapped source rectangle over [matrices]. */
    fun bounds(spec: ArraySpec, source: RectF): RectF {
        val out = RectF(source)
        if (source.isEmpty) return out
        val xs = floatArrayOf(source.left, source.right, source.right, source.left)
        val ys = floatArrayOf(source.top, source.top, source.bottom, source.bottom)
        for (m in matrices(spec, source)) {
            for (i in 0 until 4) {
                val x = m[0] * xs[i] + m[1] * ys[i] + m[2]
                val y = m[3] * xs[i] + m[4] * ys[i] + m[5]
                if (!x.isFinite() || !y.isFinite()) continue
                out.left = min(out.left, x); out.right = max(out.right, x)
                out.top = min(out.top, y); out.bottom = max(out.bottom, y)
            }
        }
        return out
    }

    /** The CIRCLE centre of [spec] for [source]: its own, else 1.2 × the source's larger side below the source's centre. */
    fun center(spec: ArraySpec, source: RectF): Pair<Float, Float> {
        val side = max(source.width(), source.height())
        return (spec.centerX ?: source.centerX()) to (spec.centerY ?: (source.centerY() + 1.2f * side))
    }

    /** The TRANSFORM pivot of [spec] for [source]: its own, else the source's centre. */
    fun pivot(spec: ArraySpec, source: RectF): Pair<Float, Float> =
        (spec.pivotX ?: source.centerX()) to (spec.pivotY ?: source.centerY())

    // ------------------------------------------------------------------ modes

    private fun line(s: ArraySpec, source: RectF, n: Int): List<FloatArray> {
        val dx = s.relativeX.toDouble() * source.width() + s.constantX
        val dy = s.relativeY.toDouble() * source.height() + s.constantY
        return List(n) { k -> if (k == 0) identity() else translate(k * dx, k * dy) }
    }

    private fun circle(s: ArraySpec, source: RectF, n: Int): List<FloatArray> {
        val (cx, cy) = center(s, source)
        val full = abs(s.sweepDeg) >= 360f - 1e-3f
        val step = if (full) s.sweepDeg.toDouble() / n else s.sweepDeg.toDouble() / (n - 1)
        val sx = source.centerX().toDouble()
        val sy = source.centerY().toDouble()
        return List(n) { k ->
            if (k == 0) return@List identity()
            val r = rotateAbout(cx.toDouble(), cy.toDouble(), k * step)
            if (s.rotateCopies) toFloat(r)
            else {
                // Only the position turns: the source's centre goes where R takes it.
                val qx = r[0] * sx + r[1] * sy + r[2]
                val qy = r[3] * sx + r[4] * sy + r[5]
                translate(qx - sx, qy - sy)
            }
        }
    }

    private fun curve(s: ArraySpec, source: RectF, n: Int): List<FloatArray> {
        val guide = s.guide ?: return listOf(identity())
        val pts = flatten(guide)
        if (pts.size < 2) return listOf(identity())
        val closed = guide.closed && guide.anchors.size > 2
        val poly = if (closed && pts.first() != pts.last()) pts + pts.first() else pts
        val cum = DoubleArray(poly.size)
        for (i in 1 until poly.size) cum[i] = cum[i - 1] + hypot((poly[i].x - poly[i - 1].x).toDouble(), (poly[i].y - poly[i - 1].y).toDouble())
        val length = cum.last()
        if (!(length > 0.0)) return listOf(identity())
        val positions = ArrayList<Double>(n)
        if (s.spacing > 0f) {
            for (k in 0 until n) {
                val at = k * s.spacing.toDouble()
                if (at > length + 1e-6) break
                positions += min(at, length)
            }
        } else {
            val step = if (closed) length / n else length / (n - 1)
            for (k in 0 until n) positions += min(k * step, length)
        }
        val (x0, y0, a0) = sample(poly, cum, 0.0)
        val cx = source.centerX().toDouble()
        val cy = source.centerY().toDouble()
        return positions.mapIndexed { k, at ->
            if (k == 0) return@mapIndexed identity()
            val (x, y, a) = sample(poly, cum, at)
            if (!s.alignToCurve) translate(x - x0, y - y0)
            else toFloat(multiply(translateD(x - x0, y - y0), rotateAbout(cx, cy, Math.toDegrees(a - a0))))
        }
    }

    private fun transform(s: ArraySpec, source: RectF, n: Int): List<FloatArray> {
        val (px, py) = pivot(s, source)
        val p = doubleArrayOf(px.toDouble(), py.toDouble())
        val sc = s.scale.toDouble()
        val t = Math.toRadians(s.turnDeg.toDouble())
        val co = cos(t); val si = sin(t)
        // M = T(pivot)·T(move)·R(turn)·S(scale)·T(−pivot)
        val a = co * sc; val b = -si * sc; val c = si * sc; val d = co * sc
        val tx = p[0] + s.moveX - (a * p[0] + b * p[1])
        val ty = p[1] + s.moveY - (c * p[0] + d * p[1])
        val m = doubleArrayOf(a, b, tx, c, d, ty, 0.0, 0.0, 1.0)
        val out = ArrayList<FloatArray>(n)
        out += identity()
        var cur = m
        for (k in 1 until n) {
            out += toFloat(cur)
            cur = multiply(m, cur)
        }
        return out
    }

    // ------------------------------------------------------------------ the guide

    /** The guide as one polyline (the Curve tool's geometry: tension 0, handles honoured). */
    private fun flatten(g: VSubpath): List<Vec2> {
        if (g.anchors.isEmpty()) return emptyList()
        val polys = CurveGeometry.toPath(g.anchors.map { it.toCurveAnchor() }, g.closed, 0f, false).flatten(GUIDE_FLATTEN)
        return polys.firstOrNull()?.points ?: emptyList()
    }

    /** Position and direction (radians) at arc length [at] of [poly] ([cum]: cumulative lengths). */
    private fun sample(poly: List<Vec2>, cum: DoubleArray, at: Double): Triple<Double, Double, Double> {
        var i = 1
        while (i < poly.lastIndex && cum[i] < at) i++
        // The segment [i − 1, i] holds [at]; skip zero-length segments for the direction.
        val a = poly[i - 1]; val b = poly[i]
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen > 0.0) ((at - cum[i - 1]) / segLen).coerceIn(0.0, 1.0) else 0.0
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        var j = i
        while (j < poly.lastIndex && cum[j] - cum[j - 1] <= 0.0) j++
        val dir = atan2((poly[j].y - poly[j - 1].y).toDouble(), (poly[j].x - poly[j - 1].x).toDouble())
        return Triple(x, y, dir)
    }

    // ------------------------------------------------------------------ 3×3 helpers

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun translate(dx: Double, dy: Double) = floatArrayOf(1f, 0f, dx.toFloat(), 0f, 1f, dy.toFloat(), 0f, 0f, 1f)

    private fun translateD(dx: Double, dy: Double) = doubleArrayOf(1.0, 0.0, dx, 0.0, 1.0, dy, 0.0, 0.0, 1.0)

    /** Rotation by [deg] about (cx, cy). */
    private fun rotateAbout(cx: Double, cy: Double, deg: Double): DoubleArray {
        val r = Math.toRadians(deg)
        val co = cos(r); val si = sin(r)
        return doubleArrayOf(co, -si, cx - co * cx + si * cy, si, co, cy - si * cx - co * cy, 0.0, 0.0, 1.0)
    }

    /** p·q (q applied first). */
    private fun multiply(p: DoubleArray, q: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            out[3 * r + c] = p[3 * r] * q[c] + p[3 * r + 1] * q[3 + c] + p[3 * r + 2] * q[6 + c]
        }
        return out
    }

    private fun toFloat(m: DoubleArray) = FloatArray(9) { m[it].toFloat() }
}
