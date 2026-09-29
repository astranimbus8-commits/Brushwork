package com.brushwork.paint.ui.layers

/**
 * Pure index/structure helpers for the layers panel (no Android dependencies, JVM-tested).
 *
 * The panel shows layers TOP-FIRST (display index 0 = top of the stack) while `Document.layers`
 * is BOTTOM-FIRST (document index 0 = bottom). Both mappings are the same reflection.
 */
object LayerListMath {

    /** Document index of the row shown at [displayIndex] in a list of [count] layers. */
    fun displayToDoc(displayIndex: Int, count: Int): Int = count - 1 - displayIndex

    /** Display (top-first) row of the layer at document index [docIndex]. */
    fun docToDisplay(docIndex: Int, count: Int): Int = count - 1 - docIndex

    /** Returns a copy of [list] with the element at [from] moved to [to] (both clamped). */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (list.isEmpty() || from !in list.indices) return list.toList()
        val target = to.coerceIn(0, list.lastIndex)
        if (from == target) return list.toList()
        val out = list.toMutableList()
        out.add(target, out.removeAt(from))
        return out
    }

    /**
     * Clipping structure in DOCUMENT order, matching the compositor: a layer is clipped iff its
     * `clipping` flag is set and it is not the bottom layer (a clipping layer at index 0 acts as a
     * normal base). The base of a clipped layer is the nearest lower non-clipped layer.
     */
    fun clipStructure(clipping: List<Boolean>): List<ClipInfo> {
        val n = clipping.size
        val clipped = BooleanArray(n) { i -> clipping[i] && i > 0 }
        var base = -1
        return List(n) { i ->
            if (!clipped[i]) {
                base = i
                ClipInfo.NONE
            } else {
                ClipInfo(
                    clipped = true,
                    baseIndex = base,
                    // Rows are drawn top-first: "above" = higher doc index, "below" = lower.
                    continuesAbove = i + 1 < n && clipped[i + 1],
                    lowestInGroup = !clipped[i - 1],
                )
            }
        }
    }

    /** [clipStructure] re-ordered for top-first display. */
    fun clipStructureForDisplay(clippingDocOrder: List<Boolean>): List<ClipInfo> =
        clipStructure(clippingDocOrder).asReversed()
}

/**
 * How one row participates in a clipping group.
 * @property baseIndex document index of the base layer (-1 when not clipped).
 * @property continuesAbove the row shown directly above is clipped to the same base.
 * @property lowestInGroup the row shown directly below is the base itself.
 */
data class ClipInfo(
    val clipped: Boolean,
    val baseIndex: Int,
    val continuesAbove: Boolean,
    val lowestInGroup: Boolean,
) {
    companion object {
        val NONE = ClipInfo(clipped = false, baseIndex = -1, continuesAbove = false, lowestInGroup = false)
    }
}

/**
 * Rate limiter for live previews (e.g. the opacity slider recomposites the whole canvas on each
 * preview). [offer] returns true when a value may be applied now; otherwise the caller should
 * schedule a trailing apply after [delayUntilNext] ms. Clock values are in milliseconds.
 */
class PreviewThrottle(private val intervalMs: Long) {
    private var lastApplied = Long.MIN_VALUE / 2

    /** True if enough time passed since the last applied value (and records [now] as applied). */
    fun offer(now: Long): Boolean {
        if (now - lastApplied < intervalMs) return false
        lastApplied = now
        return true
    }

    /** Milliseconds until the next value may be applied (0 if immediately). */
    fun delayUntilNext(now: Long): Long = (lastApplied + intervalMs - now).coerceAtLeast(0)

    /** Records an out-of-band apply (e.g. a trailing update). */
    fun markApplied(now: Long) { lastApplied = now }

    fun reset() { lastApplied = Long.MIN_VALUE / 2 }
}
