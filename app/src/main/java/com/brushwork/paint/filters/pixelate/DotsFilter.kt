package com.brushwork.paint.filters.pixelate

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Dots (Hexagonal) / Dots (Square): the image becomes round dots on a hexagonal or square
 * lattice, one dot per cell ("Size" = distance between dot centers).
 *
 * - Dot size "Uniform": every dot has the radius set by Density (100% fills the cells completely)
 *   and takes its cell's average color and opacity, like an LED / bead display.
 * - Dot size "By darkness": a halftone screen; the share of the cell covered by its dot equals
 *   the cell's ink amount (darkness x opacity) at 100% density, so white or empty cells get no
 *   dot and black cells a full one. Lower densities scale the dots down.
 * Dots use the cell color or a custom color, over transparency or a background color.
 */
class DotsFilter private constructor(id: String, name: String, private val hexagonal: Boolean) :
    Filter(id, name, FilterCategory.PIXELATE) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Size", 2f, 200f, 16f, step = 1f, pixels = true),
        FilterParam.Slider("density", "Density", 0f, 100f, 80f, step = 1f, suffix = "%"),
        FilterParam.Slider("angle", "Angle", 0f, if (hexagonal) 360f else 90f, 0f, step = 1f, suffix = "°"),
        FilterParam.Choice("sizing", "Dot size", listOf("Uniform", "By darkness"), 0),
        FilterParam.Choice("dotColor", "Dot color", listOf("Cell color", "Custom"), 0),
        FilterParam.Color("color", "Custom color", 0xFF000000.toInt(), useDrawingColor = true),
        FilterParam.Toggle("background", "Fill background", false),
        FilterParam.Color("bgColor", "Background color", 0xFFFFFFFF.toInt()),
    )

    internal fun lattice(width: Int, height: Int, pitch: Float, angle: Float): CellLattice =
        if (hexagonal) HexLattice(width, height, pitch / SQRT3, angle) else SquareLattice(width, height, pitch, angle)

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val pitch = max(1f, ctx.px(values.float("size")))
        val density = values.float("density").coerceIn(0f, 100f) / 100f
        val byDarkness = values.choice("sizing") == 1
        val custom = values.choice("dotColor") == 1
        val customColor = values.color("color")
        val customA = (customColor ushr 24) / 255f
        val fillBg = values.bool("background")
        val bg = values.color("bgColor")

        val lattice = lattice(w, h, pitch, values.float("angle"))
        val out = PixelBuffer(w, h)
        val px = out.pixels
        CellMosaic.label(lattice, w, h, px, ctx)
        ctx.progress(0.3f)
        val colors = CellMosaic.averages(src, lattice, px, ctx)
        ctx.progress(0.5f)
        // +0.5 so that at 100% every pixel of a cell (even at its corners) is fully covered.
        val fullRadius = (lattice.boundRadius + 0.5f) * density
        val lut = inkLut

        Parallel.forRows(h) { y0, y1 ->
            val c = FloatArray(2)
            for (y in y0 until y1) {
                if (((y - y0) and 15) == 0) ctx.checkCancelled()
                val py = y + 0.5f
                val row = y * w
                for (x in 0 until w) {
                    val pxf = x + 0.5f
                    val cell = lattice.cellAt(pxf, py, c)
                    val col = colors[cell]
                    val ca = col ushr 24
                    val radius: Float
                    val rgb: Int
                    val opacity: Float
                    if (byDarkness) {
                        val ink = ca / 255f * (1f - ColorUtils.luminance(col) / 255f)
                        radius = if (ink > 0f) halftoneRadius(ink, pitch, lut) * density else 0f
                        rgb = if (custom) customColor else col
                        opacity = if (custom) customA else 1f
                    } else {
                        radius = fullRadius
                        rgb = if (custom) customColor else col
                        opacity = if (custom) customA * ca / 255f else ca / 255f
                    }
                    var a = 0f
                    if (radius > 0f && opacity > 0f) {
                        val dx = pxf - c[0]; val dy = py - c[1]
                        var cov = radius + 0.5f - sqrt(dx * dx + dy * dy)
                        if (cov > 0f) {
                            if (cov > 1f) cov = 1f
                            // Sub-pixel dots fade out instead of leaving a half-covered pixel.
                            if (radius < 0.5f) cov *= 2f * radius
                            a = opacity * cov
                        }
                    }
                    px[row + x] = if (fillBg) {
                        blendOver(bg, rgb, a)
                    } else {
                        val ai = (a * 255f + 0.5f).toInt()
                        if (ai <= 0) 0 else ColorUtils.withAlpha(rgb, ai)
                    }
                }
            }
        }
        return out
    }

    /** Halftone dot radius / pitch per ink level, for this filter's cell shape. */
    private val inkLut: FloatArray by lazy { inkRadiusLut(if (hexagonal) 6 else 4) }

    companion object {
        fun hexagonal() = DotsFilter("pixelate.dots_hexagonal", "Dots (Hexagonal)", hexagonal = true)
        fun square() = DotsFilter("pixelate.dots_square", "Dots (Square)", hexagonal = false)

        /**
         * Radius (in units of the pitch) of a centered disc covering exactly `i / 256` of a regular
         * [sides]-gon cell whose inradius is half the pitch, for i in 0..256. Inverts the
         * disc/polygon intersection area (disc minus the circular segments beyond each side).
         */
        internal fun inkRadiusLut(sides: Int): FloatArray {
            val rho = 0.5
            val halfAngle = PI / sides
            val circumradius = rho / cos(halfAngle)
            val cellArea = sides * rho * rho * tan(halfAngle)
            fun covered(r: Double): Double =
                if (r <= rho) PI * r * r
                else PI * r * r - sides * (r * r * acos(rho / r) - rho * sqrt(r * r - rho * rho))
            return FloatArray(257) { i ->
                val target = cellArea * i / 256.0
                var lo = 0.0
                var hi = circumradius
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (covered(mid) < target) lo = mid else hi = mid
                }
                ((lo + hi) / 2).toFloat()
            }
        }

        /** Dot radius in pixels for [ink] 0..1; solid ink also covers the cell corners. */
        internal fun halftoneRadius(ink: Float, pitch: Float, lut: FloatArray): Float {
            val f = min(ink, 1f) * 256f
            val i = min(255, f.toInt())
            val r = (lut[i] + (lut[i + 1] - lut[i]) * (f - i)) * pitch
            return r + ((ink - 0.98f) / 0.02f).coerceIn(0f, 1f) * 0.5f
        }
    }
}
