package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Repeats the whole image, scaled down, on a grid of [columns x rows] tiles that exactly covers
 * the canvas. 1 x 1 without gap is the unchanged image.
 */
class TileCountFilter : Filter("distort.tile_count", "Tile (Count)", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("columns", "Columns", 1f, 50f, 3f, 1f),
        FilterParam.Slider("rows", "Rows", 1f, 50f, 3f, 1f),
        FilterParam.Choice("fit", "Fit", TileRenderer.FIT_OPTIONS, TileRenderer.FIT_STRETCH),
        FilterParam.Choice("mirror", "Mirror", TileRenderer.MIRROR_OPTIONS),
        FilterParam.Slider("offset", "Row offset", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Slider("gap", "Gap", 0f, 500f, 0f, 1f, pixels = true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val cols = values.int("columns").coerceIn(1, 1000)
        val rows = values.int("rows").coerceIn(1, 1000)
        return TileRenderer.render(
            src, ctx,
            periodX = src.width.toFloat() / cols,
            periodY = src.height.toFloat() / rows,
            originX = 0f, originY = 0f,
            gap = ctx.px(values.float("gap")).coerceAtLeast(0f),
            fit = values.choice("fit"),
            mirror = values.choice("mirror"),
            rowOffset = values.float("offset").coerceIn(0f, 100f) / 100f,
        )
    }
}

/**
 * Repeats the whole image scaled to a fixed tile size, as many times as fit, with one tile
 * centered on a draggable point.
 */
class TileSizeFilter : Filter("distort.tile_size", "Tile (Size)", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("width", "Tile width", 4f, 4000f, 500f, 1f, pixels = true),
        FilterParam.Slider("height", "Tile height", 4f, 4000f, 500f, 1f, pixels = true),
        FilterParam.Choice("fit", "Fit", TileRenderer.FIT_OPTIONS, TileRenderer.FIT_CROP),
        FilterParam.Choice("mirror", "Mirror", TileRenderer.MIRROR_OPTIONS),
        FilterParam.Slider("offset", "Row offset", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Slider("gap", "Gap", 0f, 500f, 0f, 1f, pixels = true),
        FilterParam.Point("origin", "Tile center"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val tw = max(0.25f, ctx.px(values.float("width")))
        val th = max(0.25f, ctx.px(values.float("height")))
        val o = values.point("origin")
        return TileRenderer.render(
            src, ctx,
            periodX = tw, periodY = th,
            originX = o[0] * src.width - tw * 0.5f,
            originY = o[1] * src.height - th * 0.5f,
            gap = ctx.px(values.float("gap")).coerceAtLeast(0f),
            fit = values.choice("fit"),
            mirror = values.choice("mirror"),
            rowOffset = values.float("offset").coerceIn(0f, 100f) / 100f,
        )
    }
}

/** Shared tiling renderer (area-prefiltered so small tiles do not alias). */
internal object TileRenderer {
    const val FIT_STRETCH = 0
    const val FIT_CROP = 1
    const val FIT_CONTAIN = 2
    val FIT_OPTIONS = listOf("Stretch", "Crop to fill", "Fit inside")
    val MIRROR_OPTIONS = listOf("None", "Horizontal", "Vertical", "Both")

    /**
     * Tiles [src] on a grid with cell size [periodX] x [periodY] whose cell (0, 0) starts at
     * ([originX], [originY]). Each cell shows the image in a box inset by half the [gap] on every
     * side; odd columns/rows are flipped per [mirror]; odd rows shift right by [rowOffset] cells.
     */
    fun render(
        src: PixelBuffer,
        ctx: FilterContext,
        periodX: Float,
        periodY: Float,
        originX: Float,
        originY: Float,
        gap: Float,
        fit: Int,
        mirror: Int,
        rowOffset: Float,
    ): PixelBuffer {
        val w = src.width; val h = src.height
        val cw = periodX - gap
        val ch = periodY - gap
        if (cw < 0.05f || ch < 0.05f) return PixelBuffer(w, h)

        // Content box (u, v) -> source (ox + u * kx, oy + v * ky); visible part [u0, u1) x [v0, v1).
        val kx: Float; val ky: Float
        var ox = 0f; var oy = 0f
        var u0 = 0f; var u1 = cw; var v0 = 0f; var v1 = ch
        when (fit) {
            FIT_CROP -> {
                val s = max(cw / w, ch / h)
                kx = 1f / s; ky = kx
                ox = (w - cw * kx) * 0.5f
                oy = (h - ch * ky) * 0.5f
            }
            FIT_CONTAIN -> {
                val s = min(cw / w, ch / h)
                kx = 1f / s; ky = kx
                u0 = (cw - w * s) * 0.5f; u1 = u0 + w * s
                v0 = (ch - h * s) * 0.5f; v1 = v0 + h * s
                ox = -u0 * kx
                oy = -v0 * ky
            }
            else -> {
                kx = w / cw
                ky = h / ch
            }
        }
        // Minification: sample a box-filtered copy at (about) the tile resolution instead.
        val small = if (kx > 1f || ky > 1f) {
            DistortMath.downscale(src, ceil(w / max(1f, kx)).toInt(), ceil(h / max(1f, ky)).toInt(), ctx)
        } else {
            src
        }
        val sfx = small.width.toFloat() / w
        val sfy = small.height.toFloat() / h
        val mirrorX = mirror == 1 || mirror == 3
        val mirrorY = mirror == 2 || mirror == 3
        val softEdges = gap > 0f || fit == FIT_CONTAIN
        val halfGap = gap * 0.5f
        val shift = rowOffset * periodX
        val invPx = 1f / periodX; val invPy = 1f / periodY

        return FilterMath.generate(w, h, ctx) { x, y ->
            var gx = x + 0.5f - originX
            val gy = y + 0.5f - originY
            val row = floor(gy * invPy).toInt()
            val oddRow = (row and 1) != 0
            if (oddRow) gx -= shift
            val col = floor(gx * invPx).toInt()
            var lx = gx - col * periodX - halfGap
            var ly = gy - row * periodY - halfGap
            var cov = 1f
            if (softEdges) {
                cov = (min(lx - u0, u1 - lx) + 0.5f).coerceIn(0f, 1f) * (min(ly - v0, v1 - ly) + 0.5f).coerceIn(0f, 1f)
            }
            if (cov <= 0f) {
                0
            } else {
                if (mirrorX && (col and 1) != 0) lx = cw - lx
                if (mirrorY && oddRow) ly = ch - ly
                val c = small.sampleBilinear((ox + lx * kx) * sfx, (oy + ly * ky) * sfy)
                if (cov >= 1f) c else {
                    val a = ((c ushr 24) * cov + 0.5f).toInt()
                    if (a <= 0) 0 else (c and 0x00FFFFFF) or (a shl 24)
                }
            }
        }
    }
}
