package com.brushwork.paint.compat18

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * v1.9 F1a: a FROZEN copy of v1.8's stored curve and shape tool options (`tools/vector/
 * CurveTool.kt`'s `CurveSettings` and `ShapeTool.kt`'s `ShapeSettings`, at 600582d9; unchanged at
 * 74915e30), and of `AppSettings.json`, in test sources only: what v1.8 reads from and writes to
 * the `vec.curve`, `vec.polyline`, `vec.path` and `vec.shape` preferences of a v1.9 install
 * (unknown keys ignored, so v1.8 drops v1.9's `ownLayer` and `newLayerEachTime`). Only the
 * fields; the clamping (`sanitized`) and the derived values are not copied. Never change it: it
 * is what v1.8 is.
 */

/** v1.8's `CurveStroke`. */
@Serializable
enum class CurveStrokeV18(val label: String) {
    BRUSH("Current brush"),
    PLAIN("Plain line"),
    NONE("No stroke"),
}

/** v1.8's `CurveSettings` (the curve, polyline and path tools' stored options). */
@Serializable
data class CurveSettingsV18(
    val closed: Boolean = false,
    val tension: Float = 0f,
    val stroke: CurveStrokeV18 = CurveStrokeV18.BRUSH,
    val plainWidth: Float = 6f,
    val fill: Boolean = false,
    val fillColor: Int? = null,
    val taper: Boolean = false,
    val taperPercent: Float = 20f,
    val unit: LengthUnitV18 = LengthUnitV18.PX,
    val nudgeStepPx: Float = 1f,
    val useBrushSize: Boolean = true,
    /** v1.8's `VSpline.DEFAULT_ORDER`. */
    val pathOrder: Int = 4,
    val pathEndpoint: Boolean = true,
    val lastStroke: CurveStrokeV18 = CurveStrokeV18.BRUSH,
)

/** v1.8's `ShapeSettings` (the shape tool's stored options). */
@Serializable
data class ShapeSettingsV18(
    val type: ShapeTypeV18 = ShapeTypeV18.RECTANGLE,
    val style: ShapeStyleV18 = ShapeStyleV18.STROKE,
    val strokeWidth: Float = 8f,
    val useBrushSize: Boolean = true,
    val strokeWith: ShapeStrokeV18 = ShapeStrokeV18.PLAIN,
    val fillColor: Int? = null,
    val lineCap: LineCapStyleV18 = LineCapStyleV18.ROUND,
    val corner: CornerStyleV18 = CornerStyleV18.SHARP,
    val cornerRadius: Float = 30f,
    val sides: Int = 5,
    val starPoints: Int = 5,
    val innerRatio: Float = 0.45f,
    val arrowHeads: ArrowHeadsV18 = ArrowHeadsV18.END,
    val arrowHeadStyle: ArrowHeadStyleV18 = ArrowHeadStyleV18.FILLED,
    val arrowHeadScale: Float = 4f,
    val fromCenter: Boolean = false,
    val keepProportions: Boolean = false,
    val snapAngle: Boolean = false,
    val unit: LengthUnitV18 = LengthUnitV18.PX,
    val nudgeStepPx: Float = 1f,
    val editable: Boolean = true,
    val cogTeeth: Int = CogParamsV18.DEFAULT.teeth,
    val cogDepth: Float = CogParamsV18.DEFAULT.depth,
    val cogToothWidth: Float = CogParamsV18.DEFAULT.toothWidth,
    val cogTaper: Float = CogParamsV18.DEFAULT.taper,
    val cogHole: Float = CogParamsV18.DEFAULT.hole,
)

/** v1.8's `AppSettings.json` and the preference keys of the tool options above. */
object ToolSettingsV18 {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val CURVE = "vec.curve"
    const val POLYLINE = "vec.polyline"
    const val PATH = "vec.path"
    const val SHAPE = "vec.shape"

    fun curve(text: String): CurveSettingsV18 = json.decodeFromString(CurveSettingsV18.serializer(), text)
    fun shape(text: String): ShapeSettingsV18 = json.decodeFromString(ShapeSettingsV18.serializer(), text)
    fun write(s: CurveSettingsV18): String = json.encodeToString(CurveSettingsV18.serializer(), s)
    fun write(s: ShapeSettingsV18): String = json.encodeToString(ShapeSettingsV18.serializer(), s)
}
