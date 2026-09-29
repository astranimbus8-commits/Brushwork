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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Light effects shared by Bloom and Cross Filter: light extracted from the bright parts of the
 * image is spread out and added back. Light may spill onto transparent pixels: the output alpha
 * grows to fit the added light.
 */
internal object Glow {

    /** Soft-knee bright pass weight (0..1) of a straight color with luma [y] (0..1). */
    fun brightWeight(y: Float, threshold: Float, squared: Boolean): Float {
        val m = ((y - threshold) / max(1e-3f, 1f - threshold)).coerceIn(0f, 1f)
        return if (squared) m * m else m
    }

    /** Downsampling factor so a light image of `w/f × h/f` stays below [maxPixels]. */
    fun minFactor(w: Int, h: Int, maxPixels: Int): Int =
        max(1, ceil(sqrt(w.toDouble() * h / maxPixels)).toInt())

    /**
     * Bright pass of [src] averaged over f×f cells: premultiplied light planes (0..255 units) of
     * size ceil(w/f) × ceil(h/f).
     */
    fun brightPass(src: PixelBuffer, f: Int, threshold: Float, ctx: FilterContext): Array<FloatArray> {
        val w = src.width; val h = src.height
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        val planes = Array(3) { FloatArray(dw * dh) }
        val pr = planes[0]; val pg = planes[1]; val pb = planes[2]
        val p = src.pixels
        Parallel.forRows(dh) { j0, j1 ->
            ctx.checkCancelled()
            for (j in j0 until j1) {
                val y0 = j * f; val y1 = min(h, y0 + f)
                for (i in 0 until dw) {
                    val x0 = i * f; val x1 = min(w, x0 + f)
                    var r = 0f; var g = 0f; var b = 0f
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in x0 until x1) {
                            val c = p[row + x]
                            val a = c ushr 24
                            if (a == 0) continue
                            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                            val m = brightWeight(ArtMath.luma(cr, cg, cb) / 255f, threshold, squared = false)
                            if (m <= 0f) continue
                            val k = m * a / 255f
                            r += cr * k; g += cg * k; b += cb * k
                        }
                    }
                    val o = j * dw + i
                    val inv = 1f / ((x1 - x0) * (y1 - y0))
                    pr[o] = r * inv; pg[o] = g * inv; pb[o] = b * inv
                }
            }
        }
        return planes
    }

    /**
     * Adds the light planes (cell size [f], bilinearly upsampled) times [gain] to [src].
     * [screen] blends with Screen (never beyond white) instead of plain addition.
     */
    fun composite(src: PixelBuffer, light: Array<FloatArray>, f: Int, gain: Float, screen: Boolean, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        val lr = light[0]; val lg = light[1]; val lb = light[2]
        // Per-column bilinear taps (shared by all rows).
        val cx0 = IntArray(w); val cx1 = IntArray(w); val ctx0 = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (dw - 1).toFloat())
            val i0 = floor(gx).toInt()
            cx0[x] = i0; cx1[x] = min(dw - 1, i0 + 1); ctx0[x] = gx - i0
        }
        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val gy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (dh - 1).toFloat())
                val j0 = floor(gy).toInt(); val j1 = min(dh - 1, j0 + 1); val ty = gy - j0
                val o0 = j0 * dw; val o1 = j1 * dw
                val row = y * w
                for (x in 0 until w) {
                    val a0 = o0 + cx0[x]; val a1 = o0 + cx1[x]; val b0 = o1 + cx0[x]; val b1 = o1 + cx1[x]
                    val tx = ctx0[x]
                    val wa0 = (1f - tx) * (1f - ty); val wa1 = tx * (1f - ty); val wb0 = (1f - tx) * ty; val wb1 = tx * ty
                    val gr = (lr[a0] * wa0 + lr[a1] * wa1 + lr[b0] * wb0 + lr[b1] * wb1) * gain
                    val gg = (lg[a0] * wa0 + lg[a1] * wa1 + lg[b0] * wb0 + lg[b1] * wb1) * gain
                    val gb = (lb[a0] * wa0 + lb[a1] * wa1 + lb[b0] * wb0 + lb[b1] * wb1) * gain
                    val c = sp[row + x]
                    dp[row + x] = if (gr <= 0.02f && gg <= 0.02f && gb <= 0.02f) c else addLight(c, gr, gg, gb, screen)
                }
            }
        }
        return out
    }

    /** Adds premultiplied light (0..255 units) to a straight ARGB color. */
    fun addLight(c: Int, lr: Float, lg: Float, lb: Float, screen: Boolean): Int {
        val a = (c ushr 24).toFloat()
        val k = a / 255f
        val sr = ((c shr 16) and 0xFF) * k; val sg = ((c shr 8) and 0xFF) * k; val sb = (c and 0xFF) * k
        val oR: Float; val oG: Float; val oB: Float
        if (screen) {
            val r = min(lr, 255f); val g = min(lg, 255f); val b = min(lb, 255f)
            oR = sr + r - sr * r / 255f; oG = sg + g - sg * g / 255f; oB = sb + b - sb * b / 255f
        } else {
            oR = sr + lr; oG = sg + lg; oB = sb + lb
        }
        val outA = min(255f, max(a, max(oR, max(oG, oB))))
        if (outA < 0.5f) return 0
        val inv = 255f / outA
        return (ChannelShift.clamp(outA) shl 24) or (ChannelShift.clamp(oR * inv) shl 16) or
            (ChannelShift.clamp(oG * inv) shl 8) or ChannelShift.clamp(oB * inv)
    }
}

/**
 * Bloom: bright parts of the image bleed a soft halo of light into their surroundings. The bright
 * pass (linear knee above 1 − Area) is blurred at five scales from Radius/16 to Radius and added
 * back, either additively or, when Balanced, with Screen so the image does not blow out.
 */
class BloomFilter : Filter("art.bloom", "Bloom", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("area", "Area", 0f, 100f, 40f, step = 1f, suffix = "%"),
        FilterParam.Slider("radius", "Radius", 1f, 200f, 30f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("brightness", "Brightness", 0f, 200f, 100f, step = 1f, suffix = "%"),
        FilterParam.Toggle("balanced", "Balanced", true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val area = (values.float("area") / 100f).coerceIn(0f, 1f)
        val gain = (values.float("brightness") / 100f).coerceIn(0f, 2f)
        if (area <= 0f || gain <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val radius = max(0.5f, ctx.px(values.float("radius").coerceIn(1f, 200f)))
        val f = max(Glow.minFactor(w, h, 2_000_000), floor(radius / 8f).toInt())
        val light = Glow.brightPass(src, f, 1f - area, ctx)
        val dw = (w + f - 1) / f; val dh = (h + f - 1) / f
        // Five scales radius/16 .. radius, equally weighted, blurred incrementally in place. The
        // weights sum to 1.75 so small light sources get a visible halo at 100 % brightness.
        val acc = Array(3) { FloatArray(dw * dh) }
        var prevSigma = 0f
        for (level in 0 until 5) {
            val sigma = radius / f * (1 shl level) / 16f
            val step = sqrt(max(0f, sigma * sigma - prevSigma * prevSigma))
            prevSigma = sigma
            for (c in 0 until 3) {
                ArtMath.gaussInPlace(light[c], dw, dh, step, ctx)
                val l = light[c]; val a = acc[c]
                for (i in a.indices) a[i] += l[i] * 0.35f
            }
        }
        return Glow.composite(src, acc, f, gain, values.bool("balanced"), ctx)
    }
}

/**
 * Cross Filter: a camera star filter. Bright points of the image (local maxima of the bright pass
 * above 1 − Area, at least about a quarter of the ray length apart) become glints that emit thin
 * tapered beams (Rays of them, rotated by Direction) fading out over Length; brighter points get
 * longer rays. Beams are rasterized with antialiasing per band of rows and added to the image.
 */
class CrossFilter : Filter("art.cross_filter", "Cross Filter", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("count", "Rays", 2f, 16f, 4f, step = 1f),
        FilterParam.Slider("direction", "Direction", 0f, 360f, 45f, step = 1f, suffix = "°"),
        FilterParam.Slider("length", "Length", 5f, 1000f, 120f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("thickness", "Thickness", 1f, 10f, 2f, step = 0.5f, suffix = "px", pixels = true),
        FilterParam.Slider("area", "Area", 0f, 100f, 20f, step = 1f, suffix = "%"),
        FilterParam.Slider("brightness", "Brightness", 0f, 200f, 100f, step = 1f, suffix = "%"),
        FilterParam.Toggle("chromatic", "Chromatic rays", false),
    )

    /** Detected light sources: centers (buffer px), premultiplied colors (0..255) and ray lengths. */
    internal class Glints(val n: Int, val x: FloatArray, val y: FloatArray, val r: FloatArray, val g: FloatArray, val b: FloatArray, val len: FloatArray)

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val area = (values.float("area") / 100f).coerceIn(0f, 1f)
        val gain = (values.float("brightness") / 100f).coerceIn(0f, 2f)
        if (area <= 0f || gain <= 0f) return src.copy()
        val w = src.width; val h = src.height
        val count = values.int("count").coerceIn(2, 16)
        val direction = values.float("direction")
        val length = max(1f, ctx.px(values.float("length").coerceIn(5f, 1000f)))
        val halfWidth = max(0.5f, ctx.px(values.float("thickness").coerceIn(1f, 10f)) / 2f)
        val glints = detect(src, 1f - area, length, ctx)
        if (glints.n == 0) return src.copy()

        val ux = FloatArray(count) { ArtMath.unitX(direction + it * 360f / count) }
        val uy = FloatArray(count) { ArtMath.unitY(direction + it * 360f / count) }
        val lengthScale = if (values.bool("chromatic")) floatArrayOf(1f, 0.8f, 0.6f) else floatArrayOf(1f, 1f, 1f)
        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        Parallel.forRows(h) { y0, y1 ->
            val bandRows = 64
            val n = w * min(bandRows, y1 - y0)
            val acc = Array(3) { FloatArray(n) }
            var by0 = y0
            while (by0 < y1) {
                ctx.checkCancelled()
                val by1 = min(y1, by0 + bandRows)
                for (c in 0 until 3) acc[c].fill(0f, 0, w * (by1 - by0))
                for (i in 0 until glints.n) {
                    val reach = glints.len[i] + halfWidth + 1f
                    if (glints.y[i] + reach < by0 || glints.y[i] - reach >= by1) continue
                    for (k in 0 until count) {
                        ray(acc, w, by0, by1, glints, i, ux[k], uy[k], halfWidth, lengthScale)
                    }
                }
                for (y in by0 until by1) {
                    val row = y * w; val arow = (y - by0) * w
                    for (x in 0 until w) {
                        val lr = acc[0][arow + x] * gain; val lg = acc[1][arow + x] * gain; val lb = acc[2][arow + x] * gain
                        val c = sp[row + x]
                        dp[row + x] = if (lr <= 0.02f && lg <= 0.02f && lb <= 0.02f) c else Glow.addLight(c, lr, lg, lb, false)
                    }
                }
                by0 = by1
            }
        }
        return out
    }

    /**
     * Finds light sources: the bright pass is max-pooled into small cells, jittered by a tiny
     * deterministic amount to break ties in flat bright areas, and every cell that is the maximum
     * of its neighborhood (radius about Length / 4) becomes a glint at its brightest pixel.
     * At most [MAX_GLINTS] of the strongest are kept.
     *
     * The jitter is smooth value noise in full-resolution coordinates, so the glints chosen in a
     * flat bright area sit at the same places in a downscaled preview and in the final result.
     */
    internal fun detect(src: PixelBuffer, threshold: Float, length: Float, ctx: FilterContext): Glints {
        val w = src.width; val h = src.height
        val p = src.pixels
        val cell = max(Glow.minFactor(w, h, 1_500_000), max(1, (length / 40f).roundToInt()))
        val cw = (w + cell - 1) / cell; val ch = (h + cell - 1) / cell
        val m = FloatArray(cw * ch)
        val arg = IntArray(cw * ch)
        val toFull = 1f / max(ctx.scale, 1e-4f)
        // Jitter features about half the glint spacing (Length / 8 at full resolution).
        val jitterScale = 1f / max(2f, length * toFull / 8f)
        Parallel.forRows(ch) { j0, j1 ->
            ctx.checkCancelled()
            for (j in j0 until j1) for (i in 0 until cw) {
                var best = 0f; var bestIdx = -1
                for (y in j * cell until min(h, j * cell + cell)) for (x in i * cell until min(w, i * cell + cell)) {
                    val c = p[y * w + x]
                    val a = c ushr 24
                    if (a == 0) continue
                    val v = Glow.brightWeight(ArtMath.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) / 255f, threshold, squared = true) * a / 255f
                    if (v > best) { best = v; bestIdx = y * w + x }
                }
                val o = j * cw + i
                m[o] = if (bestIdx < 0) 0f else {
                    val fx = (i + 0.5f) * cell * toFull * jitterScale; val fy = (j + 0.5f) * cell * toFull * jitterScale
                    best + FilterMath.valueNoise(fx, fy, 4099) * 1e-4f
                }
                arg[o] = bestIdx
            }
        }
        val radius = max(1, (length * 0.25f / cell).roundToInt())
        val mx = maxFilter(m, cw, ch, radius, ctx)
        var total = 0
        for (o in m.indices) if (m[o] > 0f && m[o] >= mx[o]) total++
        val cand = IntArray(total)
        var k = 0
        for (o in m.indices) if (m[o] > 0f && m[o] >= mx[o]) cand[k++] = o
        val order = cand.sortedByDescending { m[it] }.take(MAX_GLINTS)
        val n = order.size
        val gx = FloatArray(n); val gy = FloatArray(n); val gr = FloatArray(n); val gg = FloatArray(n); val gb = FloatArray(n); val gl = FloatArray(n)
        for ((idx, o) in order.withIndex()) {
            val pi = arg[o]
            val c = p[pi]
            val s = m[o] // brightness weight × alpha
            gx[idx] = pi % w + 0.5f; gy[idx] = pi / w + 0.5f
            gr[idx] = ((c shr 16) and 0xFF) * s; gg[idx] = ((c shr 8) and 0xFF) * s; gb[idx] = (c and 0xFF) * s
            gl[idx] = length * (0.45f + 0.55f * min(1f, s))
        }
        return Glints(n, gx, gy, gr, gg, gb, gl)
    }

    /** Sliding-window maximum over a (2r+1)² square (rows then columns), O(n) via monotonic deques. */
    private fun maxFilter(src: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            val line = FloatArray(w); val res = FloatArray(w); val dq = IntArray(w)
            for (y in y0 until y1) {
                System.arraycopy(src, y * w, line, 0, w)
                slidingMax(line, w, r, res, dq)
                System.arraycopy(res, 0, tmp, y * w, w)
            }
        }
        Parallel.forRange(w, 4) { x0, x1 ->
            ctx.checkCancelled()
            val line = FloatArray(h); val res = FloatArray(h); val dq = IntArray(h)
            for (x in x0 until x1) {
                for (y in 0 until h) line[y] = tmp[y * w + x]
                slidingMax(line, h, r, res, dq)
                for (y in 0 until h) out[y * w + x] = res[y]
            }
        }
        return out
    }

    private fun slidingMax(a: FloatArray, n: Int, r: Int, out: FloatArray, dq: IntArray) {
        var head = 0; var tail = 0
        for (i in 0 until n + r) {
            if (i < n) {
                while (tail > head && a[dq[tail - 1]] <= a[i]) tail--
                dq[tail++] = i
            }
            val j = i - r
            if (j >= 0) {
                while (dq[head] < j - r) head++
                out[j] = a[dq[head]]
            }
        }
    }

    /**
     * Rasterizes one tapered, fading ray of glint [i] along (ux, uy) into the band accumulators.
     * Intensity along the ray: exp(−2.5 t)(1 − t) with t = distance / length (per channel when
     * chromatic); width tapers from [hw0] to 30 % at the tip.
     */
    private fun ray(acc: Array<FloatArray>, w: Int, by0: Int, by1: Int, gl: Glints, i: Int, ux: Float, uy: Float, hw0: Float, ls: FloatArray) {
        val cx = gl.x[i]; val cy = gl.y[i]; val len = gl.len[i]
        val maxLen = len * max(ls[0], max(ls[1], ls[2]))
        val rp = hw0 + 0.5f
        // Rows the ray (with its width) can touch.
        val yA = max(by0, floor(min(cy, cy + uy * maxLen) - rp).toInt())
        val yB = min(by1 - 1, ceil(max(cy, cy + uy * maxLen) + rp).toInt())
        val ar = acc[0]; val ag = acc[1]; val ab = acc[2]
        val ir = gl.r[i]; val ig = gl.g[i]; val ib = gl.b[i]
        val invR = 1f / (len * ls[0]); val invG = 1f / (len * ls[1]); val invB = 1f / (len * ls[2])
        for (y in yA..yB) {
            val py = y + 0.5f - cy
            // x range: 0 - 0.5 <= along <= maxLen + 0.5 and |perp| <= rp.
            var lo = -1e9f; var hi = 1e9f
            if (abs(ux) > 1e-6f) {
                val p1 = (-0.5f - py * uy) / ux; val p2 = (maxLen + 0.5f - py * uy) / ux
                lo = max(lo, min(p1, p2)); hi = min(hi, max(p1, p2))
            } else if (py * uy < -0.5f || py * uy > maxLen + 0.5f) continue
            if (abs(uy) > 1e-6f) {
                val p1 = (py * ux - rp) / uy; val p2 = (py * ux + rp) / uy
                lo = max(lo, min(p1, p2)); hi = min(hi, max(p1, p2))
            } else if (abs(py * ux) > rp) continue
            if (lo > hi) continue
            val x0 = max(0, ceil(cx + lo - 0.5f).toInt()); val x1 = min(w - 1, floor(cx + hi - 0.5f).toInt())
            val arow = (y - by0) * w
            for (x in x0..x1) {
                val px = x + 0.5f - cx
                val along = px * ux + py * uy
                val perp = abs(-px * uy + py * ux)
                val t = (along / maxLen).coerceIn(0f, 1f)
                val hw = hw0 * (1f - 0.7f * t)
                val cov = (hw + 0.5f - perp).coerceIn(0f, min(1f, 2f * hw)) * (along + 0.5f).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val a = max(0f, along)
                ar[arow + x] += ir * cov * falloff(a * invR)
                ag[arow + x] += ig * cov * falloff(a * invG)
                ab[arow + x] += ib * cov * falloff(a * invB)
            }
        }
    }

    private fun falloff(t: Float): Float = if (t >= 1f) 0f else FALLOFF[(t * FALLOFF_STEPS).toInt()]

    private companion object {
        const val MAX_GLINTS = 2000
        const val FALLOFF_STEPS = 1024
        val FALLOFF = FloatArray(FALLOFF_STEPS + 1) { val t = it.toFloat() / FALLOFF_STEPS; exp(-2.5f * t) * (1f - t) }
    }
}
