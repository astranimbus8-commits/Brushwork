package com.brushwork.paint.filters.blur

import com.brushwork.paint.filters.Filter

/** Every filter of the Blur category, in menu order. */
val blurFilters: List<Filter> = listOf(
    GaussianBlurFilter(),
    ZoomingBlurFilter(),
    SpinBlurFilter(),
    MotionBlurFilter(),
    MosaicFilter(),
    UnsharpMaskFilter(),
    FrostedGlassFilter(),
    FrostedGlassZoomingFilter(),
    FrostedGlassMovingFilter(),
)
