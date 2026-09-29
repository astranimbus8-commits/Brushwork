package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Speck shapes of the Sheer filter family. [symmetry] is the rotation period in degrees (0 = none). */
enum class SheerShape(val title: String, val idName: String, val symmetry: Float, val defaultSize: Float, val maxSize: Float) {
    CROSS("Cross", "cross", 90f, 8f, 50f),
    LINE("Line", "line", 180f, 16f, 100f),
    SQUARE("Square", "square", 90f, 6f, 50f),
    HEX("Hex", "hex", 60f, 8f, 50f),
    CIRCLE("Circle", "circle", 0f, 6f, 50f),
    ;

    val filled: Boolean get() = this == SQUARE || this == HEX || this == CIRCLE
}

/**
 * Sheer (ibisPaint "シアー"): shaped grain. Randomly placed specks of one [shape] (crosses, short
 * lines, squares, hexagons or dots) with random signed brightness are accumulated and added to the
 * colors, giving a rough, textured atmosphere. All specks share the Direction. Alpha is unchanged.
 *
 * Specks are generated per grid cell in full-resolution coordinates from the seed, so a
 * downscaled preview shows the same pattern as the final result, and bands of rows can be
 * rendered in parallel without shared state.
 */
class SheerFilter(val shape: SheerShape) : Filter("art.sheer_${shape.idName}", "Sheer (${shape.title})", FilterCategory.ART) {

    override val params: List<FilterParam> = buildList {
        add(FilterParam.Slider("size", "Size", 1f, shape.maxSize, shape.defaultSize, step = 1f, suffix = "px", pixels = true))
        if (shape.symmetry > 0f) {
            add(FilterParam.Slider("direction", "Direction", 0f, shape.symmetry, if (shape == SheerShape.LINE) 45f else 0f, step = 1f, suffix = "°"))
        }
        add(FilterParam.Slider("amount", "Amount", 0f, 100f, 50f, step = 1f, suffix = "%"))
        if (shape.filled) add(FilterParam.Toggle("outline", "Outline only", false))
        add(FilterParam.Seed())
    }

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amount = (values.float("amount") / 100f).coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val scale = max(ctx.scale, 1e-4f)
        val sizeFull = values.float("size").coerceIn(1f, shape.maxSize)
        val s = sizeFull * scale
        val dir = if (shape.symmetry > 0f) values.float("direction") else 0f
        val dcx = ArtMath.unitX(dir); val dcy = ArtMath.unitY(dir)
        val outline = shape.filled && values.bool("outline")
        val seed = values.seed()

        // Specks per full-resolution px² and the generation grid (aiming at ~32 specks per cell).
        val density = if (shape == SheerShape.LINE) amount / (2f * sizeFull) else amount * 2f / (sizeFull * sizeFull)
        val cellFull = sqrt(32f / density).coerceIn(8f, 1024f)
        val lambda = density * cellFull * cellFull
        val baseCount = floor(lambda).toInt()
        val extraChance = lambda - baseCount
        val cellBuf = cellFull * scale
        val cellsX = max(1, ceil(w / cellBuf).toInt())
        val cellsY = max(1, ceil(h / cellBuf).toInt())

        val stamp = Stamp(shape, s, scale, outline, dcx, dcy)
        val reach = stamp.reach
        val strength = amount * 127f

        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            val bandRows = 64
            val acc = FloatArray(w * min(bandRows, y1 - y0))
            var by0 = y0
            while (by0 < y1) {
                ctx.checkCancelled()
                val by1 = min(y1, by0 + bandRows)
                acc.fill(0f, 0, w * (by1 - by0))
                val cy0 = max(0, floor((by0 - reach) / cellBuf).toInt())
                val cy1 = min(cellsY - 1, floor((by1 + reach) / cellBuf).toInt())
                for (cy in cy0..cy1) for (cx in 0 until cellsX) {
                    val key = cx * 73856093 xor cy * 19349663
                    val count = baseCount + if (FilterMath.hash01(cx, cy, seed * 7 + 3) < extraChance) 1 else 0
                    for (k in 0 until count) {
                        val fy = (cy + FilterMath.hash01(k, key, seed * 7 + 5)) * cellBuf
                        if (fy + reach < by0 || fy - reach >= by1) continue
                        val fx = (cx + FilterMath.hash01(k, key, seed * 7 + 11)) * cellBuf
                        val v = FilterMath.hash01(k, key, seed * 7 + 13) * 2f - 1f
                        stamp.draw(acc, w, by0, by1, fx, fy, v)
                    }
                }
                for (y in by0 until by1) {
                    val row = y * w; val arow = (y - by0) * w
                    for (x in 0 until w) {
                        val c = sp[row + x]
                        val n = acc[arow + x] * strength
                        dp[row + x] = if (c ushr 24 == 0 || n == 0f) c else {
                            val r = ((c shr 16) and 0xFF) + n
                            val g = ((c shr 8) and 0xFF) + n
                            val b = (c and 0xFF) + n
                            (c and 0xFF000000.toInt()) or (ChannelShift.clamp(r) shl 16) or (ChannelShift.clamp(g) shl 8) or ChannelShift.clamp(b)
                        }
                    }
                }
                by0 = by1
            }
        }
        return out
    }

    /**
     * Rasterizes one antialiased speck into a band accumulator. All sizes are in buffer pixels.
     *
     * Small specks (the common case in previews and at small sizes, where most of the work is
     * evaluating the shape per pixel) are stamped from kernels precomputed at [PHASES]×[PHASES]
     * sub-pixel positions; larger ones are rasterized exactly.
     */
    internal class Stamp(
        val shape: SheerShape,
        val s: Float,
        scale: Float,
        val outline: Boolean,
        val dcx: Float,
        val dcy: Float,
        /** Always rasterize exactly (tests compare the precomputed kernels against this). */
        exact: Boolean = false,
    ) {
        private val half = s / 2f
        /** Half width of line/cross strokes. */
        private val hw = when (shape) {
            SheerShape.LINE -> max(0.5f, 0.05f * s / scale) * scale
            else -> max(0.5f, 0.06f * s / scale) * scale
        }
        /** Stroke thickness of outlined shapes. */
        private val thick = max(1f, 0.12f * s / scale) * scale
        /** Maximum per-pixel coverage, so sub-pixel specks in a preview keep roughly their full-size energy. */
        private val cap: Float
        private val lenCap = min(1f, 2f * half)
        private val widthCap = min(1f, 2f * hw)
        val reach: Float
        /** Kernel radius (pixels around the speck's pixel) and the phase kernels, or null for exact rasterizing. */
        private val kr: Int
        private val kernels: Array<FloatArray>?

        init {
            val area = when (shape) {
                SheerShape.CROSS -> 2f * s * hw
                SheerShape.LINE -> 1.33f * s * hw
                SheerShape.SQUARE -> if (outline) 4f * s * thick else s * s
                SheerShape.HEX -> if (outline) 3f * s * thick else 0.6495f * s * s
                SheerShape.CIRCLE -> if (outline) 3.1416f * s * thick else 0.7854f * s * s
            }
            val stroke = when {
                shape == SheerShape.LINE || shape == SheerShape.CROSS -> 2f * hw
                outline -> thick
                else -> 1f
            }
            cap = min(1f, min(area, stroke))
            reach = when (shape) {
                SheerShape.LINE, SheerShape.CROSS -> half + hw + 1f
                SheerShape.SQUARE -> half * 1.4143f + 1f
                else -> half + 1f
            }
            kr = ceil(reach).toInt() + 1
            kernels = if (exact || kr > MAX_KERNEL_RADIUS) null else {
                val side = 2 * kr + 1
                Array(PHASES * PHASES) { ph ->
                    val ox = (ph % PHASES + 0.5f) / PHASES; val oy = (ph / PHASES + 0.5f) / PHASES
                    FloatArray(side * side) { k -> coverageAt(k % side - kr + 0.5f - ox, k / side - kr + 0.5f - oy) }
                }
            }
        }

        fun draw(acc: FloatArray, w: Int, by0: Int, by1: Int, fx: Float, fy: Float, v: Float) {
            val k = kernels
            when {
                k != null -> stampKernel(k, acc, w, by0, by1, fx, fy, v)
                shape == SheerShape.LINE -> segments(acc, w, by0, by1, fx, fy, v, cross = false)
                shape == SheerShape.CROSS -> segments(acc, w, by0, by1, fx, fy, v, cross = true)
                else -> area(acc, w, by0, by1, fx, fy, v)
            }
        }

        /** Coverage (already capped) of the pixel whose center is at offset (dx, dy) from the speck center. */
        private fun coverageAt(dx: Float, dy: Float): Float = when (shape) {
            SheerShape.LINE -> max(0f, coverage(dx, dy, false))
            SheerShape.CROSS -> max(0f, coverage(dx, dy, true))
            else -> {
                val cov = areaCoverage(dx, dy)
                if (cov > 0f) min(cov, cap) else 0f
            }
        }

        private fun stampKernel(kernels: Array<FloatArray>, acc: FloatArray, w: Int, by0: Int, by1: Int, fx: Float, fy: Float, v: Float) {
            val ixf = floor(fx); val iyf = floor(fy)
            val ix = ixf.toInt(); val iy = iyf.toInt()
            val px = ((fx - ixf) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val py = ((fy - iyf) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val k = kernels[py * PHASES + px]
            val side = 2 * kr + 1
            val y0 = max(by0, iy - kr); val y1 = min(by1 - 1, iy + kr)
            val x0 = max(0, ix - kr); val x1 = min(w - 1, ix + kr)
            for (y in y0..y1) {
                val krow = (y - iy + kr) * side - (ix - kr)
                val arow = (y - by0) * w
                for (x in x0..x1) acc[arow + x] += v * k[krow + x]
            }
        }

        /** Uncapped coverage of a filled or outlined area shape; <= 0 outside. */
        private fun areaCoverage(dx: Float, dy: Float): Float {
            val d = when (shape) {
                SheerShape.SQUARE -> {
                    val u = abs(dx * dcx + dy * dcy); val q = abs(-dx * dcy + dy * dcx)
                    max(u, q) - half
                }
                SheerShape.HEX -> {
                    val u = abs(dx * dcx + dy * dcy); val q = abs(-dx * dcy + dy * dcx)
                    max(u * 0.8660254f + q * 0.5f, q) - half * 0.8660254f
                }
                else -> sqrt(dx * dx + dy * dy) - half
            }
            return if (outline) thick / 2f + 0.5f - abs(d + thick / 2f) else 0.5f - d
        }

        private fun area(acc: FloatArray, w: Int, by0: Int, by1: Int, fx: Float, fy: Float, v: Float) {
            val x0 = max(0, floor(fx - reach).toInt()); val x1 = min(w - 1, ceil(fx + reach).toInt())
            val y0 = max(by0, floor(fy - reach).toInt()); val y1 = min(by1 - 1, ceil(fy + reach).toInt())
            for (y in y0..y1) {
                val dy = y + 0.5f - fy
                val arow = (y - by0) * w
                for (x in x0..x1) {
                    val cov = areaCoverage(x + 0.5f - fx, dy)
                    if (cov > 0f) acc[arow + x] += v * min(cov, cap)
                }
            }
        }

        /** One segment along the direction, or two perpendicular ones for a cross. */
        private fun segments(acc: FloatArray, w: Int, by0: Int, by1: Int, fx: Float, fy: Float, v: Float, cross: Boolean) {
            val y0 = max(by0, floor(fy - reach).toInt()); val y1 = min(by1 - 1, ceil(fy + reach).toInt())
            val reachAlong = half + 0.5f
            val reachPerp = hw + 0.5f
            for (y in y0..y1) {
                val py = y + 0.5f - fy
                val arow = (y - by0) * w
                // x interval of the first arm (direction d) and, for crosses, the second (perpendicular).
                val a0 = spanStart(py, dcx, dcy, reachAlong, reachPerp, fx)
                val a1 = spanEnd(py, dcx, dcy, reachAlong, reachPerp, fx, w)
                val b0 = if (cross) spanStart(py, -dcy, dcx, reachAlong, reachPerp, fx) else 0
                val b1 = if (cross) spanEnd(py, -dcy, dcx, reachAlong, reachPerp, fx, w) else -1
                for (x in a0..a1) {
                    val c = coverage(x + 0.5f - fx, py, cross)
                    if (c > 0f) acc[arow + x] += v * c
                }
                for (x in b0..b1) {
                    if (x in a0..a1) continue
                    val c = coverage(x + 0.5f - fx, py, cross)
                    if (c > 0f) acc[arow + x] += v * c
                }
            }
        }

        private fun coverage(px: Float, py: Float, cross: Boolean): Float {
            val c1 = armCoverage(px * dcx + py * dcy, -px * dcy + py * dcx, cross)
            if (!cross) return c1
            return max(c1, armCoverage(-px * dcy + py * dcx, px * dcx + py * dcy, true))
        }

        private fun armCoverage(along: Float, perp: Float, cross: Boolean): Float {
            val a = abs(along)
            val cw = (hw + 0.5f - abs(perp)).coerceIn(0f, widthCap)
            if (cw <= 0f) return 0f
            val cl = (half + 0.5f - a).coerceIn(0f, lenCap)
            if (cl <= 0f) return 0f
            val t = if (half > 0f) min(1f, a / half) else 0f
            // Crosses fade linearly toward the tips; lines fade smoothly at both ends.
            val profile = if (cross) 1f - t else 1f - t * t
            return cw * cl * profile
        }

        /** First/last column whose center lies within the stroke's padded rectangle on row offset [py]. */
        private fun spanStart(py: Float, ux: Float, uy: Float, ra: Float, rp: Float, fx: Float): Int {
            val lo = span(py, ux, uy, ra, rp, true)
            return if (lo.isNaN()) 1 else max(0, ceil(fx + lo - 0.5f).toInt())
        }

        private fun spanEnd(py: Float, ux: Float, uy: Float, ra: Float, rp: Float, fx: Float, w: Int): Int {
            val hi = span(py, ux, uy, ra, rp, false)
            return if (hi.isNaN()) 0 else min(w - 1, floor(fx + hi - 0.5f).toInt())
        }

        /**
         * Bound of px such that |px*ux + py*uy| <= ra and |-px*uy + py*ux| <= rp, or NaN if the
         * row misses the stroke.
         */
        private fun span(py: Float, ux: Float, uy: Float, ra: Float, rp: Float, lower: Boolean): Float {
            var lo = -1e9f; var hi = 1e9f
            if (abs(ux) > 1e-6f) {
                val p = (-ra - py * uy) / ux; val q = (ra - py * uy) / ux
                lo = max(lo, min(p, q)); hi = min(hi, max(p, q))
            } else if (abs(py * uy) > ra) return Float.NaN
            if (abs(uy) > 1e-6f) {
                val p = (py * ux - rp) / uy; val q = (py * ux + rp) / uy
                lo = max(lo, min(p, q)); hi = min(hi, max(p, q))
            } else if (abs(py * ux) > rp) return Float.NaN
            if (lo > hi) return Float.NaN
            return if (lower) lo else hi
        }

        private companion object {
            /** Sub-pixel positions per axis of the precomputed small-speck kernels. */
            const val PHASES = 16
            /** Largest kernel radius (13×13 kernels) stamped from precomputed kernels. */
            const val MAX_KERNEL_RADIUS = 6
        }
    }
}
