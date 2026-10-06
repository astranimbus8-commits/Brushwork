package com.brushwork.paint.vector

import android.graphics.RectF
import com.brushwork.paint.brush.StrokeRaster
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v1.7 (item 18, F1): the geometry of a [VStroke]'s symmetry copies ([VStroke.copies]) for the
 * frozen readers in the `vector` package. Copy k paints the stroke's dabs mapped by `copies[k]` as
 * `DabMapping` stamps them: the position mapped, the size × √|det J| at the dab (J the map's
 * Jacobian there). Every reader uses the union over the maps (the identity is the first one), and
 * a stroke without copies takes the reader's v1.6 code unchanged.
 *
 * Maps are row-major 3×3 (affine or, for the perspective array, a homography), as
 * `VectorOps.transformed` takes them. Pure arithmetic; thread-safe.
 */
object StrokeCopies {
    /** Most maps a stroke keeps (the symmetry ruler caps its copies at this). */
    const val MAX = 256

    /** A map whose determinant is smaller than this is singular (it would squash a copy to a line). */
    private const val MIN_DET = 1e-12

    /** Coefficients this large come from damaged data. */
    private const val MAX_COEF = 1e7f

    /** True when [m] is a usable map: 9 finite values of moderate size, invertible. */
    fun isUsable(m: FloatArray): Boolean {
        if (m.size != 9) return false
        for (v in m) if (!v.isFinite() || abs(v) > MAX_COEF) return false
        val d = det(m)
        return d.isFinite() && abs(d) >= MIN_DET
    }

    /** [copies] with the unusable maps dropped and at most [MAX] kept; the same instance when nothing changes. */
    fun sanitized(copies: List<FloatArray>): List<FloatArray> {
        if (copies.isEmpty()) return copies
        if (copies.size <= MAX && copies.all { isUsable(it) }) return copies
        return copies.filter { isUsable(it) }.take(MAX)
    }

    /** True when [a] and [b] hold the same maps (bit-exact values, as stored). */
    fun sameMaps(a: List<FloatArray>, b: List<FloatArray>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (!a[i].contentEquals(b[i])) return false
        return true
    }

    /**
     * The copies of a stroke that a document map [m] carried along with the stroke: each becomes
     * M·Ck·M⁻¹, so a re-rendered copy lies where [m] puts the old one. Unchanged when [m] is not
     * invertible (the stroke is then squashed anyway) and for a stroke without copies.
     */
    fun conjugated(copies: List<FloatArray>, m: FloatArray): List<FloatArray> {
        if (copies.isEmpty() || m.size < 9) return copies
        val inv = inverse(m) ?: return copies
        return copies.map { c -> normalized(multiply(multiply(m, c), inv)) }
    }

    /** The copy of a dab chain [d] ((x, y, radius) triples) that the map [m] paints. */
    fun mappedDabs(d: FloatArray, m: FloatArray): FloatArray {
        val out = FloatArray(d.size)
        var i = 0
        while (i + 2 < d.size) {
            val x = d[i]; val y = d[i + 1]
            val w = m[6] * x + m[7] * y + m[8]
            val iw = if (w != 0f) 1f / w else 0f
            out[i] = (m[0] * x + m[1] * y + m[2]) * iw
            out[i + 1] = (m[3] * x + m[4] * y + m[5]) * iw
            out[i + 2] = d[i + 2] * scaleAt(m, x, y)
            i += 3
        }
        return out
    }

    /** One dab chain per copy of [s] (see [mappedDabs]); [base] is the stroke's own chain. */
    fun dabChains(s: VStroke, base: FloatArray): List<FloatArray> = s.copies.map { mappedDabs(base, it) }

    /**
     * Each copy of [s] as a plain stroke for bounds and tiles: the points mapped and the size
     * scaled by the largest √|det J| over them (exact for affine maps, conservative for
     * homographies). Strokes without copies give a list of [s] alone.
     */
    fun expanded(s: VStroke): List<VStroke> {
        if (s.copies.isEmpty()) return listOf(s)
        return s.copies.map { m ->
            val k = maxScale(m, s)
            s.copy(points = s.points.mapped(m), sizeScale = s.sizeScale * k, copies = emptyList())
        }
    }

    /** Everything [s] can paint, all copies included (as [StrokeRaster.strokeBounds] for one). */
    fun bounds(s: VStroke): RectF {
        if (s.copies.isEmpty()) return StrokeRaster.strokeBounds(s.preset, s.sizeScale, s.points)
        val out = RectF()
        for (c in expanded(s)) {
            val b = StrokeRaster.strokeBounds(c.preset, c.sizeScale, c.points)
            if (b.isEmpty) continue
            if (out.isEmpty) out.set(b) else out.union(b)
        }
        return out
    }

    /** The largest √|det J| of [m] over [s]'s points (1 for a map that keeps sizes). */
    fun maxScale(m: FloatArray, s: VStroke): Float {
        val affine = m[6] == 0f && m[7] == 0f
        if (affine) return scaleAt(m, 0f, 0f)
        var best = 0f
        val xs = s.points.x; val ys = s.points.y
        for (i in xs.indices) best = max(best, scaleAt(m, xs[i], ys[i]))
        return if (best > 0f) best else 1f
    }

    /** √|det J| of [m] at (x, y): how much a dab's size scales there. */
    fun scaleAt(m: FloatArray, x: Float, y: Float): Float {
        val w = m[6] * x + m[7] * y + m[8]
        val s = if (m[6] == 0f && m[7] == 0f) {
            sqrt(abs(m[0] * m[4] - m[1] * m[3]) / (m[8] * m[8]))
        } else {
            if (w == 0f) return 1f
            val qx = (m[0] * x + m[1] * y + m[2]) / w
            val qy = (m[3] * x + m[4] * y + m[5]) / w
            val a = (m[0] - qx * m[6]) / w; val b = (m[1] - qx * m[7]) / w
            val c = (m[3] - qy * m[6]) / w; val d = (m[4] - qy * m[7]) / w
            sqrt(abs(a * d - b * c))
        }
        return if (s.isFinite() && s > 0f) s else 1f
    }

    private fun det(m: FloatArray): Double {
        val a = m.map { it.toDouble() }
        return a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
    }

    private fun inverse(m: FloatArray): FloatArray? {
        val d = det(m)
        if (!d.isFinite() || abs(d) < MIN_DET) return null
        val a = DoubleArray(9) { m[it].toDouble() }
        val r = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return FloatArray(9) { (r[it] / d).toFloat() }
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        (a[3 * r].toDouble() * b[c] + a[3 * r + 1].toDouble() * b[3 + c] + a[3 * r + 2].toDouble() * b[6 + c]).toFloat()
    }

    /** [m] scaled so its last value is 1 (affine maps stay exactly affine), when it can be. */
    private fun normalized(m: FloatArray): FloatArray {
        val w = m[8]
        if (w == 1f || w == 0f || !w.isFinite()) return m
        val out = FloatArray(9) { m[it] / w }
        if (m[6] == 0f && m[7] == 0f) { out[6] = 0f; out[7] = 0f }
        out[8] = 1f
        return out
    }
}
