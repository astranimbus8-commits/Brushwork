package com.brushwork.paint.tools.points

import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.IncrementMath
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.snap.SnapSession
import com.brushwork.paint.tools.select.POINT_GUIDE_EPS
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.GridSnap
import com.brushwork.paint.tools.transform.PeriodicLines
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapHit
import kotlin.math.abs

/**
 * v1.10 (A5, R8): snapping for a group of points that moves together: a drag on a point of the
 * group or inside the group gizmo, in the Shape tool and the Curve tools (Curve, Polyline and
 * Path). In 1.9 the gizmo never snapped (cause A) and a group drag snapped only the grabbed point
 * (cause B), so the other points passed every guide.
 *
 * Per axis, the candidates are the moving box's min, centre and max lines, then every moving
 * point, the grabbed one first (earlier candidates win ties). The candidate with the smallest
 * correction to a target line within the snap distance moves the whole group. An axis no target
 * placed follows the square grid when grid snapping is on (the grabbed point, or the box's min
 * corner for the gizmo, lands on a grid line, as the vector tools' single points do); else, while
 * increments are on, it moves by multiples of the Length step. So the order is the one of every
 * vector drag (design 3.4 of v1.6): a guide, then the grid, then the step. Above
 * [MAX_POINT_CANDIDATES] moving points only the box's lines are candidates.
 *
 * A gizmo scale handle snaps only the edges it drags ([scale], [edge]); the rotate knob keeps its
 * angle steps and never snaps to points.
 *
 * The moving points must not be targets themselves: the tool begins its [SnapSession] without
 * them (`beginSnapWithout { it in sel }`). Always snap the move the finger alone gives (never the
 * previously snapped one), so moving farther than the snap distance lets go of a guide.
 *
 * Pure apart from the session it asks; made per move (it is cheap) by [of].
 */
class PointGroupSnap internal constructor(
    /** One coordinate snapped to the targets on an axis ([SnapSession.snapValue]), or null. */
    private val objects: (Float, SnapAxis) -> SnapHit?,
    /** Shows the guides of every target line the box's lines lie on; null hides them. */
    private val showGuides: (DocBox?) -> Unit,
    /** The square grid the points follow (grid snapping on), or null. */
    private val grid: GridSnap?,
    /** The Length step while increments are on and the grid is off, or null. */
    private val step: Float?,
) {
    constructor(session: SnapSession, grid: GridSnap?, step: Float?) : this(
        objects = session::snapValue,
        showGuides = { box -> if (box == null) session.clearGuides() else session.showGuidesFor(box, POINT_GUIDE_EPS) },
        grid = grid,
        step = step,
    )

    /**
     * The snapped delta of a group moved by the finger's raw delta [d] (document px). [moving]
     * holds the moving points where the gesture began as x, y pairs, [box] their bounds then, and
     * [grabbed] the index (in [moving]) of the point under the finger, or -1 (the gizmo). Shows the
     * guides of what the group snapped to, or hides them.
     */
    fun snap(moving: FloatArray, box: DocBox, grabbed: Int, d: Vec2): Vec2 {
        if (moving.size < 2 || !d.x.isFinite() || !d.y.isFinite() || !finite(box)) {
            showGuides(null)
            return d
        }
        val g = if (grabbed >= 0 && grabbed < moving.size / 2) grabbed else -1
        val x = axis(SnapAxis.X, moving, box, g, d.x)
        val y = axis(SnapAxis.Y, moving, box, g, d.y)
        if (x.byObjects || y.byObjects) {
            // A point that snapped shows its own guide (a zero-width box on that axis); a box
            // line, or an axis no target placed, shows the moved box's.
            val px = x.point
            val py = y.point
            showGuides(
                DocBox(
                    if (px.isNaN()) box.left + x.d else px,
                    if (py.isNaN()) box.top + y.d else py,
                    if (px.isNaN()) box.right + x.d else px,
                    if (py.isNaN()) box.bottom + y.d else py,
                ),
            )
        } else {
            showGuides(null)
        }
        return Vec2(x.d, y.d)
    }

    /**
     * Scale handles: [movingEdgeValue] (where a dragged edge of the moving box is now, document
     * px) snapped on [axis] to the closest target within reach, else itself. Leaves the guides.
     */
    fun edge(movingEdgeValue: Float, axis: SnapAxis): Float =
        if (movingEdgeValue.isFinite()) objects(movingEdgeValue, axis)?.pos ?: movingEdgeValue else movingEdgeValue

    /**
     * A drag on a gizmo scale handle [part]: [m], the map `PointGizmo.dragMap` gives (a scale
     * about [pivot], the box centre), corrected so the edges that handle drags land on a target
     * ([edge]): the moving points' bounds were [box] where the gesture began. An edge handle moves
     * one edge, a corner one edge on each axis; with [keepProportions] a corner keeps one factor
     * and the closer edge decides it (as the Transform tool's corners). The other edges never
     * snap: they follow the factor. Shows the guides of the snapped edges, or hides them. Returns
     * [m] unchanged when no dragged edge is within reach, and for the other parts (the move, the
     * rotate knob: they keep their own rules).
     */
    fun scale(m: Affine2, part: PointGizmo.Part, box: DocBox, pivot: Vec2, keepProportions: Boolean): Affine2 {
        val sideX = when (part) {
            PointGizmo.Part.SCALE_E, PointGizmo.Part.SCALE_NE, PointGizmo.Part.SCALE_SE -> 1
            PointGizmo.Part.SCALE_W, PointGizmo.Part.SCALE_NW, PointGizmo.Part.SCALE_SW -> -1
            else -> 0
        }
        val sideY = when (part) {
            PointGizmo.Part.SCALE_S, PointGizmo.Part.SCALE_SE, PointGizmo.Part.SCALE_SW -> 1
            PointGizmo.Part.SCALE_N, PointGizmo.Part.SCALE_NE, PointGizmo.Part.SCALE_NW -> -1
            else -> 0
        }
        if (sideX == 0 && sideY == 0) return m
        // dragMap's scale has no shear: a = the x factor, d = the y factor.
        if (m.b != 0f || m.c != 0f || !finite(box) || !pivot.x.isFinite() || !pivot.y.isFinite()) return m
        val hx = edgeHit(SnapAxis.X, sideX, m.a, box.left, box.right, pivot.x)
        val hy = edgeHit(SnapAxis.Y, sideY, m.d, box.top, box.bottom, pivot.y)
        if (hx == null && hy == null) {
            showGuides(null)
            return m
        }
        val uniform = keepProportions && sideX != 0 && sideY != 0
        var kx = m.a
        var ky = m.d
        var gx = hx
        var gy = hy
        if (uniform) {
            // One factor: the closer edge decides (x on a tie); the other follows it.
            val byX = hx != null && (hy == null || hx.distance <= hy.distance + TIE_EPS)
            val k = if (byX) hx!!.factor else hy!!.factor
            kx = k
            ky = k
            if (byX) gy = null else gx = null
        } else {
            if (hx != null) kx = hx.factor
            if (hy != null) ky = hy.factor
        }
        // The guides: a snapped edge's own line; on an axis that did not snap, the scaled box's.
        fun lo(min: Float, max: Float, p: Float, k: Float) = minOf(p + (min - p) * k, p + (max - p) * k)
        fun hi(min: Float, max: Float, p: Float, k: Float) = maxOf(p + (min - p) * k, p + (max - p) * k)
        showGuides(
            DocBox(
                gx?.at ?: lo(box.left, box.right, pivot.x, kx),
                gy?.at ?: lo(box.top, box.bottom, pivot.y, ky),
                gx?.at ?: hi(box.left, box.right, pivot.x, kx),
                gy?.at ?: hi(box.top, box.bottom, pivot.y, ky),
            ),
        )
        return Affine2.scaleAbout(pivot, kx, ky)
    }

    /**
     * The dragged edge on [axis] ([side] -1 the min edge, +1 the max, 0 none) scaled by [k] about
     * [p], snapped: the factor that puts it on the target, or null (none within reach, or an edge
     * on the pivot, which no factor moves).
     */
    private fun edgeHit(axis: SnapAxis, side: Int, k: Float, min: Float, max: Float, p: Float): EdgeHit? {
        if (side == 0 || !k.isFinite()) return null
        val arm = (if (side < 0) min else max) - p
        if (abs(arm) < MIN_ARM) return null
        val now = p + arm * k
        val hit = objects(now, axis) ?: return null
        val factor = (hit.pos - p) / arm
        if (!factor.isFinite() || abs(factor) < MIN_FACTOR) return null
        return EdgeHit(factor, hit.distance, hit.pos)
    }

    /** A dragged edge that snapped: the [factor] that puts it on the target line at [at], [distance] away. */
    private class EdgeHit(val factor: Float, val distance: Float, val at: Float)

    /**
     * One axis of [snap]: the delta [d] corrected by the closest candidate within reach, else by
     * the grid, else by the step.
     */
    private fun axis(axis: SnapAxis, moving: FloatArray, box: DocBox, grabbed: Int, d: Float): AxisMove {
        val o = if (axis == SnapAxis.X) 0 else 1
        val min = if (axis == SnapAxis.X) box.left else box.top
        val max = if (axis == SnapAxis.X) box.right else box.bottom
        var best = Float.POSITIVE_INFINITY
        var correction = 0f
        var point = Float.NaN
        // The box's lines first (they win ties), then the points, the grabbed one first.
        fun consider(v: Float, isPoint: Boolean) {
            val hit = objects(v, axis) ?: return
            if (hit.distance < best - TIE_EPS) {
                best = hit.distance
                correction = hit.pos - v
                point = if (isPoint) hit.pos else Float.NaN
            }
        }
        consider(min + d, isPoint = false)
        consider((min + max) / 2f + d, isPoint = false)
        consider(max + d, isPoint = false)
        val n = moving.size / 2
        // Above the cap only the box's lines (its nine handles) are candidates: a move stays
        // within its budget however many points move.
        if (n <= MAX_POINT_CANDIDATES) {
            if (grabbed >= 0) consider(moving[grabbed * 2 + o] + d, isPoint = true)
            for (i in 0 until n) if (i != grabbed) consider(moving[i * 2 + o] + d, isPoint = true)
        }
        if (best.isFinite()) return AxisMove(d + correction, byObjects = true, point = point)
        // The grid: where the grabbed point (or the box's min corner) lands decides.
        val lines = if (axis == SnapAxis.X) grid?.x else grid?.y
        if (lines != null && lines.spacing > 0f && lines.spacing.isFinite()) {
            val ref = if (grabbed >= 0) moving[grabbed * 2 + o] else min
            return AxisMove(lines.nearest(ref + d) - ref)
        }
        val s = step ?: return AxisMove(d)
        return AxisMove(IncrementMath.snapDelta(d, s))
    }

    /**
     * What one axis of a move became: the delta [d]; [byObjects] when a target placed it, then
     * [point] is where the moving point that snapped landed (NaN when a box line snapped).
     */
    private class AxisMove(val d: Float, val byObjects: Boolean = false, val point: Float = Float.NaN)

    companion object {
        /**
         * Above this many moving points only the moving box's lines (its nine handles: min, centre
         * and max on each axis) are snap candidates, not every point (design R8, J25).
         */
        const val MAX_POINT_CANDIDATES = 256

        /** Distances this close (document px) are a tie: the earlier candidate keeps it. */
        private const val TIE_EPS = 1e-3f

        /** A dragged edge closer than this to the pivot (document px) is not scaled onto a guide. */
        private const val MIN_ARM = 1e-3f

        /** A snapped factor smaller than this would flatten the points: it is not used. */
        private const val MIN_FACTOR = 1e-3f

        private fun finite(b: DocBox) = b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite()

        /**
         * The square grid the vector tools' points follow ([grid] with grid snapping on, as
         * `SnapService.gridPoint`), else null.
         */
        fun gridOf(grid: GridSettings): GridSnap? =
            if (grid.enabled && grid.snap && grid.type == GridType.SQUARE && grid.spacingPx > 0f && grid.spacingPx.isFinite()) {
                GridSnap(PeriodicLines(grid.spacingPx, grid.offsetXPx), PeriodicLines(grid.spacingPx, grid.offsetYPx))
            } else null

        /**
         * A group snap for one move in [c]'s tools: its targets from [session], the grid when grid
         * snapping is on, else the Length step while increments are on.
         */
        fun of(session: SnapSession, c: EditorController): PointGroupSnap {
            val grid = gridOf(c.grid)
            return PointGroupSnap(session, grid, if (grid == null) c.increments.step(IncrementKind.LENGTH) else null)
        }

        /** [points] as x, y pairs (what [snap] takes). */
        fun pairs(points: List<Vec2>): FloatArray {
            val out = FloatArray(points.size * 2)
            for (i in points.indices) {
                out[i * 2] = points[i].x
                out[i * 2 + 1] = points[i].y
            }
            return out
        }

        /** The bounds of the finite points of [pairs] (x, y pairs), or null when there are none. */
        fun bounds(pairs: FloatArray): DocBox? {
            var l = Float.POSITIVE_INFINITY
            var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY
            var b = Float.NEGATIVE_INFINITY
            var i = 0
            while (i + 1 < pairs.size) {
                val x = pairs[i]
                val y = pairs[i + 1]
                i += 2
                if (!x.isFinite() || !y.isFinite()) continue
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            return if (l <= r) DocBox(l, t, r, b) else null
        }
    }
}
