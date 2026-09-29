package com.brushwork.paint.filters.adjust

import com.brushwork.paint.filters.Filter

/** Every filter of the "Color Adjustment" category, in menu order. */
val adjustFilters: List<Filter> = listOf(
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
