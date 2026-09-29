package com.brushwork.paint.filters

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Integer pixel rectangle (right/bottom exclusive). Pure Kotlin twin of android.graphics.Rect. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    companion object {
        fun full(width: Int, height: Int) = PixelRect(0, 0, width, height)
    }
}

/**
 * Pure pixel logic of [FilterSession] (no android imports, JVM-testable). The same functions run
 * for the downscaled preview and the full-resolution apply, so the preview matches the result.
 */
object FilterSessionMath {
    /** Long side of the preview source, in pixels. */
    const val PREVIEW_MAX_SIDE = 1280

    /** Preview dimensions for a [width] x [height] target: long side <= [maxSide], never upscaled. */
    fun previewSize(width: Int, height: Int, maxSide: Int = PREVIEW_MAX_SIDE): Pair<Int, Int> {
        val s = min(1f, maxSide.toFloat() / max(width, height))
        if (s >= 1f) return width to height
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    /** Rounded x / 255 for x in [-65025, 65025] (works for negative values). */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun div255(x: Int): Int = (x * 257 + 32768) shr 16

    /** Linear blend of two NON-premultiplied colors, all four channels, [t] in 0..255. */
    fun lerp255(a: Int, b: Int, t: Int): Int {
        if (t <= 0) return a
        if (t >= 255) return b
        val aa = a ushr 24; val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val ba = b ushr 24; val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        return ((aa + div255((ba - aa) * t)) shl 24) or
            ((ar + div255((br - ar) * t)) shl 16) or
            ((ag + div255((bg - ag) * t)) shl 8) or
            (ab + div255((bb - ab) * t))
    }

    /** A filter result pixel written into a grayscale layer mask: luminance over black, opaque. */
    fun toMaskPixel(c: Int): Int {
        val v = div255(ColorUtils.luminance(c) * (c ushr 24))
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /**
     * Turns a raw filter [result] into what gets written to the layer, IN PLACE, inside [region]:
     *  1. mask target: luminance (composited over black) as opaque gray;
     *     alpha lock: keep the source alpha;
     *  2. selection: `out = lerp(src, out, sel / 255)` per channel including alpha.
     * [selection] is a packed width*height coverage array (null = no selection = everything).
     * Pixels outside [region] are left untouched (they are not written back).
     */
    fun compose(
        src: PixelBuffer,
        result: PixelBuffer,
        selection: ByteArray?,
        alphaLocked: Boolean,
        maskTarget: Boolean,
        region: PixelRect = PixelRect.full(src.width, src.height),
        checkCancelled: () -> Unit = {},
    ) {
        require(src.width == result.width && src.height == result.height) { "Filter changed the image size" }
        require(selection == null || selection.size >= src.size) { "Selection size mismatch" }
        if (selection == null && !alphaLocked && !maskTarget) return // the result is used as is
        val w = src.width
        val s = src.pixels; val d = result.pixels
        val r = clampRegion(region, src.width, src.height) ?: return
        Parallel.forRange(r.height, 4) { y0, y1 ->
            checkCancelled()
            for (yy in y0 until y1) {
                val row = (r.top + yy) * w
                for (x in r.left until r.right) {
                    val i = row + x
                    val sel = if (selection == null) 255 else selection[i].toInt() and 0xFF
                    if (sel == 0) { d[i] = s[i]; continue }
                    var c = d[i]
                    if (maskTarget) c = toMaskPixel(c)
                    else if (alphaLocked) c = (c and 0x00FFFFFF) or (s[i] and 0xFF000000.toInt())
                    d[i] = if (sel == 255) c else lerp255(s[i], c, sel)
                }
            }
        }
    }

    /**
     * Tight bounds (inside [region]) of the pixels that differ between [before] and [after], or
     * null if nothing changed. Fully transparent pixels count as equal whatever their color bits.
     */
    fun changedBounds(before: PixelBuffer, after: PixelBuffer, region: PixelRect = PixelRect.full(before.width, before.height)): PixelRect? {
        val r = clampRegion(region, before.width, before.height) ?: return null
        val w = before.width
        val b = before.pixels; val a = after.pixels
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in r.top until r.bottom) {
            val row = y * w
            var first = -1; var last = -1
            for (x in r.left until r.right) {
                val p = b[row + x]; val q = a[row + x]
                if (p != q && ((p ushr 24) != 0 || (q ushr 24) != 0)) { if (first < 0) first = x; last = x }
            }
            if (first >= 0) {
                if (first < minX) minX = first
                if (last > maxX) maxX = last
                if (y < minY) minY = y
                maxY = y
            }
        }
        return if (maxX < 0) null else PixelRect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Luminance histogram (256 bins) weighted by pixel alpha and selection coverage. */
    fun luminanceHistogram(src: PixelBuffer, selection: ByteArray? = null): IntArray {
        val hist = IntArray(256)
        val p = src.pixels
        for (i in p.indices) {
            val c = p[i]
            var weight = c ushr 24
            if (selection != null) weight = div255(weight * (selection[i].toInt() and 0xFF))
            if (weight == 0) continue
            hist[ColorUtils.luminance(c)] += weight
        }
        return hist
    }

    /** True when every pixel of [buf] is fully transparent. */
    fun isFullyTransparent(buf: PixelBuffer): Boolean = buf.pixels.all { (it ushr 24) == 0 }

    private fun clampRegion(r: PixelRect, w: Int, h: Int): PixelRect? {
        val c = PixelRect(max(0, r.left), max(0, r.top), min(w, r.right), min(h, r.bottom))
        return if (c.isEmpty) null else c
    }
}

/**
 * Monotone cubic (Fritsch–Carlson) interpolation through tone-curve points, the same scheme the
 * color-adjustment filters use, so the curve drawn in the editor is the curve that gets applied.
 * Outside the first/last point the curve is flat; results are not clamped.
 */
class MonotoneCubic(points: List<CurvePoint>) {
    private val xs: FloatArray
    private val ys: FloatArray
    private val ms: FloatArray

    init {
        // Sort by x and collapse duplicate x (last one wins) so every segment has a width.
        val sorted = points.sortedBy { it.x }
        val px = ArrayList<Float>(sorted.size); val py = ArrayList<Float>(sorted.size)
        for (p in sorted) {
            if (px.isNotEmpty() && p.x - px.last() < 1e-6f) { py[py.lastIndex] = p.y; continue }
            px += p.x; py += p.y
        }
        xs = px.toFloatArray(); ys = py.toFloatArray()
        val n = xs.size
        ms = FloatArray(n)
        if (n >= 2) {
            val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
            ms[0] = d[0]; ms[n - 1] = d[n - 2]
            for (k in 1 until n - 1) ms[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
            for (k in 0 until n - 1) {
                if (d[k] == 0f) { ms[k] = 0f; ms[k + 1] = 0f; continue }
                val a = ms[k] / d[k]; val b = ms[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    ms[k] = t * a * d[k]; ms[k + 1] = t * b * d[k]
                }
            }
        }
    }

    fun eval(x: Float): Float {
        val n = xs.size
        if (n == 0) return x
        if (n == 1 || x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var k = 0
        while (k < n - 2 && x > xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        val t = (x - xs[k]) / h
        val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * ms[k] +
            (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * ms[k + 1]
    }

    /** [count] evenly spaced samples over 0..1, clamped to 0..1. */
    fun sample(count: Int): FloatArray = FloatArray(count) { i ->
        eval(if (count <= 1) 0f else i.toFloat() / (count - 1)).coerceIn(0f, 1f)
    }
}
