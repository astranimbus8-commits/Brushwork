package com.brushwork.paint.compat17

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * v1.8 F0: a FROZEN copy of v1.7's shape object and shape-layer codec (`tools/vector/
 * ShapeObject.kt`, `ShapeGeometry.kt`, `ShapePoints.kt`, `ShapeTool.kt` at 885793b4), in test
 * sources only, as v1.7 serializes and sanitizes them. v1.7 has no COG: with v1.7's JSON
 * settings (`coerceInputValues`) a v1.8 cog's type reads as the default, RECTANGLE, and its new
 * fields are dropped. The outline geometry (`ShapeOutlines`, `ShapeGeometry`) is not copied.
 * Never change it.
 */

/** v1.7's `ShapeType` (no COG). */
@Serializable
enum class ShapeTypeV17(val label: String) {
    LINE("Line"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    POLYGON("Polygon"),
    STAR("Star"),
    ARROW("Arrow");

    /** Defined by two end points instead of a box. */
    val isLineLike: Boolean get() = this == LINE || this == ARROW

    /** Supports live corners (round / bevel / inverted). */
    val hasCorners: Boolean get() = this == RECTANGLE || this == POLYGON || this == STAR
}

/** v1.7's `ShapeStyle`. */
@Serializable
enum class ShapeStyleV17(val label: String, val stroke: Boolean, val fill: Boolean) {
    STROKE("Stroke", true, false),
    FILL("Fill", false, true),
    STROKE_FILL("Stroke + fill", true, true),
}

/** v1.7's `CornerStyle`. */
@Serializable
enum class CornerStyleV17(val label: String) {
    SHARP("Sharp"),
    ROUND("Round"),
    BEVEL("Bevel"),
    INVERTED("Inverted"),
}

/** v1.7's `LineCapStyle`. */
@Serializable
enum class LineCapStyleV17(val label: String) { BUTT("Flat"), ROUND("Round"), SQUARE("Square") }

/** v1.7's `ArrowHeads`. */
@Serializable
enum class ArrowHeadsV17(val label: String, val start: Boolean, val end: Boolean) {
    END("End", false, true),
    START("Start", true, false),
    BOTH("Both", true, true),
}

/** v1.7's `ArrowHeadStyle`. */
@Serializable
enum class ArrowHeadStyleV17(val label: String) { FILLED("Filled"), OPEN("Open") }

/** v1.7's `JoinStyle`. */
enum class JoinStyleV17 { MITER, ROUND, BEVEL }

/** v1.7's `ShapeStroke`. */
@Serializable
enum class ShapeStrokeV17(val label: String) {
    PLAIN("Plain line"),
    BRUSH("Current brush"),
}

/** v1.7's `ShapeHandle`. */
@Serializable
data class ShapeHandleV17(val x: Float, val y: Float)

/** v1.7's `ShapePoint`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ShapePointV17(
    val x: Float,
    val y: Float,
    val smooth: Boolean = false,
    val handleIn: ShapeHandleV17? = null,
    val handleOut: ShapeHandleV17? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val radius: Float? = null,
) {
    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() &&
            (handleIn == null || (handleIn.x.isFinite() && handleIn.y.isFinite())) &&
            (handleOut == null || (handleOut.x.isFinite() && handleOut.y.isFinite()))
}

/** v1.7's `ShapeObject` (its serialized properties and [sanitized]). */
@Serializable
data class ShapeObjectV17(
    val type: ShapeTypeV17 = ShapeTypeV17.RECTANGLE,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    val rotation: Float = 0f,
    val style: ShapeStyleV17 = ShapeStyleV17.STROKE,
    val strokeWidth: Float = 8f,
    val strokeWith: ShapeStrokeV17 = ShapeStrokeV17.PLAIN,
    val strokeColor: Int = BLACK,
    val fillColor: Int = BLACK,
    val fillFollowsColor: Boolean = true,
    val lineCap: LineCapStyleV17 = LineCapStyleV17.ROUND,
    val corner: CornerStyleV17 = CornerStyleV17.SHARP,
    val cornerRadius: Float = 0f,
    val sides: Int = 5,
    val starPoints: Int = 5,
    val innerRatio: Float = 0.45f,
    val arrowHeads: ArrowHeadsV17 = ArrowHeadsV17.END,
    val arrowHeadStyle: ArrowHeadStyleV17 = ArrowHeadStyleV17.FILLED,
    val arrowHeadScale: Float = 4f,
    val brushTool: String? = null,
    val brushPreset: BrushPresetV17? = null,
    val points: List<ShapePointV17>? = null,
) {
    /** Lines and arrows are open paths; the other shapes are closed. */
    val closed: Boolean get() = !type.isLineLike

    /** v1.7's `ShapeObject.sanitized`: every value in its supported range; null when the placement can't be used. */
    fun sanitized(): ShapeObjectV17? {
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite() && rotation.isFinite())) return null
        val lim = MAX_LENGTH
        val pts = points?.takeIf { list -> list.size >= minPoints(!type.isLineLike) && list.all { it.isFinite } }?.let { sanitizedRadii(it, lim) }
        return copy(
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
        )
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()

        /** v1.7's `ShapeSettings.MIN_STROKE`, `MAX_STROKE` and `MAX_LENGTH`. */
        const val MIN_STROKE = 0.25f
        const val MAX_STROKE = 2000f
        const val MAX_LENGTH = 100_000f

        /** v1.7's `ShapeGeometry.MIN_SIDES` and `MAX_SIDES`. */
        const val MIN_SIDES = 3
        const val MAX_SIDES = 64

        /** v1.7's `ShapePoints.minPoints`: fewest points of a closed / open custom shape. */
        fun minPoints(closed: Boolean): Int = if (closed) 3 else 2

        /** v1.7's `ShapeGeometry.normalizeDegrees`. */
        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d <= -180f) d += 360f
            if (d > 180f) d -= 360f
            return d
        }

        private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

        private fun sanitizedRadii(pts: List<ShapePointV17>, lim: Float): List<ShapePointV17> {
            if (pts.all { p -> p.radius.let { it == null || (it.isFinite() && it in 0f..lim) } }) return pts
            return pts.map { p -> p.radius?.let { r -> p.copy(radius = if (r.isFinite()) r.coerceIn(0f, lim) else null) } ?: p }
        }
    }
}

/** v1.7's `ShapeLayerData`: what a shape layer stores in `Layer.shapeData`. */
@Serializable
data class ShapeLayerDataV17(
    val version: Int = ShapeCodecV17.VERSION,
    val shape: ShapeObjectV17,
)

/** v1.7's `ShapeCodec`: the JSON of a shape layer, as v1.7 writes and reads it. */
object ShapeCodecV17 {
    /** 1 = v1.4; 2 = v1.7 (adds [ShapePointV17.radius]). */
    const val VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(shape: ShapeObjectV17): String =
        json.encodeToString(ShapeLayerDataV17.serializer(), ShapeLayerDataV17(VERSION, shape.sanitized() ?: shape))

    /** The shape stored in [data] as v1.7 reads it, or null when there is none or v1.7 can't read it. */
    fun decode(data: String?): ShapeObjectV17? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ShapeLayerDataV17.serializer(), data).shape.sanitized()
        } catch (e: Exception) {
            null
        }
    }
}
