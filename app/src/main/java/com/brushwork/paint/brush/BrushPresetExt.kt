package com.brushwork.paint.brush

import com.brushwork.paint.tools.ToolId

/** Allowed ranges of the brush settings (shared by the engine, persistence and the UI). */
object BrushLimits {
    const val MIN_SIZE = 0.5f
    const val MAX_SIZE = 1000f
    const val MIN_SPACING = 0.01f
    const val MAX_SPACING = 2f
    const val MIN_ROUNDNESS = 0.05f
    const val MAX_TAPER = 2000f
    const val MAX_SCATTER = 3f
}

/** Returns a copy with every value clamped into its valid range (and NaNs replaced). */
fun BrushPreset.sanitized(): BrushPreset {
    fun f(v: Float, lo: Float, hi: Float, def: Float) = if (v.isNaN()) def else v.coerceIn(lo, hi)
    return copy(
        size = f(size, BrushLimits.MIN_SIZE, BrushLimits.MAX_SIZE, 12f),
        minSizeRatio = f(minSizeRatio, 0f, 1f, 0.2f),
        opacity = f(opacity, 0f, 1f, 1f),
        flow = f(flow, 0f, 1f, 1f),
        hardness = f(hardness, 0f, 1f, 0.9f),
        spacing = f(spacing, BrushLimits.MIN_SPACING, BrushLimits.MAX_SPACING, 0.08f),
        angle = if (angle.isNaN()) 0f else ((angle % 360f) + 360f) % 360f,
        roundness = f(roundness, BrushLimits.MIN_ROUNDNESS, 1f, 1f),
        scatter = f(scatter, 0f, BrushLimits.MAX_SCATTER, 0f),
        taperStart = f(taperStart, 0f, BrushLimits.MAX_TAPER, 0f),
        taperEnd = f(taperEnd, 0f, BrushLimits.MAX_TAPER, 0f),
        mixing = f(mixing, 0f, 1f, 0.5f),
        grain = f(grain, 0f, 1f, 0f),
    )
}

/** How a stroke changes pixels. */
enum class StrokeKind {
    /** Colored coverage buffer composited SRC_OVER (SRC_ATOP on alpha-locked layers). */
    PAINT,
    /** Coverage buffer composited DST_OUT. */
    ERASE,
    /** Direct per-dab pixel edits. */
    SMUDGE,
    BLUR,
    WATERCOLOR;

    /** True when dabs modify layer pixels directly instead of going through the coverage buffer. */
    val isDirect: Boolean get() = this == SMUDGE || this == BLUR || this == WATERCOLOR

    companion object {
        /** The stroke kind [toolId] uses with [preset]. */
        fun of(toolId: ToolId, preset: BrushPreset): StrokeKind = when (toolId) {
            ToolId.ERASER -> ERASE
            ToolId.SMUDGE -> SMUDGE
            ToolId.BLUR -> BLUR
            else -> when (preset.tip) {
                BrushTip.WATERCOLOR -> WATERCOLOR
                BrushTip.SMUDGE -> SMUDGE
                BrushTip.BLUR -> BLUR
                else -> PAINT
            }
        }
    }
}
