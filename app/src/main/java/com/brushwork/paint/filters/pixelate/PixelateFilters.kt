package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.filters.Filter

/** Every filter of the Pixelate category, in ibisPaint's menu order. */
val pixelateFilters: List<Filter> = listOf(
    CrystallizeFilter(),
    HexagonalPixelateFilter(),
    SquarePixelateFilter(),
    TriangularPixelateFilter(),
    PointillizeFilter(),
    DotsFilter.hexagonal(),
    DotsFilter.square(),
)
