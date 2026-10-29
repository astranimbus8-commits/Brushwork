package com.brushwork.paint.compat19

import kotlinx.serialization.Serializable

/*
 * v1.10 G2 (compat19 kit): FROZEN copies of v1.9's brush settings (`brush/BrushPreset.kt` at tag v1.9.0, unchanged
 * since v1.7) and tool ids (`tools/Tool.kt`, v1.7's plus Magnetic lasso and Color select), as the
 * v1.9 shape and vector codecs serialize them. Never change them.
 */

/** v1.9's `BrushTip`. */
@Serializable
enum class BrushTipV19(val label: String) {
    ROUND_HARD("Pen"),
    ROUND_SOFT("Soft brush"),
    AIRBRUSH("Airbrush"),
    PENCIL("Pencil"),
    CHALK("Chalk / crayon"),
    CALLIGRAPHY("Calligraphy"),
    SQUARE("Square"),
    SPRAY("Spray"),
    MARKER("Marker"),
    WATERCOLOR("Watercolor"),
    SMUDGE("Smudge"),
    BLUR("Blur"),
}

/** v1.9's `BrushPreset`. */
@Serializable
data class BrushPresetV19(
    val id: String,
    val name: String,
    val tip: BrushTipV19,
    val size: Float = 12f,
    val minSizeRatio: Float = 0.2f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val hardness: Float = 0.9f,
    val spacing: Float = 0.08f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    val angle: Float = 0f,
    val roundness: Float = 1f,
    val scatter: Float = 0f,
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val antiAlias: Boolean = true,
    val mixing: Float = 0.5f,
    val grain: Float = 0f,
)

/** v1.9's `ToolId` (a vector path's brush tool is stored by name). */
enum class ToolIdV19(val label: String) {
    BRUSH("Brush"),
    ERASER("Eraser"),
    SMUDGE("Smudge"),
    BLUR("Blur"),
    FILL("Bucket"),
    EYEDROPPER("Eyedropper"),
    MAGIC_WAND("Magic wand"),
    LASSO("Lasso"),
    MARQUEE("Select shape"),
    TRANSFORM("Transform"),
    TEXT("Text"),
    SHAPE("Shape"),
    CURVE("Curve"),
    POLYLINE("Polyline"),
    FRAME_DIVIDER("Frame divider"),
    RULER("Ruler"),
    OBJECT_SELECT("Object select"),
    REMOVE("Remove"),
    CLONE("Clone stamp"),
    MASK("Masks"),
    PATH("Path"),
    TEXT_FRAMES("Text frames"),
    ARRAY("Array"),
    SYMMETRY("Symmetry"),
    PATHFINDER("Pathfinder"),
    MAGNETIC_LASSO("Magnetic lasso"),
    COLOR_SELECT("Color select"),
}
