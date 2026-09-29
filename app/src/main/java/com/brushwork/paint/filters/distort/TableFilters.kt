package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ibisPaint's "Table" filters: draw a grid/table of rectangular cells (schedules, 4-koma panels
 * with Space > 0, windows, graph paper). Lines are anti-aliased rectangle outlines of the given
 * thickness centered on each cell edge; with Space = 0 neighbouring cells share their lines.
 */
internal object TableRenderer {
    /** Cell layout inside the table area. */
    class Layout(val x0: Float, val y0: Float, val cols: Int, val rows: Int, val cw: Float, val ch: Float, val space: Float)

    fun render(src: PixelBuffer, layout: Layout?, thickness: Float, color: Int, ctx: FilterContext): PixelBuffer {
        // Nothing fits: unchanged copy (via mapPixels so cancellation is still honored).
        if (layout == null || layout.cols <= 0 || layout.rows <= 0 || layout.cw <= 0f || layout.ch <= 0f) return FilterMath.mapPixels(src, ctx) { it }
        val half = max(0.25f, thickness / 2f)
        val colorAlpha = (color ushr 24) / 255f
        val pitchX = layout.cw + layout.space
        val pitchY = layout.ch + layout.space
        val hx = layout.cw / 2f
        val hy = layout.ch / 2f
        return FilterMath.mapXY(src, ctx) { x, y, c ->
            val px = x + 0.5f
            val py = y + 0.5f
            val ci = floor((px - layout.x0) / pitchX).toInt()
            val cj = floor((py - layout.y0) / pitchY).toInt()
            var cover = 0f
            for (j in cj - 1..cj + 1) {
                if (j < 0 || j >= layout.rows) continue
                val cy = layout.y0 + j * pitchY + hy
                for (i in ci - 1..ci + 1) {
                    if (i < 0 || i >= layout.cols) continue
                    val cx = layout.x0 + i * pitchX + hx
                    // Signed distance to the cell rectangle's outline.
                    val dx = abs(px - cx) - hx
                    val dy = abs(py - cy) - hy
                    val outside = sqrt(max(dx, 0f) * max(dx, 0f) + max(dy, 0f) * max(dy, 0f))
                    val inside = min(max(dx, dy), 0f)
                    val d = abs(outside + inside)
                    val a = (half - d + 0.5f).coerceIn(0f, 1f)
                    if (a > cover) cover = a
                }
            }
            if (cover <= 0f) c else ColorUtils.over(ColorUtils.withAlpha(color, (cover * colorAlpha * 255f + 0.5f).toInt()), c)
        }
    }

    /** Params shared by both filters. */
    fun commonParams(): List<FilterParam> = listOf(
        FilterParam.Slider("thickness", "Line thickness", 0.5f, 100f, 4f, pixels = true),
        FilterParam.Slider("margin", "Margin", 0f, 2000f, 40f, step = 1f, pixels = true),
        FilterParam.Slider("space", "Space between cells", 0f, 500f, 0f, step = 1f, pixels = true),
        FilterParam.Color("color", "Line color", 0xFF000000.toInt(), useDrawingColor = true),
    )
}

/** Table (Count): a table with a fixed number of columns and rows filling the area inside the margin. */
class TableCountFilter : Filter("frame.table_count", "Table (Count)", FilterCategory.FRAME) {
    override val generatesContent = true
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("cols", "Columns", 1f, 100f, 2f, step = 1f),
        FilterParam.Slider("rows", "Rows", 1f, 100f, 4f, step = 1f),
    ) + TableRenderer.commonParams()

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val cols = values.int("cols").coerceIn(1, 100)
        val rows = values.int("rows").coerceIn(1, 100)
        val margin = ctx.px(values.float("margin").coerceAtLeast(0f))
        val space = ctx.px(values.float("space").coerceAtLeast(0f))
        val iw = src.width - 2f * margin
        val ih = src.height - 2f * margin
        val cw = (iw - (cols - 1) * space) / cols
        val ch = (ih - (rows - 1) * space) / rows
        val layout = if (cw > 0f && ch > 0f) TableRenderer.Layout(margin, margin, cols, rows, cw, ch, space) else null
        return TableRenderer.render(src, layout, ctx.px(values.float("thickness")), values.color("color"), ctx)
    }
}

/** Table (Size): as many cells of a fixed size as fit inside the margin, centered. */
class TableSizeFilter : Filter("frame.table_size", "Table (Size)", FilterCategory.FRAME) {
    override val generatesContent = true
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("cellW", "Cell width", 2f, 4000f, 100f, step = 1f, pixels = true),
        FilterParam.Slider("cellH", "Cell height", 2f, 4000f, 100f, step = 1f, pixels = true),
        FilterParam.Choice("align", "Alignment", listOf("Center", "Top left"), 0),
    ) + TableRenderer.commonParams()

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val margin = ctx.px(values.float("margin").coerceAtLeast(0f))
        val space = ctx.px(values.float("space").coerceAtLeast(0f))
        val cw = ctx.px(values.float("cellW").coerceAtLeast(1f))
        val ch = ctx.px(values.float("cellH").coerceAtLeast(1f))
        val iw = src.width - 2f * margin
        val ih = src.height - 2f * margin
        val cols = if (iw <= 0f) 0 else floor((iw + space) / (cw + space)).toInt().coerceAtMost(10_000)
        val rows = if (ih <= 0f) 0 else floor((ih + space) / (ch + space)).toInt().coerceAtMost(10_000)
        val layout = if (cols > 0 && rows > 0) {
            val usedW = cols * cw + (cols - 1) * space
            val usedH = rows * ch + (rows - 1) * space
            val centered = values.choice("align") == 0
            val x0 = margin + if (centered) (iw - usedW) / 2f else 0f
            val y0 = margin + if (centered) (ih - usedH) / 2f else 0f
            TableRenderer.Layout(x0, y0, cols, rows, cw, ch, space)
        } else null
        return TableRenderer.render(src, layout, ctx.px(values.float("thickness")), values.color("color"), ctx)
    }
}
