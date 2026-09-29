package com.brushwork.paint.filters.draw

import com.brushwork.paint.filters.Filter

/** Every filter of the Draw category (plus the AI Background Removal, which lives here too). */
val drawFilters: List<Filter> = listOf(
    ParallelGradationFilter(),
    ConcentricGradationFilter(),
    RadialLineGradationFilter(),
)
