package com.brushwork.paint.vector.copies

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.StrokeCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 (R1 (a), J18, LA4): edits of one side (copy k) of a mirrored path or shape that keep it
 * linked. The copy maps C_j are affine 3×3 row-major; C_0 = identity is the source; M is the
 * side's transform in document space, always an isometry (resizing a side is blocked, LA4;
 * [nearestIsometry] snaps a residual drift).
 *
 * - [offset] k ≥ 1: `C_k' = M·C_k`, nothing else changes. k = 0: the source is mapped by M on its
 *   own points and `C_j' = C_j·M⁻¹` for every j ≥ 1, so every other side stays where it is drawn.
 * - [dropCopy] k ≥ 1: map k goes. k = 0: rebase, `src' = C_1·src`, `C_j' = C_j·C_1⁻¹` (j ≥ 2),
 *   and C_1 goes. A Mirror (2 maps) becomes a plain object.
 * - Both merge coinciding maps ([merged]: within [MERGE_EPS] of the identity or of an earlier map)
 *   and refuse (null) a result `StrokeCopies.isUsable` rejects.
 *
 * `CopyEdit.detachOne` (a side unlinked into its own object) is not built in 1.10: the user chose
 * to block resizing one side instead (LA4). Pure geometry; thread-safe.
 */
object CopyEdit {
    /** The isometry test: the linear part L of a map satisfies `max|LᵀL − I| ≤ ISOMETRY_EPS`. */
    const val ISOMETRY_EPS = 1e-4f

    /** Two maps closer than this in every coefficient coincide (one of them is removed). */
    const val MERGE_EPS = 1e-4f

    /** True when the affine [m] keeps lengths (a turn, a flip, a move or a mix of them). */
    fun isIsometry(m: FloatArray): Boolean {
        if (!isAffine(m)) return false
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[3].toDouble(); val d = m[4].toDouble()
        val e = maxOf(abs(a * a + c * c - 1.0), abs(a * b + c * d), abs(b * b + d * d - 1.0))
        return e <= ISOMETRY_EPS
    }

    /**
     * The isometry nearest to the affine [m] (the polar decomposition of its linear part: the
     * rotation, or the reflection when det < 0, it holds), placed so that [pivot] goes where [m]
     * takes it (the box centre stays put when a drift is snapped).
     */
    fun nearestIsometry(m: FloatArray, pivot: Vec2): FloatArray {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[3].toDouble(); val d = m[4].toDouble()
        val r00: Double; val r01: Double; val r10: Double; val r11: Double
        if (a * d - b * c >= 0.0) {
            val th = atan2(c - b, a + d)
            r00 = cos(th); r01 = -sin(th); r10 = sin(th); r11 = cos(th)
        } else {
            // L·diag(1, −1) is a rotation R'; R = R'·diag(1, −1).
            val th = atan2(c + b, a - d)
            r00 = cos(th); r01 = sin(th); r10 = sin(th); r11 = -cos(th)
        }
        val px = pivot.x.toDouble(); val py = pivot.y.toDouble()
        val qx = a * px + b * py + m[2]
        val qy = c * px + d * py + m[5]
        val tx = qx - (r00 * px + r01 * py)
        val ty = qy - (r10 * px + r11 * py)
        return floatArrayOf(r00.toFloat(), r01.toFloat(), tx.toFloat(), r10.toFloat(), r11.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    }

    /**
     * [o] with side [k] moved by the isometry [m] (see the class notes), its id and every other
     * side kept; null when [o] has no copy k, [m] is unusable, or the result would be.
     */
    fun offset(o: VObject, k: Int, m: FloatArray): VObject? {
        val copies = ObjectCopies.of(o)
        if (copies.isEmpty || k !in copies.maps.indices || !isAffine(m) || !StrokeCopies.isUsable(m)) return null
        val maps = copies.maps
        if (k >= 1) {
            val next = ArrayList<FloatArray>(maps)
            next[k] = multiply(m, maps[k])
            val kept = merged(next) ?: return null
            return ObjectCopies.withCopies(o, CopyMaps.of(kept))
        }
        val inv = inverse(m) ?: return null
        val src = VectorOps.transformed(ObjectCopies.source(o), m)
        val next = ArrayList<FloatArray>(maps.size)
        next += identity()
        for (j in 1 until maps.size) next += multiply(maps[j], inv)
        val kept = merged(next) ?: return null
        return ObjectCopies.withCopies(src, CopyMaps.of(kept))
    }

    /**
     * [o] without side [k] (see the class notes; its id kept): a plain object when one side is
     * left; null when [o] has no copy k or the rebased maps are unusable.
     */
    fun dropCopy(o: VObject, k: Int): VObject? {
        val copies = ObjectCopies.of(o)
        if (copies.isEmpty || k !in copies.maps.indices) return null
        val maps = copies.maps
        if (k >= 1) {
            val next = maps.filterIndexed { j, _ -> j != k }
            val kept = merged(next) ?: return null
            return ObjectCopies.withCopies(o, CopyMaps.of(kept))
        }
        val c1 = maps[1]
        val inv = inverse(c1) ?: return null
        val src = VectorOps.transformed(ObjectCopies.source(o), c1)
        val next = ArrayList<FloatArray>(maps.size - 1)
        next += identity()
        for (j in 2 until maps.size) next += multiply(maps[j], inv)
        val kept = merged(next) ?: return null
        return ObjectCopies.withCopies(src, CopyMaps.of(kept))
    }

    /**
     * True when side [k] of [o] can be lifted alone: [o] has live copies, copy k among them, and
     * the object without it ([dropCopy], what the edit preview's hole shows) is usable.
     */
    fun canLift(o: VObject, k: Int): Boolean = CopyPick.side(o, k) != null && ObjectCopies.hasCopies(o) && dropCopy(o, k) != null

    /**
     * [maps] (identity first) without the maps j ≥ 1 that coincide with the identity or with an
     * earlier kept map (within [MERGE_EPS]); null when a map is not affine or not usable.
     */
    fun merged(maps: List<FloatArray>): List<FloatArray>? {
        val out = ArrayList<FloatArray>(maps.size)
        for ((j, m) in maps.withIndex()) {
            if (!isAffine(m) || !StrokeCopies.isUsable(m)) return null
            if (j == 0) { out += m; continue }
            if (out.any { near(it, m) }) continue
            out += m
        }
        return out
    }

    /** a·b (3×3 row-major). */
    internal fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val col = i % 3
        (a[r * 3].toDouble() * b[col] + a[r * 3 + 1].toDouble() * b[3 + col] + a[r * 3 + 2].toDouble() * b[6 + col]).toFloat()
    }

    /** The inverse of the affine [m] as a 3×3 map, or null when singular. */
    internal fun inverse(m: FloatArray): FloatArray? {
        val i = ObjectCopies.inverse(m) ?: return null
        return floatArrayOf(i[0], i[1], i[2], i[3], i[4], i[5], 0f, 0f, 1f)
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun isAffine(m: FloatArray): Boolean = m.size == 9 && m[6] == 0f && m[7] == 0f && m[8] == 1f

    private fun near(a: FloatArray, b: FloatArray): Boolean {
        for (i in 0 until 6) if (abs(a[i] - b[i]) > MERGE_EPS) return false
        return true
    }
}
