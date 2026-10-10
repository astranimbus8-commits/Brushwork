package com.brushwork.paint.compat17

import kotlinx.serialization.Serializable

/*
 * v1.8 F0: FROZEN copies of v1.7's brush settings (`brush/BrushPreset.kt` at 885793b4) and tool
 * ids (`tools/Tool.kt`), as the v1.7 codecs serialize them. Never change them.
 */

/** v1.7's `BrushTip`. */
@Serializable
enum class BrushTipV17(val label: String) {
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

/** v1.7's `BrushPreset`. */
@Serializable
data class BrushPresetV17(
    val id: String,
    val name: String,
    val tip: BrushTipV17,
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

/** v1.7's `ToolId` (a vector path's brush tool is stored by name). */
enum class ToolIdV17(val label: String) {
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
}
