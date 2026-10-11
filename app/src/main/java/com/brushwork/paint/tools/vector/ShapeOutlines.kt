package com.brushwork.paint.tools.vector

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import kotlin.math.max

/** Geometry of a [ShapeObject] in document pixels (outline, arrow parts, hit testing). */
object ShapeOutlines {

    /** True when [o] uses its custom points. */
    fun isCustom(o: ShapeObject): Boolean = o.points.let { it != null && it.size >= ShapePoints.minPoints(o.closed) }

    /** Corner treatment of a custom closed outline (rectangles, polygons and stars). */
    private fun customCorner(o: ShapeObject): CornerStyle = if (o.type.hasCorners) o.corner else CornerStyle.SHARP

    /**
     * The outline in document pixels: a box shape's closed outline (corner styles included), the
     * centerline of a line / arrow, or the custom outline.
     */
    fun outline(o: ShapeObject): VectorPath {
        val box = o.box
        val pts = o.points
        if (pts != null && isCustom(o)) {
            val local = ShapePoints.outline(ShapePoints.localAnchors(pts, box.w, box.h), o.closed, customCorner(o), o.cornerRadius)
            return local.transformed { box.toDoc(it) }
        }
        return if (o.type.isLineLike) VectorPath.polyline(listOf(box.start, box.end)) else ShapeGeometry.outline(o.type, box, o.outlineParams)
    }

    /** The centerline of a custom line / arrow, flattened (arrows are built along it). */
    private fun centerline(o: ShapeObject): List<Vec2> = outline(o).flatten(0.1f).firstOrNull()?.points ?: emptyList()

    /** The stroked shaft (+ open heads) and filled heads of an arrow. */
    fun arrow(o: ShapeObject): ArrowGeometry {
        val box = o.box
        return if (isCustom(o)) ShapeGeometry.arrowAlong(centerline(o), o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
        else ShapeGeometry.arrow(box.start, box.end, o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
    }

    /**
     * The path a painting tool follows for the outline (one continuous sub-path). v1.8: a cog
     * with a hole has two (the teeth, then the hole); [brushContours] splits them, and each is
     * painted as a stroke of its own.
     */
    fun brushOutline(o: ShapeObject): VectorPath = when {
        o.type == ShapeType.ARROW && isCustom(o) ->
            ShapeGeometry.arrowBrushOutlineAlong(centerline(o), o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
        o.type == ShapeType.ARROW -> ShapeGeometry.arrowBrushOutline(o.box.start, o.box.end, o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
        else -> outline(o)
    }

    /**
     * v1.8 (D21): [brushOutline] split into its sub-paths, each one brush stroke: one for every
     * shape but a cog with a hole (two: the teeth, then the hole).
     */
    fun brushContours(o: ShapeObject): List<VectorPath> = contoursOf(brushOutline(o))

    /** [path] split at each MoveTo (the same list of one path when it has one sub-path). */
    fun contoursOf(path: VectorPath): List<VectorPath> {
        val ops = path.ops
        if (ops.count { it is PathOp.MoveTo } <= 1) return listOf(path)
        val out = ArrayList<VectorPath>(2)
        var start = 0
        for (i in 1 until ops.size) if (ops[i] is PathOp.MoveTo) {
            out += VectorPath(ops.subList(start, i).toList())
            start = i
        }
        out += VectorPath(ops.subList(start, ops.size).toList())
        return out
    }

    /**
     * The plain items of shape [o]: everything when [brush] is false; with the brush only what
     * stays plain (the fill, filled arrowheads), since the brush paints the outline. (Moved out
     * of ShapeTool in v1.5 so vector layers draw shapes exactly like the Shape tool.)
     */
    fun paintSpec(o: ShapeObject, brush: Boolean): VectorPaintSpec? = paintParts(o, brush)?.spec()

    /**
     * v1.8: the paths [paintSpec] draws, before they become a spec, so that a shape's symmetry
     * copies can be built from the same paths ([ShapeCopies]); null when nothing is plain (a
     * line stroked with the brush). [o]'s copies are not read here.
     */
    internal fun paintParts(o: ShapeObject, brush: Boolean): PaintParts? {
        val color = o.strokeColor
        val w = o.strokeWidth
        return when (o.type) {
            ShapeType.LINE -> if (brush) null else PaintParts(null, 0, outline(o), color, w, o.lineCap, join(o), null)
            ShapeType.ARROW -> {
                val g = arrow(o)
                PaintParts(null, 0, if (brush) null else g.stroke, color, w, o.lineCap, JoinStyle.ROUND, g.fill)
            }
            else -> {
                val outline = outline(o)
                PaintParts(
                    if (o.style.fill) outline else null, o.fillColor,
                    if (o.style.stroke && !brush) outline else null, color, w, LineCapStyle.ROUND, join(o), null,
                )
            }
        }
    }

    /**
     * v1.8: the plain items of a shape as paths (document px): [fill] filled with [fillColor],
     * [stroke] stroked with [strokeColor] ([width], [cap], [join]), [strokeFill] (arrowheads)
     * filled with [strokeColor]. [spec] builds the [VectorPaintSpec], each path through [map].
     */
    internal class PaintParts(
        val fill: VectorPath?,
        val fillColor: Int,
        val stroke: VectorPath?,
        val strokeColor: Int,
        val width: Float,
        val cap: LineCapStyle,
        val join: JoinStyle,
        val strokeFill: VectorPath?,
    ) {
        fun spec(map: (VectorPath) -> VectorPath = { it }): VectorPaintSpec? =
            VectorPaintSpec.build(fill?.let(map), fillColor, stroke?.let(map), strokeColor, width, cap, join, strokeFill?.let(map))
    }

    /** Stroke join of the outline: round for ellipses and lines, the corner style's join otherwise. */
    fun join(o: ShapeObject): JoinStyle = when {
        o.type.isLineLike -> JoinStyle.ROUND
        o.type == ShapeType.ELLIPSE -> if (isCustom(o)) JoinStyle.MITER else JoinStyle.ROUND
        else -> ShapeGeometry.joinFor(o.corner)
    }

    /** How far paint reaches from the outline (half the stroke or brush, document px). */
    fun reach(o: ShapeObject): Float {
        if (!o.strokes) return 0f
        val brush = o.brushPreset?.size?.takeIf { o.paintsWithBrush && it.isFinite() } ?: 0f
        return max(o.strokeWidth, brush) / 2f
    }

    /**
     * True when [p] is on the shape: within [tol] (plus the stroke) of its outline, inside its
     * fill, or on an arrowhead.
     */
    fun hits(o: ShapeObject, p: Vec2, tol: Float): Boolean {
        val reach = tol + reach(o)
        val outline = outline(o)
        val polys = outline.flatten(0.5f)
        for (poly in polys) {
            val pts = poly.points
            if (pts.size == 1 && pts[0].distanceTo(p) <= reach) return true
            for (i in 1 until pts.size) if (Geometry.distanceToSegment(p, pts[i - 1], pts[i]) <= reach) return true
            if (poly.closed && pts.size > 2 && Geometry.distanceToSegment(p, pts.last(), pts[0]) <= reach) return true
        }
        if (!o.type.isLineLike && o.style.fill) {
            // v1.8: inside an odd number of contours (a cog's hole is not its fill). One contour: as in v1.7.
            var inside = false
            for (poly in polys) if (poly.points.size > 2 && Geometry.pointInPolygon(p, poly.points)) inside = !inside
            if (inside) return true
        }
        if (o.type == ShapeType.ARROW) {
            val heads = arrow(o)
            for (poly in heads.fill.flatten(0.5f)) {
                if (poly.points.size > 2 && Geometry.pointInPolygon(p, poly.points)) return true
            }
            for (poly in heads.stroke.flatten(0.5f)) {
                val pts = poly.points
                for (i in 1 until pts.size) if (Geometry.distanceToSegment(p, pts[i - 1], pts[i]) <= reach) return true
            }
        }
        return false
    }

    /**
     * Points other things align to (document px): the vertices / points of the outline, the ends
     * of lines, the box corners and the center.
     */
    fun featurePoints(o: ShapeObject): List<Vec2> {
        val box = o.box
        val out = ArrayList<Vec2>()
        val pts = o.points
        when {
            pts != null && isCustom(o) -> ShapePoints.docAnchors(box, pts).mapTo(out) { it.pos }
            o.type.isLineLike -> { out += box.start; out += box.end }
            o.type == ShapeType.ELLIPSE -> {
                out += box.toDoc(Vec2(0f, -box.h / 2f)); out += box.toDoc(Vec2(box.w / 2f, 0f))
                out += box.toDoc(Vec2(0f, box.h / 2f)); out += box.toDoc(Vec2(-box.w / 2f, 0f))
            }
            else -> ShapeGeometry.vertices(o.type, box.w, box.h, o.outlineParams).mapTo(out) { box.toDoc(it) }
        }
        if (!o.type.isLineLike || isCustom(o)) {
            if (box.w > 0f && box.h > 0f) out += box.corners()
        }
        out += box.center
        return out
    }
}
