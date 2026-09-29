package com.brushwork.paint.filters.art

import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Oil Paint: groups colors into flat, brush-stroke-like patches while keeping edges, simplifies
 * tones and adds an impasto relief.
 *
 *  1. A generalized Kuwahara filter with eight overlapping rectangular sectors (four quadrants and
 *     four half-width sides) of radius Brush size. Each output pixel is the mean color of the
 *     sectors weighted by their uniformity, ((1 + minVar) / (1 + var))^p; Smoothness raises p, so
 *     patches get flatter. Sector sums come from per-strip integral images: O(1) per pixel for any
 *     brush size.
 *  2. Posterize (luminance levels), Contrast and Detail (re-added fine detail of the source).
 *  3. Texture: an embossed height field made of the painted luminance plus bristle streaks (noise
 *     averaged along the local stroke direction from a low-resolution structure tensor), lit from
 *     the upper left.
 *
 * Alpha comes from the same weighted sectors, so edges of paint on a transparent layer become
 * painterly too.
 */
class OilPaintFilter : Filter("art.oil_paint", "Oil Paint", FilterCategory.ART) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("size", "Brush size", 1f, 30f, 6f, step = 1f, suffix = "px", pixels = true),
        FilterParam.Slider("smoothness", "Smoothness", 0f, 100f, 50f, step = 1f),
        FilterParam.Slider("posterize", "Posterize", 2f, 64f, 32f, step = 1f),
        FilterParam.Slider("contrast", "Contrast", -100f, 100f, 0f, step = 1f),
        FilterParam.Slider("detail", "Detail", 0f, 100f, 0f, step = 1f, suffix = "%"),
        FilterParam.Slider("texture", "Texture", 0f, 100f, 40f, step = 1f, suffix = "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val brush = values.float("size").coerceIn(1f, 30f)
        val radius = ctx.px(brush).roundToInt()
        val power = 1 + (values.float("smoothness").coerceIn(0f, 100f) / 100f * 7f).roundToInt()
        val out = if (radius >= 1) kuwahara(src, radius, power, ctx) else src.copy()
        tone(
            src, out,
            levels = values.int("posterize").coerceIn(2, 64),
            contrast = 1f + values.float("contrast").coerceIn(-100f, 100f) / 100f,
            detail = values.float("detail").coerceIn(0f, 100f) / 100f,
            ctx = ctx,
        )
        val texture = values.float("texture").coerceIn(0f, 100f) / 100f
        if (texture > 0f) impasto(out, brush, texture, ctx)
        return out
    }

    // ------------------------------------------------------------------ 1. Kuwahara

    private fun kuwahara(src: PixelBuffer, r: Int, power: Int, ctx: FilterContext): PixelBuffer {
        val w = src.width; val h = src.height
        val out = PixelBuffer(w, h)
        val sp = src.pixels; val dp = out.pixels
        val stripH = max(32, 2 * r)
        val strips = (h + stripH - 1) / stripH
        val perChunk = max(1, (strips + Parallel.threadCount - 1) / Parallel.threadCount)
        val half = r / 2
        // Sector rectangles as offsets (x0, y0, x1, y1), inclusive.
        val sec = intArrayOf(
            -r, -r, 0, 0, 0, -r, r, 0, -r, 0, 0, r, 0, 0, r, r,
            -half, -r, half, 0, -half, 0, half, r, -r, -half, 0, half, 0, -half, r, half,
        )
        Parallel.forRange(strips, perChunk) { s0, s1 ->
            val stride = w + 1
            val rows = min(h, stripH + 2 * r) + 1
            val iR = IntArray(rows * stride); val iG = IntArray(rows * stride); val iB = IntArray(rows * stride)
            val iA = IntArray(rows * stride); val iV = IntArray(rows * stride); val iV2 = LongArray(rows * stride)
            val corner = IntArray(32)
            val count = IntArray(8)
            val weight = FloatArray(8)
            for (s in s0 until s1) {
                ctx.checkCancelled()
                val y0 = s * stripH; val y1 = min(h, y0 + stripH)
                val ya = max(0, y0 - r); val yb = min(h, y1 + r)
                // Integral images of rows [ya, yb): local row j holds sums of rows < ya + j.
                for (x in 0 until stride) { iR[x] = 0; iG[x] = 0; iB[x] = 0; iA[x] = 0; iV[x] = 0; iV2[x] = 0 }
                for (yy in ya until yb) {
                    val o = (yy - ya + 1) * stride
                    iR[o] = 0; iG[o] = 0; iB[o] = 0; iA[o] = 0; iV[o] = 0; iV2[o] = 0
                    var sr = 0; var sg = 0; var sb = 0; var sa = 0; var sv = 0; var sv2 = 0L
                    val row = yy * w
                    for (x in 0 until w) {
                        val c = sp[row + x]
                        val a = c ushr 24
                        val pr = (((c shr 16) and 0xFF) * a + 127) / 255
                        val pg = (((c shr 8) and 0xFF) * a + 127) / 255
                        val pb = ((c and 0xFF) * a + 127) / 255
                        // Feature for the uniformity test: premultiplied luma and alpha, so an
                        // opaque black stroke differs from transparent background.
                        val v = ((pr * 299 + pg * 587 + pb * 114) / 1000 + a) shr 1
                        sr += pr; sg += pg; sb += pb; sa += a; sv += v; sv2 += (v * v).toLong()
                        val i = o + x + 1
                        iR[i] = iR[i - stride] + sr; iG[i] = iG[i - stride] + sg; iB[i] = iB[i - stride] + sb
                        iA[i] = iA[i - stride] + sa; iV[i] = iV[i - stride] + sv; iV2[i] = iV2[i - stride] + sv2
                    }
                }
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in 0 until w) {
                        var minVar = Float.MAX_VALUE
                        for (k in 0 until 8) {
                            val xa = max(0, x + sec[k * 4]); val xb = min(w - 1, x + sec[k * 4 + 2])
                            val yA = max(0, y + sec[k * 4 + 1]); val yB = min(h - 1, y + sec[k * 4 + 3])
                            if (xa > xb || yA > yB) { count[k] = 0; continue }
                            val top = (yA - ya) * stride; val bot = (yB - ya + 1) * stride
                            val c00 = top + xa; val c01 = top + xb + 1; val c10 = bot + xa; val c11 = bot + xb + 1
                            corner[k * 4] = c00; corner[k * 4 + 1] = c01; corner[k * 4 + 2] = c10; corner[k * 4 + 3] = c11
                            val n = (xb - xa + 1) * (yB - yA + 1)
                            count[k] = n
                            val sv = (iV[c11] - iV[c01] - iV[c10] + iV[c00]).toFloat()
                            val sv2 = (iV2[c11] - iV2[c01] - iV2[c10] + iV2[c00]).toFloat()
                            val mean = sv / n
                            val variance = max(0f, sv2 / n - mean * mean)
                            weight[k] = variance
                            if (variance < minVar) minVar = variance
                        }
                        var tw = 0f; var ar = 0f; var ag = 0f; var ab = 0f; var aa = 0f
                        for (k in 0 until 8) {
                            val n = count[k]
                            if (n == 0) continue
                            val base = (1f + minVar) / (1f + weight[k])
                            var wk = base
                            for (e in 1 until power) wk *= base
                            if (wk < 1e-3f) continue
                            val c00 = corner[k * 4]; val c01 = corner[k * 4 + 1]; val c10 = corner[k * 4 + 2]; val c11 = corner[k * 4 + 3]
                            val f = wk / n
                            ar += (iR[c11] - iR[c01] - iR[c10] + iR[c00]) * f
                            ag += (iG[c11] - iG[c01] - iG[c10] + iG[c00]) * f
                            ab += (iB[c11] - iB[c01] - iB[c10] + iB[c00]) * f
                            aa += (iA[c11] - iA[c01] - iA[c10] + iA[c00]) * f
                            tw += wk
                        }
                        dp[row + x] = if (tw <= 0f) sp[row + x] else {
                            val a = aa / tw
                            if (a < 0.5f) 0 else {
                                val inv = 255f / (a * tw)
                                (ChannelShift.clamp(a) shl 24) or (ChannelShift.clamp(ar * inv) shl 16) or
                                    (ChannelShift.clamp(ag * inv) shl 8) or ChannelShift.clamp(ab * inv)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 2. Tone

    private fun tone(src: PixelBuffer, out: PixelBuffer, levels: Int, contrast: Float, detail: Float, ctx: FilterContext) {
        val w = out.width; val h = out.height
        val sp = src.pixels; val dp = out.pixels
        val step = 255f / (levels - 1)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = dp[i]
                    if (c ushr 24 == 0) continue
                    var r = ((c shr 16) and 0xFF).toFloat(); var g = ((c shr 8) and 0xFF).toFloat(); var b = (c and 0xFF).toFloat()
                    if (detail > 0f) {
                        // High-pass of the source: pixel minus its alpha-weighted 3×3 mean.
                        var mr = 0f; var mg = 0f; var mb = 0f; var ma = 0f
                        for (dy in -1..1) {
                            val yy = min(h - 1, max(0, y + dy)) * w
                            for (dx in -1..1) {
                                val s = sp[yy + min(w - 1, max(0, x + dx))]
                                val a = (s ushr 24).toFloat()
                                mr += ((s shr 16) and 0xFF) * a; mg += ((s shr 8) and 0xFF) * a; mb += (s and 0xFF) * a; ma += a
                            }
                        }
                        val s = sp[i]
                        if (ma > 0f && s ushr 24 != 0) {
                            r += detail * (((s shr 16) and 0xFF) - mr / ma)
                            g += detail * (((s shr 8) and 0xFF) - mg / ma)
                            b += detail * ((s and 0xFF) - mb / ma)
                        }
                    }
                    if (levels < 64) {
                        val l = r * 0.299f + g * 0.587f + b * 0.114f
                        val shift = (l / step).roundToInt() * step - l
                        r += shift; g += shift; b += shift
                    }
                    if (contrast != 1f) {
                        r = (r - 128f) * contrast + 128f; g = (g - 128f) * contrast + 128f; b = (b - 128f) * contrast + 128f
                    }
                    dp[i] = (c and 0xFF000000.toInt()) or (ChannelShift.clamp(r) shl 16) or (ChannelShift.clamp(g) shl 8) or ChannelShift.clamp(b)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 3. Impasto texture

    private fun impasto(out: PixelBuffer, brush: Float, texture: Float, ctx: FilterContext) {
        val w = out.width; val h = out.height
        val dp = out.pixels
        val field = strokeField(out, brush, ctx)
        val g = field.cell; val fw = field.width
        // Bristles are about 1.5 px wide at full resolution; streaks run up to ~1.5 brush sizes.
        val bw = max(1f, ctx.px(1.5f))
        val taps = (brush * 0.75f * max(ctx.scale, 1e-4f) / bw).roundToInt().coerceIn(2, 7)
        // The mean of m uniform values has standard deviation 1 / sqrt(12 m); normalize to 1.
        val norm = 3.4641016f * kotlin.math.sqrt((2 * taps + 1).toFloat())
        val height = FloatArray(w * h)
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val cy = min(field.height - 1, y / g)
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = dp[i]
                    val lum = ArtMath.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) / 255f * ((c ushr 24) / 255f)
                    val fi = cy * fw + min(fw - 1, x / g)
                    val ux = field.dx[fi] * bw; val uy = field.dy[fi] * bw
                    var n = 0f
                    for (k in -taps..taps) {
                        val px = (x + k * ux) / bw; val py = (y + k * uy) / bw
                        n += FilterMath.hash01(floor(px).toInt(), floor(py).toInt(), 7919)
                    }
                    val bristle = (n / (2 * taps + 1) - 0.5f) * norm
                    height[i] = 0.4f * lum + 0.12f * bristle
                }
            }
        }
        val d = max(1, ctx.px(1.2f).roundToInt())
        val gain = texture * 220f
        Parallel.forRows(h) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) {
                val yu = max(0, y - d) * w; val yd = min(h - 1, y + d) * w
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = dp[i]
                    if (c ushr 24 == 0) continue
                    // Lit from the upper left: slopes rising toward the lower right catch the light.
                    val shade = (height[yd + min(w - 1, x + d)] - height[yu + max(0, x - d)]) * gain
                    val r = ((c shr 16) and 0xFF) + shade; val gg = ((c shr 8) and 0xFF) + shade; val b = (c and 0xFF) + shade
                    dp[i] = (c and 0xFF000000.toInt()) or (ChannelShift.clamp(r) shl 16) or (ChannelShift.clamp(gg) shl 8) or ChannelShift.clamp(b)
                }
            }
        }
    }

    /** Unit stroke directions (perpendicular to the smoothed luminance gradient) on a coarse grid. */
    private class StrokeField(val cell: Int, val width: Int, val height: Int, val dx: FloatArray, val dy: FloatArray)

    private fun strokeField(img: PixelBuffer, brush: Float, ctx: FilterContext): StrokeField {
        val w = img.width; val h = img.height
        val g = max(Glow.minFactor(w, h, 250_000), max(1, ctx.px(2f).roundToInt()))
        val fw = (w + g - 1) / g; val fh = (h + g - 1) / g
        val lum = FloatArray(fw * fh)
        val p = img.pixels
        for (j in 0 until fh) for (i in 0 until fw) {
            var s = 0f; var n = 0
            for (y in j * g until min(h, j * g + g)) for (x in i * g until min(w, i * g + g)) {
                val c = p[y * w + x]
                s += ArtMath.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) * (c ushr 24) / 255f
                n++
            }
            lum[j * fw + i] = s / max(1, n)
        }
        val jxx = FloatArray(fw * fh); val jxy = FloatArray(fw * fh); val jyy = FloatArray(fw * fh)
        for (j in 0 until fh) for (i in 0 until fw) {
            fun at(ii: Int, jj: Int) = lum[min(fh - 1, max(0, jj)) * fw + min(fw - 1, max(0, ii))]
            val gx = (at(i + 1, j - 1) + 2 * at(i + 1, j) + at(i + 1, j + 1)) - (at(i - 1, j - 1) + 2 * at(i - 1, j) + at(i - 1, j + 1))
            val gy = (at(i - 1, j + 1) + 2 * at(i, j + 1) + at(i + 1, j + 1)) - (at(i - 1, j - 1) + 2 * at(i, j - 1) + at(i + 1, j - 1))
            val o = j * fw + i
            jxx[o] = gx * gx; jxy[o] = gx * gy; jyy[o] = gy * gy
        }
        ctx.checkCancelled()
        val sigma = max(1f, ctx.px(brush) / g)
        ArtMath.gaussInPlace(jxx, fw, fh, sigma, ctx)
        ArtMath.gaussInPlace(jxy, fw, fh, sigma, ctx)
        ArtMath.gaussInPlace(jyy, fw, fh, sigma, ctx)
        val dx = FloatArray(fw * fh); val dy = FloatArray(fw * fh)
        val defaultAngle = Math.toRadians(60.0)
        for (o in dx.indices) {
            val strength = jxx[o] + jyy[o]
            // Gradient orientation from the tensor; strokes run perpendicular to it.
            val angle = if (strength < 1f) defaultAngle else 0.5 * atan2(2.0 * jxy[o], (jxx[o] - jyy[o]).toDouble()) + Math.PI / 2
            dx[o] = cos(angle).toFloat(); dy[o] = sin(angle).toFloat()
        }
        return StrokeField(g, fw, fh, dx, dy)
    }
}
