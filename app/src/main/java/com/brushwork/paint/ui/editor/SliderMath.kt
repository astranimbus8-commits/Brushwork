package com.brushwork.paint.ui.editor

import com.brushwork.paint.core.Units
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/** Value mappings and labels for the editor's side sliders and size readouts. */
object SliderMath {
    const val MIN_BRUSH_SIZE = 0.5f
    const val MAX_BRUSH_SIZE = 1000f

    private val logRange = ln(MAX_BRUSH_SIZE / MIN_BRUSH_SIZE)

    /** Brush diameter (px) -> slider position 0..1 (logarithmic, so small sizes get room). */
    fun sizeToFraction(size: Float): Float =
        (ln(size.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE) / MIN_BRUSH_SIZE) / logRange).coerceIn(0f, 1f)

    /** Slider position 0..1 -> brush diameter (px), rounded to a sensible step. */
    fun fractionToSize(fraction: Float): Float {
        val raw = MIN_BRUSH_SIZE * exp(fraction.coerceIn(0f, 1f) * logRange)
        return roundSize(raw).coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
    }

    /** 0.1 px steps below 10 px, whole pixels above. */
    fun roundSize(size: Float): Float = if (size < 10f) (size * 10f).roundToInt() / 10f else size.roundToInt().toFloat()

    fun formatSize(size: Float): String = Units.formatNumber(size.toDouble(), if (size < 10f) 1 else 0)

    fun formatPercent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    /** "150%", or one decimal for small zooms ("2.5%"). */
    fun formatZoom(scale: Float): String {
        val pct = scale * 100f
        return if (pct < 10f) "${Units.formatNumber(pct.toDouble(), 1)}%" else "${pct.roundToInt()}%"
    }
}
