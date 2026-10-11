package com.brushwork.paint.tools.select.colorrange

import android.graphics.Bitmap
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pixels Color select previews on (v1.8, design §3.5 c): a proxy of the Refer pixels, long
 * side at most [ColorMasks.PROXY_MAX], with its Lab cached in hundredths (built once per tool
 * session; every change after that recomputes only the membership). The pixels stay for their
 * alpha and HSV. Immutable once built; any thread.
 */
class ColorProxy internal constructor(
    val width: Int,
    val height: Int,
    internal val pixels: IntArray,
    internal val l: ShortArray,
    internal val a: ShortArray,
    internal val b: ShortArray,
) {
    /** Bytes held. */
    val bytes: Long get() = pixels.size * 4L + (l.size + a.size + b.size) * 2L
}

/**
 * What the tool shows (`ColorSelectTool.preview`): the proxy's membership [mask] (0..255 per
 * pixel, row by row, [width] × [height]) and its [version], which grows with every preview
 * published. [bitmap] is [mask] as an ALPHA_8 bitmap the overlay tints red (main thread only).
 */
class ColorPreview internal constructor(
    val version: Int,
    val mask: ByteArray,
    val width: Int,
    val height: Int,
    internal val bitmap: Bitmap,
)

/**
 * Color select's masks (v1.8, design §3.5 c), computed in row bands on every core:
 * [proxy] (the Lab cache), [previewMask] (the proxy's membership) and [fullMask] (the document's,
 * at full resolution, in [BAND_ROWS]-row bands). The preview and the commit share
 * [ColorRange.labQ] and [ColorRange.member], so the preview shows what the commit selects.
 */
object ColorMasks {
    /** The preview proxy's longest side (px). */
    const val PROXY_MAX = 1280

    /** Rows per band of the full-resolution mask. */
    const val BAND_ROWS = 256

    /** Rows per band of the proxy's work. */
    private const val PROXY_BAND_ROWS = 64

    /** Size of the cache of colors met in one band of the full-resolution pass (a power of two). */
    private const val CACHE = 4096

    /** The proxy's size for a [docW] × [docH] document (as `Compositor.renderThumbnail(PROXY_MAX)`). */
    fun proxySize(docW: Int, docH: Int): IntArray {
        val s = min(1f, PROXY_MAX.toFloat() / max(docW, docH))
        return intArrayOf(max(1, (docW * s).roundToInt()), max(1, (docH * s).roundToInt()))
    }

    /**
     * Runs [band] for bands 0 until [bands], spread over the cores ([workers] at most); each
     * worker gets its own scratch from [scratch]. False when [cancelled] stopped it.
     */
    private inline fun <T> parallel(bands: Int, crossinline scratch: () -> T, crossinline cancelled: () -> Boolean, crossinline band: (Int, T) -> Unit): Boolean {
        val workers = min(bands, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        IntStream.range(0, workers).parallel().forEach { w ->
            val s = scratch()
            var i = w
            while (i < bands && !cancelled()) {
                band(i, s)
                i += workers
            }
        }
        return !cancelled()
    }

    /** The Lab cache of [pixels] (ARGB, unpremultiplied, [w] × [h]); null when cancelled. */
    fun proxy(pixels: IntArray, w: Int, h: Int, cancelled: () -> Boolean = { false }): ColorProxy? {
        val n = w * h
        val l = ShortArray(n); val a = ShortArray(n); val b = ShortArray(n)
        val bands = (h + PROXY_BAND_ROWS - 1) / PROXY_BAND_ROWS
        val done = parallel(bands, { Unit }, cancelled) { band, _ ->
            val from = band * PROXY_BAND_ROWS * w
            val to = min(n, from + PROXY_BAND_ROWS * w)
            var last = 0; var lastQ = ColorRange.labQ(0)
            for (i in from until to) {
                val c = pixels[i] or ALPHA
                val q = if (c == last) lastQ else ColorRange.labQ(c).also { last = c; lastQ = it }
                l[i] = ColorRange.qL(q).toShort(); a[i] = ColorRange.qA(q).toShort(); b[i] = ColorRange.qB(q).toShort()
            }
        }
        return if (done) ColorProxy(w, h, pixels, l, a, b) else null
    }

    /** The Lab cache of [bitmap] (any size; read here, not recycled); null when cancelled. */
    fun proxy(bitmap: Bitmap, cancelled: () -> Boolean = { false }): ColorProxy? {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (cancelled()) return null
        return proxy(pixels, w, h, cancelled)
    }

    /** The membership (0..255) of every pixel of [proxy] under [p]; null when cancelled. */
    fun previewMask(proxy: ColorProxy, p: ColorRange.Params, cancelled: () -> Boolean = { false }): ByteArray? {
        val n = proxy.width * proxy.height
        val out = ByteArray(n)
        if (p.selectsNothing) return out
        val px = proxy.pixels; val l = proxy.l; val a = proxy.a; val b = proxy.b
        val rows = PROXY_BAND_ROWS * proxy.width
        val bands = (n + rows - 1) / rows
        val done = parallel(bands, { Unit }, cancelled) { band, _ ->
            val from = band * rows
            val to = min(n, from + rows)
            for (i in from until to) out[i] = ColorRange.member(px[i], l[i].toInt(), a[i].toInt(), b[i].toInt(), p).toByte()
        }
        return if (done) out else null
    }

    private class Scratch(w: Int) {
        val row = IntArray(w * BAND_ROWS)
        val keys = IntArray(CACHE)
        val values = ShortArray(CACHE)
    }

    /**
     * The document's mask (0..255 per pixel, row by row) under [p], read from [source] (the Refer
     * pixels at full resolution, not recycled here) in [BAND_ROWS]-row bands; null when cancelled.
     */
    fun fullMask(source: Bitmap, p: ColorRange.Params, cancelled: () -> Boolean = { false }): ByteArray? {
        val w = source.width; val h = source.height
        val out = ByteArray(w * h)
        if (p.selectsNothing) return out
        val bands = (h + BAND_ROWS - 1) / BAND_ROWS
        val done = parallel(bands, { Scratch(w) }, cancelled) { band, s ->
            val y = band * BAND_ROWS
            val rows = min(BAND_ROWS, h - y)
            source.getPixels(s.row, 0, w, 0, y, w, rows)
            // A colour met before in this band is looked up, not computed again.
            s.values.fill((-1).toShort())
            val base = y * w
            var last = 0; var lastV = member(0, p)
            for (i in 0 until w * rows) {
                val c = s.row[i]
                val v: Int
                if (c == last) {
                    v = lastV
                } else {
                    val k = (c * HASH) ushr (32 - CACHE_BITS)
                    if (s.values[k] >= 0 && s.keys[k] == c) {
                        v = s.values[k].toInt()
                    } else {
                        v = member(c, p)
                        s.keys[k] = c
                        s.values[k] = v.toShort()
                    }
                    last = c; lastV = v
                }
                out[base + i] = v.toByte()
            }
        }
        return if (done) out else null
    }

    /** The selection value of one pixel (ARGB, unpremultiplied). */
    fun member(argb: Int, p: ColorRange.Params): Int {
        val q = ColorRange.labQ(argb)
        return ColorRange.member(argb, ColorRange.qL(q), ColorRange.qA(q), ColorRange.qB(q), p)
    }

    private const val ALPHA = 0xFF shl 24
    private const val CACHE_BITS = 12
    private const val HASH = -0x61c88647 // 0x9E3779B9, Fibonacci hashing
}
