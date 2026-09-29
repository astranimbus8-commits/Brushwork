package com.brushwork.paint.model

import com.brushwork.paint.core.LengthUnit
import kotlinx.serialization.Serializable

@Serializable
enum class GridType(val label: String) {
    SQUARE("Square"),
    RULE_OF_THIRDS("Rule of thirds"),
    ISOMETRIC("Isometric"),
    DIAGONAL("Diagonal"),
}

/** Overlay grid (display only; never rendered into the artwork). Distances are document px. */
@Serializable
data class GridSettings(
    val enabled: Boolean = false,
    val type: GridType = GridType.SQUARE,
    val spacingPx: Float = 100f,
    /** Every Nth line is drawn stronger (0 or 1 = none). */
    val majorEvery: Int = 4,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val color: Int = 0xFF2F80ED.toInt(),
    val opacity: Float = 0.45f,
    /** Unit used when showing/editing the numbers in the grid panel. */
    val unit: LengthUnit = LengthUnit.PX,
    /** Snap painting / shape points to grid intersections. */
    val snap: Boolean = false,
)

@Serializable
enum class RulerType(val label: String) {
    STRAIGHT("Straight"),
    CIRCLE("Circular"),
    ELLIPSE("Elliptical"),
    RADIAL("Radial"),
}

@Serializable
enum class RulerSnap(val label: String) {
    /** Strokes follow a line parallel to / concentric with the ruler through the stroke start. */
    PARALLEL("Parallel / concentric"),
    /** Strokes are pulled onto the ruler itself. */
    ON_RULER("On the ruler"),
}

/**
 * The drawing ruler. All positions/lengths are in DOCUMENT PIXELS (floats, sub-pixel allowed);
 * [unit] only controls how numbers are displayed/edited. [centerX]/[centerY] < 0 means
 * "not placed yet" (the ruler is centered on the canvas when first enabled).
 *
 *  - STRAIGHT: a line through (centerX, centerY) at [angleDeg].
 *  - CIRCLE:   circle at (centerX, centerY) with [radius].
 *  - ELLIPSE:  ellipse at (centerX, centerY), semi-axes [radiusX]/[radiusY], rotated [angleDeg].
 *  - RADIAL:   lines through (centerX, centerY) — every stroke goes toward/away from the center.
 *              [radialLines] is only used to draw guide lines.
 */
@Serializable
data class RulerSettings(
    val enabled: Boolean = false,
    val type: RulerType = RulerType.STRAIGHT,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 0f,
    val radius: Float = 300f,
    val radiusX: Float = 400f,
    val radiusY: Float = 250f,
    val radialLines: Int = 24,
    val snap: RulerSnap = RulerSnap.PARALLEL,
    val unit: LengthUnit = LengthUnit.PX,
    /** Step for nudge arrow buttons, in [unit]. */
    val nudgeStep: Float = 1f,
)

@Serializable
enum class StabilizerMode(val label: String) {
    OFF("Off"),
    /** Weighted moving average (like ibisPaint's "Stabilization"). */
    SMOOTH("Smooth"),
    /** Blender-style "lazy mouse": a string of fixed length drags the brush behind the finger. */
    ROPE("Rope (lazy mouse)"),
}

@Serializable
data class StabilizerSettings(
    val mode: StabilizerMode = StabilizerMode.OFF,
    /** 0..1 for SMOOTH. */
    val strength: Float = 0.5f,
    /** Rope length in SCREEN dp (like Blender's radius) for ROPE. */
    val ropeLengthDp: Float = 60f,
    /** When lifting the finger, finish the stroke up to the finger position. */
    val catchUp: Boolean = true,
)
