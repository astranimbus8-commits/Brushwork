package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Orientation of a guide: [X] = a vertical line at an x position, [Y] = a horizontal line at a y position. */
enum class SnapAxis { X, Y }

/** One of the three alignment lines of a box on an axis: left / top, center, right / bottom. */
enum class SnapEdge { START, CENTER, END }

/** What a snap line belongs to (earlier entries win ties). */
enum class SnapSource { CANVAS, SELECTION, OBJECT, GRID }

/** A box other things align to (document px). [name] is shown in guide labels (e.g. a layer name). */
data class SnapBox(val box: DocBox, val name: String, val source: SnapSource = SnapSource.OBJECT)

/**
 * One line something can snap to: [pos] on [axis] (document px). [spanStart]..[spanEnd] is the
 * extent of the object it belongs to along the other axis (where the guide is drawn).
 */
data class SnapLine(
    val axis: SnapAxis,
    val pos: Float,
    val edge: SnapEdge,
    val source: SnapSource,
    val name: String,
    val spanStart: Float,
    val spanEnd: Float,
) {
    /** Short text for the guide label: "Canvas center", "Layer 2 top", "Grid"... */
    val label: String
        get() = when (source) {
            SnapSource.CANVAS -> if (edge == SnapEdge.CENTER) "Canvas center" else "Canvas edge"
            SnapSource.GRID -> "Grid"
            else -> "$name ${SnapGuides.edgeWord(axis, edge)}"
        }
}

/** Evenly spaced lines on one axis: `offset + k * spacing` ([spacing] > 0). */
data class PeriodicLines(val spacing: Float, val offset: Float) {
    /** The line nearest to [v]. */
    fun nearest(v: Float): Float = offset + TransformState.roundHalfUp((v - offset) / spacing) * spacing

    /** The first line strictly after [v] in [direction] (+1 / -1). */
    fun next(v: Float, direction: Int): Float {
        val k = (v - offset) / spacing
        return if (direction > 0) offset + (floor(k) + 1f) * spacing else offset + (ceil(k) - 1f) * spacing
    }
}

/**
 * Everything a moving box can snap to, per axis. Build with [SnapTargets.build]. [gridX] /
 * [gridY] are optional evenly spaced grid lines (spanning the canvas).
 */
class SnapTargets(
    val xs: List<SnapLine>,
    val ys: List<SnapLine>,
    val gridX: PeriodicLines? = null,
    val gridY: PeriodicLines? = null,
    val canvasW: Float = 0f,
    val canvasH: Float = 0f,
) {
    fun lines(axis: SnapAxis): List<SnapLine> = if (axis == SnapAxis.X) xs else ys
    fun grid(axis: SnapAxis): PeriodicLines? = if (axis == SnapAxis.X) gridX else gridY

    /** A grid line at [pos] as a [SnapLine] (spans the whole canvas). */
    fun gridLine(axis: SnapAxis, pos: Float): SnapLine =
        SnapLine(axis, pos, SnapEdge.CENTER, SnapSource.GRID, "Grid", 0f, if (axis == SnapAxis.X) canvasH else canvasW)

    companion object {
        /**
         * The canvas edges and center ([canvasW] x [canvasH], unless [includeCanvas] is false),
         * then the left / center / right and top / center / bottom lines of every box of
         * [boxes] (in order: earlier boxes win ties), plus [grid].
         */
        fun build(canvasW: Float, canvasH: Float, boxes: List<SnapBox>, grid: GridSnap? = null, includeCanvas: Boolean = true): SnapTargets {
            val xs = ArrayList<SnapLine>(boxes.size * 3 + 3)
            val ys = ArrayList<SnapLine>(boxes.size * 3 + 3)
            fun add(b: SnapBox) {
                val box = b.box
                if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) return
                for (e in SnapEdge.entries) {
                    xs += SnapLine(SnapAxis.X, SnapGuides.feature(box, SnapAxis.X, e), e, b.source, b.name, box.top, box.bottom)
                    ys += SnapLine(SnapAxis.Y, SnapGuides.feature(box, SnapAxis.Y, e), e, b.source, b.name, box.left, box.right)
                }
            }
            if (includeCanvas && canvasW > 0f && canvasH > 0f) add(SnapBox(DocBox(0f, 0f, canvasW, canvasH), "Canvas", SnapSource.CANVAS))
            boxes.forEach(::add)
            grid?.fixedX?.forEach { xs += SnapLine(SnapAxis.X, it, SnapEdge.CENTER, SnapSource.GRID, "Grid", 0f, canvasH) }
            grid?.fixedY?.forEach { ys += SnapLine(SnapAxis.Y, it, SnapEdge.CENTER, SnapSource.GRID, "Grid", 0f, canvasW) }
            return SnapTargets(xs, ys, grid?.x, grid?.y, canvasW, canvasH)
        }
    }
}

/** Grid lines to snap to: evenly spaced families and/or fixed lines (e.g. the rule of thirds). */
data class GridSnap(
    val x: PeriodicLines? = null,
    val y: PeriodicLines? = null,
    val fixedX: List<Float> = emptyList(),
    val fixedY: List<Float> = emptyList(),
)

/**
 * A guide to show for an alignment: the line at [pos] on [axis], drawn strongly from [start] to
 * [end] (covering both the target object and the moving box, document px) and faintly across
 * the whole canvas. [label] names what was aligned to.
 */
data class SnapGuide(
    val axis: SnapAxis,
    val pos: Float,
    val start: Float,
    val end: Float,
    val label: String,
    val source: SnapSource,
)

/**
 * Result of a snap: move the box by ([dx], [dy]) and show [guides]. [snappedX] / [snappedY] tell
 * whether a line was within reach on that axis: true with a zero offset when the box already sits
 * exactly on a line (it must then stay exactly there, e.g. not be rounded to whole pixels).
 */
data class SnapResult(
    val dx: Float,
    val dy: Float,
    val guides: List<SnapGuide>,
    val snappedX: Boolean = dx != 0f,
    val snappedY: Boolean = dy != 0f,
)

/** A value that snapped to [line] (its position is [line].pos). */
data class SnapHit(val line: SnapLine, val distance: Float) {
    val pos: Float get() = line.pos
}

/**
 * Smart guides (Illustrator-style alignment snapping), pure Kotlin in document pixels, so any
 * tool that moves a box (transform, text, shapes) can reuse it:
 *  1. build [SnapTargets] from the canvas, the other objects' bounds (e.g. layer content
 *     bounds) and optionally the grid ([gridLines]);
 *  2. once the finger has really started dragging (past a few dp of touch slop, so a tap never
 *     jumps the box onto a guide), snap the box the finger alone would give (never the
 *     previously snapped one, so moving the finger farther than the threshold releases the
 *     snap) with [snapMove], resize handles with [snapValue], free points with [snapPoint],
 *     nudges with [snapNudge];
 *  3. draw the returned [SnapGuide]s (e.g. with SnapGuideRenderer) until the finger lifts.
 *
 * Any of the moving box's left / center / right lines snaps to any vertical target line within
 * the threshold (edges to edges, centers to centers, side by side), likewise top / center /
 * bottom; the closest one wins.
 */
object SnapGuides {
    /** Guides are listed for lines within this distance (document px) of the box. */
    const val ALIGNED_EPS = 1e-3f

    /** Position of line [edge] of [box] on [axis]. */
    fun feature(box: DocBox, axis: SnapAxis, edge: SnapEdge): Float = when (axis) {
        SnapAxis.X -> when (edge) {
            SnapEdge.START -> box.left
            SnapEdge.CENTER -> (box.left + box.right) / 2f
            SnapEdge.END -> box.right
        }
        SnapAxis.Y -> when (edge) {
            SnapEdge.START -> box.top
            SnapEdge.CENTER -> (box.top + box.bottom) / 2f
            SnapEdge.END -> box.bottom
        }
    }

    fun edgeWord(axis: SnapAxis, edge: SnapEdge): String = when (edge) {
        SnapEdge.START -> if (axis == SnapAxis.X) "left" else "top"
        SnapEdge.CENTER -> "center"
        SnapEdge.END -> if (axis == SnapAxis.X) "right" else "bottom"
    }

    /**
     * The target line closest to [value] on [axis] within [threshold] (document px), or null.
     * Ties go to the earlier line (canvas, then the boxes in order, grid last).
     */
    fun snapValue(value: Float, axis: SnapAxis, targets: SnapTargets, threshold: Float): SnapHit? {
        if (!value.isFinite() || !(threshold >= 0f)) return null
        var best: SnapLine? = null
        var bestD = Float.POSITIVE_INFINITY
        for (l in targets.lines(axis)) {
            val d = abs(l.pos - value)
            if (d <= threshold && d < bestD) { best = l; bestD = d }
        }
        targets.grid(axis)?.let { g ->
            if (g.spacing > 0f && g.spacing.isFinite()) {
                val p = g.nearest(value)
                val d = abs(p - value)
                if (d <= threshold && d < bestD) { best = targets.gridLine(axis, p); bestD = d }
            }
        }
        return best?.let { SnapHit(it, bestD) }
    }

    /**
     * Snaps a moving [box]: the closest of its left / center / right lines to a vertical target
     * line within [threshold], and likewise top / center / bottom. Returns the offset to apply
     * (0 on an axis that didn't snap, or that is already exactly on a line: see
     * [SnapResult.snappedX]) and the guides of the snapped box.
     */
    fun snapMove(box: DocBox, targets: SnapTargets, threshold: Float): SnapResult {
        val dx = bestOffset(box, SnapAxis.X, targets, threshold)
        val dy = bestOffset(box, SnapAxis.Y, targets, threshold)
        val moved = box.offset(dx ?: 0f, dy ?: 0f)
        return SnapResult(dx ?: 0f, dy ?: 0f, guidesFor(moved, targets), snappedX = dx != null, snappedY = dy != null)
    }

    /**
     * Offset that puts the closest of the box's lines on its target line, or null when none is
     * within reach. When two of the box's lines are equally close (e.g. its top and its center
     * straddle the canvas center), a like-for-like pairing wins (center to center, top to top...),
     * then the box's center.
     */
    private fun bestOffset(box: DocBox, axis: SnapAxis, targets: SnapTargets, threshold: Float): Float? {
        var best: SnapHit? = null
        var bestEdge = SnapEdge.START
        var offset: Float? = null
        for (e in SnapEdge.entries) {
            val v = feature(box, axis, e)
            val hit = snapValue(v, axis, targets, threshold) ?: continue
            if (best == null || isBetter(hit, e, best, bestEdge)) {
                best = hit
                bestEdge = e
                offset = hit.pos - v
            }
        }
        return offset
    }

    /** Whether [hit] of the box's line [edge] beats [other] of its line [otherEdge] (see [bestOffset]). */
    private fun isBetter(hit: SnapHit, edge: SnapEdge, other: SnapHit, otherEdge: SnapEdge): Boolean {
        if (abs(hit.distance - other.distance) > TIE_EPS) return hit.distance < other.distance
        val like = hit.line.edge == edge
        val otherLike = other.line.edge == otherEdge
        if (like != otherLike) return like
        return edge == SnapEdge.CENTER && otherEdge != SnapEdge.CENTER
    }

    /**
     * A nudge by ([dx], [dy]) from [box] that stops at the first target line one of the box's
     * lines would jump over, so repeated nudges land exactly on alignments instead of skipping
     * them. Lines closer than [minStep] are ignored (a whole-pixel nudge always moves).
     */
    fun snapNudge(box: DocBox, dx: Float, dy: Float, targets: SnapTargets, minStep: Float = 1f): SnapResult {
        val sx = nudgeAxis(box, SnapAxis.X, dx, targets, minStep)
        val sy = nudgeAxis(box, SnapAxis.Y, dy, targets, minStep)
        return SnapResult(sx, sy, guidesFor(box.offset(sx, sy), targets))
    }

    private fun nudgeAxis(box: DocBox, axis: SnapAxis, d: Float, targets: SnapTargets, minStep: Float): Float {
        if (d == 0f || !d.isFinite()) return d
        val dir = if (d > 0f) 1 else -1
        val limit = abs(d)
        val floor = max(minStep, 0f)
        var best = limit
        for (e in SnapEdge.entries) {
            val v = feature(box, axis, e)
            for (l in targets.lines(axis)) {
                val t = (l.pos - v) * dir
                if (t >= floor && t < best && t > 0f) best = t
            }
            targets.grid(axis)?.let { g ->
                if (g.spacing > 0f && g.spacing.isFinite()) {
                    var n = g.next(v, dir)
                    // Skip grid lines too close to move to (a sub-pixel away).
                    var guard = 0
                    while ((n - v) * dir < floor && guard++ < 4) n = g.next(n, dir)
                    val t = (n - v) * dir
                    if (t >= floor && t < best) best = t
                }
            }
        }
        return best * dir
    }

    /**
     * Snaps a free point (e.g. a distort corner) on both axes independently. Returns the snapped
     * point and its guides.
     */
    fun snapPoint(p: Vec2, targets: SnapTargets, threshold: Float): Pair<Vec2, List<SnapGuide>> {
        val hx = snapValue(p.x, SnapAxis.X, targets, threshold)
        val hy = snapValue(p.y, SnapAxis.Y, targets, threshold)
        val q = Vec2(hx?.pos ?: p.x, hy?.pos ?: p.y)
        val guides = ArrayList<SnapGuide>(2)
        hx?.let { guides += guide(it.line, q.y, q.y) }
        hy?.let { guides += guide(it.line, q.x, q.x) }
        return q to guides
    }

    /**
     * Guides for every target line that one of [box]'s lines lies on (within [eps]). [xEdges] /
     * [yEdges] limit which of the box's lines count (e.g. only the edge being dragged).
     */
    fun guidesFor(
        box: DocBox,
        targets: SnapTargets,
        eps: Float = ALIGNED_EPS,
        xEdges: Collection<SnapEdge> = SnapEdge.entries,
        yEdges: Collection<SnapEdge> = SnapEdge.entries,
    ): List<SnapGuide> {
        val out = ArrayList<SnapGuide>(4)
        collect(box, SnapAxis.X, xEdges, targets, eps, out)
        collect(box, SnapAxis.Y, yEdges, targets, eps, out)
        return out
    }

    private fun collect(box: DocBox, axis: SnapAxis, edges: Collection<SnapEdge>, targets: SnapTargets, eps: Float, out: MutableList<SnapGuide>) {
        // Extent of the moving box along the other axis.
        val lo = if (axis == SnapAxis.X) box.top else box.left
        val hi = if (axis == SnapAxis.X) box.bottom else box.right
        for (e in edges) {
            val v = feature(box, axis, e)
            if (!v.isFinite()) continue
            for (l in targets.lines(axis)) if (abs(l.pos - v) <= eps) add(out, guide(l, lo, hi))
            targets.grid(axis)?.let { g ->
                if (g.spacing > 0f && g.spacing.isFinite()) {
                    val p = g.nearest(v)
                    if (abs(p - v) <= eps) add(out, guide(targets.gridLine(axis, p), lo, hi))
                }
            }
        }
    }

    /** Guide for [line] reaching over the moving object's extent [lo]..[hi]. */
    private fun guide(line: SnapLine, lo: Float, hi: Float): SnapGuide =
        SnapGuide(line.axis, line.pos, min(line.spanStart, lo), max(line.spanEnd, hi), line.label, line.source)

    /** Adds [g], merging it with a guide already on the same line (one line, one label). */
    private fun add(out: MutableList<SnapGuide>, g: SnapGuide) {
        val i = out.indexOfFirst { it.axis == g.axis && abs(it.pos - g.pos) <= ALIGNED_EPS }
        if (i < 0) { out += g; return }
        val o = out[i]
        out[i] = o.copy(start = min(o.start, g.start), end = max(o.end, g.end))
    }

    /**
     * Grid lines to snap to for [grid] on a [docW] x [docH] canvas (null when the grid is off or
     * has nothing to align to): square grids snap to every line, the rule of thirds to its four
     * lines, isometric grids to their vertical lines.
     */
    fun gridLines(grid: GridSettings, docW: Int, docH: Int): GridSnap? {
        if (!grid.enabled) return null
        val sp = grid.spacingPx
        return when (grid.type) {
            GridType.SQUARE ->
                if (sp > MIN_GRID_SPACING && sp.isFinite()) GridSnap(PeriodicLines(sp, grid.offsetXPx), PeriodicLines(sp, grid.offsetYPx)) else null
            GridType.ISOMETRIC -> {
                val s = sp * (sqrt(3.0) / 2.0).toFloat()
                if (s > MIN_GRID_SPACING && s.isFinite()) GridSnap(x = PeriodicLines(s, grid.offsetXPx)) else null
            }
            GridType.RULE_OF_THIRDS -> GridSnap(
                fixedX = listOf(docW / 3f, docW * 2f / 3f),
                fixedY = listOf(docH / 3f, docH * 2f / 3f),
            )
            // Its lines through the center are the canvas center already.
            GridType.DIAGONAL -> null
        }
    }

    /** Grid spacings below this (document px) are not snapped to (every position would snap). */
    private const val MIN_GRID_SPACING = 0.5f

    /** Distances this close (document px) count as a tie between two of the box's lines. */
    private const val TIE_EPS = 1e-3f
}

/** [this] moved by ([dx], [dy]). */
fun DocBox.offset(dx: Float, dy: Float): DocBox =
    if (dx == 0f && dy == 0f) this else DocBox(left + dx, top + dy, right + dx, bottom + dy)
