package com.brushwork.paint.engine

import com.brushwork.paint.core.Parallel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Row-oriented pixel reader so big images can be processed in strips without full-size arrays.
 * Pixels are NON-premultiplied ARGB, rows are packed with stride [width].
 */
interface RowSource {
    val width: Int
    val height: Int
    fun read(y: Int, rows: Int, out: IntArray)
}

/** Row-oriented pixel writer (NON-premultiplied ARGB, stride = image width). */
fun interface RowSink {
    fun write(y: Int, rows: Int, pixels: IntArray)
}

/** In-memory [RowSource]/[RowSink] over a packed array (tests and small images). */
class IntArrayImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) : RowSource, RowSink {
    override fun read(y: Int, rows: Int, out: IntArray) = System.arraycopy(pixels, y * width, out, 0, rows * width)
    override fun write(y: Int, rows: Int, pixels: IntArray) = System.arraycopy(pixels, 0, this.pixels, y * width, rows * width)
}

/** Reconstruction kernels for [Resampler]. [support] is the radius in source pixels at scale 1. */
enum class ResampleKernel(val support: Double) {
    /** Linear interpolation ("bilinear"); a tent/area filter when shrinking. */
    TRIANGLE(1.0) {
        override fun weight(x: Double): Double {
            val ax = abs(x)
            return if (ax < 1.0) 1.0 - ax else 0.0
        }
    },

    /** Catmull-Rom cubic (a = -0.5): sharp, high-quality interpolation. */
    CATMULL_ROM(2.0) {
        override fun weight(x: Double): Double {
            val ax = abs(x)
            return when {
                ax < 1.0 -> (1.5 * ax - 2.5) * ax * ax + 1.0
                ax < 2.0 -> ((-0.5 * ax + 2.5) * ax - 4.0) * ax + 2.0
                else -> 0.0
            }
        }
    };

    abstract fun weight(x: Double): Double
}

/**
 * Separable, antialiased image resampling (the approach used by Pillow): when shrinking, the
 * kernel is widened by the scale factor so every source pixel contributes (no aliasing); when
 * enlarging it interpolates. Colors are filtered in premultiplied space so transparent pixels
 * don't leak dark fringes. Work is done in horizontal strips with bounded buffers and runs on
 * [Parallel] worker threads.
 */
object Resampler {

    /** Filter taps for one axis: output i reads [count] inputs from [start], weights at i*stride. */
    class Taps(val start: IntArray, val count: IntArray, val weights: FloatArray, val stride: Int)

    fun taps(inSize: Int, outSize: Int, kernel: ResampleKernel): Taps {
        require(inSize > 0 && outSize > 0)
        val scale = inSize.toDouble() / outSize
        val filterScale = max(1.0, scale)
        val support = kernel.support * filterScale
        val stride = ceil(support).toInt() * 2 + 2
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val weights = FloatArray(outSize * stride)
        val tmp = DoubleArray(stride)
        for (o in 0 until outSize) {
            val center = (o + 0.5) * scale
            val lo = max(0, (center - support + 0.5).toInt())
            val hi = min(inSize, (center + support + 0.5).toInt())
            var n = min(stride, hi - lo)
            var sum = 0.0
            for (k in 0 until n) {
                val w = kernel.weight((k + lo - center + 0.5) / filterScale)
                tmp[k] = w
                sum += w
            }
            if (n <= 0 || abs(sum) < 1e-12) {
                // Degenerate (can't happen for sane sizes): take the nearest input pixel.
                start[o] = center.toInt().coerceIn(0, inSize - 1)
                count[o] = 1
                weights[o * stride] = 1f
                continue
            }
            // Drop zero-weight taps at both ends (keeps loops tight).
            var first = 0
            while (first < n - 1 && tmp[first] == 0.0) first++
            while (n - 1 > first && tmp[n - 1] == 0.0) n--
            start[o] = lo + first
            count[o] = n - first
            for (k in first until n) weights[o * stride + k - first] = (tmp[k] / sum).toFloat()
        }
        return Taps(start, count, weights, stride)
    }

    /** Output-strip height that keeps the working buffers around [budgetElems] 4-byte elements. */
    internal fun stripHeight(srcW: Int, srcH: Int, dstW: Int, dstH: Int, tapRows: Int, budgetElems: Int = 4_000_000): Int {
        val perSrcRow = 5L * srcW + 4L * dstW
        val maxRows = max(tapRows.toLong() + 1, budgetElems / perSrcRow)
        val scaleY = srcH.toDouble() / dstH
        val strip = ((maxRows - tapRows) / scaleY).toInt()
        // The output strip itself (dstW ints per row) stays within ~1M ints.
        return strip.coerceIn(1, min(dstH, max(1, min(512, 1_000_000 / dstW))))
    }

    /**
     * Resamples [src] to [dstW] x [dstH] with [kernel], writing rows to [dst]. [onProgress]
     * (0..1) is called on the calling thread after every strip; it may throw to abort.
     */
    fun resample(src: RowSource, dstW: Int, dstH: Int, kernel: ResampleKernel, dst: RowSink, onProgress: (Float) -> Unit = {}) {
        val srcW = src.width
        val srcH = src.height
        require(dstW > 0 && dstH > 0)
        val tx = taps(srcW, dstW, kernel)
        val ty = taps(srcH, dstH, kernel)
        val strip = stripHeight(srcW, srcH, dstW, dstH, ty.stride)

        // Largest number of source rows any strip needs.
        var maxRows = 1
        run {
            var y0 = 0
            while (y0 < dstH) {
                val y1 = min(dstH, y0 + strip)
                maxRows = max(maxRows, rowsNeeded(ty, y0, y1).let { it.second - it.first })
                y0 = y1
            }
        }
        val srcInts = IntArray(srcW * maxRows)
        val pre = FloatArray(srcW * maxRows * 4)
        val tmp = FloatArray(dstW * maxRows * 4)
        val out = IntArray(dstW * strip)

        var y0 = 0
        while (y0 < dstH) {
            val y1 = min(dstH, y0 + strip)
            val (sy0, sy1) = rowsNeeded(ty, y0, y1)
            val nRows = sy1 - sy0
            src.read(sy0, nRows, srcInts)
            Parallel.forRange(nRows, 1) { r0, r1 ->
                premultiply(srcInts, pre, r0 * srcW, r1 * srcW)
                for (r in r0 until r1) horizontal(pre, r * srcW, tmp, r * dstW, dstW, tx)
            }
            Parallel.forRange(y1 - y0, 1) { o0, o1 ->
                for (o in o0 until o1) vertical(tmp, sy0, dstW, ty, y0 + o, out, o * dstW)
            }
            dst.write(y0, y1 - y0, out)
            y0 = y1
            onProgress(y0 / dstH.toFloat())
        }
    }

    /**
     * Nearest-neighbour (pixel art): each output pixel copies the source pixel under its center.
     * [onProgress] works as in [resample].
     */
    fun nearest(src: RowSource, dstW: Int, dstH: Int, dst: RowSink, onProgress: (Float) -> Unit = {}) {
        val srcW = src.width
        val srcH = src.height
        require(dstW > 0 && dstH > 0)
        val mapX = IntArray(dstW) { x -> ((x + 0.5) * srcW / dstW).toInt().coerceIn(0, srcW - 1) }
        val strip = max(1, min(dstH, 1_000_000 / dstW))
        val out = IntArray(dstW * strip)
        val row = IntArray(srcW)
        var loaded = -1
        var y0 = 0
        while (y0 < dstH) {
            val y1 = min(dstH, y0 + strip)
            for (y in y0 until y1) {
                val sy = ((y + 0.5) * srcH / dstH).toInt().coerceIn(0, srcH - 1)
                if (sy != loaded) { src.read(sy, 1, row); loaded = sy }
                val off = (y - y0) * dstW
                for (x in 0 until dstW) out[off + x] = row[mapX[x]]
            }
            dst.write(y0, y1 - y0, out)
            y0 = y1
            onProgress(y0 / dstH.toFloat())
        }
    }

    private fun rowsNeeded(ty: Taps, y0: Int, y1: Int): Pair<Int, Int> {
        var lo = Int.MAX_VALUE
        var hi = 0
        for (y in y0 until y1) {
            lo = min(lo, ty.start[y])
            hi = max(hi, ty.start[y] + ty.count[y])
        }
        return lo to hi
    }

    /** NON-premultiplied ints -> premultiplied floats (a, r, g, b in 0..255). */
    private fun premultiply(src: IntArray, dst: FloatArray, from: Int, to: Int) {
        for (i in from until to) {
            val c = src[i]
            val a = c ushr 24
            val o = i * 4
            if (a == 0) {
                dst[o] = 0f; dst[o + 1] = 0f; dst[o + 2] = 0f; dst[o + 3] = 0f
            } else {
                val f = a / 255f
                dst[o] = a.toFloat()
                dst[o + 1] = ((c shr 16) and 0xFF) * f
                dst[o + 2] = ((c shr 8) and 0xFF) * f
                dst[o + 3] = (c and 0xFF) * f
            }
        }
    }

    private fun horizontal(pre: FloatArray, srcOff: Int, tmp: FloatArray, dstOff: Int, dstW: Int, tx: Taps) {
        val w = tx.weights
        val stride = tx.stride
        for (x in 0 until dstW) {
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            var s = (srcOff + tx.start[x]) * 4
            val wb = x * stride
            for (k in 0 until tx.count[x]) {
                val wk = w[wb + k]
                a += pre[s] * wk; r += pre[s + 1] * wk; g += pre[s + 2] * wk; b += pre[s + 3] * wk
                s += 4
            }
            val o = (dstOff + x) * 4
            tmp[o] = a; tmp[o + 1] = r; tmp[o + 2] = g; tmp[o + 3] = b
        }
    }

    private fun vertical(tmp: FloatArray, sy0: Int, dstW: Int, ty: Taps, y: Int, out: IntArray, outOff: Int) {
        val first = ty.start[y] - sy0
        val n = ty.count[y]
        val wb = y * ty.stride
        val w = ty.weights
        for (x in 0 until dstW) {
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            var s = (first * dstW + x) * 4
            val step = dstW * 4
            for (k in 0 until n) {
                val wk = w[wb + k]
                a += tmp[s] * wk; r += tmp[s + 1] * wk; g += tmp[s + 2] * wk; b += tmp[s + 3] * wk
                s += step
            }
            out[outOff + x] = unpremultiply(a, r, g, b)
        }
    }

    /** Premultiplied floats -> NON-premultiplied int, clamping cubic overshoot. */
    internal fun unpremultiply(a: Float, r: Float, g: Float, b: Float): Int {
        val ai = (a + 0.5f).toInt().coerceIn(0, 255)
        if (ai == 0) return 0
        val af = a.coerceIn(1f, 255f)
        val scale = 255f / af
        val ri = (r.coerceIn(0f, af) * scale + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g.coerceIn(0f, af) * scale + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b.coerceIn(0f, af) * scale + 0.5f).toInt().coerceIn(0, 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
