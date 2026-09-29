package com.brushwork.paint.filters.style

import com.brushwork.paint.filters.Filter

/** Every filter of the Style category, in menu order. */
val styleFilters: List<Filter> = listOf(
    StrokeBothFilter(),
    StrokeOuterFilter(),
    StrokeInnerFilter(),
)
