package com.brushwork.paint.assist

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapHit
import kotlin.math.abs

/**
 * "Snap to objects" math of the ruler's size handles (pure, document px): a radius or semi-axis
 * snaps so that the ruler's outline touches a target line exactly (a table line, a layer's edge,
 * the canvas center...).
 */
internal object RulerHandleSnap {
    /**
     * Only axes a handle really moves along snap: a direction whose component on an axis is below
     * this would turn a few px of snapping into a big jump of the radius.
     */
    private const val MIN_SLOPE = 0.5f

    /**
     * [r] (a radius / semi-axis of a ruler centered at [center]) snapped so that one of the points
     * `center ± dir * r` of [dirs] lands on the closest target line within reach ([snapValue] finds
     * a line near a coordinate on an axis, or null). Returns the snapped radius (at least
     * [minRadius]) and the point that lies on the line, or null when no line is within reach.
     */
    fun radius(center: Vec2, r: Float, dirs: List<Vec2>, minRadius: Float, snapValue: (Float, SnapAxis) -> SnapHit?): Pair<Float, Vec2>? {
        if (!r.isFinite() || !center.x.isFinite() || !center.y.isFinite()) return null
        var best: SnapHit? = null
        var bestR = r
        var bestPoint = center
        for (dir in dirs) {
            for (sign in SIGNS) {
                val u = dir * sign
                for (axis in SnapAxis.entries) {
                    val k = if (axis == SnapAxis.X) u.x else u.y
                    if (!(abs(k) >= MIN_SLOPE)) continue
                    val c = if (axis == SnapAxis.X) center.x else center.y
                    val hit = snapValue(c + k * r, axis) ?: continue
                    val nr = (hit.pos - c) / k
                    if (!nr.isFinite() || nr < minRadius) continue
                    if (best == null || hit.distance < best.distance) {
                        best = hit
                        bestR = nr
                        bestPoint = center + u * nr
                    }
                }
            }
        }
        return if (best == null) null else bestR to bestPoint
    }

    private val SIGNS = floatArrayOf(1f, -1f)
}
