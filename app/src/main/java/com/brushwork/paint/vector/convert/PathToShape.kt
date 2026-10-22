package com.brushwork.paint.vector.convert

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveAnchor
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.CurveWidths
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeAnchor
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorOps
import kotlin.math.abs

/**
 * v1.10 (R11, A6, "Convert with a warning"): a path ([VPath]: Curve, Polyline, Path tool, an
 * imported or a generated path) as a [VShape] of the Shape tool with its own points, so the shape
 * menu edits it. Pure Kotlin; O(anchors).
 *
 * Mapping. The path's one subpath gives the points: a Path-tool spline is read in its Bézier form
 * (the subpath, `SplineBezier`'s, invariant I9). Each anchor becomes a [ShapeAnchor] at the same
 * place with the handles the path draws ([CurveGeometry.handles], the path's tension included):
 * a smooth anchor stays smooth, a sharp one sharp, and on a straight segment (as
 * [CurveGeometry.toPath] draws it) both handles are 0, so its ends are corners the shape menu can
 * round. Polyline anchors are all sharp, without handles. [ShapePoints.fit] places the box
 * (rotation 0). A closed path (more than 2 anchors) becomes a closed custom outline
 * ([ShapeType.RECTANGLE] with points), an open one a line with points ([ShapeType.LINE]).
 *
 * Style: the line's color, width, cap and join (as the corner: miter = sharp, round = round,
 * bevel = bevel; radius 0, so the outline is unchanged) are copied; a line of other thickness
 * factors than 1 is drawn round-capped and round-joined (as the renderer draws it) with its width
 * times the common factor. A brush line keeps its tool, brush (sized by the factor) and grain. A
 * solid fill is copied. The id, opacity and live copies ([VPath.copies], S2 offsets included)
 * stay. Not kept, without a note: the path's miter limit (a shape's is fixed) and an open path's
 * miter or bevel joins (a line shape always joins round, `ShapeOutlines.join`).
 *
 * Lossy cases convert, each with one note ([Result.notes], in this order): a gradient fill becomes
 * its first color ([ObjectBarLabels110.NOTE_GRADIENT]); varying thickness or a taper becomes the
 * mean thickness ([ObjectBarLabels110.NOTE_WIDTH]); a generator is dropped, its outline kept
 * ([ObjectBarLabels110.NOTE_GENERATED]); an open path loses its fill
 * ([ObjectBarLabels110.NOTE_OPEN_FILL]). [refusal] names what can't convert at all.
 */
object PathToShape {
    /** The converted shape and the notes of what changed (empty when nothing did). */
    data class Result(val shape: VShape, val notes: List<String>)

    /**
     * Why [path] can't become a shape, or null when it can: several separate pieces
     * ([ObjectBarLabels110.REFUSE_PIECES]), more points than the Shape tool edits
     * ([PointBevel.MAX_POINTS_SHAPE], [ObjectBarLabels110.REFUSE_POINTS]), fewer than 2 points,
     * damaged coordinates or all points in one place ([ObjectBarLabels110.REFUSE_SMALL]).
     */
    fun refusal(path: VPath): String? {
        if (path.subpaths.size > 1) return ObjectBarLabels110.REFUSE_PIECES
        val anchors = path.subpaths.firstOrNull()?.anchors.orEmpty()
        if (anchors.size > PointBevel.MAX_POINTS_SHAPE) return ObjectBarLabels110.REFUSE_POINTS
        if (anchors.size < ShapePoints.MIN_OPEN) return ObjectBarLabels110.REFUSE_SMALL
        if (anchors.any { a -> !a.x.isFinite() || !a.y.isFinite() || !finite(a.inX, a.inY) || !finite(a.outX, a.outY) }) {
            return ObjectBarLabels110.REFUSE_SMALL
        }
        val first = anchors[0]
        if (anchors.all { abs(it.x - first.x) < SAME && abs(it.y - first.y) < SAME }) return ObjectBarLabels110.REFUSE_SMALL
        return null
    }

    /**
     * [path] as a shape (see the object's docs). Only for a path [refusal] accepts; any other
     * throws [IllegalArgumentException].
     */
    fun convert(path: VPath): Result {
        val refused = refusal(path)
        require(refused == null) { refused.toString() }
        val sub = path.subpaths[0]
        val closed = sub.closed && sub.anchors.size > 2
        // The anchors exactly as the renderer reads them (raw thickness factors).
        val ca = VectorOps.curveAnchors(sub)
        val (box, points) = ShapePoints.fit(0f, anchorsOf(ca, path, closed), closed)

        val notes = ArrayList<String>(4)
        val fillColor: Int? = when (val f = path.fill) {
            null -> null
            is VPaint.Solid -> f.color
            is VPaint.Linear -> firstColor(f.stops)
            is VPaint.Radial -> firstColor(f.stops)
        }
        val fill = fillColor?.takeIf { closed }
        if (fill != null && path.fill !is VPaint.Solid) notes += ObjectBarLabels110.NOTE_GRADIENT
        val stroke = path.stroke
        val widths = ca.map { CurveWidths.factor(it.width) }
        val even = widths.all { abs(it - widths[0]) < WIDTH_SAME }
        if (stroke != null && (!even || stroke.taperPercent > 0f)) notes += ObjectBarLabels110.NOTE_WIDTH
        if (path.generator != null) notes += ObjectBarLabels110.NOTE_GENERATED
        if (!closed && path.fill != null) notes += ObjectBarLabels110.NOTE_OPEN_FILL

        // The line's one thickness: the common factor, else the mean one.
        val factor = if (even) widths[0] else widths.average().toFloat()
        val brush = stroke?.kind == VStrokeKind.BRUSH
        // Factors other than 1 are drawn as a filled outline with round ends and joins.
        val roundOutline = stroke != null && !brush && ca.any { it.width != 1f }
        val style = when {
            !closed -> ShapeStyle.STROKE
            fill != null && stroke != null -> ShapeStyle.STROKE_FILL
            fill != null -> ShapeStyle.FILL
            else -> ShapeStyle.STROKE
        }
        val shape = ShapeObject(
            type = if (closed) ShapeType.RECTANGLE else ShapeType.LINE,
            cx = box.cx,
            cy = box.cy,
            w = box.w,
            h = box.h,
            rotation = 0f,
            style = style,
            strokeWidth = stroke?.let { it.width * factor } ?: DEFAULT_WIDTH,
            strokeWith = if (brush) ShapeStroke.BRUSH else ShapeStroke.PLAIN,
            // No line: transparent (an open shape is always stroked; a closed one only fills).
            strokeColor = stroke?.color ?: TRANSPARENT,
            fillColor = fill ?: stroke?.color ?: TRANSPARENT,
            fillFollowsColor = false,
            lineCap = if (roundOutline) LineCapStyle.ROUND else stroke?.cap ?: LineCapStyle.ROUND,
            corner = if (roundOutline) CornerStyle.ROUND else cornerOf(stroke?.join),
            cornerRadius = 0f,
            brushTool = if (brush) stroke?.brushTool?.name else null,
            brushPreset = if (brush) stroke?.brush?.let { b -> if (factor != 1f) b.copy(size = b.size * factor) else b } else null,
            points = points,
            copies = path.copies,
        )
        // Usable numbers only, as a load makes them (e.g. a line thinner than the Shape tool's least).
        val sane = shape.sanitized() ?: shape
        return Result(VShape(path.id, path.opacity, sane, seed = if (brush) stroke?.seed ?: 0L else 0L), notes)
    }

    /** The points [ca] of [path]'s one subpath as the shape's document anchors (see the object's docs). */
    private fun anchorsOf(ca: List<CurveAnchor>, path: VPath, closed: Boolean): List<ShapeAnchor> {
        if (path.polyline) return ca.map { ShapeAnchor(it.pos) }
        val n = ca.size
        val hIn = arrayOfNulls<Vec2>(n)
        val hOut = arrayOfNulls<Vec2>(n)
        for (i in 0 until n) {
            val (a, b) = CurveGeometry.handles(ca, i, closed, path.tension)
            hIn[i] = a
            hOut[i] = b
        }
        // A straight segment (both control points on its chord) has no handles: its ends are corners.
        for (s in 0 until CurveGeometry.segmentCount(n, closed)) {
            val e = (s + 1) % n
            val p0 = ca[s].pos
            val p1 = ca[e].pos
            if (onChord(p0 + hOut[s]!!, p0, p1) && onChord(p1 + hIn[e]!!, p0, p1)) {
                hOut[s] = Vec2.ZERO
                hIn[e] = Vec2.ZERO
            }
        }
        // An open path's ends have no outer handle.
        if (!closed) {
            hIn[0] = Vec2.ZERO
            hOut[n - 1] = Vec2.ZERO
        }
        return List(n) { i ->
            val sharp = ca[i].sharp
            val hi = hIn[i]!!
            val ho = hOut[i]!!
            if (sharp && hi == Vec2.ZERO && ho == Vec2.ZERO) ShapeAnchor(ca[i].pos)
            else ShapeAnchor(ca[i].pos, smooth = !sharp, handleIn = hi, handleOut = ho)
        }
    }

    /** The corner that draws a path's [join] (a shape's corner sets its join, `ShapeGeometry.joinFor`). */
    private fun cornerOf(join: JoinStyle?): CornerStyle = when (join) {
        JoinStyle.MITER -> CornerStyle.SHARP
        JoinStyle.BEVEL -> CornerStyle.BEVEL
        JoinStyle.ROUND, null -> CornerStyle.ROUND
    }

    /** A gradient's first color (the stop nearest its start). */
    private fun firstColor(stops: List<VStop>): Int? = stops.minByOrNull { if (it.offset.isFinite()) it.offset else Float.MAX_VALUE }?.color

    private fun finite(x: Float?, y: Float?): Boolean = (x == null || x.isFinite()) && (y == null || y.isFinite())

    /** As [CurveGeometry.toPath] decides a segment is straight. */
    private fun onChord(c: Vec2, a: Vec2, b: Vec2): Boolean = Geometry.distanceToSegment(c, a, b) < 1e-3f

    /** Two points closer than this (document px) are one place. */
    private const val SAME = 1e-3f

    /** Two thickness factors closer than this are equal. */
    private const val WIDTH_SAME = 1e-3f

    /** The Shape tool's own line width, for a path without a line. */
    private const val DEFAULT_WIDTH = 8f

    private const val TRANSPARENT = 0x00000000
}
