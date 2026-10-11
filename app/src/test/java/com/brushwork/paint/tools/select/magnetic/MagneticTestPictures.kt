package com.brushwork.paint.tools.select.magnetic

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Synthetic luminance pictures and path helpers for the magnetic lasso's pure tests. */
internal object MagneticTestPictures {

    /** A [w] × [h] picture, white with a black disc of radius [r] at ([cx], [cy]) (pixel centres inside). */
    fun disc(w: Int, h: Int, cx: Float, cy: Float, r: Float): ByteArray {
        val lum = ByteArray(w * h) { 255.toByte() }
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x - cx, y - cy) < r) lum[y * w + x] = 0
        }
        return lum
    }

    /** Every pixel of [path] (x, y pairs), the straight runs between its points filled in. */
    fun pixels(path: IntArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        out += path[0] to path[1]
        for (i in 2 until path.size step 2) {
            val x0 = path[i - 2]; val y0 = path[i - 1]; val x1 = path[i]; val y1 = path[i + 1]
            val n = max(abs(x1 - x0), abs(y1 - y0))
            for (s in 1..n) out += (x0 + (x1 - x0) * s / n) to (y0 + (y1 - y0) * s / n)
        }
        return out
    }

    /** The largest distance of [path]'s pixels from the circle ([cx], [cy], [r]). */
    fun offRim(path: IntArray, cx: Float, cy: Float, r: Float): Float =
        pixels(path).maxOf { (x, y) -> abs(hypot(x - cx, y - cy) - r) }
}
