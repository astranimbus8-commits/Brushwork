package com.brushwork.paint.tools

import android.graphics.RectF
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import kotlin.math.max

/**
 * Which two-finger gestures go to the object being placed instead of the view (v1.5, §4.7;
 * frozen): a pinch scales, rotates or moves the object only when finger A or finger B lands
 * inside the object's box AS DRAWN on screen. Their midpoint is only the pivot: two fingers
 * on either side of a small object zoom the view.
 *
 * Every test is done in screen space: the box gets [EDGE_GRACE_DP] of grace at its edges, and a
 * box smaller than [MIN_BOX_DP] on screen (in either direction) is enlarged to that size around
 * its centre so small objects can still be grabbed. All inputs are document px.
 */
object PinchTargeting {
    const val EDGE_GRACE_DP = 4f
    const val MIN_BOX_DP = 44f
    const val LINE_BAND_DP = 12f

    /** True when [a] or [b] (doc px) is inside the quad [corners] (doc px), grown by the grace and to the minimum on-screen size. */
    fun acceptsQuad(a: Vec2, b: Vec2, corners: List<Vec2>, t: ViewTransform): Boolean {
        if (corners.size < 3 || corners.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val sa = finiteScreen(a, t)
        val sb = finiteScreen(b, t)
        if (sa == null && sb == null) return false
        val quad = enlarged(corners.map { t.docToScreen(it) }, t.dp(MIN_BOX_DP))
        val grace = t.dp(EDGE_GRACE_DP)
        return (sa != null && inside(sa, quad, grace)) || (sb != null && inside(sb, quad, grace))
    }

    /** [acceptsQuad] for an axis-aligned document rectangle (the view may rotate it on screen). */
    fun acceptsRect(a: Vec2, b: Vec2, r: RectF, t: ViewTransform): Boolean = acceptsQuad(
        a, b,
        listOf(Vec2(r.left, r.top), Vec2(r.right, r.top), Vec2(r.right, r.bottom), Vec2(r.left, r.bottom)),
        t,
    )

    /**
     * Line-like targets: [a] or [b] within max([halfWidthDoc], [LINE_BAND_DP]) (plus the grace)
     * of the segment [p0]-[p1]; a segment shorter than [MIN_BOX_DP] on screen is lengthened to
     * that around its centre.
     */
    fun acceptsSegment(a: Vec2, b: Vec2, p0: Vec2, p1: Vec2, halfWidthDoc: Float, t: ViewTransform): Boolean {
        if (!p0.x.isFinite() || !p0.y.isFinite() || !p1.x.isFinite() || !p1.y.isFinite()) return false
        val sa = finiteScreen(a, t)
        val sb = finiteScreen(b, t)
        if (sa == null && sb == null) return false
        var s0 = t.docToScreen(p0)
        var s1 = t.docToScreen(p1)
        val min = t.dp(MIN_BOX_DP)
        val len = s0.distanceTo(s1)
        if (len < min) {
            val c = (s0 + s1) / 2f
            val dir = if (len > 1e-3f) (s1 - s0) / len else Vec2(1f, 0f)
            s0 = c - dir * (min / 2f)
            s1 = c + dir * (min / 2f)
        }
        val halfScreen = if (halfWidthDoc.isFinite() && halfWidthDoc > 0f) halfWidthDoc * t.zoom else 0f
        val band = max(halfScreen, t.dp(LINE_BAND_DP)) + t.dp(EDGE_GRACE_DP)
        return (sa != null && Geometry.distanceToSegment(sa, s0, s1) <= band) ||
            (sb != null && Geometry.distanceToSegment(sb, s0, s1) <= band)
    }

    private fun finiteScreen(p: Vec2, t: ViewTransform): Vec2? =
        if (p.x.isFinite() && p.y.isFinite()) t.docToScreen(p) else null

    /** Inside the polygon, or within [grace] of its outline. */
    private fun inside(p: Vec2, poly: List<Vec2>, grace: Float): Boolean {
        if (Geometry.pointInPolygon(p, poly)) return true
        for (i in poly.indices) {
            if (Geometry.distanceToSegment(p, poly[i], poly[(i + 1) % poly.size]) <= grace) return true
        }
        return false
    }

    /**
     * [quad] (screen px) stretched around its centre so its extent along its own first edge and
     * across it is at least [min] (rotated boxes stay rotated).
     */
    internal fun enlarged(quad: List<Vec2>, min: Float): List<Vec2> {
        var cx = 0f; var cy = 0f
        for (q in quad) { cx += q.x; cy += q.y }
        val c = Vec2(cx / quad.size, cy / quad.size)
        val edge = quad[1] - quad[0]
        val u = if (edge.length > 1e-3f) edge.normalized() else {
            // A degenerate first edge: use any other edge's direction, else the screen axes.
            quad.indices.map { quad[(it + 1) % quad.size] - quad[it] }.firstOrNull { it.length > 1e-3f }?.normalized() ?: Vec2(1f, 0f)
        }
        val v = u.perpendicular()
        var uMin = Float.POSITIVE_INFINITY; var uMax = Float.NEGATIVE_INFINITY
        var vMin = Float.POSITIVE_INFINITY; var vMax = Float.NEGATIVE_INFINITY
        for (q in quad) {
            val d = q - c
            val pu = d.dot(u); val pv = d.dot(v)
            if (pu < uMin) uMin = pu
            if (pu > uMax) uMax = pu
            if (pv < vMin) vMin = pv
            if (pv > vMax) vMax = pv
        }
        val wu = uMax - uMin
        val wv = vMax - vMin
        if (wu >= min && wv >= min) return quad
        // Degenerate extents (a point or a line) can't be scaled: build the box from the centre.
        if (wu < 1e-3f || wv < 1e-3f) {
            val hu = max(wu, min) / 2f
            val hv = max(wv, min) / 2f
            val mu = (uMin + uMax) / 2f
            val mv = (vMin + vMax) / 2f
            val o = c + u * mu + v * mv
            return listOf(o - u * hu - v * hv, o + u * hu - v * hv, o + u * hu + v * hv, o - u * hu + v * hv)
        }
        val ku = if (wu < min) min / wu else 1f
        val kv = if (wv < min) min / wv else 1f
        return quad.map { q ->
            val d = q - c
            c + u * (d.dot(u) * ku) + v * (d.dot(v) * kv)
        }
    }
}
