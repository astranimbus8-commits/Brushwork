package com.brushwork.paint.vector.select

import android.graphics.RectF

/**
 * v1.9 (R19, design 2.4.11; frozen): the order behind "Bring forward" and "Send backward" for
 * vector objects (A4's object bar), texts (A3's More row) and shapes (A9's More row).
 */
object ArrangeOrder {
    /**
     * Forward (dir = +1) or Backward (-1) for [selected] indices of a list in picture order (bottom
     * first): each moves past the next object whose bounds overlap its own; when none overlaps it
     * moves one plain step (R19). The new order as a permutation, or null when nothing moves.
     *
     * The permutation: the new list's item i is the old list's item `perm[i]`. Selected items keep
     * their order among themselves: Forward takes them from the top down, and each looks only at the
     * unselected objects between it and the next selected item above it (Backward mirrors this). It
     * goes right above the first of them whose bounds overlap its own, or right above the nearest
     * one when none does; with none between, it stays. Bounds overlap as in v1.8's
     * `ObjectBounds.overlap`: both non-empty and meeting with a positive area (touching edges do
     * not count); a NaN bound never overlaps. Indices outside [bounds] are ignored; a [dir] other
     * than +1 or -1 moves nothing.
     */
    fun step(bounds: List<RectF>, selected: Set<Int>, dir: Int): IntArray? {
        val n = bounds.size
        if (dir != 1 && dir != -1) return null
        val chosen = selected.filter { it in 0 until n }.toHashSet()
        if (chosen.isEmpty() || chosen.size == n) return null
        val order = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
        if (dir == 1) {
            for (s in chosen.sortedDescending()) {
                val pos = order.indexOf(s)
                var limit = pos + 1
                while (limit < n && order[limit] !in chosen) limit++
                if (limit == pos + 1) continue
                var target = pos + 1
                for (k in pos + 1 until limit) {
                    if (overlap(bounds[s], bounds[order[k]])) { target = k; break }
                }
                // Removing s first shifts the others down by one: inserting at target puts s right above it.
                order.removeAt(pos)
                order.add(target, s)
            }
        } else {
            for (s in chosen.sorted()) {
                val pos = order.indexOf(s)
                var limit = pos - 1
                while (limit >= 0 && order[limit] !in chosen) limit--
                if (limit == pos - 1) continue
                var target = pos - 1
                for (k in pos - 1 downTo limit + 1) {
                    if (overlap(bounds[s], bounds[order[k]])) { target = k; break }
                }
                order.removeAt(pos)
                order.add(target, s)
            }
        }
        if (order.indices.all { order[it] == it }) return null
        return order.toIntArray()
    }

    /** v1.8's `ObjectBounds.overlap` on the fields (a NaN compares false, so it never overlaps). */
    private fun overlap(a: RectF, b: RectF): Boolean =
        a.left < a.right && a.top < a.bottom && b.left < b.right && b.top < b.bottom &&
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
