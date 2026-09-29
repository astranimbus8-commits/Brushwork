package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
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
 * Watercolor pooling at the edges (ibisPaint "Wet Edge"): pigment collects along the borders of
 * painted areas, so edges become darker and denser while the interior thins out. Darkening is a
 * self-multiply, which deepens saturated colors like real pigment and barely affects white.
 */
class WetEdgeFilter : Filter("style.wet_edge", "Wet Edge", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("width", "Width", 1f, 200f, 16f, 1f, pixels = true),
        FilterParam.Slider("strength", "Strength", 0f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("interior", "Interior fade", 0f, 100f, 30f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val width = max(0.5f, ctx.px(values.float("width").coerceAtLeast(0f)))
        val strength = StyleMath.percent(values.float("strength")).coerceIn(0f, 1f)
        val interior = StyleMath.percent(values.float("interior")).coerceIn(0f, 1f)
        return StyleMath.aroundContent(src, 3) { img, _, _ ->
            val w = img.width
            val sd = StyleMath.signedDistance(img, ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                if (c ushr 24 == 0) c else {
                    val d = max(0f, -sd[y * w + x])
                    val t = (1f - d / width).coerceIn(0f, 1f)
                    val e = t * t
                    val k = strength * e
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    val nr = r + (r * r / 255f * 0.9f - r) * k
                    val ng = g + (g * g / 255f * 0.9f - g) * k
                    val nb = b + (b * b / 255f * 0.9f - b) * k
                    val a = (c ushr 24) * (1f - interior * (1f - e))
                    StyleMath.pack(a / 255f, nr, ng, nb)
                }
            }
        }
    }
}

/**
 * Stained glass (ibisPaint "Stained Glass"): thick lead lines of the chosen color along every
 * boundary between color regions, around the shapes and over existing dark line art, like the
 * leading between glass panes. Works best on flat-colored illustrations.
 */
class StainedGlassFilter : Filter("style.stained_glass", "Stained Glass", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("width", "Line width", 1f, 200f, 8f, 1f, pixels = true),
        StyleMath.antialiasParam(),
        FilterParam.Color("color", "Line color", 0xFF2B2B2B.toInt()),
        FilterParam.Slider("threshold", "Color threshold", 1f, 100f, 12f, 1f, "%"),
        FilterParam.Toggle("dark_lines", "Trace dark lines", true),
        StyleMath.outputParam("Lines over layer", "Lines only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val half = max(0.5f, ctx.px(values.float("width").coerceAtLeast(0f))) * 0.5f
        val soft = StyleMath.softness(values.float("antialias"), ctx)
        val color = values.color("color")
        val tol = StyleMath.percent(values.float("threshold")).coerceIn(0.001f, 1f) * 255f
        val dark = values.bool("dark_lines")
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(half + soft)) { img, _, _ ->
            val w = img.width; val h = img.height
            val p = img.pixels
            val seeds = ByteArray(w * h)
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                for (y in y0 until y1) for (x in 0 until w) {
                    val i = y * w + x
                    val c = p[i]
                    val boundary =
                        (x > 0 && differs(c, p[i - 1], tol)) || (x < w - 1 && differs(c, p[i + 1], tol)) ||
                            (y > 0 && differs(c, p[i - w], tol)) || (y < h - 1 && differs(c, p[i + w], tol)) ||
                            (dark && c ushr 24 >= 128 && ColorUtils.luminance(c) < 64)
                    if (boundary) seeds[i] = 1
                }
            }
            val d = StyleMath.distanceToMask(seeds, w, h, ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                // Seeds sit on both sides of a boundary, so the boundary itself is half a pixel away.
                val cov = StyleMath.ramp(half - (d[y * w + x] + 0.5f), soft)
                if (only) StyleMath.solid(color, cov) else StyleMath.over(c, color, cov)
            }
        }
    }

    /** True when two pixels belong to different regions (alpha crosses 50 % or premultiplied colors differ). */
    private fun differs(c1: Int, c2: Int, tol: Float): Boolean {
        val a1 = c1 ushr 24; val a2 = c2 ushr 24
        if ((a1 >= 128) != (a2 >= 128)) return true
        if (a1 < 128) return false
        val k1 = a1 / 255f; val k2 = a2 / 255f
        return abs(((c1 shr 16) and 0xFF) * k1 - ((c2 shr 16) and 0xFF) * k2) > tol ||
            abs(((c1 shr 8) and 0xFF) * k1 - ((c2 shr 8) and 0xFF) * k2) > tol ||
            abs((c1 and 0xFF) * k1 - (c2 and 0xFF) * k2) > tol
    }
}

/**
 * Stained glass made of irregular glass cells (Voronoi pieces on a jittered grid): each piece takes
 * the average color of the artwork around its centre, is shaded like slightly curved glass, and is
 * outlined with lead lines. The shapes' own alpha is kept; lead can also run around the shapes.
 */
class StainedGlassCellsFilter : Filter("style.stained_glass_cells", "Stained Glass (Cells)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("cell_size", "Cell size", 4f, 500f, 48f, 1f, pixels = true),
        FilterParam.Slider("width", "Line width", 1f, 100f, 5f, 1f, pixels = true),
        FilterParam.Color("color", "Line color", 0xFF2B2B2B.toInt()),
        FilterParam.Slider("irregularity", "Irregularity", 0f, 100f, 80f, 1f, "%"),
        FilterParam.Slider("shading", "Glass shading", 0f, 100f, 35f, 1f, "%"),
        FilterParam.Toggle("outline", "Lead around shapes", true),
        FilterParam.Seed(),
        StyleMath.outputParam("Glass", "Lead lines only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val cell = max(2f, ctx.px(values.float("cell_size").coerceAtLeast(0f)))
        val half = max(0.5f, ctx.px(values.float("width").coerceAtLeast(0f))) * 0.5f
        val color = values.color("color")
        val jitter = StyleMath.percent(values.float("irregularity")).coerceIn(0f, 1f)
        val shading = StyleMath.percent(values.float("shading")).coerceIn(0f, 1f)
        val outline = values.bool("outline")
        val seed = values.seed()
        val only = values.choice("output") == 1
        return StyleMath.aroundContent(src, StyleMath.margin(half + 1f)) { img, offX, offY ->
            val w = img.width; val h = img.height
            val sd = if (outline) StyleMath.signedDistance(img, ctx) else null
            // Grid cells overlapping the crop, plus a two-cell ring for neighbour searches.
            val gx0 = floor(offX / cell).toInt() - 2
            val gy0 = floor(offY / cell).toInt() - 2
            val gw = floor((offX + w) / cell).toInt() + 3 - gx0
            val gh = floor((offY + h) / cell).toInt() + 3 - gy0
            val siteX = FloatArray(gw * gh); val siteY = FloatArray(gw * gh)
            for (j in 0 until gh) for (i in 0 until gw) {
                val ci = gx0 + i; val cj = gy0 + j
                // Sites in crop-local coordinates (pixel centres at integers).
                siteX[j * gw + i] = (ci + 0.5f + jitter * (FilterMath.hash01(ci, cj, seed) - 0.5f)) * cell - offX - 0.5f
                siteY[j * gw + i] = (cj + 0.5f + jitter * (FilterMath.hash01(ci, cj, seed + 911) - 0.5f)) * cell - offY - 0.5f
            }
            val cellColor = IntArray(gw * gh)
            val sampleR = cell * 0.3f
            Parallel.forRange(gw * gh, 16) { k0, k1 ->
                ctx.checkCancelled()
                for (k in k0 until k1) cellColor[k] = averageAround(img, siteX[k], siteY[k], sampleR)
            }
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val cx = floor((x + offX + 0.5f) / cell).toInt() - gx0
                val cy = floor((y + offY + 0.5f) / cell).toInt() - gy0
                // Nearest site among the 3x3 neighbourhood.
                var best = -1; var bestD = Float.MAX_VALUE
                for (j in max(0, cy - 1)..min(gh - 1, cy + 1)) for (i in max(0, cx - 1)..min(gw - 1, cx + 1)) {
                    val k = j * gw + i
                    val dx = siteX[k] - x; val dy = siteY[k] - y
                    val dd = dx * dx + dy * dy
                    if (dd < bestD) { bestD = dd; best = k }
                }
                val mx = siteX[best]; val my = siteY[best]
                val bi = best % gw; val bj = best / gw
                // Exact distance to the nearest Voronoi edge (5x5 around the owning cell).
                var edge = Float.MAX_VALUE
                for (j in max(0, bj - 2)..min(gh - 1, bj + 2)) for (i in max(0, bi - 2)..min(gw - 1, bi + 2)) {
                    val k = j * gw + i
                    if (k == best) continue
                    val ex = siteX[k] - mx; val ey = siteY[k] - my
                    val el = sqrt(ex * ex + ey * ey)
                    if (el < 1e-5f) continue
                    val dist = ((x - (mx + siteX[k]) * 0.5f) * ex + (y - (my + siteY[k]) * 0.5f) * ey) / -el
                    if (dist < edge) edge = dist
                }
                val s = sd?.get(y * w + x)
                val a = c ushr 24
                // With the outline, lead follows the silhouette (straddling it) as well as the cell
                // borders inside; without it, lead stays on the painted pixels.
                val lineDist = when {
                    s != null -> if (s > 0f) s else min(edge, -s)
                    a == 0 -> Float.MAX_VALUE
                    else -> edge
                }
                val cov = StyleMath.coverageWithin(lineDist, half, 1f)
                if (only) {
                    StyleMath.solid(color, cov)
                } else {
                    val glass = if (a == 0) 0 else {
                        val cc = if (cellColor[best] ushr 24 == 0) c else cellColor[best]
                        val t = (edge / (cell * 0.5f)).coerceIn(0f, 1f)
                        val vary = (FilterMath.hash01(bi + gx0, bj + gy0, seed + 77) - 0.5f) * 0.3f
                        val f = 1f + shading * (0.18f * t - 0.45f * (1f - t) * (1f - t) + vary)
                        StyleMath.pack(
                            a / 255f,
                            ((cc shr 16) and 0xFF) * f,
                            ((cc shr 8) and 0xFF) * f,
                            (cc and 0xFF) * f,
                        )
                    }
                    StyleMath.over(glass, color, cov)
                }
            }
        }
    }

    /** Premultiplied average of a 5x5 sample grid within [r] px of (sx, sy); transparent outside. */
    private fun averageAround(img: PixelBuffer, sx: Float, sy: Float, r: Float): Int {
        var sa = 0f; var sr = 0f; var sg = 0f; var sb = 0f
        for (j in -2..2) for (i in -2..2) {
            val x = (sx + i * r * 0.5f + 0.5f).toInt()
            val y = (sy + j * r * 0.5f + 0.5f).toInt()
            if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue
            val c = img.pixels[y * img.width + x]
            val a = (c ushr 24).toFloat()
            sa += a; sr += ((c shr 16) and 0xFF) * a; sg += ((c shr 8) and 0xFF) * a; sb += (c and 0xFF) * a
        }
        if (sa <= 0f) return 0
        // Alpha at least 1 so "has content" survives rounding.
        val a = max(1, ColorUtils.clamp255(sa / 25f))
        return ColorUtils.argbUnchecked(a, ColorUtils.clamp255(sr / sa), ColorUtils.clamp255(sg / sa), ColorUtils.clamp255(sb / sa))
    }
}
