package com.brushwork.paint.filters.art

import com.brushwork.paint.filters.Filter

/** Every filter of the Art category, in the order shown in the filter browser. */
val artFilters: List<Filter> = listOf(
    ChromaticAberrationMovingFilter(),
    ChromaticAberrationZoomingFilter(),
    NoiseFilter(),
)
