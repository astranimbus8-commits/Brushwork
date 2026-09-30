package com.brushwork.paint.brush

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns raw input points into evenly spaced dab positions along a lightly smoothed path.
 *
 * Smoothing: quadratic curves from midpoint to midpoint of the input polyline, with the input
 * points as control points, so fast strokes (few, far-apart samples) stay round instead of
 * showing polygon corners. The curve trails the newest input point by half a segment until
 * [end] draws the remainder.
 *
 * Dabs are emitted every `spacingAt(pressure, distance)` document px (sub-pixel positions),
 * with pressure interpolated along the path. Pure Kotlin (no Android dependencies).
 *
 * The callbacks are primitive-typed interfaces (Kotlin function types would box every float of
 * every dab: a long path re-rendered on each frame would allocate megabytes per second).
 */
class StrokeSampler(
    private val spacingAt: Spacing,
    private val onSample: Sink,
) {
    /** Distance to the next dab after one at ([pressure], [distance]). */
    fun interface Spacing {
        fun spacing(pressure: Float, distance: Float): Float
    }

    /** Receives each dab position. */
    fun interface Sink {
        fun sample(x: Float, y: Float, pressure: Float, distance: Float)
    }

    /** Length of the smoothed path walked so far (document px). */
    var length = 0f
        private set

    /** Distance along the path of the most recently emitted sample. */
    var lastSampleDistance = 0f
        private set

    var isStarted = false
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var lastP = 1f
    private var midX = 0f
    private var midY = 0f
    private var midP = 1f
    private var toNext = 0f

    /** Everything the sampler carries from one input point to the next (see [snapshot]). */
    class State internal constructor(
        internal val length: Float,
        internal val lastSampleDistance: Float,
        internal val isStarted: Boolean,
        internal val lastX: Float,
        internal val lastY: Float,
        internal val lastP: Float,
        internal val midX: Float,
        internal val midY: Float,
        internal val midP: Float,
        internal val toNext: Float,
    )

    /** The current state; [restore] brings it back (a stroke re-rendered from a point on). */
    fun snapshot(): State = State(length, lastSampleDistance, isStarted, lastX, lastY, lastP, midX, midY, midP, toNext)

    /** Returns to a [snapshot] of this sampler: later input continues exactly as it did then. */
    fun restore(s: State) {
        length = s.length
        lastSampleDistance = s.lastSampleDistance
        isStarted = s.isStarted
        lastX = s.lastX; lastY = s.lastY; lastP = s.lastP
        midX = s.midX; midY = s.midY; midP = s.midP
        toNext = s.toNext
    }

    /** Starts a stroke; emits the first sample at the start point. */
    fun begin(x: Float, y: Float, pressure: Float) {
        isStarted = true
        length = 0f
        lastX = x; lastY = y; lastP = pressure
        midX = x; midY = y; midP = pressure
        emit(x, y, pressure, 0f)
    }

    /** Adds an input point. */
    fun add(x: Float, y: Float, pressure: Float) {
        if (!isStarted) { begin(x, y, pressure); return }
        if (hypot(x - lastX, y - lastY) < MIN_MOVE) {
            lastP = pressure
            return
        }
        val mx = (lastX + x) * 0.5f
        val my = (lastY + y) * 0.5f
        val mp = (lastP + pressure) * 0.5f
        curve(midX, midY, midP, lastX, lastY, lastP, mx, my, mp)
        midX = mx; midY = my; midP = mp
        lastX = x; lastY = y; lastP = pressure
    }

    /**
     * Finishes the path up to the last input point. When [closeGap] is true and the last dab is
     * noticeably before the end point, one more dab is placed exactly at the end.
     */
    fun end(closeGap: Boolean = true) {
        if (!isStarted) return
        walk(midX, midY, midP, lastX, lastY, lastP)
        midX = lastX; midY = lastY; midP = lastP
        if (closeGap) {
            val gap = length - lastSampleDistance
            if (gap > 0.35f * max(MIN_SPACING, spacingAt.spacing(lastP, length))) emit(lastX, lastY, lastP, length)
        }
        isStarted = false
    }

    private fun emit(x: Float, y: Float, p: Float, d: Float) {
        lastSampleDistance = d
        onSample.sample(x, y, p, d)
        toNext = max(MIN_SPACING, spacingAt.spacing(p, d))
    }

    private fun curve(x0: Float, y0: Float, p0: Float, cx: Float, cy: Float, cp: Float, x1: Float, y1: Float, p1: Float) {
        val approx = hypot(cx - x0, cy - y0) + hypot(x1 - cx, y1 - cy)
        if (approx <= 0f) return
        val n = ceil(approx / FLATTEN_STEP).toInt().coerceIn(1, 512)
        var px = x0; var py = y0; var pp = p0
        for (i in 1..n) {
            val t = i.toFloat() / n
            val u = 1f - t
            val a = u * u; val b = 2f * u * t; val c = t * t
            val qx = a * x0 + b * cx + c * x1
            val qy = a * y0 + b * cy + c * y1
            val qp = a * p0 + b * cp + c * p1
            walk(px, py, pp, qx, qy, qp)
            px = qx; py = qy; pp = qp
        }
    }

    private fun walk(ax: Float, ay: Float, ap: Float, bx: Float, by: Float, bp: Float) {
        val seg = hypot(bx - ax, by - ay)
        if (seg <= 0f) return
        var pos = 0f
        while (toNext <= seg - pos) {
            pos += toNext
            val t = pos / seg
            emit(ax + (bx - ax) * t, ay + (by - ay) * t, ap + (bp - ap) * t, length + pos)
        }
        toNext -= seg - pos
        length += seg
    }

    companion object {
        /** Input moves shorter than this are merged (only their pressure is kept). */
        const val MIN_MOVE = 0.02f
        /** Smallest allowed dab spacing (guards against pathological spacing callbacks). */
        const val MIN_SPACING = 0.1f
        /** Flattening step for the smoothing curves (document px). */
        const val FLATTEN_STEP = 1.5f
    }
}
