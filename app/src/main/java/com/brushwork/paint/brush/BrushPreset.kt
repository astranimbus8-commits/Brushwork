package com.brushwork.paint.brush

import kotlinx.serialization.Serializable

/** Shape/behavior family of a brush tip. */
@Serializable
enum class BrushTip(val label: String) {
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

/**
 * All settings of one brush. Sizes are DOCUMENT pixels (diameter). Ratios are 0..1.
 * Immutable: create modified copies with `copy(...)`.
 */
@Serializable
data class BrushPreset(
    val id: String,
    val name: String,
    val tip: BrushTip,
    val size: Float = 12f,
    /** Diameter at zero pressure as a fraction of [size] (when [pressureSize]). */
    val minSizeRatio: Float = 0.2f,
    /** Max opacity of a whole stroke (overlapping dabs inside one stroke never exceed it). */
    val opacity: Float = 1f,
    /** Opacity of each individual dab (build-up within a stroke). */
    val flow: Float = 1f,
    /** 1 = crisp edge, 0 = fully feathered. */
    val hardness: Float = 0.9f,
    /** Distance between dabs as a fraction of the current diameter. */
    val spacing: Float = 0.08f,
    val pressureSize: Boolean = true,
    val pressureOpacity: Boolean = false,
    /** Tip rotation in degrees (calligraphy/square). */
    val angle: Float = 0f,
    /** Tip roundness (1 = circle, 0.1 = thin ellipse). */
    val roundness: Float = 1f,
    /** Random dab offset as a fraction of the diameter. */
    val scatter: Float = 0f,
    /** Length (document px) of the automatic thin-in / thin-out for finger drawing. 0 = off. */
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val antiAlias: Boolean = true,
    /** Smudge/watercolor: how much canvas color is picked up (0..1). Blur: blur strength. */
    val mixing: Float = 0.5f,
    /** Random per-dab opacity variation 0..1 (texture for pencil/chalk). */
    val grain: Float = 0f,
)
