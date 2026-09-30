package com.brushwork.paint.ui.layers

import kotlin.math.roundToInt

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

    /**
     * Size in dp (width, height) of the floating layers window on a [screenW] x [screenH] dp
     * screen, before the editor fits it between its top and bottom chrome.
     *
     * Portrait (and tall landscape tablets): about 78 % of the width, at most 300 dp (360 dp on
     * tablets), and half the height. Short screens (a phone in landscape): a wider window for a
     * side-by-side layout that takes all the height the chrome leaves.
     */
    fun windowSize(screenW: Int, screenH: Int): WindowDp {
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        if (isShortScreen(h)) {
            return WindowDp(minOf(SHORT_MAX_WIDTH, (w * 0.65f).roundToInt()).coerceAtLeast(minOf(w, SHORT_MIN_WIDTH)), h)
        }
        val maxW = if (w >= TABLET_WIDTH) TABLET_MAX_WIDTH else PHONE_MAX_WIDTH
        val width = minOf(maxW, (w * 0.78f).roundToInt())
        val height = maxOf(MIN_HEIGHT, (h * 0.5f).roundToInt())
        return WindowDp(width, height)
    }

    /** Screens this short (dp) get the side-by-side layers window. */
    fun isShortScreen(screenH: Int): Boolean = screenH < 480

    private const val PHONE_MAX_WIDTH = 300
    private const val TABLET_MAX_WIDTH = 360
    private const val TABLET_WIDTH = 600
    private const val SHORT_MAX_WIDTH = 520
    private const val SHORT_MIN_WIDTH = 380
    private const val MIN_HEIGHT = 240

    /**
     * Top of a dragged row of height [size] whose finger-following top is [top], kept inside the
     * viewport [viewportStart]..[viewportEnd] (unchanged when the row is taller than the viewport).
     */
    fun clampRowTop(top: Float, size: Float, viewportStart: Float, viewportEnd: Float): Float {
        val max = viewportEnd - size
        return if (max < viewportStart) top else top.coerceIn(viewportStart, max)
    }

    /**
     * Edge auto-scroll velocity in px/s (negative = up) for a dragged row of height [size] whose
     * finger-following top is [top], after a total finger travel of [travel] px (its sign is the
     * drag direction). Non-zero only once the row entered the edge zone ([edgeZone] rows deep)
     * on the side it is being dragged towards, after at least [startTravel] rows of travel;
     * ramps linearly to [maxRowsPerSecond] rows/s at the full zone depth.
     */
    fun edgeScrollSpeed(
        top: Float,
        size: Float,
        travel: Float,
        viewportStart: Float,
        viewportEnd: Float,
        edgeZone: Float = 0.6f,
        startTravel: Float = 0.25f,
        maxRowsPerSecond: Float = 9f,
    ): Float {
        if (size <= 0f || travel == 0f || kotlin.math.abs(travel) < size * startTravel) return 0f
        val zone = size * edgeZone
        val depth = if (travel > 0f) (top + size) - (viewportEnd - zone) else (viewportStart + zone) - top
        if (depth <= 0f) return 0f
        val speed = size * maxRowsPerSecond * (depth / zone).coerceAtMost(1f)
        return if (travel > 0f) speed else -speed
    }
}

/** A width x height in dp. */
data class WindowDp(val width: Int, val height: Int)

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
