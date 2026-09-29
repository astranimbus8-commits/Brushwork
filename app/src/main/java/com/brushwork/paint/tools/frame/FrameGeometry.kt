package com.brushwork.paint.tools.frame

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure-Kotlin geometry of manga frame borders (no android imports, unit-tested on the JVM).
 * Everything is in DOCUMENT pixels.
 */

/** Axis-aligned rectangle. */
data class FrameRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /** Corners clockwise on screen (y down), starting top-left. */
    fun toPolygon(): List<Vec2> = listOf(Vec2(left, top), Vec2(right, top), Vec2(right, bottom), Vec2(left, bottom))
}

/** One panel: a convex polygon (vertices in order, either winding). */
data class Panel(val points: List<Vec2>) {
    val area: Float get() = abs(Geometry.signedArea(points))
    fun contains(p: Vec2): Boolean = Geometry.pointInPolygon(p, points)

    /** Bounding box of the vertices. */
    fun bounds(): FrameRect = FrameRect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}

/**
 * Settings of the frame divider. Gutters: [gutterH] is the gap between rows (used by cuts that
 * are more horizontal than vertical), [gutterV] the gap between columns (vertical cuts).
 */
data class FrameSettings(
    val borderWidth: Float,
    val borderColor: Int = 0xFF000000.toInt(),
    val uniformMargins: Boolean = true,
    val marginTop: Float,
    val marginRight: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val gutterH: Float,
    val gutterV: Float,
    /** Fill everything outside the panels with white (else transparent). */
    val fillOutside: Boolean = true,
    /** Layout of a new frame layer. */
    val rows: Int = 1,
    val cols: Int = 1,
) {
    fun withUniformMargin(m: Float) = copy(marginTop = m, marginRight = m, marginBottom = m, marginLeft = m)

    companion object {
        /** Sensible defaults relative to the canvas size (manga-like proportions). */
        fun defaultsFor(width: Int, height: Int): FrameSettings {
            val s = min(width, height).toFloat()
            val margin = (s * 0.05f).roundToInt().toFloat()
            return FrameSettings(
                borderWidth = max(2f, (s * 0.004f).roundToInt().toFloat()),
                marginTop = margin, marginRight = margin, marginBottom = margin, marginLeft = margin,
                gutterH = max(2f, (s * 0.025f).roundToInt().toFloat()),
                gutterV = max(2f, (s * 0.0125f).roundToInt().toFloat()),
            )
        }
    }
}

/** Visual style baked into a frame layer. */
data class FrameStyle(val borderWidth: Float, val borderColor: Int, val fillOutside: Boolean)

/** The editable state of one frame layer: the area inside the margins and its panels. */
data class FrameModel(val area: FrameRect, val panels: List<Panel>, val style: FrameStyle)

object FrameMath {
    /** Cuts within this angle of horizontal/vertical are snapped to it. */
    const val SNAP_DEGREES = 5f

    private const val EPS = 1e-3f

    /** Area inside the margins, or null if the margins leave no room. */
    fun frameArea(width: Int, height: Int, s: FrameSettings): FrameRect? {
        val r = FrameRect(s.marginLeft, s.marginTop, width - s.marginRight, height - s.marginBottom)
        return if (r.isEmpty || s.marginLeft < 0f || s.marginTop < 0f || s.marginRight < 0f || s.marginBottom < 0f) null else r
    }

    /**
     * Snaps the cut [a]→[b] to exactly horizontal or vertical when it is within [snapDegrees]
     * of that axis; [a] stays fixed.
     */
    fun snapCut(a: Vec2, b: Vec2, snapDegrees: Float = SNAP_DEGREES): Vec2 {
        val d = b - a
        if (d.lengthSq < 1e-9f) return b
        val deg = Math.toDegrees(atan2(abs(d.y), abs(d.x)).toDouble()).toFloat() // 0 = horizontal, 90 = vertical
        return when {
            deg <= snapDegrees -> Vec2(b.x, a.y)
            deg >= 90f - snapDegrees -> Vec2(a.x, b.y)
            else -> b
        }
    }

    /** True when the cut is more vertical than horizontal (it then uses the vertical gutter). */
    fun isVerticalCut(a: Vec2, b: Vec2): Boolean = abs(b.y - a.y) > abs(b.x - a.x)

    /**
     * Sutherland–Hodgman clip of a convex polygon to the half-plane
     * `{ p : (p - origin) · normal >= offset }` ([normal] must be unit length).
     */
    fun clipHalfPlane(poly: List<Vec2>, origin: Vec2, normal: Vec2, offset: Float): List<Vec2> {
        if (poly.isEmpty()) return poly
        val out = ArrayList<Vec2>(poly.size + 2)
        fun dist(p: Vec2) = (p - origin).dot(normal) - offset
        for (i in poly.indices) {
            val cur = poly[i]
            val next = poly[(i + 1) % poly.size]
            val dc = dist(cur)
            val dn = dist(next)
            if (dc >= 0f) out += cur
            if ((dc >= 0f) != (dn >= 0f)) {
                val t = dc / (dc - dn)
                out += cur.lerp(next, t)
            }
        }
        return dedupe(out)
    }

    private fun dedupe(poly: List<Vec2>): List<Vec2> {
        if (poly.size < 2) return poly
        val out = ArrayList<Vec2>(poly.size)
        for (p in poly) if (out.isEmpty() || out.last().distanceTo(p) > EPS) out += p
        while (out.size > 1 && out.first().distanceTo(out.last()) <= EPS) out.removeAt(out.lastIndex)
        return out
    }

    /** Minimum width of a convex polygon (smallest extent perpendicular to one of its edges). */
    fun minWidth(poly: List<Vec2>): Float {
        if (poly.size < 3) return 0f
        var best = Float.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]
            val dir = (poly[(i + 1) % poly.size] - a).normalized()
            if (dir.lengthSq < 0.5f) continue
            val n = dir.perpendicular()
            var extent = 0f
            for (p in poly) extent = max(extent, abs((p - a).dot(n)))
            best = min(best, extent)
        }
        return if (best == Float.MAX_VALUE) 0f else best
    }

    /** A piece is usable when it is at least [minSize] wide in every direction. */
    fun isSubstantial(poly: List<Vec2>, minSize: Float): Boolean =
        poly.size >= 3 && abs(Geometry.signedArea(poly)) > minSize * minSize * 0.5f && minWidth(poly) >= minSize

    /**
     * Splits a convex polygon by the infinite line through [a] and [b]. Each piece is pulled back
     * from the line by `gutter / 2` along the line normal. Returns null when the line doesn't cross
     * the polygon or when either piece would be a sliver narrower than [minSize].
     */
    fun splitPolygon(poly: List<Vec2>, a: Vec2, b: Vec2, gutter: Float, minSize: Float): Pair<List<Vec2>, List<Vec2>>? {
        val dir = (b - a).normalized()
        if (dir.lengthSq < 0.5f || poly.size < 3) return null
        val n = dir.perpendicular()
        var pos = false
        var neg = false
        for (p in poly) {
            val d = (p - a).dot(n)
            if (d > EPS) pos = true
            if (d < -EPS) neg = true
        }
        if (!pos || !neg) return null
        val half = max(0f, gutter) / 2f
        val first = clipHalfPlane(poly, a, n, half)
        val second = clipHalfPlane(poly, a, -n, half)
        if (!isSubstantial(first, minSize) || !isSubstantial(second, minSize)) return null
        return first to second
    }

    /**
     * True when the segment [a]-[b] passes through the interior of the convex polygon
     * (Cyrus–Beck clipping; merely touching an edge or vertex does not count).
     */
    fun segmentCrossesPolygon(a: Vec2, b: Vec2, poly: List<Vec2>, minLength: Float = 0.5f): Boolean {
        if (poly.size < 3) return false
        val sign = if (Geometry.signedArea(poly) >= 0f) 1f else -1f
        val d = b - a
        var t0 = 0f
        var t1 = 1f
        for (i in poly.indices) {
            val p = poly[i]
            val edge = poly[(i + 1) % poly.size] - p
            val inward = edge.perpendicular() * sign
            val num = (a - p).dot(inward)
            val den = d.dot(inward)
            if (abs(den) < 1e-9f) {
                if (num <= 0f) return false // parallel and outside (or on) this edge
            } else {
                val t = -num / den
                if (den > 0f) t0 = max(t0, t) else t1 = min(t1, t)
                if (t0 >= t1) return false
            }
        }
        return (t1 - t0) * d.length >= minLength
    }

    /**
     * Divides every panel crossed by the segment [a]-[b] along the segment's line. The gutter is
     * [gutterV] for cuts more vertical than horizontal, else [gutterH]. Returns the new panel
     * list, or null when no panel could be split.
     */
    fun divide(panels: List<Panel>, a: Vec2, b: Vec2, gutterH: Float, gutterV: Float, minSize: Float): List<Panel>? {
        if ((b - a).lengthSq < 1e-6f) return null
        val gutter = if (isVerticalCut(a, b)) gutterV else gutterH
        var changed = false
        val out = ArrayList<Panel>(panels.size + 4)
        for (panel in panels) {
            val split = if (segmentCrossesPolygon(a, b, panel.points)) splitPolygon(panel.points, a, b, gutter, minSize) else null
            if (split == null) {
                out += panel
            } else {
                out += Panel(split.first)
                out += Panel(split.second)
                changed = true
            }
        }
        return if (changed) out else null
    }

    /**
     * [rows] x [cols] panels filling [area] exactly, separated by [gutterH] between rows and
     * [gutterV] between columns. Null if the gutters leave no room for the panels.
     */
    fun grid(area: FrameRect, rows: Int, cols: Int, gutterH: Float, gutterV: Float): List<Panel>? {
        if (rows < 1 || cols < 1 || area.isEmpty) return null
        val cellW = (area.width - (cols - 1) * gutterV) / cols
        val cellH = (area.height - (rows - 1) * gutterH) / rows
        if (cellW <= 1f || cellH <= 1f) return null
        val out = ArrayList<Panel>(rows * cols)
        for (r in 0 until rows) {
            val top = area.top + r * (cellH + gutterH)
            val bottom = if (r == rows - 1) area.bottom else top + cellH
            for (c in 0 until cols) {
                val left = area.left + c * (cellW + gutterV)
                val right = if (c == cols - 1) area.right else left + cellW
                out += Panel(FrameRect(left, top, right, bottom).toPolygon())
            }
        }
        return out
    }

    /**
     * Shrinks a convex polygon by [d] on every side (the intersection of its edges' inward
     * half-planes), keeping sharp (mitered) corners. Null if it collapses.
     */
    fun inset(poly: List<Vec2>, d: Float): List<Vec2>? {
        if (poly.size < 3) return null
        if (d <= 0f) return poly
        val sign = if (Geometry.signedArea(poly) >= 0f) 1f else -1f
        var result = poly
        for (i in poly.indices) {
            val p = poly[i]
            val dir = (poly[(i + 1) % poly.size] - p).normalized()
            if (dir.lengthSq < 0.5f) continue
            result = clipHalfPlane(result, p, dir.perpendicular() * sign, d)
            if (result.size < 3) return null
        }
        return if (abs(Geometry.signedArea(result)) < 1e-3f) null else result
    }

    /** Topmost panel containing [p] (panels don't overlap, so any match is the one). */
    fun panelAt(panels: List<Panel>, p: Vec2): Int = panels.indexOfFirst { it.contains(p) }
}
