package com.brushwork.paint.filters.draw

import com.brushwork.paint.filters.Filter

/**
 * Every filter of the Draw category, plus Background Removal (category AI, id
 * "ai.background_removal") which this module also provides.
 */
val drawFilters: List<Filter> = listOf(
    ParallelGradationFilter(),
    ConcentricGradationFilter(),
    RadialLineGradationFilter(),
    RadialLineFilter(),
    SpeedLineFilter(),
    CloudsFilter(),
    QrCodeFilter(),
    WatercolorFilter(),
    AnimeBackgroundFilter(),
    MangaBackgroundFilter(),
    BackgroundRemovalFilter(),
)
