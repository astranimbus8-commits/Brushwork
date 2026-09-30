package com.brushwork.paint.ui.editor

import com.brushwork.paint.core.Vec2
import kotlin.math.atan2
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

    /**
     * True while the current gesture may still end as a two- or three-finger tap, checked BEFORE
     * the last pointer lifts at [nowMs] (e.g. to decide whether a pinch handed to a tool must be
     * reverted because it was really an undo tap).
     */
    fun tapStillPossible(nowMs: Long): Boolean =
        !cancelled && maxPointers in 2..3 && maxMove < tapSlopPx && nowMs - startTime <= tapTimeoutMs

    /**
     * True while the current (or just ended) gesture has had exactly one pointer that never
     * moved [tapSlopPx] or more: with a short enough duration, a one-finger tap. (Kept apart from
     * [up], whose taps are the multi-finger undo/redo ones.)
     */
    fun isSinglePointerStill(): Boolean = !cancelled && maxPointers == 1 && maxMove < tapSlopPx

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

/**
 * How a two-finger gesture changed since it started, measured in DOCUMENT coordinates (so the
 * view's zoom, rotation and mirroring are already accounted for): [translation] of the midpoint
 * of the fingers, [scale] of their distance and [rotationDeg] of their angle, in (-180, 180],
 * positive = clockwise on an unmirrored screen (the tools' rotation convention, y down). A tool
 * applies the scale and rotation around the START midpoint, then the translation, so the content
 * under the fingers follows them — also on a mirrored or rotated view.
 */
data class TwoFingerChange(val translation: Vec2, val scale: Float, val rotationDeg: Float) {
    companion object {
        val NONE = TwoFingerChange(Vec2.ZERO, 1f, 0f)

        /**
         * Change from the finger positions [start] to [now] (each `ax, ay, bx, by`, document
         * pixels). A start distance below [minSpread] (document px) is too small to measure a
         * scale or angle from: they stay neutral.
         */
        fun between(start: FloatArray, now: FloatArray, minSpread: Float = 1e-3f): TwoFingerChange {
            val t = Vec2((now[0] + now[2] - start[0] - start[2]) / 2f, (now[1] + now[3] - start[1] - start[3]) / 2f)
            val dx0 = start[2] - start[0]; val dy0 = start[3] - start[1]
            val dx1 = now[2] - now[0]; val dy1 = now[3] - now[1]
            val d0 = hypot(dx0, dy0)
            val d1 = hypot(dx1, dy1)
            if (!(d0 >= minSpread) || !(d1 >= minSpread) || !t.x.isFinite() || !t.y.isFinite()) {
                return TwoFingerChange(if (t.x.isFinite() && t.y.isFinite()) t else Vec2.ZERO, 1f, 0f)
            }
            val angle = Math.toDegrees(atan2(dy1.toDouble(), dx1.toDouble()) - atan2(dy0.toDouble(), dx0.toDouble())).toFloat()
            return TwoFingerChange(t, d1 / d0, normalizeDegrees(angle))
        }

        /** Normalizes to (-180, 180]. */
        fun normalizeDegrees(d: Float): Float {
            var r = d % 360f
            if (r <= -180f) r += 360f
            if (r > 180f) r -= 360f
            return r
        }
    }
}
