package com.brushwork.paint.brush

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.8 D (design §3.1 D1b): where a clone stroke's copy k may show the stroke's coverage. The
 * coverage buffer is the document's size and its shader repeats its edge pixels beyond it
 * (CLAMP), so under a map that turns or tilts the document, the rectangles a copy is drawn over
 * (bounding boxes of where its map takes the coverage) reach places whose preimage lies off the
 * document, where the clamp would smear the edge's coverage into streaks. A copy shows only over
 * the image of the document under its map: [covers] says when a rectangle needs no clip (its
 * preimage lies on the document: almost always), [clip] is that image otherwise.
 *
 * Indexed by copy (1..n, as `CoverageCopies`); [n] is the array size (copies + 1). Main thread.
 */
internal class CopyDocumentClips(private val docW: Int, private val docH: Int, n: Int) {
    private val inverse = arrayOfNulls<FloatArray>(n)
    private val clips = arrayOfNulls<Path>(n)
    private val values = FloatArray(9)
    private val inv = Matrix()

    private fun inverseOf(k: Int, map: Matrix): FloatArray? {
        inverse[k]?.let { return it }
        if (!map.invert(inv)) return null
        return FloatArray(9).also { inv.getValues(it); inverse[k] = it }
    }

    /**
     * True when the preimage of [r] (document px, where copy [k] is drawn) under [map] lies on
     * the document: the copy shows only the coverage there, no clip needed. (The preimage of a
     * rectangle is a convex quad when no corner crosses the map's horizon, so its corners decide.)
     */
    fun covers(k: Int, map: Matrix, r: Rect): Boolean {
        val m = inverseOf(k, map) ?: return false
        var sign = 0
        for (c in 0 until 4) {
            val x = (if (c == 0 || c == 3) r.left else r.right).toFloat()
            val y = (if (c < 2) r.top else r.bottom).toFloat()
            val w = m[6] * x + m[7] * y + m[8]
            val s = if (w > EPS) 1 else if (w < -EPS) -1 else return false
            if (sign == 0) sign = s else if (s != sign) return false
            val u = (m[0] * x + m[1] * y + m[2]) / w
            val v = (m[3] * x + m[4] * y + m[5]) / w
            if (!(u >= 0f && v >= 0f && u <= docW && v <= docH)) return false
        }
        return true
    }

    /**
     * The image of the document under copy [k]'s [map] (document px): a parallelogram for an
     * affine map; for a homography the images of the document's parts on either side of the
     * map's horizon (each convex), leaving out a sliver along the horizon whose image lies far
     * beyond any canvas. Empty when nothing of the document maps to a finite place.
     */
    fun clip(k: Int, map: Matrix): Path {
        clips[k]?.let { return it }
        map.getValues(values)
        val v = values.copyOf()
        val path = Path()
        val xs = floatArrayOf(0f, docW.toFloat(), docW.toFloat(), 0f)
        val ys = floatArrayOf(0f, 0f, docH.toFloat(), docH.toFloat())
        if (v[6] == 0f && v[7] == 0f) {
            addImage(path, v, xs, ys, xs.size)
        } else {
            var most = 0f
            for (i in 0 until 4) most = max(most, abs(v[6] * xs[i] + v[7] * ys[i] + v[8]))
            if (most > 0f) {
                // The parts where w ≥ eps and w ≤ −eps (one of them is often empty).
                val eps = most * HORIZON_SLIVER
                for (side in intArrayOf(1, -1)) {
                    val px = FloatArray(8)
                    val py = FloatArray(8)
                    var np = 0
                    for (i in 0 until 4) {
                        val j = (i + 1) % 4
                        val wi = side * (v[6] * xs[i] + v[7] * ys[i] + v[8]) - eps
                        val wj = side * (v[6] * xs[j] + v[7] * ys[j] + v[8]) - eps
                        if (wi >= 0f) { px[np] = xs[i]; py[np] = ys[i]; np++ }
                        if ((wi >= 0f) != (wj >= 0f)) {
                            val t = wi / (wi - wj)
                            px[np] = xs[i] + (xs[j] - xs[i]) * t
                            py[np] = ys[i] + (ys[j] - ys[i]) * t
                            np++
                        }
                    }
                    if (np >= 3) addImage(path, v, px, py, np)
                }
            }
        }
        clips[k] = path
        return path
    }

    /** Adds the polygon ([xs], [ys], [n] vertices) mapped through [v] (row-major 3×3) to [path]. */
    private fun addImage(path: Path, v: FloatArray, xs: FloatArray, ys: FloatArray, n: Int) {
        val us = FloatArray(n)
        val ts = FloatArray(n)
        for (i in 0 until n) {
            val x = xs[i]
            val y = ys[i]
            val w = v[6] * x + v[7] * y + v[8]
            us[i] = (v[0] * x + v[1] * y + v[2]) / w
            ts[i] = (v[3] * x + v[4] * y + v[5]) / w
            if (!us[i].isFinite() || !ts[i].isFinite()) return
        }
        path.moveTo(us[0], ts[0])
        for (i in 1 until n) path.lineTo(us[i], ts[i])
        path.close()
    }

    private companion object {
        /** |w| below this is at a map's horizon. */
        const val EPS = 1e-6f

        /** The sliver along a homography's horizon left out of [clip], as a fraction of the largest |w| over the document. */
        const val HORIZON_SLIVER = 1e-3f
    }
}
