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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pointillize: the image is rebuilt from randomly scattered, antialiased round dots, each filled
 * with the average color of the image under it (plus optional per-dot color variation), painted
 * in random order over a background.
 *
 * Density 50% places about enough dots to cover the canvas once (overlaps leave some gaps);
 * 100% covers it about twice. The dot layout depends only on the seed and the canvas proportions,
 * so the preview matches the full-resolution result.
 */
class PointillizeFilter : Filter("pixelate.pointillize", "Pointillize", FilterCategory.PIXELATE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Size", 2f, 100f, 12f, step = 1f, pixels = true),
        FilterParam.Slider("density", "Density", 0f, 100f, 50f, step = 1f, suffix = "%"),
        FilterParam.Slider("variation", "Color variation", 0f, 100f, 10f, step = 1f, suffix = "%"),
        FilterParam.Choice("background", "Background", listOf("Color", "Transparent", "Original image"), 0),
        FilterParam.Color("bgColor", "Background color", 0xFFFFFFFF.toInt()),
        FilterParam.Seed(),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val out = when (values.choice("background")) {
            1 -> PixelBuffer(w, h)
            2 -> src.copy()
            else -> PixelBuffer.filled(w, h, values.color("bgColor"))
        }
        val radius = max(0.5f, ctx.px(values.float("size")) / 2f)
        val density = values.float("density").coerceIn(0f, 100f) / 100f
        val jitter = values.float("variation").coerceIn(0f, 100f) / 100f * MAX_JITTER
        val seed = values.seed()

        // Dot centers are spread over the canvas grown by the largest dot radius so the edges
        // get the same coverage as the middle.
        val margin = radius * MAX_SIZE_FACTOR
        val domW = w + 2f * margin
        val domH = h + 2f * margin
        val wanted = density * 2.0 * domW * domH / (PI * radius * radius)
        val count = min(wanted, 4.0 * w * h + 64.0).toInt()
        if (count <= 0) return out

        // Horizontal bands in parallel; each band replays the dots in order and paints only its
        // own rows, so the result is identical to one sequential pass. The dots go in sequential
        // batches so progress can be reported from this thread in between.
        val bands = min(h, Parallel.threadCount)
        val batches = min(count, PROGRESS_STEPS)
        for (batch in 0 until batches) {
            val k0 = (batch.toLong() * count / batches).toInt()
            val k1 = ((batch + 1).toLong() * count / batches).toInt()
            Parallel.forRange(bands, 1) { b0, b1 ->
                for (b in b0 until b1) {
                    val y0 = (b.toLong() * h / bands).toInt()
                    val y1 = ((b + 1).toLong() * h / bands).toInt()
                    if (y1 > y0) paintBand(src, out, y0, y1, k0, k1, radius, margin, domW, domH, jitter, seed, ctx)
                }
            }
            ctx.progress((batch + 1f) / batches)
        }
        return out
    }

    /** Paints dots [k0] until [k1] into rows [y0] until [y1] of [out]. */
    private fun paintBand(
        src: PixelBuffer, out: PixelBuffer, y0: Int, y1: Int, k0: Int, k1: Int, radius: Float, margin: Float,
        domW: Float, domH: Float, jitter: Float, seed: Int, ctx: FilterContext,
    ) {
        val w = src.width
        val dst = out.pixels
        for (k in k0 until k1) {
            if (((k - k0) and 0x3FFF) == 0) ctx.checkCancelled()
            val cy = PixelRandom.rand01(k, 1, seed) * domH - margin
            if (cy + margin + 1f < y0 || cy - margin - 1f > y1) continue
            val rk = radius * (MIN_SIZE_FACTOR + (MAX_SIZE_FACTOR - MIN_SIZE_FACTOR) * PixelRandom.rand01(k, 2, seed))
            val reach = rk + 0.5f
            val ya = max(y0, floor(cy - reach).toInt())
            val yb = min(y1 - 1, floor(cy + reach).toInt())
            if (ya > yb) continue
            val cx = PixelRandom.rand01(k, 0, seed) * domW - margin
            var color = discAverage(src, cx, cy, rk)
            val ca = color ushr 24
            if (ca == 0) continue
            if (jitter > 0f) {
                color = ColorUtils.argb(
                    ca,
                    ((color shr 16) and 0xFF) + ((PixelRandom.rand01(k, 3, seed) - 0.5f) * 2f * jitter).roundToInt(),
                    ((color shr 8) and 0xFF) + ((PixelRandom.rand01(k, 4, seed) - 0.5f) * 2f * jitter).roundToInt(),
                    (color and 0xFF) + ((PixelRandom.rand01(k, 5, seed) - 0.5f) * 2f * jitter).roundToInt(),
                )
            }
            val opacity = ca / 255f
            val reach2 = reach * reach
            for (y in ya..yb) {
                val dy = y + 0.5f - cy
                val span2 = reach2 - dy * dy
                if (span2 <= 0f) continue
                val span = sqrt(span2)
                val xa = max(0, floor(cx - span - 0.5f).toInt())
                val xb = min(w - 1, ceil(cx + span - 0.5f).toInt())
                val row = y * w
                for (x in xa..xb) {
                    val dx = x + 0.5f - cx
                    var cov = reach - sqrt(dx * dx + dy * dy)
                    if (cov <= 0f) continue
                    if (cov > 1f) cov = 1f
                    dst[row + x] = blendOver(dst[row + x], color, opacity * cov)
                }
            }
        }
    }

    /**
     * Alpha-weighted average of [src] inside the disc at ([cx], [cy]) of radius [r], from at most
     * ~11x11 samples; falls back to the nearest pixel when the disc barely touches the image.
     */
    private fun discAverage(src: PixelBuffer, cx: Float, cy: Float, r: Float): Int {
        val w = src.width; val h = src.height; val p = src.pixels
        val step = max(1, (r / 3f).toInt())
        val n = (r / step).toInt()
        val bx = floor(cx).toInt(); val by = floor(cy).toInt()
        val lim = (r + 0.5f) * (r + 0.5f)
        var cnt = 0; var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
        for (j in -n..n) {
            val y = by + j * step
            if (y < 0 || y >= h) continue
            val dy = y + 0.5f - cy
            for (i in -n..n) {
                val x = bx + i * step
                if (x < 0 || x >= w) continue
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy > lim) continue
                val c = p[y * w + x]
                val a = c ushr 24
                cnt++
                if (a != 0) {
                    sa += a
                    sr += ((c shr 16) and 0xFF) * a
                    sg += ((c shr 8) and 0xFF) * a
                    sb += (c and 0xFF) * a
                }
            }
        }
        if (cnt == 0) return src.getClamped(bx, by)
        return CellMosaic.averageColor(cnt, sa, sr, sg, sb)
    }

    private companion object {
        const val MIN_SIZE_FACTOR = 0.8f
        const val MAX_SIZE_FACTOR = 1.2f
        /** Dot batches, each followed by a progress report. */
        const val PROGRESS_STEPS = 16
        /** Largest per-channel color offset at 100% variation. */
        const val MAX_JITTER = 80f
    }
}
