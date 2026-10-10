package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.CopyMaps
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to draw a placed shape again exactly and to edit it: what an editable shape
 * layer stores in `Layer.shapeData` (JSON via [ShapeCodec]). Lengths are document pixels; the
 * colors are the ones it was drawn with. [points] (box-local, normalized: see [ShapePoints]) is
 * the custom outline made in "Points" mode, null for the regular outline of [type].
 *
 * v1.8: the cog fields (type [ShapeType.COG]) and the live symmetry [copies] are never written
 * at their defaults, so a shape without them saves exactly as in v1.7. A cog is written with
 * [forWriting] (its outline as fallback [points], [cogFallback] set) so v1.7 draws its teeth;
 * [sanitized] drops those points on read (D23).
 */
@OptIn(ExperimentalSerializationApi::class)
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
    /** v1.8 (item 4): the cog's number of teeth, 3..64. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogTeeth: Int = 12,            // 3..64
    /** v1.8: the teeth's depth, 0.05..0.5 of the outer radius. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogDepth: Float = 0.2f,        // 0.05..0.5 of the outer radius
    /** v1.8: a tooth's width, 0.2..0.8 of the pitch. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogToothWidth: Float = 0.5f,   // 0.2..0.8 of the pitch
    /** v1.8: how much a tooth narrows toward its tip, 0..0.9. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogTaper: Float = 0.3f,        // 0..0.9
    /** v1.8: the hole's radius, 0..0.8 of the root radius; 0 = no hole. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogHole: Float = 0f,           // 0..0.8 of the root radius; 0 = no hole
    /** Written only by forWriting(): [points] is the v1.7 fallback outline of a cog (D23). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogFallback: Boolean = false,
    /** v1.8 (I15): live symmetry copies of the shape; NONE is never written. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val copies: CopyMaps = CopyMaps.NONE,
) {
    val box: ShapeBox get() = ShapeBox(cx, cy, w, h, rotation)

    /** v1.8: the cog fields as one value (what [ShapeGeometry] draws a cog from). */
    val cogParams: CogParams get() = CogParams(cogTeeth, cogDepth, cogToothWidth, cogTaper, cogHole)

    val outlineParams: OutlineParams get() = OutlineParams(sides, starPoints, innerRatio, corner, cornerRadius, cogParams)

    /** Lines and arrows are open paths; the other shapes are closed. */
    val closed: Boolean get() = !type.isLineLike

    /** The outline has a stroke (lines and arrows always do). */
    val strokes: Boolean get() = type.isLineLike || style.stroke

    /** The outline is painted with a painting tool. */
    val paintsWithBrush: Boolean get() = strokeWith == ShapeStroke.BRUSH && strokes

    /** The painting tool of a brush-stroked shape (null: unknown / none). */
    val brushToolId: ToolId? get() = brushTool?.let { n -> ToolId.entries.firstOrNull { it.name == n } }

    fun withBox(b: ShapeBox) = copy(cx = b.cx, cy = b.cy, w = b.w, h = b.h, rotation = b.rotationDeg)

    /**
     * Every value in its supported range; null when the placement can't be used at all. This
     * instance when nothing changes.
     *
     * v1.8: the cog fields are clamped (a non-finite one takes its default), and a cog's fallback
     * outline ([cogFallback], written by [forWriting] for v1.7) is dropped with its flag, so a
     * reopened cog is drawn from its fields again (D23).
     */
    fun sanitized(): ShapeObject? {
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && rotation.isFinite())) return null
        val lim = ShapeSettings.MAX_LENGTH
        val pts = if (cogFallback) null
        else points?.takeIf { list -> list.size >= ShapePoints.minPoints(!type.isLineLike) && list.all { it.isFinite } }?.let { sanitizedRadii(it, lim) }
        val n = copy(
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
            cogTeeth = cogTeeth.coerceIn(CogParams.MIN_TEETH, CogParams.MAX_TEETH),
            cogDepth = cogDepth.finiteOr(CogParams.DEFAULT.depth).coerceIn(CogParams.MIN_DEPTH, CogParams.MAX_DEPTH),
            cogToothWidth = cogToothWidth.finiteOr(CogParams.DEFAULT.toothWidth).coerceIn(CogParams.MIN_TOOTH_WIDTH, CogParams.MAX_TOOTH_WIDTH),
            cogTaper = cogTaper.finiteOr(CogParams.DEFAULT.taper).coerceIn(0f, CogParams.MAX_TAPER),
            cogHole = cogHole.finiteOr(CogParams.DEFAULT.hole).coerceIn(0f, CogParams.MAX_HOLE),
            cogFallback = false,
        )
        return if (n == this) this else n
    }

    /**
     * v1.8 (D23): what is written. A cog without custom [points] gets its outline (without the
     * hole) as [points], normalized to the box as [ShapePoints] stores them, and [cogFallback]
     * set: v1.7 reads the type as RECTANGLE and draws those points, so it shows the teeth.
     * Anything else is written as it is (this instance).
     */
    fun forWriting(): ShapeObject {
        if (type != ShapeType.COG || points != null) return this
        val outline = ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, outlineParams).map { ShapePoint(it.x, it.y) }
        return copy(points = outline, cogFallback = true)
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
 * v1.8 (item 4): the parameters of a cog ([ShapeType.COG]), as [ShapeObject.cogParams] gives
 * them: [teeth] 3..64; [depth] of the teeth, 0.05..0.5 of the outer radius; [toothWidth],
 * 0.2..0.8 of the pitch; [taper] of a tooth toward its tip, 0..0.9; [hole], 0..0.8 of the root
 * radius (0 = no hole). The ranges are [ShapeObject.sanitized]'s.
 */
data class CogParams(val teeth: Int = 12, val depth: Float = 0.2f, val toothWidth: Float = 0.5f, val taper: Float = 0.3f, val hole: Float = 0f) {
    companion object {
        const val MIN_TEETH = 3
        const val MAX_TEETH = 64
        const val MIN_DEPTH = 0.05f
        const val MAX_DEPTH = 0.5f
        const val MIN_TOOTH_WIDTH = 0.2f
        const val MAX_TOOTH_WIDTH = 0.8f
        const val MAX_TAPER = 0.9f
        const val MAX_HOLE = 0.8f

        /** The defaults, as a new cog gets them. */
        val DEFAULT = CogParams()
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

    /** v1.8: a cog is written with its v1.7 fallback outline ([ShapeObject.forWriting]). */
    fun encode(shape: ShapeObject): String =
        json.encodeToString(ShapeLayerData.serializer(), ShapeLayerData(VERSION, (shape.sanitized() ?: shape).forWriting()))

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
