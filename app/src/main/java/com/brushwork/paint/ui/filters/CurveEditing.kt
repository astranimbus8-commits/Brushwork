package com.brushwork.paint.ui.filters

import com.brushwork.paint.filters.CurvePoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Editing rules for tone-curve points (normalized 0..1, sorted by x). The first and last points
 * are the curve's endpoints: they only move vertically and can't be deleted. Interior points stay
 * strictly between their neighbors, so indices never change while dragging.
 */
object CurveEditing {
    /** Minimum horizontal distance between two points. */
    const val MIN_GAP = 0.02f

    /** Sorted copy with values clamped to 0..1. */
    fun normalized(points: List<CurvePoint>): List<CurvePoint> =
        points.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }.sortedBy { it.x }

    /**
     * Index of the point nearest to ([x], [y]) within an ellipse of radii ([rx], [ry]) (normalized
     * units, so the touch radius can be the same in dp on both axes), or -1.
     */
    fun hitTest(points: List<CurvePoint>, x: Float, y: Float, rx: Float, ry: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = hypot((p.x - x) / rx, (p.y - y) / ry)
            if (d <= 1f && d < bestD) { best = i; bestD = d }
        }
        return best
    }

    /** Inserts a point at ([x], [y]); returns the new list and its index, or null if too close to another point. */
    fun add(points: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int>? {
        if (points.size < 2) return null
        val cx = x.coerceIn(0f, 1f); val cy = y.coerceIn(0f, 1f)
        if (cx <= points.first().x + MIN_GAP || cx >= points.last().x - MIN_GAP) return null
        if (points.any { abs(it.x - cx) < MIN_GAP }) return null
        val index = points.indexOfFirst { it.x > cx }
        val list = points.toMutableList().apply { add(index, CurvePoint(cx, cy)) }
        return list to index
    }

    /** Moves point [index] to ([x], [y]) within its allowed range. */
    fun move(points: List<CurvePoint>, index: Int, x: Float, y: Float): List<CurvePoint> {
        if (index !in points.indices) return points
        val ny = y.coerceIn(0f, 1f)
        val nx = if (isEndpoint(points, index)) points[index].x else {
            val lo = points[index - 1].x + MIN_GAP
            val hi = points[index + 1].x - MIN_GAP
            if (lo > hi) points[index].x else x.coerceIn(lo, hi)
        }
        return points.toMutableList().apply { set(index, CurvePoint(nx, ny)) }
    }

    fun isEndpoint(points: List<CurvePoint>, index: Int): Boolean = index == 0 || index == points.lastIndex

    fun canRemove(points: List<CurvePoint>, index: Int): Boolean = index in 1 until points.lastIndex

    /** Removes point [index] if it is an interior point. */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> =
        if (canRemove(points, index)) points.toMutableList().apply { removeAt(index) } else points

    /** Histogram heights 0..1 for drawing (square-root scaled so small counts stay visible). */
    fun histogramHeights(hist: IntArray): FloatArray? {
        val max = hist.maxOrNull() ?: return null
        if (max <= 0) return null
        return FloatArray(hist.size) { kotlin.math.sqrt(hist[it].toFloat() / max) }
    }
}
