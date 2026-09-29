package com.brushwork.paint.filters

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared building blocks for filters. All functions are pure and thread-safe.
 * Filter authors: prefer these over re-implementing blur/alpha helpers.
 */
object FilterMath {

    /** Maps every pixel through [f] in parallel. [f] receives and returns non-premultiplied ARGB. */
    inline fun mapPixels(src: PixelBuffer, ctx: FilterContext, crossinline f: (Int) -> Int): PixelBuffer {
        val out = PixelBuffer(src.width, src.height)
        val s = src.pixels; val d = out.pixels; val w = src.width
        Parallel.forRows(src.height) { y0, y1 ->
            ctx.checkCancelled()
            for (i in y0 * w until y1 * w) d[i] = f(s[i])
        }
        return out
    }

    /** Maps every pixel with its coordinates. */
    inline fun mapXY(src: PixelBuffer, ctx: FilterContext, crossinline f: (x: Int, y: Int, c: Int) -> Int): PixelBuffer {
        val out = PixelBuffer(src.width, src.height)
        val s = src.pixels; val d = out.pixels; val w = src.width
        Parallel.forRows(src.height) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) { val row = y * w; for (x in 0 until w) d[row + x] = f(x, y, s[row + x]) }
        }
        return out
    }

    /** Builds a new image by evaluating [f] at each (x, y). */
    inline fun generate(width: Int, height: Int, ctx: FilterContext, crossinline f: (x: Int, y: Int) -> Int): PixelBuffer {
        val out = PixelBuffer(width, height)
        val d = out.pixels
        Parallel.forRows(height) { y0, y1 ->
            ctx.checkCancelled()
            for (y in y0 until y1) { val row = y * width; for (x in 0 until width) d[row + x] = f(x, y) }
        }
        return out
    }

    /** Builds a 256-entry lookup table from [f] and applies it per channel (R,G,B), alpha kept. */
    fun applyLut(src: PixelBuffer, ctx: FilterContext, lutR: IntArray, lutG: IntArray = lutR, lutB: IntArray = lutR): PixelBuffer =
        mapPixels(src, ctx) { c ->
            (c and 0xFF000000.toInt()) or (lutR[(c shr 16) and 0xFF] shl 16) or (lutG[(c shr 8) and 0xFF] shl 8) or lutB[c and 0xFF]
        }

    // ---------------------------------------------------------------- premultiplied float planes

    /** Splits into premultiplied float planes [a, r, g, b] (0..255) for correct blurring. */
    fun toPremulPlanes(src: PixelBuffer): Array<FloatArray> {
        val n = src.size
        val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        val p = src.pixels
        for (i in 0 until n) {
            val c = p[i]
            val al = (c ushr 24).toFloat()
            val k = al / 255f
            a[i] = al
            r[i] = ((c shr 16) and 0xFF) * k
            g[i] = ((c shr 8) and 0xFF) * k
            b[i] = (c and 0xFF) * k
        }
        return arrayOf(a, r, g, b)
    }

    fun fromPremulPlanes(planes: Array<FloatArray>, width: Int, height: Int): PixelBuffer {
        val out = PixelBuffer(width, height)
        val a = planes[0]; val r = planes[1]; val g = planes[2]; val b = planes[3]
        val d = out.pixels
        for (i in d.indices) {
            val al = a[i]
            if (al <= 0.5f) { d[i] = 0; continue }
            val inv = 255f / al
            d[i] = ColorUtils.argb(al.roundToInt(), (r[i] * inv).roundToInt(), (g[i] * inv).roundToInt(), (b[i] * inv).roundToInt())
        }
        return out
    }

    /** Separable gaussian blur of a single float plane (in place semantics: returns new array). */
    fun gaussianBlurPlane(src: FloatArray, width: Int, height: Int, sigma: Float, ctx: FilterContext? = null): FloatArray {
        if (sigma < 0.3f) return src.copyOf()
        val kernel = gaussianKernel(sigma)
        val radius = kernel.size / 2
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        Parallel.forRows(height) { y0, y1 ->
            ctx?.checkCancelled()
            for (y in y0 until y1) {
                val row = y * width
                for (x in 0 until width) {
                    var acc = 0f
                    for (k in -radius..radius) {
                        val xx = min(width - 1, max(0, x + k))
                        acc += src[row + xx] * kernel[k + radius]
                    }
                    tmp[row + x] = acc
                }
            }
        }
        Parallel.forRange(width, 4) { x0, x1 ->
            ctx?.checkCancelled()
            for (x in x0 until x1) {
                for (y in 0 until height) {
                    var acc = 0f
                    for (k in -radius..radius) {
                        val yy = min(height - 1, max(0, y + k))
                        acc += tmp[yy * width + x] * kernel[k + radius]
                    }
                    out[y * width + x] = acc
                }
            }
        }
        return out
    }

    fun gaussianKernel(sigma: Float): FloatArray {
        val radius = max(1, (sigma * 3f).toInt())
        val k = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            k[i + radius] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    /**
     * Fast gaussian-like blur (3 box passes) of a full ARGB image, alpha-correct.
     * [radius] is in pixels of THIS buffer (already scaled). Large radii are fine (O(n)).
     */
    fun blur(src: PixelBuffer, radius: Float, ctx: FilterContext? = null): PixelBuffer {
        if (radius < 0.5f) return src.copy()
        val planes = toPremulPlanes(src)
        val w = src.width; val h = src.height
        val boxes = boxesForGauss(radius / 2f + 0.01f, 3)
        val out = Array(4) { i ->
            var p = planes[i]
            for (bx in boxes) p = boxBlurPlane(p, w, h, (bx - 1) / 2, ctx)
            p
        }
        return fromPremulPlanes(out, w, h)
    }

    /** Box blur of a plane with integer radius r, horizontal + vertical, O(n). */
    fun boxBlurPlane(src: FloatArray, w: Int, h: Int, r: Int, ctx: FilterContext? = null): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        Parallel.forRows(h) { y0, y1 ->
            ctx?.checkCancelled()
            for (y in y0 until y1) {
                val row = y * w
                var acc = 0f
                for (k in -r..r) acc += src[row + min(w - 1, max(0, k))]
                for (x in 0 until w) {
                    tmp[row + x] = acc * norm
                    val addX = min(w - 1, x + r + 1); val subX = max(0, x - r)
                    acc += src[row + addX] - src[row + subX]
                }
            }
        }
        Parallel.forRange(w, 4) { x0, x1 ->
            ctx?.checkCancelled()
            for (x in x0 until x1) {
                var acc = 0f
                for (k in -r..r) acc += tmp[min(h - 1, max(0, k)) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = acc * norm
                    val addY = min(h - 1, y + r + 1); val subY = max(0, y - r)
                    acc += tmp[addY * w + x] - tmp[subY * w + x]
                }
            }
        }
        return out
    }

    /** Box sizes approximating a gaussian of [sigma] with [n] passes. */
    fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt((12f * sigma * sigma / n) + 1f)
        var wl = wIdeal.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) max(1, wl) else max(1, wu) }
    }

    // ---------------------------------------------------------------- alpha channel helpers

    /** Alpha channel as floats 0..1. */
    fun alphaPlane(src: PixelBuffer): FloatArray = FloatArray(src.size) { (src.pixels[it] ushr 24) / 255f }

    /**
     * Euclidean-ish dilation of a 0..1 plane by [radius] pixels (max filter over a disc).
     * Implemented as a distance transform for large radii. Returns new array.
     */
    fun dilate(plane: FloatArray, w: Int, h: Int, radius: Float, ctx: FilterContext? = null): FloatArray {
        if (radius <= 0f) return plane.copyOf()
        // Binary distance transform on thresholded plane, then soft edge.
        val dist = distanceToCoverage(plane, w, h, 0.5f, ctx)
        val out = FloatArray(plane.size)
        for (i in out.indices) {
            val d = dist[i]
            out[i] = max(plane[i], (radius + 0.5f - d).coerceIn(0f, 1f))
        }
        return out
    }

    /** Erosion = dilation of the inverse. */
    fun erode(plane: FloatArray, w: Int, h: Int, radius: Float, ctx: FilterContext? = null): FloatArray {
        val inv = FloatArray(plane.size) { 1f - plane[it] }
        val d = dilate(inv, w, h, radius, ctx)
        return FloatArray(plane.size) { 1f - d[it] }
    }

    /**
     * Exact Euclidean distance (pixels) from each pixel to the nearest pixel whose value is
     * >= [threshold]. Felzenszwalb & Huttenlocher separable squared-distance transform.
     */
    fun distanceToCoverage(plane: FloatArray, w: Int, h: Int, threshold: Float = 0.5f, ctx: FilterContext? = null): FloatArray {
        val inf = 1e20f
        val f = FloatArray(w * h) { if (plane[it] >= threshold) 0f else inf }
        // columns
        Parallel.forRange(w, 4) { x0, x1 ->
            ctx?.checkCancelled()
            val col = FloatArray(h); val d = FloatArray(h); val v = IntArray(h); val z = FloatArray(h + 1)
            for (x in x0 until x1) {
                for (y in 0 until h) col[y] = f[y * w + x]
                edt1d(col, h, d, v, z)
                for (y in 0 until h) f[y * w + x] = d[y]
            }
        }
        // rows
        Parallel.forRows(h) { y0, y1 ->
            ctx?.checkCancelled()
            val row = FloatArray(w); val d = FloatArray(w); val v = IntArray(w); val z = FloatArray(w + 1)
            for (y in y0 until y1) {
                val off = y * w
                for (x in 0 until w) row[x] = f[off + x]
                edt1d(row, w, d, v, z)
                for (x in 0 until w) f[off + x] = sqrt(d[x])
            }
        }
        return f
    }

    private fun edt1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -Float.MAX_VALUE
        z[1] = Float.MAX_VALUE
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val vk = v[k]
                s = ((f[q] + q * q) - (f[vk] + vk * vk)) / (2f * q - 2f * vk)
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) { // k == 0 case where s <= z[0] is impossible, kept for safety
                v[0] = q; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE; k = 0; continue
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Float.MAX_VALUE
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = q - v[k]
            d[q] = dq.toFloat() * dq + f[v[k]]
        }
    }

    // ---------------------------------------------------------------- misc

    /** Deterministic hash-based random in [0,1) for (x, y, seed). */
    fun hash01(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / 2147483648f
    }

    /** Smooth value noise in [0,1] at continuous coordinates (for clouds, glitch, etc.). */
    fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val xi = kotlin.math.floor(x).toInt(); val yi = kotlin.math.floor(y).toInt()
        val tx = x - xi; val ty = y - yi
        val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
        val a = hash01(xi, yi, seed); val b = hash01(xi + 1, yi, seed)
        val c = hash01(xi, yi + 1, seed); val d = hash01(xi + 1, yi + 1, seed)
        val top = a + (b - a) * sx; val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    /** Fractal Brownian motion of [valueNoise] with [octaves], result roughly in [0,1]. */
    fun fbm(x: Float, y: Float, octaves: Int, seed: Int, persistence: Float = 0.5f): Float {
        var amp = 1f; var freq = 1f; var sum = 0f; var norm = 0f
        for (o in 0 until octaves) {
            sum += valueNoise(x * freq, y * freq, seed + o * 1013) * amp
            norm += amp
            amp *= persistence
            freq *= 2f
        }
        return sum / norm
    }

    /** Blends [result] back over [src] by [amount] 0..1 (per channel incl. alpha). */
    fun mix(src: PixelBuffer, result: PixelBuffer, amount: Float): PixelBuffer {
        if (amount >= 1f) return result
        val out = PixelBuffer(src.width, src.height)
        for (i in out.pixels.indices) out.pixels[i] = ColorUtils.lerp(src.pixels[i], result.pixels[i], amount)
        return out
    }
}
