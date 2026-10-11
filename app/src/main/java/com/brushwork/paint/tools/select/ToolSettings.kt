package com.brushwork.paint.tools.select

import com.brushwork.paint.tools.select.colorrange.ColorPreset
import kotlinx.serialization.Serializable

/** Magic wand options (persisted). */
@Serializable
data class WandSettings(
    /** 0..255 max channel difference, see [RegionFill.colorDistance]. */
    val tolerance: Int = 32,
    val contiguous: Boolean = true,
    val source: SampleSource = SampleSource.LAYER,
    val antiAlias: Boolean = true,
)

/** Lasso options (persisted). */
@Serializable
data class LassoSettings(
    /** Tap corner points instead of drawing freehand. */
    val polygon: Boolean = false,
    val antiAlias: Boolean = true,
)

@Serializable
enum class MarqueeShape(val label: String) {
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
}

/** Rectangle / ellipse selection options (persisted). */
@Serializable
data class MarqueeSettings(
    val shape: MarqueeShape = MarqueeShape.RECTANGLE,
    /** Constrain to a square / circle. */
    val square: Boolean = false,
    /** The first touch is the center instead of a corner. */
    val fromCenter: Boolean = false,
)

/** Bucket fill options (persisted). */
@Serializable
data class FillSettings(
    val tolerance: Int = 32,
    val source: SampleSource = SampleSource.LAYER,
    /** 0..[MAX_GAP_CLOSE] px, see [RegionParams.gapClose]. */
    val gapClose: Int = 0,
    /** 0..[MAX_EXPAND] px. */
    val expand: Int = 0,
    val antiAlias: Boolean = true,
) {
    companion object {
        const val MAX_GAP_CLOSE = 10
        const val MAX_EXPAND = 8
    }
}

/** Eyedropper options (persisted). */
@Serializable
data class EyedropperSettings(
    val source: SampleSource = SampleSource.CANVAS,
    /** Averaged square: 1, 3 or 5 px. */
    val sampleSize: Int = 1,
    /** Switch back to the last painting tool after picking. */
    val returnToBrush: Boolean = true,
)

/** Magnetic lasso options (persisted; v1.8, design §3.4). */
@Serializable
data class MagneticSettings(
    /** How far (screen dp) a placed anchor looks for the strongest edge, [MIN_REACH_DP]..[MAX_REACH_DP]. */
    val edgeReachDp: Int = 24,
    /** Edges weaker than this (% of the strongest) count as flat paper, 0..[MAX_CONTRAST]. */
    val edgeContrast: Int = 10,
    /** A placed anchor snaps to the strongest edge within reach. */
    val snapToEdge: Boolean = true,
    /** The pixels whose edges the outline follows (as Magic wand's Refer). */
    val source: SampleSource = SampleSource.LAYER,
    val antiAlias: Boolean = true,
) {
    /** The contrast floor on the edge map's 0..255 scale. */
    val floor: Int get() = edgeContrast.coerceIn(0, MAX_CONTRAST) * 255 / 100

    companion object {
        const val MIN_REACH_DP = 8
        const val MAX_REACH_DP = 64
        const val MAX_CONTRAST = 50
    }
}

/** Color select options (persisted; v1.8, design §3.5). */
@Serializable
data class ColorSelectSettings(
    /** How far from a sample a color still counts (ΔE76 for sampled colors; widens the presets' bands), 0..[MAX_FUZZINESS]. */
    val fuzziness: Int = 40,
    /** The colors that count: the samples, or one of the presets. */
    val preset: ColorPreset = ColorPreset.SAMPLED,
    /** The pixels whose colors count (as Magic wand's Refer). */
    val source: SampleSource = SampleSource.LAYER,
    /** Soft edges from the fuzziness band; off: every pixel in or out. */
    val antiAlias: Boolean = true,
    /** Selects the colors that do NOT count (transparent pixels never count). */
    val invert: Boolean = false,
) {
    companion object {
        const val MAX_FUZZINESS = 200
    }
}
