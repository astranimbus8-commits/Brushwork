package com.brushwork.paint.filters.adjust

import com.brushwork.paint.filters.Filter

/** Every filter of the "Color Adjustment" category, in menu order (Tone first, v1.5). */
val adjustFilters: List<Filter> = listOf(
    ToneFilter(),
    BrightnessContrastFilter(),
    ToneCurveFilter(),
    ColorBalanceFilter(),
    HueSaturationFilter(),
    LevelsFilter(),
    ReplaceColorFilter(),
    GradationMapFilter(),
    PosterizeFilter(),
    InvertFilter(),
    GrayscaleFilter(),
    BlackWhiteFilter(),
    MonocolorFilter(),
    ChangeDrawingColorFilter(),
    ExtractLineDrawingFilter(),
    FindEdgesFilter(),
)
