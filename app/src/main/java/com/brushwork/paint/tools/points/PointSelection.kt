package com.brushwork.paint.tools.points

/**
 * v1.7 (item 1, design §3.1 and §4.5, I12): the selected points of ONE object, as indices into
 * its point list of [size] points. Immutable; every change returns a new selection.
 *
 * [primary] is the point the single-point controls act on (v1.6's `selectedPoint`): the point
 * added last, -1 when nothing is selected, and always one of [indices] otherwise. Indices are
 * kept sorted and unique, each in `0 until size` (out-of-range indices are ignored).
 *
 * Inserts, deletes, a reopened object and in-tool undo keep a selection right through
 * [afterInsert], [afterRemove] and [resized]. Two selections are equal when they have the same
 * size, indices and primary.
 */
class PointSelection private constructor(private val sorted: IntArray, val size: Int, val primary: Int) {

    /** How many points are selected. */
    val count: Int get() = sorted.size

    val isEmpty: Boolean get() = sorted.isEmpty()

    /** Exactly one point selected (the v1.6 case, I12). */
    val isSingle: Boolean get() = sorted.size == 1

    /** The selected indices, ascending. */
    val indices: List<Int> get() = sorted.asList()

    operator fun contains(i: Int): Boolean = sorted.binarySearch(i) >= 0

    /** Only point [i] ([none] when [i] is out of range). */
    fun only(i: Int): PointSelection = if (i in 0 until size) PointSelection(intArrayOf(i), size, i) else none(size)

    /** This selection with [i] added; [i] becomes the primary. Out of range: unchanged. */
    fun plus(i: Int): PointSelection {
        if (i !in 0 until size) return this
        if (i in this) return if (primary == i) this else PointSelection(sorted, size, i)
        return PointSelection(insertSorted(sorted, i), size, i)
    }

    /** This selection without [i]; the primary moves to the largest index left when it was [i]. */
    fun minus(i: Int): PointSelection {
        if (i !in this) return this
        val next = sorted.filter { it != i }.toIntArray()
        return PointSelection(next, size, if (primary == i) next.lastOrNull() ?: -1 else primary)
    }

    /** [i] removed when selected, else added (as the primary). */
    fun toggled(i: Int): PointSelection = if (i in this) minus(i) else plus(i)

    /** This selection with every index of [ids] added; the last one added becomes the primary. */
    fun plusAll(ids: Collection<Int>): PointSelection {
        var r = this
        for (i in ids) r = r.plus(i)
        return r
    }

    /** After a point was inserted at [at] (the list grew by one): indices ≥ [at] shift up. */
    fun afterInsert(at: Int): PointSelection {
        val shifted = IntArray(sorted.size) { k -> sorted[k].let { if (it >= at) it + 1 else it } }
        return PointSelection(shifted, size + 1, if (primary >= at) primary + 1 else primary)
    }

    /**
     * After the points [removed] (indices before the removal) were deleted: those leave the
     * selection and the indices above each removed one shift down. The primary follows its point,
     * or becomes the largest index left when it was removed.
     */
    fun afterRemove(removed: Collection<Int>): PointSelection {
        val gone = removed.filter { it in 0 until size }.distinct().sorted().toIntArray()
        if (gone.isEmpty()) return this
        fun shifted(i: Int): Int {
            // The number of removed indices below i.
            val pos = gone.binarySearch(i)
            return i - (if (pos >= 0) pos else -pos - 1)
        }
        val kept = sorted.filter { gone.binarySearch(it) < 0 }.map { shifted(it) }.toIntArray()
        val newSize = size - gone.size
        val p = if (primary >= 0 && gone.binarySearch(primary) < 0) shifted(primary) else kept.lastOrNull() ?: -1
        return PointSelection(kept, newSize, p)
    }

    /** The same selection over [newSize] points: indices that no longer exist are dropped. */
    fun resized(newSize: Int): PointSelection {
        val n = newSize.coerceAtLeast(0)
        if (n == size) return this
        val kept = sorted.filter { it < n }.toIntArray()
        val p = if (primary in 0 until n) primary else kept.lastOrNull() ?: -1
        return PointSelection(kept, n, p)
    }

    override fun equals(other: Any?): Boolean =
        other is PointSelection && other.size == size && other.primary == primary && other.sorted.contentEquals(sorted)

    override fun hashCode(): Int = (sorted.contentHashCode() * 31 + size) * 31 + primary

    override fun toString(): String = "PointSelection(${sorted.joinToString(",", "[", "]")} of $size, primary $primary)"

    companion object {
        /** Nothing selected among [size] points. */
        fun none(size: Int): PointSelection = PointSelection(IntArray(0), size.coerceAtLeast(0), -1)

        /** Every one of [size] points; the primary is the last point. */
        fun all(size: Int): PointSelection {
            val n = size.coerceAtLeast(0)
            return PointSelection(IntArray(n) { it }, n, n - 1)
        }

        /** The points [i] (out-of-range ones ignored) among [size]; the last valid one is the primary. */
        fun of(size: Int, vararg i: Int): PointSelection = none(size).plusAll(i.asList())

        private fun insertSorted(a: IntArray, v: Int): IntArray {
            val pos = -a.binarySearch(v) - 1
            val r = IntArray(a.size + 1)
            a.copyInto(r, 0, 0, pos)
            r[pos] = v
            a.copyInto(r, pos + 1, pos, a.size)
            return r
        }
    }
}
