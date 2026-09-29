package com.brushwork.paint.tools.select

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
