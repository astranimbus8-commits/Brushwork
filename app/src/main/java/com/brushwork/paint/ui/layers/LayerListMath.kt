package com.brushwork.paint.ui.layers

import com.brushwork.paint.tools.text.TextThreadSpec
import kotlin.math.ceil
import kotlin.math.floor
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

    /**
     * The layer opacity after the window's − / + ([up]) from [fraction] (0..1). Without a step
     * (increments off, v1.5) it moves by 1 % from the rounded percentage; with [stepPercent] it goes
     * to the next multiple of the step in that direction (37 % with 5 %: 40 / 35), 0 and 100 %
     * reachable. The result is rounded to 0.01 %.
     */
    fun stepOpacity(fraction: Float, up: Boolean, stepPercent: Float?): Float {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        if (stepPercent == null || !stepPercent.isFinite() || stepPercent <= 0f) {
            val pct = (f * 100f).roundToInt()
            return ((pct + if (up) 1 else -1).coerceIn(0, 100)) / 100f
        }
        val k = f * 100.0 / stepPercent
        val next = if (up) floor(k + STEP_EPS) + 1.0 else ceil(k - STEP_EPS) - 1.0
        val pct = (next * stepPercent).coerceIn(0.0, 100.0)
        return ((pct * 100.0).roundToInt() / 10_000.0).toFloat()
    }

    private const val STEP_EPS = 1e-4

    /**
     * Frame badges of the rows of linked text frames: [threads] holds each row's thread (null =
     * not a frame). Frame k of m counts the frames of the same story among [threads]; the red +
     * shows on the frame whose thread says the story continues past it (overset).
     */
    fun frameBadges(threads: List<TextThreadSpec?>): List<FrameBadge?> {
        val counts = HashMap<Long, Int>()
        for (t in threads) if (t != null && t.isOn) counts[t.storyId] = (counts[t.storyId] ?: 0) + 1
        return threads.map { t ->
            if (t == null || !t.isOn) null
            else {
                val count = counts[t.storyId] ?: 1
                FrameBadge(index = t.index.coerceIn(0, count - 1), count = count, overset = t.overset)
            }
        }
    }

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
 * The badge of a linked text frame's row (v1.6 §3.7.7): ⛓ "k/m", frame [index] (0-based) of
 * [count], with a red + when the story is [overset] (it continues past this, the last frame).
 */
data class FrameBadge(val index: Int, val count: Int, val overset: Boolean) {
    /** "k/m". */
    val text: String get() = "${index + 1}/$count"

    /** Spoken description: "Text frame k of m" (", more text than fits" when overset). */
    val description: String get() = "Text frame ${index + 1} of $count" + if (overset) ", more text than fits" else ""
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
