package com.brushwork.paint.compat19

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

/*
 * v1.10 G2 (compat19 kit): a FROZEN copy of v1.9's shape object and shape-layer codec (`tools/vector/
 * ShapeObject.kt`, the enums of `ShapeGeometry.kt` and `ShapeTool.kt`, `ShapePoints.kt`'s point,
 * and the outer contour of `CogGeometry.kt` that `ShapeObject.forWriting` writes, at tag v1.9.0),
 * in test sources only, as v1.9 serializes, sanitizes and writes them: a cog is written with its
 * v1.7 fallback outline, which v1.9 drops on read (D23). The drawing geometry (outlines, corners,
 * the cog's hole) is not copied. Never change it: it is what v1.9 is.
 */

/** v1.9's `ShapeType` (COG last). */
@Serializable
enum class ShapeTypeV19(val label: String) {
    LINE("Line"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    POLYGON("Polygon"),
    STAR("Star"),
    ARROW("Arrow"),
    COG("Cog");

    /** Defined by two end points instead of a box. */
    val isLineLike: Boolean get() = this == LINE || this == ARROW
}

/** v1.9's `ShapeStyle`. */
@Serializable
enum class ShapeStyleV19(val label: String, val stroke: Boolean, val fill: Boolean) {
    STROKE("Stroke", true, false),
    FILL("Fill", false, true),
    STROKE_FILL("Stroke + fill", true, true),
}

/** v1.9's `CornerStyle`. */
@Serializable
enum class CornerStyleV19(val label: String) {
    SHARP("Sharp"),
    ROUND("Round"),
    BEVEL("Bevel"),
    INVERTED("Inverted"),
}

/** v1.9's `LineCapStyle`. */
@Serializable
enum class LineCapStyleV19(val label: String) { BUTT("Flat"), ROUND("Round"), SQUARE("Square") }

/** v1.9's `ArrowHeads`. */
@Serializable
enum class ArrowHeadsV19(val label: String, val start: Boolean, val end: Boolean) {
    END("End", false, true),
    START("Start", true, false),
    BOTH("Both", true, true),
}

/** v1.9's `ArrowHeadStyle`. */
@Serializable
enum class ArrowHeadStyleV19(val label: String) { FILLED("Filled"), OPEN("Open") }

/** v1.9's `JoinStyle`. */
enum class JoinStyleV19 { MITER, ROUND, BEVEL }

/** v1.9's `ShapeStroke`. */
@Serializable
enum class ShapeStrokeV19(val label: String) {
    PLAIN("Plain line"),
    BRUSH("Current brush"),
}

/** v1.9's `ShapeHandle`. */
@Serializable
data class ShapeHandleV19(val x: Float, val y: Float)

/** v1.9's `ShapePoint`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShapePointV19(
    val x: Float,
    val y: Float,
    val smooth: Boolean = false,
    val handleIn: ShapeHandleV19? = null,
    val handleOut: ShapeHandleV19? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val radius: Float? = null,
) {
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() &&
            (handleIn == null || (handleIn.x.isFinite() && handleIn.y.isFinite())) &&
            (handleOut == null || (handleOut.x.isFinite() && handleOut.y.isFinite()))
}

/** v1.9's `ShapeObject` (its serialized properties, [sanitized] and [forWriting]). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShapeObjectV19(
    val type: ShapeTypeV19 = ShapeTypeV19.RECTANGLE,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    val rotation: Float = 0f,
    val style: ShapeStyleV19 = ShapeStyleV19.STROKE,
    val strokeWidth: Float = 8f,
    val strokeWith: ShapeStrokeV19 = ShapeStrokeV19.PLAIN,
    val strokeColor: Int = BLACK,
    val fillColor: Int = BLACK,
    val fillFollowsColor: Boolean = true,
    val lineCap: LineCapStyleV19 = LineCapStyleV19.ROUND,
    val corner: CornerStyleV19 = CornerStyleV19.SHARP,
    val cornerRadius: Float = 0f,
    val sides: Int = 5,
    val starPoints: Int = 5,
    val innerRatio: Float = 0.45f,
    val arrowHeads: ArrowHeadsV19 = ArrowHeadsV19.END,
    val arrowHeadStyle: ArrowHeadStyleV19 = ArrowHeadStyleV19.FILLED,
    val arrowHeadScale: Float = 4f,
    val brushTool: String? = null,
    val brushPreset: BrushPresetV19? = null,
    val points: List<ShapePointV19>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogTeeth: Int = 12,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogDepth: Float = 0.2f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogToothWidth: Float = 0.5f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogTaper: Float = 0.3f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogHole: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cogFallback: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val copies: CopyMapsV19 = CopyMapsV19.NONE,
) {
    /** v1.9's `ShapeObject.cogParams`. */
    val cogParams: CogParamsV19 get() = CogParamsV19(cogTeeth, cogDepth, cogToothWidth, cogTaper, cogHole)

    /** v1.9's `ShapeObject.sanitized`: every value in range (a cog's fallback points dropped); null when unusable. */
    fun sanitized(): ShapeObjectV19? {
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && rotation.isFinite())) return null
        val lim = MAX_LENGTH
        val pts = if (cogFallback) null
        else points?.takeIf { list -> list.size >= minPoints(!type.isLineLike) && list.all { it.isFinite } }?.let { sanitizedRadii(it, lim) }
        val n = copy(
            cx = cx.coerceIn(-lim, lim),
            cy = cy.coerceIn(-lim, lim),
            w = w.coerceIn(0f, lim),
            h = h.coerceIn(0f, lim),
            rotation = normalizeDegrees(rotation),
            strokeWidth = strokeWidth.finiteOr(8f).coerceIn(MIN_STROKE, MAX_STROKE),
            cornerRadius = cornerRadius.finiteOr(0f).coerceIn(0f, lim),
            sides = sides.coerceIn(MIN_SIDES, MAX_SIDES),
            starPoints = starPoints.coerceIn(MIN_SIDES, MAX_SIDES),
            innerRatio = innerRatio.finiteOr(0.45f).coerceIn(0.05f, 0.95f),
            arrowHeadScale = arrowHeadScale.finiteOr(4f).coerceIn(2f, 12f),
            points = pts,
            cogTeeth = cogTeeth.coerceIn(CogParamsV19.MIN_TEETH, CogParamsV19.MAX_TEETH),
            cogDepth = cogDepth.finiteOr(CogParamsV19.DEFAULT.depth).coerceIn(CogParamsV19.MIN_DEPTH, CogParamsV19.MAX_DEPTH),
            cogToothWidth = cogToothWidth.finiteOr(CogParamsV19.DEFAULT.toothWidth).coerceIn(CogParamsV19.MIN_TOOTH_WIDTH, CogParamsV19.MAX_TOOTH_WIDTH),
            cogTaper = cogTaper.finiteOr(CogParamsV19.DEFAULT.taper).coerceIn(0f, CogParamsV19.MAX_TAPER),
            cogHole = cogHole.finiteOr(CogParamsV19.DEFAULT.hole).coerceIn(0f, CogParamsV19.MAX_HOLE),
            cogFallback = false,
        )
        return if (n == this) this else n
    }

    /** v1.9's `ShapeObject.forWriting`: a cog without custom points gets its outer contour as fallback points. */
    fun forWriting(): ShapeObjectV19 {
        if (type != ShapeTypeV19.COG || points != null) return this
        val outline = CogGeometryV19.outer(1f, 1f, cogParams).map { (x, y) -> ShapePointV19(x, y) }
        return copy(points = outline, cogFallback = true)
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()

        /** v1.9's `ShapeSettings.MIN_STROKE`, `MAX_STROKE` and `MAX_LENGTH`. */
        const val MIN_STROKE = 0.25f
        const val MAX_STROKE = 2000f
        const val MAX_LENGTH = 100_000f

        /** v1.9's `ShapeGeometry.MIN_SIDES` and `MAX_SIDES`. */
        const val MIN_SIDES = 3
        const val MAX_SIDES = 64

        /** v1.9's `ShapePoints.minPoints`. */
        fun minPoints(closed: Boolean): Int = if (closed) 3 else 2

        /** v1.9's `ShapeGeometry.normalizeDegrees`. */
        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d <= -180f) d += 360f
            if (d > 180f) d -= 360f
            return d
        }

        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

        private fun sanitizedRadii(pts: List<ShapePointV19>, lim: Float): List<ShapePointV19> {
            if (pts.all { p -> p.radius.let { it == null || (it.isFinite() && it in 0f..lim) } }) return pts
            return pts.map { p -> p.radius?.let { r -> p.copy(radius = if (r.isFinite()) r.coerceIn(0f, lim) else null) } ?: p }
        }
    }
}

/** v1.9's `CogParams`. */
data class CogParamsV19(val teeth: Int = 12, val depth: Float = 0.2f, val toothWidth: Float = 0.5f, val taper: Float = 0.3f, val hole: Float = 0f) {
    companion object {
        const val MIN_TEETH = 3
        const val MAX_TEETH = 64
        const val MIN_DEPTH = 0.05f
        const val MAX_DEPTH = 0.5f
        const val MIN_TOOTH_WIDTH = 0.2f
        const val MAX_TOOTH_WIDTH = 0.8f
        const val MAX_TAPER = 0.9f
        const val MAX_HOLE = 0.8f

        val DEFAULT = CogParamsV19()
    }
}

/** v1.9's `CogGeometry`: the outer contour only (what `ShapeObject.forWriting` writes), as (x, y) pairs. */
object CogGeometryV19 {
    private const val MAX_ROOT_STEP_DEG = 20.0

    /** v1.9's `CogGeometry.clamped`. */
    fun clamped(c: CogParamsV19): CogParamsV19 {
        val d = CogParamsV19.DEFAULT
        fun Float.or(v: Float) = if (isFinite()) this else v
        val n = CogParamsV19(
            teeth = c.teeth.coerceIn(CogParamsV19.MIN_TEETH, CogParamsV19.MAX_TEETH),
            depth = c.depth.or(d.depth).coerceIn(CogParamsV19.MIN_DEPTH, CogParamsV19.MAX_DEPTH),
            toothWidth = c.toothWidth.or(d.toothWidth).coerceIn(CogParamsV19.MIN_TOOTH_WIDTH, CogParamsV19.MAX_TOOTH_WIDTH),
            taper = c.taper.or(d.taper).coerceIn(0f, CogParamsV19.MAX_TAPER),
            hole = c.hole.or(d.hole).coerceIn(0f, CogParamsV19.MAX_HOLE),
        )
        return if (n == c) c else n
    }

    /** v1.9's `CogGeometry.rootSteps`. */
    fun rootSteps(c: CogParamsV19): Int {
        val k = clamped(c)
        val gap = 2 * PI / k.teeth * (1.0 - k.toothWidth)
        return max(1, ceil(gap / Math.toRadians(MAX_ROOT_STEP_DEG) - 1e-9).toInt())
    }

    /** v1.9's `CogGeometry.unitOuter`. */
    fun unitOuter(c: CogParamsV19): List<Pair<Float, Float>> {
        val k = clamped(c)
        val n = k.teeth
        val pitch = 2 * PI / n
        val base = k.toothWidth * pitch
        val tip = base * (1.0 - k.taper)
        val root = 1f - k.depth
        val gap = pitch - base
        val m = rootSteps(k)
        val out = ArrayList<Pair<Float, Float>>(n * (3 + m))
        for (t in 0 until n) {
            val a = -PI / 2 + t * pitch
            out += polar(root, a - base / 2)
            out += polar(1f, a - tip / 2)
            out += polar(1f, a + tip / 2)
            out += polar(root, a + base / 2)
            for (j in 1 until m) out += polar(root, a + base / 2 + j * gap / m)
        }
        return out
    }

    /** v1.9's `CogGeometry.outer`: box-local outer contour of a [w] x [h] cog. */
    fun outer(w: Float, h: Float, c: CogParamsV19): List<Pair<Float, Float>> {
        val sx = w / 2f
        val sy = h / 2f
        return unitOuter(c).map { (x, y) -> Pair(x * sx, y * sy) }
    }

    private fun polar(r: Float, a: Double) = Pair((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())
}

/** v1.9's `ShapeLayerData`. */
@Serializable
data class ShapeLayerDataV19(
    val version: Int = ShapeCodecV19.VERSION,
    val shape: ShapeObjectV19,
)

/** v1.9's `ShapeCodec`: the JSON of a shape layer, as v1.9 writes and reads it. */
object ShapeCodecV19 {
    /** 1 = v1.4; 2 = v1.7 (adds [ShapePointV19.radius]); v1.9 keeps 2. */
    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** v1.9's `ShapeCodec.encode`: a cog is written with its v1.7 fallback outline. */
    fun encode(shape: ShapeObjectV19): String =
        json.encodeToString(ShapeLayerDataV19.serializer(), ShapeLayerDataV19(VERSION, (shape.sanitized() ?: shape).forWriting()))

    /** The shape stored in [data] as v1.9 reads it, or null when there is none or v1.9 can't read it. */
    fun decode(data: String?): ShapeObjectV19? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ShapeLayerDataV19.serializer(), data).shape.sanitized()
        } catch (e: Exception) {
            null
        }
    }
}
