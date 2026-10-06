package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ToolId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

/**
 * Everything needed to draw a placed shape again exactly and to edit it: what an editable shape
 * layer stores in `Layer.shapeData` (JSON via [ShapeCodec]). Lengths are document pixels; the
 * colors are the ones it was drawn with. [points] (box-local, normalized: see [ShapePoints]) is
 * the custom outline made in "Points" mode, null for the regular outline of [type].
 */
@Serializable
data class ShapeObject(
    val type: ShapeType = ShapeType.RECTANGLE,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    val rotation: Float = 0f,
    val style: ShapeStyle = ShapeStyle.STROKE,
    val strokeWidth: Float = 8f,
    val strokeWith: ShapeStroke = ShapeStroke.PLAIN,
    val strokeColor: Int = BLACK,
    val fillColor: Int = BLACK,
    /** The fill followed the main color when drawn (it then follows the stroke color when edited). */
    val fillFollowsColor: Boolean = true,
    val lineCap: LineCapStyle = LineCapStyle.ROUND,
    val corner: CornerStyle = CornerStyle.SHARP,
    val cornerRadius: Float = 0f,
    val sides: Int = 5,
    val starPoints: Int = 5,
    val innerRatio: Float = 0.45f,
    val arrowHeads: ArrowHeads = ArrowHeads.END,
    val arrowHeadStyle: ArrowHeadStyle = ArrowHeadStyle.FILLED,
    val arrowHeadScale: Float = 4f,
    /** The painting tool ([ToolId] name) whose stroke follows the outline (strokeWith BRUSH). */
    val brushTool: String? = null,
    /** That tool's brush when the shape was drawn (re-edits paint with it, not the current brush). */
    val brushPreset: BrushPreset? = null,
    val points: List<ShapePoint>? = null,
) {
    val box: ShapeBox get() = ShapeBox(cx, cy, w, h, rotation)

    val outlineParams: OutlineParams get() = OutlineParams(sides, starPoints, innerRatio, corner, cornerRadius)

    /** Lines and arrows are open paths; the other shapes are closed. */
    val closed: Boolean get() = !type.isLineLike

    /** The outline has a stroke (lines and arrows always do). */
    val strokes: Boolean get() = type.isLineLike || style.stroke

    /** The outline is painted with a painting tool. */
    val paintsWithBrush: Boolean get() = strokeWith == ShapeStroke.BRUSH && strokes

    /** The painting tool of a brush-stroked shape (null: unknown / none). */
    val brushToolId: ToolId? get() = brushTool?.let { n -> ToolId.entries.firstOrNull { it.name == n } }

    fun withBox(b: ShapeBox) = copy(cx = b.cx, cy = b.cy, w = b.w, h = b.h, rotation = b.rotationDeg)

    /** Every value in its supported range; null when the placement can't be used at all. */
    fun sanitized(): ShapeObject? {
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && rotation.isFinite())) return null
        val lim = ShapeSettings.MAX_LENGTH
        val pts = points?.takeIf { list -> list.size >= ShapePoints.minPoints(!type.isLineLike) && list.all { it.isFinite } }?.let { sanitizedRadii(it, lim) }
        return copy(
            cx = cx.coerceIn(-lim, lim),
            cy = cy.coerceIn(-lim, lim),
            w = w.coerceIn(0f, lim),
            h = h.coerceIn(0f, lim),
            rotation = ShapeGeometry.normalizeDegrees(rotation),
            strokeWidth = strokeWidth.finiteOr(8f).coerceIn(ShapeSettings.MIN_STROKE, ShapeSettings.MAX_STROKE),
            cornerRadius = cornerRadius.finiteOr(0f).coerceIn(0f, lim),
            sides = sides.coerceIn(ShapeGeometry.MIN_SIDES, ShapeGeometry.MAX_SIDES),
            starPoints = starPoints.coerceIn(ShapeGeometry.MIN_SIDES, ShapeGeometry.MAX_SIDES),
            innerRatio = innerRatio.finiteOr(0.45f).coerceIn(0.05f, 0.95f),
            arrowHeadScale = arrowHeadScale.finiteOr(4f).coerceIn(2f, 12f),
            points = pts,
        )
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()

        /**
         * v1.7 (item 2): [pts] with every corner radius ([ShapePoint.radius]) in 0..[lim] (a
         * non-finite one becomes null, the shape's own); the same list when all of them are.
         */
        private fun sanitizedRadii(pts: List<ShapePoint>, lim: Float): List<ShapePoint> {
            if (pts.all { p -> p.radius.let { it == null || (it.isFinite() && it in 0f..lim) } }) return pts
            return pts.map { p -> p.radius?.let { r -> p.copy(radius = if (r.isFinite()) r.coerceIn(0f, lim) else null) } ?: p }
        }
    }
}

/**
 * What a shape layer stores in `Layer.shapeData`: the shape plus a format [version] for future
 * migrations. Unknown fields are ignored and missing ones take their defaults.
 */
@Serializable
data class ShapeLayerData(
    val version: Int = ShapeCodec.VERSION,
    val shape: ShapeObject,
)

/** JSON encoding of [ShapeObject]s for shape layers (pure Kotlin). */
object ShapeCodec {
    /** Current format version (1 = v1.4; 2 = v1.7, which adds [ShapePoint.radius]; older readers ignore it). */
    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An enum value written by a newer version falls back to the field's default.
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(shape: ShapeObject): String =
        json.encodeToString(ShapeLayerData.serializer(), ShapeLayerData(VERSION, shape.sanitized() ?: shape))

    /** The shape stored in [data], or null when there is none or it can't be read. */
    fun decode(data: String?): ShapeObject? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ShapeLayerData.serializer(), data).shape.sanitized()
        } catch (e: Exception) {
            null
        }
    }
}

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

    /** The path a painting tool follows for the outline (one continuous sub-path). */
    fun brushOutline(o: ShapeObject): VectorPath = when {
        o.type == ShapeType.ARROW && isCustom(o) ->
            ShapeGeometry.arrowBrushOutlineAlong(centerline(o), o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
        o.type == ShapeType.ARROW -> ShapeGeometry.arrowBrushOutline(o.box.start, o.box.end, o.strokeWidth, o.arrowHeads, o.arrowHeadStyle, o.arrowHeadScale)
        else -> outline(o)
    }

    /**
     * The plain items of shape [o]: everything when [brush] is false; with the brush only what
     * stays plain (the fill, filled arrowheads), since the brush paints the outline. (Moved out
     * of ShapeTool in v1.5 so vector layers draw shapes exactly like the Shape tool.)
     */
    fun paintSpec(o: ShapeObject, brush: Boolean): VectorPaintSpec? {
        val color = o.strokeColor
        val w = o.strokeWidth
        return when (o.type) {
            ShapeType.LINE -> if (brush) null else VectorPaintSpec.build(
                null, 0, outline(o), color, w, o.lineCap, join(o),
            )
            ShapeType.ARROW -> {
                val g = arrow(o)
                VectorPaintSpec.build(null, 0, if (brush) null else g.stroke, color, w, o.lineCap, JoinStyle.ROUND, g.fill)
            }
            else -> {
                val outline = outline(o)
                VectorPaintSpec.build(
                    if (o.style.fill) outline else null, o.fillColor,
                    if (o.style.stroke && !brush) outline else null, color, w, LineCapStyle.ROUND, join(o),
                )
            }
        }
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
            for (poly in polys) if (poly.points.size > 2 && Geometry.pointInPolygon(p, poly.points)) return true
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
