package com.brushwork.paint.filters.style

import com.brushwork.paint.filters.Filter

/** Every filter of the Style category, in menu order (as in ibisPaint's Style list). */
val styleFilters: List<Filter> = listOf(
    StrokeBothFilter(),
    StrokeOuterFilter(),
    StrokeInnerFilter(),
    StainedGlassFilter(),
    StainedGlassCellsFilter(),
    WetEdgeFilter(),
    GlowInnerFilter(),
    GlowOuterFilter(),
    BevelOuterFilter(),
    ReliefFilter(),
    ReliefHQFilter(),
    WaterdropFilter(),
    SatinFilter(),
    DropShadowFilter(),
    ExtrudeParallelFilter(),
    GodRaysFilter(),
)
