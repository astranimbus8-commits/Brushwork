package com.brushwork.paint.ui.editor

import kotlin.math.hypot

/**
 * Pure-Kotlin recognizer for the canvas' multi-finger taps and single-finger long press.
 * Feed it every pointer the canvas treats as part of the current gesture; a gesture starts with
 * the first [down] after all pointers were up.
 *
 * - Two/three-finger tap: every finger went down and up within [tapTimeoutMs] (first down to
 *   last up), no finger moved [tapSlopPx] or more, and exactly 2 (or 3) fingers were down at
 *   the same time at most.
 * - Long press: exactly one pointer during the whole gesture, still down, moved less than
 *   [longPressSlopPx], held for [longPressTimeoutMs].
 */
class TouchGestureClassifier(
    private val tapSlopPx: Float,
    private val longPressSlopPx: Float,
    private val tapTimeoutMs: Long = TAP_TIMEOUT_MS,
    private val longPressTimeoutMs: Long = LONG_PRESS_TIMEOUT_MS,
) {
    enum class Tap { NONE, TWO_FINGER, THREE_FINGER }

    private class Pointer(val downX: Float, val downY: Float)

    private val pointers = HashMap<Int, Pointer>()
    private var startTime = 0L
    private var maxMove = 0f
    private var cancelled = false
    private var longPressFired = false

    /** Largest number of pointers down at the same time in the current gesture. */
    var maxPointers = 0
        private set

    val activePointers: Int get() = pointers.size

    fun down(id: Int, x: Float, y: Float, timeMs: Long) {
        if (pointers.isEmpty()) {
            startTime = timeMs
            maxMove = 0f
            maxPointers = 0
            cancelled = false
            longPressFired = false
        }
        pointers[id] = Pointer(x, y)
        if (pointers.size > maxPointers) maxPointers = pointers.size
    }

    fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        val d = hypot(x - p.downX, y - p.downY)
        if (d > maxMove) maxMove = d
    }

    /** Pointer [id] went up. Returns the recognized tap when it was the last pointer. */
    fun up(id: Int, timeMs: Long): Tap {
        if (pointers.remove(id) == null) return Tap.NONE
        if (pointers.isNotEmpty() || cancelled) return Tap.NONE
        if (timeMs - startTime > tapTimeoutMs || maxMove >= tapSlopPx) return Tap.NONE
        return when (maxPointers) {
            2 -> Tap.TWO_FINGER
            3 -> Tap.THREE_FINGER
            else -> Tap.NONE
        }
    }

    /** The gesture can no longer be a tap or long press (system cancel, stylus involved...). */
    fun invalidate() { cancelled = true }

    /** Forgets everything (ACTION_CANCEL). */
    fun cancel() {
        pointers.clear()
        cancelled = true
    }

    /** True once when the current single-pointer gesture qualifies as a long press at [nowMs]. */
    fun longPressDue(nowMs: Long): Boolean =
        !cancelled && !longPressFired && pointers.size == 1 && maxPointers == 1 &&
            maxMove < longPressSlopPx && nowMs - startTime >= longPressTimeoutMs

    fun markLongPressFired() { longPressFired = true }

    companion object {
        const val TAP_TIMEOUT_MS = 300L
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val TAP_SLOP_DP = 12f
        const val LONG_PRESS_SLOP_DP = 8f
    }
}
