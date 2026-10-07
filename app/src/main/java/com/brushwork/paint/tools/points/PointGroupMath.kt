package com.brushwork.paint.tools.points

import android.graphics.RectF
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import kotlin.math.atan2

/**
 * v1.7 (item 1, design §3.1 and §4.5, I12): the pure math of a point group (no tool state). The
 * gizmo, the marquee, the pinch and the X / Y pill share it, so every point editor maps its group
 * the same way.
 */
object PointGroupMath {

    /** The document bounds of the selected points of [points] (indices past its end ignored); null when none. */
    fun bounds(points: List<Vec2>, sel: PointSelection): RectF? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        var any = false
        for (i in sel.indices) {
            if (i >= points.size) break
            val p = points[i]
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            any = true
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return if (any) RectF(l, t, r, b) else null
    }

    /**
     * [points] with its SELECTED points mapped by [m] (the others, and the list's size, unchanged):
     * what a group gesture applies to the points captured at its start. The identity returns
     * bitwise-equal values.
     */
    fun mapped(points: List<Vec2>, sel: PointSelection, m: Affine2): List<Vec2> {
        if (m == Affine2.IDENTITY || sel.isEmpty) return points.toList()
        val out = points.toMutableList()
        for (i in sel.indices) {
            if (i >= out.size) break
            out[i] = m.map(out[i])
        }
        return out
    }

    /** Indices whose points lie inside the document rectangle (the marquee; edges count, either drag direction). */
    fun inside(points: List<Vec2>, rect: RectF): List<Int> {
        val l = minOf(rect.left, rect.right)
        val r = maxOf(rect.left, rect.right)
        val t = minOf(rect.top, rect.bottom)
        val b = maxOf(rect.top, rect.bottom)
        val out = ArrayList<Int>()
        for (i in points.indices) {
            val p = points[i]
            if (p.x >= l && p.x <= r && p.y >= t && p.y <= b) out.add(i)
        }
        return out
    }

    /**
     * The similarity a two-finger pinch makes (scale and rotation about the box centre, plus the
     * centroid's move): fingers from [startA], [startB] now at [nowA], [nowB]; [pivot] is the box
     * centre. Fingers that started on one spot only move the group.
     */
    fun pinch(startA: Vec2, startB: Vec2, nowA: Vec2, nowB: Vec2, pivot: Vec2): Affine2 {
        val s = startB - startA
        val n = nowB - nowA
        val move = (nowA + nowB) * 0.5f - (startA + startB) * 0.5f
        val translate = Affine2.translate(move.x, move.y)
        val sl = s.length
        val nl = n.length
        if (!(sl > MIN_SPAN) || !nl.isFinite()) return translate
        val k = nl / sl
        val deg = Math.toDegrees(atan2(s.cross(n).toDouble(), s.dot(n).toDouble())).toFloat()
        return translate * Affine2.rotateAbout(pivot, deg) * Affine2.scaleAbout(pivot, k, k)
    }

    /** Fingers closer than this (document px) at the start make no scale or rotation. */
    private const val MIN_SPAN = 1e-3f
}
