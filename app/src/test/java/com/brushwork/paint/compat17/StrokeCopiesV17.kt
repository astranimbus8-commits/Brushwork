package com.brushwork.paint.compat17

import kotlin.math.abs

/**
 * v1.8 F0: a FROZEN copy of the part of v1.7's `StrokeCopies` (`vector/StrokeCopies.kt` at
 * 885793b4) that [VectorCodecV17] uses: which symmetry maps of a stroke v1.7 keeps on read, and
 * how it compares them. Never change it.
 */
object StrokeCopiesV17 {
    /** Most maps a stroke keeps. */
    const val MAX = 256

    private const val MIN_DET = 1e-12

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

    private fun det(m: FloatArray): Double {
        val a = m.map { it.toDouble() }
        return a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
    }
}
