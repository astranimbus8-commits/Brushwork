package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.PixelMapper

/**
 * Lightroom-style tone controls (v1.5 §4.8; owned by A6): Exposure (EV), Contrast, Highlights,
 * Shadows, Whites and Blacks, hue-preserving (luminance ratio), first in Color Adjustment and the
 * default effect of adjustment layers. The parameter keys and ranges are frozen.
 *
 * Foundation stub: the identity (A6 writes the real mapping). Until then Invert Color is the
 * non-identity live effect to test adjustment layers with (`InvertFilter.pixelMapper`).
 */
class ToneFilter : Filter("adjust.tone", "Tone", FilterCategory.ADJUST) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider(EXPOSURE, "Exposure", -5f, 5f, 0f, 0.01f, suffix = "EV"),
        FilterParam.Slider(CONTRAST, "Contrast", -100f, 100f, 0f, 1f),
        FilterParam.Slider(HIGHLIGHTS, "Highlights", -100f, 100f, 0f, 1f),
        FilterParam.Slider(SHADOWS, "Shadows", -100f, 100f, 0f, 1f),
        FilterParam.Slider(WHITES, "Whites", -100f, 100f, 0f, 1f),
        FilterParam.Slider(BLACKS, "Blacks", -100f, 100f, 0f, 1f),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer = src.copy()

    override fun pixelMapper(values: FilterValues): PixelMapper = IDENTITY

    companion object {
        const val ID = "adjust.tone"
        const val EXPOSURE = "exposure"
        const val CONTRAST = "contrast"
        const val HIGHLIGHTS = "highlights"
        const val SHADOWS = "shadows"
        const val WHITES = "whites"
        const val BLACKS = "blacks"

        private val IDENTITY = PixelMapper { _, _, _ -> }
    }
}
