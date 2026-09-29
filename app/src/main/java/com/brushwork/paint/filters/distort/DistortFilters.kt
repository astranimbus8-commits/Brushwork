package com.brushwork.paint.filters.distort

import com.brushwork.paint.filters.Filter

/** Every filter of the Distortion category (plus the Frame & Weather filters Blur Frame and Rain). */
val distortFilters: List<Filter> = listOf(
    ExpansionFilter(),
    FishLensFilter(),
    SphereLensFilter(),
    WaveFilter(),
    RippleFilter(),
    TwirlFilter(),
    PolarCoordinatesFilter(),
    TileCountFilter(),
    TileSizeFilter(),
    BlurFrameFilter(),
    RainFilter(),
)
