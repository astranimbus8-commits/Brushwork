package com.brushwork.paint.tools.select.magnetic

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The magnetic lasso's edge strength (v1.8, design §3.4 c), over a proxy of the picture whose
 * long side is at most [MAX_SIDE] px: per proxy pixel the Sobel magnitude of the 3 × 3 Gaussian
 * blur of its Rec. 709 luminance (transparent pixels count over white paper), normalized with a
 * 256-bin histogram so the 99th percentile maps to 255. Immutable once built; [grad] holds one
 * byte per proxy pixel (4 MB at 2048 × 2048, the build's peak is twice that).
 */
class EdgeMap internal constructor(
    val width: Int,
    val height: Int,
    /** Proxy px per document px (at most 1). */
    val scale: Float,
    internal val grad: ByteArray,
) {
    init {
        require(grad.size == width * height) { "grad ${grad.size} for $width × $height" }
    }

    /** The edge strength 0..255 at proxy pixel ([x], [y]). */
    fun at(x: Int, y: Int): Int = grad[y * width + x].toInt() and 0xFF

    /**
     * The same map at half resolution, each pixel the strongest of its 2 × 2 (an edge one pixel
     * wide survives), for searches over [LiveWire.MAX_NODES]. Built once, on first use.
     */
    val half: EdgeMap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val hw = max(1, (width + 1) / 2)
        val hh = max(1, (height + 1) / 2)
        val out = ByteArray(hw * hh)
        for (y in 0 until hh) for (x in 0 until hw) {
            var m = 0
            for (dy in 0..1) for (dx in 0..1) {
                val sx = min(width - 1, 2 * x + dx)
                val sy = min(height - 1, 2 * y + dy)
                m = max(m, at(sx, sy))
            }
            out[y * hw + x] = m.toByte()
        }
        EdgeMap(hw, hh, scale * hw / width, out)
    }

    companion object {
        /** The proxy's longest side (px). */
        const val MAX_SIDE = 2048

        /** The largest Sobel magnitude of 8-bit values: √2 × 4 × 255. */
        internal const val MAX_MAGNITUDE = 1443

        /** Below this (of [MAX_MAGNITUDE]) a percentile is noise, never the 255 of the map. */
        internal const val MIN_REFERENCE = 8

        /** The proxy size of a [w] × [h] picture: scaled down so its long side is at most [MAX_SIDE]. */
        fun proxySize(w: Int, h: Int): Pair<Int, Int> {
            val long = max(w, h)
            if (long <= MAX_SIDE) return w to h
            val s = MAX_SIDE.toDouble() / long
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }

        /**
         * Builds the map of [bitmap] (the picture Refer names, [docW] document px wide), scaled
         * down to its proxy. Worker thread; null when [cancelled].
         */
        fun build(bitmap: Bitmap, docW: Int, cancelled: () -> Boolean = { false }): EdgeMap? {
            val (pw, ph) = proxySize(bitmap.width, bitmap.height)
            val src = if (pw == bitmap.width && ph == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, pw, ph, true)
            try {
                val lum = ByteArray(pw * ph)
                val band = max(1, min(ph, (1 shl 18) / pw))
                val buf = IntArray(pw * band)
                var y = 0
                while (y < ph) {
                    if (cancelled()) return null
                    val rows = min(band, ph - y)
                    src.getPixels(buf, 0, pw, 0, y, pw, rows)
                    val base = y * pw
                    for (i in 0 until pw * rows) lum[base + i] = luminance(buf[i]).toByte()
                    y += rows
                }
                return fromLuminance(lum, pw, ph, pw.toFloat() / max(1, docW), cancelled)
            } finally {
                if (src !== bitmap) src.recycle()
            }
        }

        /** Rec. 709 luminance 0..255 of an unpremultiplied ARGB pixel over white. */
        fun luminance(argb: Int): Int {
            val a = argb ushr 24
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val y = (54 * r + 183 * g + 19 * b + 128) shr 8
            return (a * y + (255 - a) * 255 + 127) / 255
        }

        /**
         * Pure: the map of the [w] × [h] luminance [lum] (whose array becomes the map's, so the
         * caller lets go of it). Null when [cancelled].
         */
        fun fromLuminance(lum: ByteArray, w: Int, h: Int, scale: Float, cancelled: () -> Boolean = { false }): EdgeMap? {
            require(lum.size == w * h)
            val blur = gaussian(lum, w, h)
            if (cancelled()) return null
            // Pass 1: the magnitudes' 256-bin histogram (bin = magnitude × 256 / (MAX + 1)).
            val hist = IntArray(256)
            for (y in 0 until h) {
                if (y and 63 == 0 && cancelled()) return null
                for (x in 0 until w) hist[magnitude(blur, w, h, x, y) * 256 / (MAX_MAGNITUDE + 1)]++
            }
            val reference = reference(hist, w * h)
            // Pass 2: normalized into the luminance array (no longer needed).
            for (y in 0 until h) {
                if (y and 63 == 0 && cancelled()) return null
                val row = y * w
                for (x in 0 until w) {
                    val m = magnitude(blur, w, h, x, y)
                    lum[row + x] = min(255, (m * 255 + reference / 2) / reference).toByte()
                }
            }
            return EdgeMap(w, h, scale, lum)
        }

        /**
         * The magnitude that maps to 255: the histogram's 99th percentile (its bin's lower end).
         * A picture whose edges cover under 1 % of it (line art on white) has a flat 99th
         * percentile: then the 99th percentile of the pixels above noise is used. At least
         * [MIN_REFERENCE].
         */
        internal fun reference(hist: IntArray, total: Int): Int {
            fun percentile(from: Int, count: Int): Int {
                if (count <= 0) return 0
                val target = (count * 0.99).toLong()
                var seen = 0L
                for (b in from until 256) {
                    seen += hist[b]
                    if (seen > target) return binBottom(b)
                }
                return binBottom(255)
            }
            val all = percentile(0, total)
            val noise = MIN_REFERENCE * 256 / (MAX_MAGNITUDE + 1) + 1
            val above = (noise until 256).sumOf { hist[it] }
            val r = if (all >= MIN_REFERENCE) all else max(all, percentile(noise, above))
            return max(MIN_REFERENCE, r)
        }

        /** The smallest magnitude in bin [b]: every magnitude of the percentile's bin maps to 255. */
        private fun binBottom(b: Int) = (b * (MAX_MAGNITUDE + 1) + 255) / 256

        /** The 3 × 3 Gaussian ([1 2 1] ⊗ [1 2 1] / 16), edges clamped. */
        internal fun gaussian(src: ByteArray, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                val up = max(0, y - 1) * w
                val mid = y * w
                val down = min(h - 1, y + 1) * w
                for (x in 0 until w) {
                    row[x] = (src[up + x].toInt() and 0xFF) + 2 * (src[mid + x].toInt() and 0xFF) + (src[down + x].toInt() and 0xFF)
                }
                for (x in 0 until w) {
                    val l = row[max(0, x - 1)]
                    val r = row[min(w - 1, x + 1)]
                    out[mid + x] = ((l + 2 * row[x] + r + 8) shr 4).toByte()
                }
            }
            return out
        }

        /** The Sobel magnitude 0..[MAX_MAGNITUDE] of [p] at ([x], [y]), edges clamped. */
        internal fun magnitude(p: ByteArray, w: Int, h: Int, x: Int, y: Int): Int {
            val x0 = max(0, x - 1); val x2 = min(w - 1, x + 1)
            val r0 = max(0, y - 1) * w; val r1 = y * w; val r2 = min(h - 1, y + 1) * w
            fun v(i: Int) = p[i].toInt() and 0xFF
            val a = v(r0 + x0); val b = v(r0 + x); val c = v(r0 + x2)
            val d = v(r1 + x0); val f = v(r1 + x2)
            val g = v(r2 + x0); val hh = v(r2 + x); val i = v(r2 + x2)
            val gx = (c + 2 * f + i) - (a + 2 * d + g)
            val gy = (g + 2 * hh + i) - (a + 2 * b + c)
            return min(MAX_MAGNITUDE, sqrt((gx * gx + gy * gy).toFloat()).toInt())
        }
    }
}
