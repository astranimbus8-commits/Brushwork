package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues

/**
 * Pixelate Crystallize: irregular polygonal (Voronoi) cells around jittered grid seeds, each
 * filled with the alpha-weighted average color of its pixels.
 */
class CrystallizeFilter : Filter("pixelate.crystallize", "Pixelate Crystallize", FilterCategory.PIXELATE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Size", 3f, 300f, 20f, step = 1f, pixels = true),
        FilterParam.Slider("irregularity", "Irregularity", 0f, 100f, 100f, step = 1f, suffix = "%"),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val lattice = VoronoiLattice(
            src.width, src.height,
            ctx.px(values.float("size")),
            values.float("irregularity") / 100f,
            values.seed(),
        )
        return CellMosaic.pixelate(src, lattice, ctx)
    }
}

/**
 * Mosaic over a regular lattice with a rotatable grid (rotation is about the canvas center, and a
 * cell is centered there). "Radius" is the cell's circumradius for hexagons/triangles and half
 * the side for squares, matching ibisPaint's parameter.
 */
abstract class LatticePixelateFilter(
    id: String,
    name: String,
    maxAngle: Float,
    defaultRadius: Float,
) : Filter(id, name, FilterCategory.PIXELATE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("radius", "Radius", 1f, 200f, defaultRadius, step = 1f, pixels = true),
        FilterParam.Slider("angle", "Angle", 0f, maxAngle, 0f, step = 1f, suffix = "°"),
    )

    /** The cell lattice for an image of [width] x [height]; [radius] is already scaled. */
    internal abstract fun lattice(width: Int, height: Int, radius: Float, angle: Float): CellLattice

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val lattice = lattice(src.width, src.height, ctx.px(values.float("radius")), values.float("angle"))
        return CellMosaic.pixelate(src, lattice, ctx)
    }
}

/** Square Pixelate: uniform square blocks of side 2 x Radius on a rotatable grid. */
class SquarePixelateFilter : LatticePixelateFilter("pixelate.square_pixelate", "Square Pixelate", 90f, 8f) {
    override fun lattice(width: Int, height: Int, radius: Float, angle: Float): CellLattice =
        SquareLattice(width, height, 2f * radius, angle)
}

/** Hexagonal Pixelate: a honeycomb of hexagons (circumradius = Radius), each one color. */
class HexagonalPixelateFilter : LatticePixelateFilter("pixelate.hexagonal_pixelate", "Hexagonal Pixelate", 360f, 12f) {
    override fun lattice(width: Int, height: Int, radius: Float, angle: Float): CellLattice =
        HexLattice(width, height, radius, angle)
}

/** Triangular Pixelate: alternating up/down equilateral triangles (circumradius = Radius). */
class TriangularPixelateFilter : LatticePixelateFilter("pixelate.triangular_pixelate", "Triangular Pixelate", 360f, 14f) {
    override fun lattice(width: Int, height: Int, radius: Float, angle: Float): CellLattice =
        TriangleLattice(width, height, radius, angle)
}
